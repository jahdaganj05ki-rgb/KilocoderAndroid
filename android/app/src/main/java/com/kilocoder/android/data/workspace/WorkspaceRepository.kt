package com.kilocoder.android.data.workspace

import java.io.File
import java.io.IOException
import java.nio.file.Files
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class WorkspaceException(message: String) : Exception(message)

data class WorkspaceFile(
    val path: String,
    val content: String,
    val bytes: Int,
)

data class WorkspaceSnapshot(
    val files: List<WorkspaceFile>,
    val skipped: Int,
)

class WorkspaceRepository(private val root: File) {
    init {
        if (!root.exists()) root.mkdirs()
    }

    val rootPath: String get() = root.absolutePath

    fun resolve(path: String): File {
        val base = root.canonicalFile
        val clean = path.trim().ifBlank { "." }
        val target = File(base, clean).canonicalFile
        if (target != base && !target.path.startsWith(base.path + File.separator)) {
            throw WorkspaceException("Path escapes the workspace: $path")
        }
        return target
    }

    suspend fun snapshot(
        maxFiles: Int = 500,
        maxTotalBytes: Int = 5_000_000,
    ): WorkspaceSnapshot = withContext(Dispatchers.IO) {
        val base = root.canonicalFile
        if (!base.exists()) return@withContext WorkspaceSnapshot(emptyList(), 0)
        val files = mutableListOf<WorkspaceFile>()
        var skipped = 0
        var total = 0

        fun scan(dir: File) {
            val children = dir.listFiles()
                ?.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
                ?: return
            for (child in children) {
                if (files.size >= maxFiles) {
                    skipped += 1
                    continue
                }
                if (child.name in IGNORED || !isInside(child) || sensitive(child.name)) continue
                if (Files.isSymbolicLink(child.toPath())) {
                    skipped += 1
                    continue
                }
                if (child.isDirectory) {
                    scan(child)
                    continue
                }
                if (!child.isFile || child.length() > MAX_PUSH_FILE) {
                    skipped += 1
                    continue
                }
                val bytes = child.readBytes()
                if (bytes.any { it == 0.toByte() }) {
                    skipped += 1
                    continue
                }
                if (total + bytes.size > maxTotalBytes) {
                    skipped += 1
                    continue
                }
                files += WorkspaceFile(
                    path = relative(child).replace(File.separatorChar, '/'),
                    content = bytes.toString(Charsets.UTF_8),
                    bytes = bytes.size,
                )
                total += bytes.size
            }
        }

        scan(base)
        WorkspaceSnapshot(files, skipped)
    }

    suspend fun list(path: String = ".", recursive: Boolean = false): String =
        withContext(Dispatchers.IO) {
            val base = resolve(path)
            if (!base.exists()) throw WorkspaceException("Path not found: $path")
            if (!base.isDirectory) return@withContext relative(base)
            val out = mutableListOf<String>()

            fun visit(dir: File, depth: Int) {
                if (out.size >= LIMIT) return
                val children = dir.listFiles()
                    ?.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
                    ?: return
                for (child in children) {
                    if (out.size >= LIMIT) return
                    if (child.name in IGNORED || !isInside(child)) continue
                    out += (if (child.isDirectory) "dir " else "file ") +
                        child.length() + " " + relative(child)
                    if (recursive && child.isDirectory && depth < 8) visit(child, depth + 1)
                }
            }

            visit(base, 0)
            val body = if (out.isEmpty()) "(empty)" else out.joinToString("\n")
            if (out.size >= LIMIT) "$body\n… truncated" else body
        }

    suspend fun read(
        path: String,
        startLine: Int? = null,
        endLine: Int? = null,
    ): String = withContext(Dispatchers.IO) {
        val file = resolve(path)
        if (!file.isFile) throw WorkspaceException("Not a file: $path")
        if (file.length() > MAX_READ) {
            throw WorkspaceException("File is too large to read (${file.length()} bytes).")
        }
        val lines = file.readLines()
        val from = (startLine ?: 1).coerceAtLeast(1)
        val to = (endLine ?: lines.size).coerceAtMost(lines.size)
        if (from > to) return@withContext ""
        lines.subList(from - 1, to)
            .mapIndexed { index, line -> "${from + index}: $line" }
            .joinToString("\n")
    }

    suspend fun write(path: String, content: String): String = withContext(Dispatchers.IO) {
        if (content.length > MAX_WRITE) {
            throw WorkspaceException("Content is too large (${content.length} chars).")
        }
        val file = resolve(path)
        if (file.isDirectory) throw WorkspaceException("Path is a directory: $path")
        file.parentFile?.mkdirs()
        file.writeText(content)
        "Wrote ${content.length} chars to ${relative(file)}"
    }

    suspend fun replace(
        path: String,
        old: String,
        new: String,
        replaceAll: Boolean,
    ): String = withContext(Dispatchers.IO) {
        if (old.isEmpty()) throw WorkspaceException("The old text must not be empty.")
        val file = resolve(path)
        if (!file.isFile) throw WorkspaceException("Not a file: $path")
        if (file.length() > MAX_READ) {
            throw WorkspaceException("File is too large to edit (${file.length()} bytes).")
        }
        val text = file.readText()
        var count = 0
        var index = text.indexOf(old)
        while (index >= 0) {
            count++
            index = text.indexOf(old, index + old.length)
        }
        if (count == 0) throw WorkspaceException("Text not found in $path.")
        if (count > 1 && !replaceAll) {
            throw WorkspaceException("Text occurs $count times; set replace_all=true or use a longer match.")
        }
        val updated = if (replaceAll) text.replace(old, new) else text.replaceFirst(old, new)
        file.writeText(updated)
        "Replaced $count occurrence(s) in ${relative(file)}"
    }

    suspend fun search(
        query: String,
        path: String = ".",
        maxResults: Int = 80,
    ): String = withContext(Dispatchers.IO) {
        if (query.isBlank()) throw WorkspaceException("Search query must not be empty.")
        val base = resolve(path)
        if (!base.exists()) throw WorkspaceException("Path not found: $path")
        val limit = maxResults.coerceIn(1, 200)
        val hits = mutableListOf<String>()

        fun scan(file: File, depth: Int) {
            if (hits.size >= limit) return
            if (file.isDirectory) {
                if (depth >= 8) return
                file.listFiles()
                    ?.sortedBy { it.name.lowercase() }
                    ?.forEach { child ->
                        if (child.name !in IGNORED && isInside(child)) {
                            scan(child, depth + 1)
                        }
                    }
                return
            }
            if (file.length() > MAX_SEARCH_FILE || !looksText(file)) return
            file.useLines { lines ->
                lines.forEachIndexed { index, line ->
                    if (hits.size < limit && line.contains(query, ignoreCase = true)) {
                        hits += "${relative(file)}:${index + 1}: ${line.trim().take(200)}"
                    }
                }
            }
        }

        scan(base, 0)
        if (hits.isEmpty()) "No matches for \"$query\"." else hits.joinToString("\n")
    }

    suspend fun makeDirectory(path: String): String = withContext(Dispatchers.IO) {
        val dir = resolve(path)
        if (dir.exists()) throw WorkspaceException("Path already exists: $path")
        if (!dir.mkdirs()) throw WorkspaceException("Could not create directory: $path")
        "Created directory ${relative(dir)}"
    }

    suspend fun delete(path: String): String = withContext(Dispatchers.IO) {
        val file = resolve(path)
        if (file == root.canonicalFile) throw WorkspaceException("Refusing to delete the workspace root.")
        if (!file.exists()) throw WorkspaceException("Path not found: $path")
        if (file.isDirectory && file.listFiles()?.isNotEmpty() == true) {
            throw WorkspaceException("Directory is not empty: $path")
        }
        if (!file.delete()) throw WorkspaceException("Could not delete: $path")
        "Deleted ${relative(file)}"
    }

    suspend fun clear() = withContext(Dispatchers.IO) {
        if (!root.deleteRecursively()) throw WorkspaceException("Could not clear the workspace.")
        if (!root.mkdirs()) throw WorkspaceException("Could not recreate the workspace.")
    }

    private fun sensitive(name: String): Boolean {
        val lower = name.lowercase()
        if (lower == "local.properties") return true
        if (lower == ".env") return true
        if (lower.startsWith(".env.") &&
            lower != ".env.example" &&
            lower != ".env.sample"
        ) {
            return true
        }
        return lower.endsWith(".jks") ||
            lower.endsWith(".keystore") ||
            lower.endsWith(".p12") ||
            lower.endsWith(".pfx") ||
            lower == "id_rsa" ||
            lower == "id_ed25519"
    }

    private fun isInside(file: File): Boolean {
        val base = root.canonicalFile
        val target = try {
            file.canonicalFile
        } catch (error: IOException) {
            return false
        }
        return target == base || target.path.startsWith(base.path + File.separator)
    }

    private fun relative(file: File): String {
        val base = root.canonicalFile
        return file.canonicalFile.toRelativeString(base).ifBlank { "." }
    }

    private fun looksText(file: File): Boolean {
        val bytes = ByteArray(2048)
        val read = file.inputStream().use { it.read(bytes) }
        if (read <= 0) return true
        for (index in 0 until read) {
            if (bytes[index] == 0.toByte()) return false
        }
        return true
    }

    private companion object {
        const val LIMIT = 500
        const val MAX_READ = 400_000L
        const val MAX_WRITE = 1_000_000
        const val MAX_SEARCH_FILE = 256_000L
        const val MAX_PUSH_FILE = 1_000_000L
        val IGNORED = setOf(".git", "build", ".gradle", "node_modules", ".idea", ".kotlin")
    }
}
