package com.personalai.app

import android.content.Context
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Read-only access layer for Rayan's encrypted screen-memory archive.
 * Does not modify, delete, or re-encrypt existing records.
 */
object RayanScreenMemoryRepository {
    private const val ARCHIVE_FILE = "rayan_screen_memory_v1.enc"
    private const val DEFAULT_RECORD_LIMIT = 12
    private const val DEFAULT_CHAR_LIMIT = 6000

    @Throws(Exception::class)
    fun readRecentContext(
        context: Context,
        maxRecords: Int = DEFAULT_RECORD_LIMIT,
        maxChars: Int = DEFAULT_CHAR_LIMIT
    ): String {
        require(maxRecords in 1..100) { "maxRecords must be between 1 and 100" }
        require(maxChars in 256..20000) { "maxChars must be between 256 and 20000" }

        val records = RayanMemoryCrypto.readAllRecords(context, ARCHIVE_FILE)
        if (records.isEmpty()) return ""

        val formatter = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
        val selected = records.takeLast(maxRecords)
        val output = StringBuilder()

        for (raw in selected) {
            val item = try {
                JSONObject(raw)
            } catch (_: Exception) {
                continue
            }

            val text = item.optString("text").trim()
            if (text.isEmpty()) continue

            val timestamp = item.optLong("captured_at", 0L)
            if (output.isNotEmpty()) output.append("\n\n")
            if (timestamp > 0L) {
                output.append("[")
                    .append(formatter.format(Date(timestamp)))
                    .append("]\n")
            }
            output.append(text)
        }

        val result = output.toString()
        if (result.length <= maxChars) return result
        return result.takeLast(maxChars)
    }

    @Throws(Exception::class)
    fun recordCount(context: Context): Int {
        return RayanMemoryCrypto.readAllRecords(context, ARCHIVE_FILE).size
    }
}
