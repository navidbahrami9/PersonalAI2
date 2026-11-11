package com.personalai.app

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Stores only a speaker embedding, never a voice recording.
 * AES-GCM key is non-exportable and held by Android Keystore.
 *
 * Storage only: a calibrated verifier must compare a fresh embedding
 * before any command is accepted.
 */
object RayanVoiceTemplateStore {
    private const val KEYSTORE = "AndroidKeyStore"
    private const val KEY_ALIAS = "rayan_owner_voice_template_aes_v1"
    private const val FILE_NAME = "rayan_owner_voice_template_v1.enc"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val MAGIC = 0x52565431 // RVT1
    private const val VERSION = 1
    private const val IV_LENGTH = 12
    private const val TAG_BITS = 128
    private const val MIN_EMBEDDING_SIZE = 64
    private const val MAX_EMBEDDING_SIZE = 4096
    private const val MAX_CIPHERTEXT_SIZE = 20_000
    private val aad = "RayanOwnerVoiceTemplate:v1".toByteArray(Charsets.UTF_8)
    private val lock = Any()

    data class Template(val modelId: String, val embedding: FloatArray)

    fun isEnrolled(context: Context): Boolean = synchronized(lock) {
        context.getFileStreamPath(FILE_NAME).isFile
    }

    @Throws(Exception::class)
    fun save(context: Context, modelId: String, embedding: FloatArray) {
        require(modelId.isNotBlank() && modelId.length <= 128) { "Invalid speaker model ID" }
        require(embedding.size in MIN_EMBEDDING_SIZE..MAX_EMBEDDING_SIZE) {
            "Invalid speaker embedding size"
        }
        require(embedding.all { it.isFinite() }) { "Embedding contains invalid values" }

        synchronized(lock) {
            val plainBytes = java.io.ByteArrayOutputStream().use { bytes ->
                DataOutputStream(bytes).use { output ->
                    output.writeUTF(modelId)
                    output.writeInt(embedding.size)
                    embedding.forEach(output::writeFloat)
                }
                bytes.toByteArray()
            }

            try {
                val cipher = Cipher.getInstance(TRANSFORMATION)
                cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
                cipher.updateAAD(aad)
                val encrypted = cipher.doFinal(plainBytes)
                val iv = cipher.iv
                require(iv.size == IV_LENGTH) { "Unexpected AES-GCM IV length" }
                require(encrypted.size <= MAX_CIPHERTEXT_SIZE) { "Encrypted template is too large" }

                val directory = context.filesDir
                val finalFile = File(directory, FILE_NAME)
                val tempFile = File.createTempFile(".rayan_voice_template_", ".tmp", directory)
                val backupFile = File(directory, FILE_NAME + ".previous")
                try {
                    FileOutputStream(tempFile).use { fileOutput ->
                        val output = DataOutputStream(BufferedOutputStream(fileOutput))
                        output.writeInt(MAGIC)
                        output.writeByte(VERSION)
                        output.writeByte(iv.size)
                        output.writeInt(encrypted.size)
                        output.write(iv)
                        output.write(encrypted)
                        output.flush()
                        fileOutput.fd.sync()
                        output.close()
                    }

                    if (backupFile.exists() && !backupFile.delete()) {
                        throw IllegalStateException("Could not clear previous template backup")
                    }
                    if (finalFile.exists() && !finalFile.renameTo(backupFile)) {
                        throw IllegalStateException("Could not preserve old voice template")
                    }
                    if (!tempFile.renameTo(finalFile)) {
                        if (backupFile.exists()) backupFile.renameTo(finalFile)
                        throw IllegalStateException("Could not commit voice template")
                    }
                    if (backupFile.exists()) backupFile.delete()
                } finally {
                    if (tempFile.exists()) tempFile.delete()
                }
            } finally {
                plainBytes.fill(0)
            }
        }
    }

    @Throws(Exception::class)
    fun load(context: Context): Template? = synchronized(lock) {
        val file = context.getFileStreamPath(FILE_NAME)
        if (!file.isFile) return@synchronized null

        DataInputStream(BufferedInputStream(FileInputStream(file))).use { input ->
            require(input.readInt() == MAGIC) { "Invalid voice template frame" }
            require(input.readUnsignedByte() == VERSION) { "Unsupported voice template version" }
            val ivLength = input.readUnsignedByte()
            val encryptedLength = input.readInt()
            require(ivLength == IV_LENGTH) { "Invalid voice template IV" }
            require(encryptedLength in 16..MAX_CIPHERTEXT_SIZE) { "Invalid voice template size" }

            val iv = ByteArray(ivLength)
            val encrypted = ByteArray(encryptedLength)
            input.readFully(iv)
            input.readFully(encrypted)
            require(input.read() == -1) { "Unexpected trailing voice template data" }

            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(TAG_BITS, iv))
            cipher.updateAAD(aad)
            val plain = cipher.doFinal(encrypted)

            try {
                DataInputStream(plain.inputStream()).use { decoded ->
                    val modelId = decoded.readUTF()
                    require(modelId.isNotBlank() && modelId.length <= 128) { "Invalid model ID" }
                    val size = decoded.readInt()
                    require(size in MIN_EMBEDDING_SIZE..MAX_EMBEDDING_SIZE) {
                        "Invalid speaker embedding size"
                    }
                    val embedding = FloatArray(size) { decoded.readFloat() }
                    require(embedding.all { it.isFinite() }) { "Invalid speaker embedding values" }
                    require(decoded.read() == -1) { "Unexpected voice template payload" }
                    Template(modelId, embedding)
                }
            } finally {
                plain.fill(0)
            }
        }
    }

    fun delete(context: Context): Boolean = synchronized(lock) {
        val file = context.getFileStreamPath(FILE_NAME)
        val backup = context.getFileStreamPath(FILE_NAME + ".previous")
        val fileDeleted = (!file.exists() || file.delete()) &&
            (!backup.exists() || backup.delete())
        val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        if (keyStore.containsAlias(KEY_ALIAS)) keyStore.deleteEntry(KEY_ALIAS)
        fileDeleted && !context.getFileStreamPath(FILE_NAME).exists() &&
            !context.getFileStreamPath(FILE_NAME + ".previous").exists()
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
