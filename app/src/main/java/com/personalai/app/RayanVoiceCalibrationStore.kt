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
 * Encrypted speaker threshold calibration. Stores only numeric similarity scores
 * and the derived threshold, never audio or transcripts.
 */
object RayanVoiceCalibrationStore {
    private const val KEYSTORE = "AndroidKeyStore"
    private const val KEY_ALIAS = "rayan_voice_calibration_aes_v1"
    private const val FILE_NAME = "rayan_voice_calibration_v1.enc"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val MAGIC = 0x52564331
    private const val VERSION = 1
    private const val IV_LENGTH = 12
    private const val TAG_BITS = 128
    private const val MIN_GAP = 0.08f
    private const val MIN_SAMPLES = 3
    private val aad = "RayanVoiceCalibration:v1".toByteArray(Charsets.UTF_8)
    private val lock = Any()

    data class Calibration(
        val modelId: String,
        val threshold: Float,
        val minOwnerScore: Float,
        val maxOtherScore: Float,
        val ownerSamples: Int,
        val otherSamples: Int
    )

    @Throws(Exception::class)
    fun save(
        context: Context,
        modelId: String,
        ownerScores: List<Float>,
        otherScores: List<Float>
    ): Calibration {
        require(modelId.isNotBlank() && modelId.length <= 128)
        require(ownerScores.size >= MIN_SAMPLES && otherScores.size >= MIN_SAMPLES) {
            "At least three owner and three non-owner samples are required"
        }
        require((ownerScores + otherScores).all { it.isFinite() && it in -1f..1f }) {
            "Invalid calibration score"
        }
        val minOwner = ownerScores.minOrNull()!!
        val maxOther = otherScores.maxOrNull()!!
        require(minOwner - maxOther >= MIN_GAP) {
            "Voice scores overlap; collect more varied samples and retry"
        }
        val calibration = Calibration(
            modelId = modelId,
            threshold = (minOwner + maxOther) / 2f,
            minOwnerScore = minOwner,
            maxOtherScore = maxOther,
            ownerSamples = ownerScores.size,
            otherSamples = otherScores.size
        )
        synchronized(lock) {
            val plain = java.io.ByteArrayOutputStream().use { bytes ->
                DataOutputStream(bytes).use { out ->
                    out.writeUTF(calibration.modelId)
                    out.writeFloat(calibration.threshold)
                    out.writeFloat(calibration.minOwnerScore)
                    out.writeFloat(calibration.maxOtherScore)
                    out.writeInt(calibration.ownerSamples)
                    out.writeInt(calibration.otherSamples)
                }
                bytes.toByteArray()
            }
            try {
                val cipher = Cipher.getInstance(TRANSFORMATION)
                cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
                cipher.updateAAD(aad)
                val encrypted = cipher.doFinal(plain)
                val iv = cipher.iv
                require(iv.size == IV_LENGTH)

                val directory = context.filesDir
                val finalFile = File(directory, FILE_NAME)
                val backupFile = File(directory, FILE_NAME + ".previous")
                val tempFile = File.createTempFile(".rayan_voice_calibration_", ".tmp", directory)
                try {
                    FileOutputStream(tempFile).use { file ->
                        DataOutputStream(BufferedOutputStream(file)).use { out ->
                            out.writeInt(MAGIC)
                            out.writeByte(VERSION)
                            out.writeByte(iv.size)
                            out.writeInt(encrypted.size)
                            out.write(iv)
                            out.write(encrypted)
                            out.flush()
                            file.fd.sync()
                        }
                    }
                    if (backupFile.exists() && !backupFile.delete()) error("Cannot clear calibration backup")
                    if (finalFile.exists() && !finalFile.renameTo(backupFile)) error("Cannot preserve old calibration")
                    if (!tempFile.renameTo(finalFile)) {
                        if (backupFile.exists()) backupFile.renameTo(finalFile)
                        error("Cannot commit voice calibration")
                    }
                    if (backupFile.exists()) backupFile.delete()
                } finally {
                    if (tempFile.exists()) tempFile.delete()
                }
            } finally {
                plain.fill(0)
            }
        }
        return calibration
    }

    @Throws(Exception::class)
    fun load(context: Context): Calibration? = synchronized(lock) {
        val file = context.getFileStreamPath(FILE_NAME)
        if (!file.isFile) return@synchronized null
        DataInputStream(BufferedInputStream(FileInputStream(file))).use { input ->
            require(input.readInt() == MAGIC) { "Invalid voice calibration frame" }
            require(input.readUnsignedByte() == VERSION) { "Unsupported voice calibration version" }
            val ivLength = input.readUnsignedByte()
            val encryptedLength = input.readInt()
            require(ivLength == IV_LENGTH && encryptedLength in 16..4096) { "Invalid voice calibration size" }
            val iv = ByteArray(ivLength)
            val encrypted = ByteArray(encryptedLength)
            input.readFully(iv)
            input.readFully(encrypted)
            require(input.read() == -1) { "Unexpected trailing calibration data" }

            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(TAG_BITS, iv))
            cipher.updateAAD(aad)
            val plain = cipher.doFinal(encrypted)
            try {
                DataInputStream(plain.inputStream()).use { decoded ->
                    val modelId = decoded.readUTF()
                    val threshold = decoded.readFloat()
                    val minOwner = decoded.readFloat()
                    val maxOther = decoded.readFloat()
                    val ownerCount = decoded.readInt()
                    val otherCount = decoded.readInt()
                    require(modelId.isNotBlank() && modelId.length <= 128)
                    require(listOf(threshold, minOwner, maxOther).all { it.isFinite() && it in -1f..1f })
                    require(minOwner - maxOther >= MIN_GAP)
                    require(ownerCount >= MIN_SAMPLES && otherCount >= MIN_SAMPLES)
                    require(decoded.read() == -1)
                    Calibration(modelId, threshold, minOwner, maxOther, ownerCount, otherCount)
                }
            } finally {
                plain.fill(0)
            }
        }
    }

    fun delete(context: Context): Boolean = synchronized(lock) {
        val file = context.getFileStreamPath(FILE_NAME)
        val backup = context.getFileStreamPath(FILE_NAME + ".previous")
        val removed = (!file.exists() || file.delete()) && (!backup.exists() || backup.delete())
        val store = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        if (store.containsAlias(KEY_ALIAS)) store.deleteEntry(KEY_ALIAS)
        removed && !file.exists() && !backup.exists()
    }

    private fun getOrCreateKey(): SecretKey {
        val store = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        val existing = store.getKey(KEY_ALIAS, null)
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
