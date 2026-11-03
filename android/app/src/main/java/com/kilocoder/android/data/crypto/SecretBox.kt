package com.kilocoder.android.data.crypto

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

interface SecretBox {
    fun encrypt(value: String): String
    fun decrypt(value: String): String
}

class AndroidSecretBox : SecretBox {
    override fun encrypt(value: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val iv = cipher.iv
        val data = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        val output = ByteArray(1 + iv.size + data.size)
        output[0] = iv.size.toByte()
        System.arraycopy(iv, 0, output, 1, iv.size)
        System.arraycopy(data, 0, output, 1 + iv.size, data.size)
        return Base64.encodeToString(output, Base64.NO_WRAP)
    }

    override fun decrypt(value: String): String {
        val bytes = Base64.decode(value, Base64.NO_WRAP)
        require(bytes.isNotEmpty()) { "Leerer Key." }
        val size = bytes[0].toInt() and 0xFF
        require(size in 12..16 && bytes.size > 1 + size) { "Ungültiger Key." }
        val iv = bytes.copyOfRange(1, 1 + size)
        val data = bytes.copyOfRange(1 + size, bytes.size)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
        return String(cipher.doFinal(data), Charsets.UTF_8)
    }

    private fun key(): SecretKey {
        val cached = cachedKey
        if (cached != null) return cached
        return synchronized(this) {
            cachedKey ?: loadOrCreate().also { cachedKey = it }
        }
    }

    private fun loadOrCreate(): SecretKey {
        val store = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        val existing = store.getKey(ALIAS, null) as? SecretKey
        if (existing != null) return existing
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val ALIAS = "kilocoder.master"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
    }

    @Volatile
    private var cachedKey: SecretKey? = null
}
