package com.personalai.app

/**
 * Strict parser for explicit Persian voice commands.
 * Parsing only: this class never executes actions.
 */
object RayanVoiceCommandRouter {
    enum class Action {
        STATUS, REPORT, SAFE_AUDIT, MEMORY_TEST, BUILD_APK, HELP
    }

    data class Request(
        val action: Action,
        val requiresParkedConfirmation: Boolean,
        val spokenPhrase: String
    )

    fun parse(transcript: String): Request? {
        val normalized = normalize(transcript)
        if (!normalized.startsWith("رایان ")) return null

        val phrase = normalized.removePrefix("رایان ").trim()
        val action = when {
            phrase in setOf("وضعیت", "وضعیت پروژه", "وضعیت سیستم", "خبر") ->
                Action.STATUS
            phrase in setOf("گزارش", "گزارش بده", "گزارش پروژه", "نتیجه را بگو") ->
                Action.REPORT
            phrase in setOf("بررسی سلامت", "بررسی کلی", "بازبینی امن", "کنترل سلامت") ->
                Action.SAFE_AUDIT
            phrase in setOf("آزمون حافظه", "تست حافظه", "بررسی حافظه") ->
                Action.MEMORY_TEST
            phrase in setOf("ساخت apk", "ساخت برنامه", "بیلد برنامه") ->
                Action.BUILD_APK
            phrase in setOf("راهنما", "چه فرمان هایی", "فرمان ها") ->
                Action.HELP
            else -> return null
        }

        val parkedOnly = action == Action.BUILD_APK ||
            action == Action.SAFE_AUDIT ||
            action == Action.MEMORY_TEST

        return Request(action, parkedOnly, phrase)
    }

    private fun normalize(value: String): String =
        value.replace('ي', 'ی')
            .replace('ك', 'ک')
            .replace('ۀ', 'ه')
            .replace('ة', 'ه')
            .replace(Regex("[\\u064B-\\u065F\\u0670]"), "")
            .replace(Regex("[،؛:,.!?؟]+"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
}
