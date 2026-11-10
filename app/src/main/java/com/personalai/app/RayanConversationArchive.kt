package com.personalai.app

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Append-only encrypted conversation archive.
 * Each message is a separate AES-GCM file, written to a synced temporary file
 * and atomically renamed so an interrupted write cannot corrupt older records.
 */
object RayanConversationArchive {
    private const val KEYSTORE = "AndroidKeyStore"
    private const val KEY_ALIAS = "rayan_conversation_archive_aes_v1"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val MAGIC = 0x52434131 // "RCA1"
    private const val VERSION = 1
    private const val IV_LENGTH = 12
    private const val TAG_BITS = 128
    private const val MAX_CIPHERTEXT_BYTES = 1_100_000
    private const val PREFIX = "rayan_conversation_v1_"
    private const val PREFS = "rayan_conversation_archive"
    private const val CONVERSATION_KEY = "conversation_id"
    private val lock = Any()
    private val aad = "RayanConversationArchive:v1".toByteArray(Charsets.UTF_8)

    data class Message(
        val messageId: String,
        val conversationId: String,
        val sequence: Long,
        val timestamp: Long,
        val role: String,
        val text: String,
        val status: String,
        val origin: String
    )

    @Throws(Exception::class)
    fun append(
        context: Context,
        role: String,
        text: String,
        origin: String,
        status: String = "pending",
        messageId: String = UUID.randomUUID().toString()
    ): Boolean {
        require(role == "user" || role == "assistant") { "Invalid conversation role" }
        require(text.isNotBlank()) { "Message text is empty" }
        require(text.length <= 100_000) { "Message is too large" }
        require(origin == "typed" || origin == "speech" || origin == "assistant" || origin == "system") {
            "Invalid message origin"
        }
        synchronized(lock) {
            val directory = context.filesDir
            val existing = readAllLocked(directory)
            if (existing.any { it.messageId == messageId }) return false

            val conversationId = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(CONVERSATION_KEY, null) ?: UUID.randomUUID().toString().also { id ->
                    check(
                        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                            .edit().putString(CONVERSATION_KEY, id).commit()
                    ) { "Could not persist conversation ID" }
                }
            val sequence = (existing.maxOfOrNull { it.sequence } ?: 0L) + 1L
            val message = Message(
                messageId = messageId,
                conversationId = conversationId,
                sequence = sequence,
                timestamp = System.currentTimeMillis(),
                role = role,
                text = text,
                status = status,
                origin = origin
            )
            val plain = JSONObject().apply {
                put("message_id", message.messageId)
                put("conversation_id", message.conversationId)
                put("sequence", message.sequence)
                put("timestamp", message.timestamp)
                put("role", message.role)
                put("text", message.text)
                put("status", message.status)
                put("origin", message.origin)
            }.toString().toByteArray(Charsets.UTF_8)

            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
            cipher.updateAAD(aad)
            val encrypted = cipher.doFinal(plain)
            val iv = cipher.iv
            require(iv.size == IV_LENGTH) { "Unexpected AES-GCM IV length" }
            require(encrypted.size <= MAX_CIPHERTEXT_BYTES) { "Encrypted message is too large" }

            val finalFile = File(directory, PREFIX + sequence.toString().padStart(12, '0') + "_" + messageId + ".enc")
            check(!finalFile.exists()) { "Archive sequence collision" }
            val tempFile = File.createTempFile(".rayan_conversation_", ".tmp", directory)
            try {
                val fileOutput = FileOutputStream(tempFile)
                try {
                    val output = DataOutputStream(BufferedOutputStream(fileOutput))
                    output.writeInt(MAGIC)
                    output.writeByte(VERSION)
                    output.writeByte(iv.size)
                    output.writeInt(encrypted.size)
                    output.write(iv)
                    output.write(encrypted)
                    output.flush()
                    fileOutput.fd.sync()
                } finally {
                    fileOutput.close()
                }
                check(tempFile.renameTo(finalFile)) { "Could not commit encrypted archive record" }
            } finally {
                if (tempFile.exists()) tempFile.delete()
            }
            return true
        }
    }

    @Throws(Exception::class)
    fun readAll(context: Context): List<Message> = synchronized(lock) {
        readAllLocked(context.filesDir)
    }

    private fun readAllLocked(directory: File): List<Message> {
        val files = directory.listFiles { file ->
            file.isFile && file.name.startsWith(PREFIX) && file.name.endsWith(".enc")
        } ?: return emptyList()
        return files.map { file -> readRecord(file) }.sortedBy { it.sequence }
    }

    private fun readRecord(file: File): Message {
        DataInputStream(BufferedInputStream(FileInputStream(file))).use { input ->
            require(input.readInt() == MAGIC) { "Invalid conversation archive frame" }
            require(input.readUnsignedByte() == VERSION) { "Unsupported conversation archive version" }
            val ivLength = input.readUnsignedByte()
            val encryptedLength = input.readInt()
            require(ivLength == IV_LENGTH) { "Invalid AES-GCM IV length" }
            require(encryptedLength in 16..MAX_CIPHERTEXT_BYTES) { "Invalid encrypted record length" }
            val iv = ByteArray(ivLength)
            val encrypted = ByteArray(encryptedLength)
            input.readFully(iv)
            input.readFully(encrypted)
            require(input.read() == -1) { "Unexpected trailing archive data" }

            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(TAG_BITS, iv))
            cipher.updateAAD(aad)
            val json = JSONObject(String(cipher.doFinal(encrypted), Charsets.UTF_8))
            return Message(
                messageId = json.getString("message_id"),
                conversationId = json.getString("conversation_id"),
                sequence = json.getLong("sequence"),
                timestamp = json.getLong("timestamp"),
                role = json.getString("role"),
                text = json.getString("text"),
                status = json.optString("status", "pending"),
                origin = json.optString("origin", "typed")
            )
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
