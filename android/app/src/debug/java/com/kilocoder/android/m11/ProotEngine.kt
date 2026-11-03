package com.kilocoder.android.m11

import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Startet PRoot aus nativeLibraryDir. Kein Exec aus filesDir: das verbietet
 * SELinux ab targetSdk 29, auch mit chmod +x.
 */
class ProotEngine(
    private val native: File,
    private val work: File,
) {

    class Result(
        val exit: Int,
        val output: List<String>,
        val timedOut: Boolean,
    )

    private val loader = File(native, "libproot-loader.so")
    private val proot = File(native, "libproot.so")
    private val talloc = File(native, "libtalloc.so")
    private val libs = File(work.parentFile, "lib")

    fun missingLibraries(): List<String> =
        listOf(proot, loader, talloc, File(native, "libandroid-shmem.so"))
            .filterNot { it.isFile }
            .map { it.name }

    fun version(timeoutMs: Long = 30_000): Result {
        val command = listOf(proot.absolutePath, "--version")
        return execute(command, null, timeoutMs)
    }

    fun shell(rootfs: File, script: String, timeoutMs: Long = 120_000): Result {
        val command = listOf(
            proot.absolutePath,
            "--link2symlink",
            "--kill-on-exit",
            "--sysvipc",
            "-0",
            "-r",
            rootfs.absolutePath,
            "-b",
            "/dev",
            "-b",
            "/proc",
            "-b",
            "/sys",
            "-w",
            "/root",
            "/usr/bin/env",
            "-i",
            "HOME=/root",
            "PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
            "TERM=dumb",
            "LANG=C",
            "/bin/sh",
            "-c",
            script,
        )
        return execute(command, rootfs, timeoutMs)
    }

    fun start(rootfs: File, script: String): Process {
        val command = listOf(
            proot.absolutePath,
            "--link2symlink",
            "--kill-on-exit",
            "--sysvipc",
            "-0",
            "-r",
            rootfs.absolutePath,
            "-b",
            "/dev",
            "-b",
            "/proc",
            "-b",
            "/sys",
            "-w",
            "/root",
            "/usr/bin/env",
            "-i",
            "HOME=/root",
            "PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
            "TERM=dumb",
            "/bin/sh",
            "-c",
            script,
        )
        return builder(command, rootfs).start()
    }

    private fun execute(command: List<String>, rootfs: File?, timeoutMs: Long): Result {
        val process = builder(command, rootfs).start()
        val lines = mutableListOf<String>()
        val reader = Thread {
            process.inputStream.bufferedReader().useLines { sequence ->
                sequence.forEach { line ->
                    synchronized(lines) { lines += line }
                }
            }
        }
        reader.isDaemon = true
        reader.start()
        val finished = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
        if (!finished) {
            process.destroy()
            if (!process.waitFor(5, TimeUnit.SECONDS)) process.destroyForcibly()
        }
        reader.join(5_000)
        return synchronized(lines) {
            Result(
                exit = if (finished) process.exitValue() else -1,
                output = lines.toList(),
                timedOut = !finished,
            )
        }
    }

    private fun builder(command: List<String>, rootfs: File?): ProcessBuilder {
        work.mkdirs()
        val builder = ProcessBuilder(command)
        builder.directory(work)
        builder.redirectErrorStream(true)
        val env = builder.environment()
        env.clear()
        env["PROOT_LOADER"] = loader.absolutePath
        env["PROOT_TMP_DIR"] = File(work, "tmp").apply { mkdirs() }.absolutePath
        // libtalloc.so heißt im APK ohne Versionssuffix; proot verlangt libtalloc.so.2.
        // Eine Kopie unter dem SONAME im privaten Verzeichnis erfüllt das DT_NEEDED,
        // ohne LD_PRELOAD an den Guest zu vererben.
        libs.mkdirs()
        val link = File(libs, "libtalloc.so.2")
        talloc.copyTo(link, overwrite = true)
        env["LD_LIBRARY_PATH"] = listOf(libs.absolutePath, native.absolutePath).joinToString(":")
        env["HOME"] = "/root"
        env["TMPDIR"] = File(work, "tmp").absolutePath
        if (rootfs != null && !rootfs.isDirectory) {
            throw IOException("Rootfs fehlt: ${rootfs.absolutePath}")
        }
        return builder
    }
}
