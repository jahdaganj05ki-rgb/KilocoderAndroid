package com.kilocoder.android.m11

import android.content.Context
import android.os.Build
import android.os.StatFs
import android.system.Os
import android.system.OsConstants
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * M1.1-Smoke-Test: beantwortet die Go/No-Go-Frage, ob PRoot unter targetSdk 36
 * ein Guest-Kommando starten kann. Läuft nur im Debug-Build.
 */
class M11Smoke(
    context: Context,
    private val onLine: (String) -> Unit,
    private val cancelled: () -> Boolean,
) {
    private val app = context.applicationContext
    private val base = File(app.filesDir, "m11")
    private val rootfs = File(base, "rootfs")
    private val marker = File(rootfs, ".m11-ok")
    private val archive = File(base, "rootfs.tar.xz")
    private val native = File(app.applicationInfo.nativeLibraryDir)
    private val engine = ProotEngine(native, File(base, "work"))
    private val report = StringBuilder()

    private var passed = 0
    private var failed = 0
    private var skipped = 0

    data class Summary(
        val passed: Int,
        val failed: Int,
        val skipped: Int,
        val report: File,
        val aborted: Boolean,
    )

    fun run(installJava: Boolean): Summary {
        base.mkdirs()
        header()
        if (cancelled()) return finish(true)

        if (!preflight()) return finish(false)
        if (!ensureRootfs()) return finish(false)
        if (cancelled()) return finish(true)

        attempt("PRoot startet aus nativeLibraryDir") { checkVersion() }
        attempt("Guest: /bin/echo ok") { checkCommand("Guest: /bin/echo ok", "echo ok", "ok") }
        attempt("Guest: readlink /bin/sh") { checkCommand("Guest: readlink /bin/sh", "readlink /bin/sh", "dash") }
        attempt("Guest: bash -c echo ok") { checkCommand("Guest: bash -c echo ok", "/bin/bash -c 'echo ok'", "ok") }
        attempt("Guest: uname -m / PAGE_SIZE") { checkPlatform() }
        attempt("Exit-Code wird durchgereicht") { checkExitCode() }
        attempt("ELF: aarch64 und 16-KB-LOAD") { checkElf() }
        attempt("Abbruch beendet PRoot") { checkCancel() }
        if (installJava) {
            attempt("Guest: java -version") { checkJava() }
        } else {
            skip("Guest: java -version", "optionaler JDK-Schritt nicht gewählt")
        }

        return finish(false)
    }

    private fun header() {
        val pageSize = Os.sysconf(OsConstants._SC_PAGESIZE)
        val free = StatFs(base.absolutePath).availableBytes
        line("INFO", "Gerät ${Build.MANUFACTURER} ${Build.MODEL}")
        line("INFO", "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        line("INFO", "ABIs ${Build.SUPPORTED_ABIS.joinToString()}")
        line("INFO", "PAGE_SIZE $pageSize · frei ${free / (1024 * 1024)} MB")
        line("INFO", "nativeLibraryDir $native")
    }

    private fun preflight(): Boolean {
        if (Build.SUPPORTED_ABIS.none { it == "arm64-v8a" }) {
            fail("Preflight: arm64-v8a", "Gerät meldet ${Build.SUPPORTED_ABIS.joinToString()}")
            return false
        }
        val missing = engine.missingLibraries()
        if (missing.isNotEmpty()) {
            fail("Preflight: Native Libraries", "fehlen: ${missing.joinToString()}")
            line("INFO", "Build ohne src/debug/jniLibs/arm64-v8a gestartet? Siehe tools/m1_1/BINARIES.md")
            return false
        }
        val free = StatFs(base.absolutePath).availableBytes
        if (free < MIN_FREE) {
            fail("Preflight: Speicher", "nur ${free / (1024 * 1024)} MB frei, benötigt ${MIN_FREE / (1024 * 1024)} MB")
            return false
        }
        pass("Preflight: arm64, Libraries, Speicher", "${free / (1024 * 1024)} MB frei")
        return true
    }

    private fun ensureRootfs(): Boolean {
        if (marker.isFile) {
            pass("Rootfs vorhanden", rootfs.absolutePath)
            return true
        }
        return try {
            if (!archive.isFile || archive.length() != ROOTFS_SIZE || sha256(archive) != ROOTFS_SHA256) {
                archive.delete()
                download(ROOTFS_URL, archive)
            }
            pass("Rootfs heruntergeladen", "${archive.length()} Bytes, SHA-256 stimmt")
            if (cancelled()) return false
            val tmp = File(base, "rootfs.tmp")
            if (tmp.exists()) tmp.deleteRecursively()
            val stats = TarXzExtractor(tmp).extract(archive) { line("INFO", it) }
            // Das proot-distro-Tarball hat genau ein Top-Level-Verzeichnis; PRoot erwartet /bin direkt unter -r.
            val entries = tmp.listFiles().orEmpty()
            val content = if (entries.size == 1 && entries[0].isDirectory) entries[0] else tmp
            if (rootfs.exists()) rootfs.deleteRecursively()
            if (!content.renameTo(rootfs)) throw IOException("Rootfs konnte nicht nach $rootfs verschoben werden")
            if (content != tmp) tmp.deleteRecursively()
            marker.writeText("ok")
            pass("Rootfs entpackt", stats.toString())
            true
        } catch (ex: IOException) {
            fail("Rootfs", ex.message ?: ex.javaClass.simpleName)
            false
        }
    }

    private fun checkVersion() {
        val result = engine.version()
        val output = result.output.joinToString(" ").trim()
        if (result.exit == 0 && output.contains("proot")) {
            pass("PRoot startet aus nativeLibraryDir", output.take(120))
        } else {
            fail("PRoot startet aus nativeLibraryDir", "Exit ${result.exit}: ${output.take(160)}")
        }
    }

    private fun checkCommand(title: String, script: String, expected: String) {
        val result = engine.shell(rootfs, script)
        val output = result.output.joinToString("\n").trim()
        if (result.exit == 0 && output == expected) {
            pass(title, output)
        } else {
            fail(title, "Exit ${result.exit}: ${output.take(200)}")
        }
    }

    private fun checkPlatform() {
        val result = engine.shell(rootfs, "uname -m; getconf PAGE_SIZE")
        val output = result.output.map { it.trim() }.filter { it.isNotEmpty() }
        val arch = output.firstOrNull() ?: ""
        val pages = output.getOrNull(1) ?: ""
        if (result.exit == 0 && arch == "aarch64" && pages.isNotEmpty()) {
            pass("Guest: uname -m / PAGE_SIZE", "$arch · $pages")
        } else {
            fail("Guest: uname -m / PAGE_SIZE", "Exit ${result.exit}: ${output.joinToString(" ")}")
        }
    }

    private fun checkExitCode() {
        val result = engine.shell(rootfs, "exit 7")
        if (result.exit == 7) {
            pass("Exit-Code wird durchgereicht", "7")
        } else {
            fail("Exit-Code wird durchgereicht", "erwartet 7, erhalten ${result.exit}")
        }
    }

    private fun checkElf() {
        val names = listOf("libproot.so", "libproot-loader.so", "libtalloc.so", "libandroid-shmem.so")
        val problems = mutableListOf<String>()
        val details = mutableListOf<String>()
        for (name in names) {
            val file = File(native, name)
            if (!file.isFile) {
                problems += "$name fehlt"
                continue
            }
            try {
                val info = ElfCheck.read(file)
                details += "$name align=0x${info.maxLoadAlign.toString(16)}"
                if (!info.isElf64 || !info.isAarch64) problems += "$name ist kein aarch64-ELF"
                if (info.maxLoadAlign < 0x4000) problems += "$name ist nicht 16-KB-ausgerichtet"
            } catch (ex: IOException) {
                problems += ex.message ?: "$name unlesbar"
            }
        }
        if (problems.isEmpty()) {
            pass("ELF: aarch64 und 16-KB-LOAD", details.joinToString(", "))
        } else {
            fail("ELF: aarch64 und 16-KB-LOAD", problems.joinToString("; "))
        }
    }

    private fun checkCancel() {
        val process = engine.start(rootfs, "sleep 60")
        Thread.sleep(2_000)
        process.destroy()
        if (!process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)) process.destroyForcibly()
        val again = engine.shell(rootfs, "echo ok")
        val output = again.output.joinToString("\n").trim()
        if (again.exit == 0 && output == "ok") {
            pass("Abbruch beendet PRoot", "zweiter Lauf liefert ok")
        } else {
            fail("Abbruch beendet PRoot", "Exit ${again.exit}: ${output.take(160)}")
        }
    }

    private fun checkJava() {
        val resolv = File(rootfs, "etc/resolv.conf")
        resolv.parentFile?.mkdirs()
        resolv.writeText("nameserver 1.1.1.1\nnameserver 8.8.8.8\n")
        line("INFO", "apt-get install openjdk-17-jre-headless (kann 10–20 Minuten dauern)")
        val result = engine.shell(
            rootfs,
            "apt-get update && DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends " +
                "openjdk-17-jre-headless && java -version",
            JAVA_TIMEOUT,
        )
        val output = result.output.joinToString("\n")
        if (result.exit == 0 && output.contains("17.")) {
            pass("Guest: java -version", output.lines().first { it.contains("version") }.take(120))
        } else {
            fail("Guest: java -version", "Exit ${result.exit}: ${output.takeLast(300)}")
        }
    }

    private fun finish(aborted: Boolean): Summary {
        val verdict = when {
            aborted -> "ABGEBROCHEN"
            failed == 0 -> "GO"
            else -> "NO-GO"
        }
        line("INFO", "Ergebnis: $verdict · $passed bestanden, $failed fehlgeschlagen, $skipped übersprungen")
        val file = File(base, "report.txt")
        file.writeText(report.toString())
        return Summary(passed, failed, skipped, file, aborted)
    }

    private fun attempt(title: String, block: () -> Unit) {
        try {
            block()
        } catch (ex: Exception) {
            fail(title, ex.message ?: ex.javaClass.simpleName)
        }
    }

    private fun pass(title: String, detail: String = "") {
        passed++
        line("PASS", if (detail.isEmpty()) title else "$title — $detail")
    }

    private fun fail(title: String, detail: String) {
        failed++
        line("FAIL", "$title — $detail")
    }

    private fun skip(title: String, reason: String) {
        skipped++
        line("SKIP", "$title — $reason")
    }

    private fun line(level: String, text: String) {
        val stamp = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
        val row = "$stamp $level $text"
        report.appendLine(row)
        onLine(row)
    }

    private fun download(url: String, target: File) {
        target.parentFile?.mkdirs()
        var current = URL(url)
        repeat(6) {
            val connection = current.openConnection() as HttpURLConnection
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 30_000
            connection.readTimeout = 60_000
            connection.setRequestProperty("User-Agent", "KiloCoder-M11/1.0")
            val code = connection.responseCode
            if (code == 301 || code == 302 || code == 303 || code == 307 || code == 308) {
                val location = connection.getHeaderField("Location") ?: throw IOException("Redirect ohne Location")
                connection.disconnect()
                current = URL(current, location)
                return@repeat
            }
            if (code != 200) throw IOException("HTTP $code für $current")
            val total = connection.contentLengthLong
            val tmp = File(target.parentFile, "${target.name}.part")
            tmp.delete()
            val digest = MessageDigest.getInstance("SHA-256")
            var read = 0L
            connection.inputStream.use { input ->
                FileOutputStream(tmp).use { out ->
                    val buffer = ByteArray(1 shl 16)
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        out.write(buffer, 0, n)
                        digest.update(buffer, 0, n)
                        read += n
                        if (read % (8L * 1024 * 1024) < buffer.size) {
                            line("INFO", "Download ${read / (1024 * 1024)} MB" + if (total > 0) " / ${total / (1024 * 1024)} MB" else "")
                        }
                    }
                }
            }
            connection.disconnect()
            val hex = digest.digest().joinToString("") { "%02x".format(it) }
            if (hex != ROOTFS_SHA256) {
                tmp.delete()
                throw IOException("SHA-256 falsch: $hex")
            }
            if (!tmp.renameTo(target)) throw IOException("Download konnte nicht umbenannt werden")
            return
        }
        throw IOException("Zu viele Redirects")
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(1 shl 16)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private companion object {
        const val ROOTFS_URL =
            "https://github.com/termux/proot-distro/releases/download/v4.7.0/debian-bookworm-aarch64-pd-v4.7.0.tar.xz"
        const val ROOTFS_SHA256 = "4baa32280cc70b67e2c650777c1d974349f0cdf23afaabc305ad3bc6182b8df8"
        const val ROOTFS_SIZE = 42_894_344L
        const val MIN_FREE = 2L * 1024 * 1024 * 1024
        const val JAVA_TIMEOUT = 1_200_000L
    }
}
