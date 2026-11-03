package com.kilocoder.android.m11

import java.io.File
import java.io.IOException

/** Liest die PT_LOAD-Ausrichtung einer ELF64-Datei (aarch64, little endian). */
object ElfCheck {

    data class Info(
        val isElf64: Boolean,
        val machine: Int,
        val maxLoadAlign: Long,
        val hasRelro: Boolean,
    ) {
        val isAarch64: Boolean get() = machine == 183
    }

    fun read(file: File): Info {
        val bytes = file.readBytes()
        if (bytes.size < 64) throw IOException("${file.name}: zu kurz für ELF")
        if (bytes[0] != 0x7F.toByte() || bytes[1] != 'E'.code.toByte() ||
            bytes[2] != 'L'.code.toByte() || bytes[3] != 'F'.code.toByte()
        ) {
            throw IOException("${file.name}: kein ELF")
        }
        val is64 = bytes[4].toInt() == 2
        if (!is64) return Info(false, 0, 0, false)
        val machine = u16(bytes, 18)
        val phoff = u64(bytes, 32)
        val phentsize = u16(bytes, 54)
        val phnum = u16(bytes, 56)
        var align = 0L
        var relro = false
        var offset = phoff.toInt()
        repeat(phnum) {
            if (offset + phentsize > bytes.size || offset < 0) throw IOException("${file.name}: Programmkopf außerhalb der Datei")
            val type = u32(bytes, offset)
            if (type == 1L) align = maxOf(align, u64(bytes, offset + 48))
            if (type == 0x6474E552L) relro = true
            offset += phentsize
        }
        return Info(true, machine, align, relro)
    }

    private fun u16(bytes: ByteArray, at: Int): Int =
        (bytes[at].toInt() and 0xFF) or ((bytes[at + 1].toInt() and 0xFF) shl 8)

    private fun u32(bytes: ByteArray, at: Int): Long {
        var value = 0L
        for (i in 3 downTo 0) value = (value shl 8) or (bytes[at + i].toLong() and 0xFF)
        return value
    }

    private fun u64(bytes: ByteArray, at: Int): Long {
        var value = 0L
        for (i in 7 downTo 0) value = (value shl 8) or (bytes[at + i].toLong() and 0xFF)
        return value
    }
}
