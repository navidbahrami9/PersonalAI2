package com.personalai.app

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import org.json.JSONObject
import java.util.ArrayDeque

/**
 * Explicitly user-enabled reader for visible text in the ChatGPT Android app.
 * It does not capture screenshots, keystrokes, or content from other packages.
 * Each record is encrypted with AES-GCM using a non-exportable Android Keystore key.
 */
class RayanScreenMemoryService : AccessibilityService() {

    private var lastSnapshot: String = ""

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        if (event.packageName?.toString() != TARGET_PACKAGE) return

        val root = rootInActiveWindow ?: event.source ?: return
        val snapshot = collectVisibleText(root)
        if (snapshot.isBlank() || snapshot == lastSnapshot) return

        val record = JSONObject()
            .put("captured_at", System.currentTimeMillis())
            .put("package", TARGET_PACKAGE)
            .put("event_type", event.eventType)
            .put("text", snapshot)

        try {
            RayanMemoryCrypto.appendEncryptedRecord(
                this,
                ENCRYPTED_ARCHIVE_FILE,
                record.toString()
            )
            lastSnapshot = snapshot
        } catch (_: Exception) {
            // Keep the service alive; a later accessibility event can retry.
        }
    }

    private fun collectVisibleText(root: AccessibilityNodeInfo): String {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        val collected = LinkedHashSet<String>()
        queue.add(root)
        var visited = 0

        while (queue.isNotEmpty() && visited < MAX_NODES) {
            val node = queue.removeFirst()
            visited++

            if (!node.isPassword) {
                node.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let(collected::add)
                node.contentDescription?.toString()?.trim()
                    ?.takeIf { it.isNotEmpty() }?.let(collected::add)
            }

            for (index in 0 until node.childCount) {
                node.getChild(index)?.let(queue::addLast)
            }
        }

        return collected.joinToString("\n").take(MAX_TEXT_CHARS)
    }

    override fun onInterrupt() = Unit

    companion object {
        private const val TARGET_PACKAGE = "com.openai.chatgpt"
        private const val ENCRYPTED_ARCHIVE_FILE = "rayan_screen_memory_v1.enc"
        private const val MAX_NODES = 1200
        private const val MAX_TEXT_CHARS = 16000
    }
}
