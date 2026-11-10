package com.personalai.app

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.nio.ByteBuffer
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Per-record AES-256-GCM encryption backed by the Android Keystore.
 * The key is non-exportable and remains on this device.
 *
 * File frame: magic(4), version(1), IV length(1), ciphertext length(4), IV, ciphertext+tag.
 */
object RayanMemoryCrypto {
    private const val KEYSTORE = "AndroidKeyStore"
    private const val KEY_ALIAS = "rayan_screen_memory_aes_v1"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val MAGIC = 0x524D454D // "RMEM"
    private const val VERSION = 1
    private const val IV_LENGTH = 12
    private const val TAG_BITS = 128
    private const val MAX_CIPHERTEXT_BYTES = 1_100_000
    private val lock = Any()
    private val aad = "RayanScreenMemory:v1".toByteArray(Charsets.UTF_8)

    @Throws(Exception::class)
    fun appendEncryptedRecord(context: Context, fileName: String, plainText: String) {
        synchronized(lock) {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
            cipher.updateAAD(aad)
            val encrypted = cipher.doFinal(plainText.toByteArray(Charsets.UTF_8))
            val iv = cipher.iv
            require(iv.size == IV_LENGTH) { "Unexpected AES-GCM IV length" }
            require(encrypted.size <= MAX_CIPHERTEXT_BYTES) { "Encrypted record is too large" }

            DataOutputStream(
                BufferedOutputStream(
                    context.openFileOutput(fileName, Context.MODE_PRIVATE or Context.MODE_APPEND)
                )
            ).use { output ->
                output.writeInt(MAGIC)
                output.writeByte(VERSION)
                output.writeByte(iv.size)
                output.writeInt(encrypted.size)
                output.write(iv)
                output.write(encrypted)
                output.flush()
            }
        }
    }

    @Throws(Exception::class)
    fun readAllRecords(context: Context, fileName: String): List<String> {
        synchronized(lock) {
            val file = context.getFileStreamPath(fileName)
            if (!file.exists()) return emptyList()
            val result = ArrayList<String>()
            DataInputStream(BufferedInputStream(file.inputStream())).use { input ->
                while (true) {
                    val first = input.read()
                    if (first == -1) break
                    val rest = ByteArray(9)
                    input.readFully(rest)
                    val header = ByteBuffer.allocate(10).put(first.toByte()).put(rest).flip()
                    val magic = header.int
                    val version = header.get().toInt() and 0xff
                    val ivLength = header.get().toInt() and 0xff
                    val encryptedLength = header.int
                    require(magic == MAGIC) { "Invalid memory archive frame" }
                    require(version == VERSION) { "Unsupported memory archive version" }
                    require(ivLength == IV_LENGTH) { "Invalid AES-GCM IV length" }
                    require(encryptedLength in 16..MAX_CIPHERTEXT_BYTES) { "Invalid encrypted record length" }

                    val iv = ByteArray(ivLength)
                    val encrypted = ByteArray(encryptedLength)
                    input.readFully(iv)
                    input.readFully(encrypted)

                    val cipher = Cipher.getInstance(TRANSFORMATION)
                    cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(TAG_BITS, iv))
                    cipher.updateAAD(aad)
                    result.add(String(cipher.doFinal(encrypted), Charsets.UTF_8))
                }
            }
            return result
        }
    }

    fun deleteArchive(context: Context, fileName: String): Boolean {
        synchronized(lock) {
            val file = context.getFileStreamPath(fileName)
            return !file.exists() || file.delete()
        }
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        val existing = keyStore.getKey(KEY_ALIAS, null)
        if (existing is SecretKey) return existing

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setKeySize(256)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return generator.generateKey()
    }
}
