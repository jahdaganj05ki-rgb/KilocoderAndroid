package com.kilocoder.android.m11

import org.tukaani.xz.XZInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermission
import java.util.EnumSet

/**
 * Entpackt ein tar.xz-Rootfs und erhält Symlinks, harte Links und Unix-Modi.
 *
 * Bewusst ohne ZIP: ZIP verliert genau diese Informationen. Der Extraktor
 * verweigert Pfade außerhalb des Zielverzeichnisses und schreibt nie durch
 * einen Symlink hindurch.
 */
class TarXzExtractor(private val dest: File) {

    class Stats {
        var files = 0
        var dirs = 0
        var symlinks = 0
        var hardlinks = 0
        var skipped = 0
        var bytes = 0L
        var hardlinkFailures = 0

        override fun toString(): String =
            "Dateien $files, Verzeichnisse $dirs, Symlinks $symlinks, " +
                "Hardlinks $hardlinks, übersprungen $skipped, ${bytes / (1024 * 1024)} MB"
    }

    private class Entry {
        var name = ""
        var mode = 0
        var size = 0L
        var type = '0'
        var link = ""
        var allZero = false
    }

    fun extract(archive: File, onLine: (String) -> Unit): Stats {
        val root = dest.canonicalFile.toPath()
        Files.createDirectories(root)
        val stats = Stats()
        val pendingLinks = mutableListOf<Pair<Path, Path>>()
        val dirModes = mutableListOf<Pair<Path, Int>>()

        archive.inputStream().buffered(BUFFER).use { raw ->
            XZInputStream(raw).use { xz ->
                var longName: String? = null
                var longLink: String? = null
                var paxPath: String? = null
                var paxLink: String? = null
                var paxSize: Long? = null
                while (true) {
                    val entry = readEntry(xz) ?: break
                    if (entry.allZero) break
                    when (entry.type) {
                        'x', 'X' -> {
                            parsePax(readData(xz, entry.size)).forEach { (key, value) ->
                                when (key) {
                                    "path" -> paxPath = value
                                    "linkpath" -> paxLink = value
                                    "size" -> paxSize = value.toLongOrNull()
                                }
                            }
                        }

                        'g' -> skipData(xz, entry.size)
                        'L' -> longName = readData(xz, entry.size).toString(StandardCharsets.UTF_8).trimEnd('\u0000')
                        'K' -> longLink = readData(xz, entry.size).toString(StandardCharsets.UTF_8).trimEnd('\u0000')
                        else -> {
                            val name = paxPath ?: longName ?: entry.name
                            val link = paxLink ?: longLink ?: entry.link
                            val size = paxSize ?: entry.size
                            paxPath = null
                            paxLink = null
                            paxSize = null
                            longName = null
                            longLink = null
                            val path = resolve(root, name)
                            when (entry.type) {
                                '5' -> {
                                    Files.createDirectories(path)
                                    dirModes += path to entry.mode
                                    stats.dirs++
                                }

                                '2' -> {
                                    createParents(root, path)
                                    if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) Files.delete(path)
                                    Files.createSymbolicLink(path, Paths.get(link))
                                    stats.symlinks++
                                }

                                '1' -> {
                                    pendingLinks += path to resolve(root, link)
                                }

                                '0', '\u0000', '7' -> {
                                    createParents(root, path)
                                    Files.newOutputStream(
                                        path,
                                        StandardOpenOption.CREATE,
                                        StandardOpenOption.TRUNCATE_EXISTING,
                                        StandardOpenOption.WRITE,
                                    ).use { out ->
                                        copyExactly(xz, out, size)
                                    }
                                    applyMode(path, entry.mode)
                                    stats.files++
                                    stats.bytes += size
                                }

                                else -> {
                                    skipData(xz, size)
                                    stats.skipped++
                                }
                            }
                        }
                    }
                }
            }
        }

        for ((linkPath, target) in pendingLinks) {
            try {
                createParents(root, linkPath)
                if (Files.exists(linkPath, LinkOption.NOFOLLOW_LINKS)) Files.delete(linkPath)
                Files.createLink(linkPath, target)
                stats.hardlinks++
            } catch (ex: IOException) {
                stats.hardlinkFailures++
                onLine("Hardlink fehlgeschlagen: ${linkPath.fileName} -> ${target.fileName}: ${ex.message}")
            }
        }

        // Verzeichnismodi zuletzt, damit ein Modus wie 0555 das Schreiben nicht blockiert.
        dirModes.sortedByDescending { it.first.nameCount }.forEach { applyMode(it.first, it.second) }
        return stats
    }

    private fun readEntry(input: InputStream): Entry? {
        val header = ByteArray(512)
        var read = 0
        while (read < header.size) {
            val n = input.read(header, read, header.size - read)
            if (n < 0) {
                if (read == 0) return null
                throw IOException("Abgeschnittener tar-Header nach $read Bytes")
            }
            read += n
        }
        val entry = Entry()
        if (header.all { it.toInt() == 0 }) {
            entry.allZero = true
            return entry
        }
        if (!checksumOk(header)) throw IOException("tar-Header-Prüfsumme stimmt nicht")
        entry.name = cString(header, 0, 100)
        val prefix = cString(header, 345, 155)
        if (prefix.isNotEmpty()) entry.name = "$prefix/${entry.name}"
        entry.mode = parseNumber(header, 100, 8).toInt()
        entry.size = parseNumber(header, 124, 12)
        entry.type = header[156].toInt().toChar().let { if (it == '\u0000') '0' else it }
        entry.link = cString(header, 157, 100)
        return entry
    }

    private fun checksumOk(header: ByteArray): Boolean {
        val expected = parseNumber(header, 148, 8)
        var sum = 0L
        for (i in header.indices) {
            sum += if (i in 148..155) 32 else header[i].toInt() and 0xFF
        }
        return expected == sum
    }

    private fun parseNumber(header: ByteArray, offset: Int, length: Int): Long {
        if (header[offset].toInt() and 0x80 != 0) {
            var value = header[offset].toLong() and 0x7F
            for (i in 1 until length) value = (value shl 8) or (header[offset + i].toLong() and 0xFF)
            return value
        }
        var value = 0L
        for (i in offset until offset + length) {
            val b = header[i].toInt() and 0xFF
            if (b == 0 || b == 32) continue
            if (b < '0'.code || b > '7'.code) continue
            value = value * 8 + (b - '0'.code)
        }
        return value
    }

    private fun cString(header: ByteArray, offset: Int, length: Int): String {
        var end = offset
        while (end < offset + length && header[end].toInt() != 0) end++
        return String(header, offset, end - offset, StandardCharsets.UTF_8)
    }

    private fun parsePax(data: ByteArray): Map<String, String> {
        val result = mutableMapOf<String, String>()
        var pos = 0
        while (pos < data.size) {
            var space = pos
            while (space < data.size && data[space] != ' '.code.toByte()) space++
            if (space >= data.size) break
            val length = String(data, pos, space - pos, StandardCharsets.UTF_8).toIntOrNull() ?: break
            if (length <= 0 || pos + length > data.size) break
            val record = String(data, space + 1, pos + length - space - 2, StandardCharsets.UTF_8)
            val eq = record.indexOf('=')
            if (eq > 0) result[record.substring(0, eq)] = record.substring(eq + 1)
            pos += length
        }
        return result
    }

    private fun readData(input: InputStream, size: Long): ByteArray {
        if (size < 0 || size > MAX_METADATA) throw IOException("Unerwartet großer tar-Metadatensatz: $size")
        val data = ByteArray(size.toInt())
        var read = 0
        while (read < data.size) {
            val n = input.read(data, read, data.size - read)
            if (n < 0) throw IOException("tar-Daten abgeschnitten")
            read += n
        }
        skipPadding(input, size)
        return data
    }

    private fun skipData(input: InputStream, size: Long) {
        var left = size
        val buffer = ByteArray(BUFFER)
        while (left > 0) {
            val n = input.read(buffer, 0, minOf(left, buffer.size.toLong()).toInt())
            if (n < 0) throw IOException("tar-Daten abgeschnitten")
            left -= n
        }
        skipPadding(input, size)
    }

    private fun copyExactly(input: InputStream, out: OutputStream, size: Long) {
        var left = size
        val buffer = ByteArray(BUFFER)
        while (left > 0) {
            val n = input.read(buffer, 0, minOf(left, buffer.size.toLong()).toInt())
            if (n < 0) throw IOException("tar-Daten abgeschnitten")
            out.write(buffer, 0, n)
            left -= n
        }
        skipPadding(input, size)
    }

    private fun skipPadding(input: InputStream, size: Long) {
        val pad = ((512 - size % 512) % 512).toInt()
        var left = pad
        while (left > 0) {
            val n = input.skip(left.toLong()).toInt()
            if (n <= 0) {
                if (input.read() < 0) throw IOException("tar-Padding abgeschnitten")
                left--
                continue
            }
            left -= n
        }
    }

    private fun resolve(root: Path, name: String): Path {
        val clean = name.removePrefix("./").trimStart('/')
        if (clean.isEmpty() || clean == ".") return root
        if (clean.split('/').any { it == ".." }) throw IOException("Pfad außerhalb des Rootfs: $name")
        val path = root.resolve(clean).normalize()
        if (!path.startsWith(root)) throw IOException("Pfad außerhalb des Rootfs: $name")
        return path
    }

    private fun createParents(root: Path, path: Path) {
        val parent = path.parent ?: return
        if (parent == root) return
        val stack = ArrayDeque<Path>()
        var current: Path? = parent
        while (current != null && current != root) {
            stack.addFirst(current)
            current = current.parent
        }
        for (dir in stack) {
            if (Files.exists(dir, LinkOption.NOFOLLOW_LINKS)) {
                if (Files.isSymbolicLink(dir)) throw IOException("Symlink im Pfad: $dir")
                continue
            }
            Files.createDirectory(dir)
        }
    }

    private fun applyMode(path: Path, mode: Int) {
        Files.setPosixFilePermissions(path, permissions(mode))
    }

    private fun permissions(mode: Int): Set<PosixFilePermission> {
        val set = EnumSet.noneOf(PosixFilePermission::class.java)
        if (mode and 0x100 != 0) set += PosixFilePermission.OWNER_READ
        if (mode and 0x080 != 0) set += PosixFilePermission.OWNER_WRITE
        if (mode and 0x040 != 0) set += PosixFilePermission.OWNER_EXECUTE
        if (mode and 0x020 != 0) set += PosixFilePermission.GROUP_READ
        if (mode and 0x010 != 0) set += PosixFilePermission.GROUP_WRITE
        if (mode and 0x008 != 0) set += PosixFilePermission.GROUP_EXECUTE
        if (mode and 0x004 != 0) set += PosixFilePermission.OTHERS_READ
        if (mode and 0x002 != 0) set += PosixFilePermission.OTHERS_WRITE
        if (mode and 0x001 != 0) set += PosixFilePermission.OTHERS_EXECUTE
        return set
    }

    private companion object {
        const val BUFFER = 1 shl 16
        const val MAX_METADATA = 1 shl 20
    }
}
