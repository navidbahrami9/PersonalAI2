package com.personalai.app

import android.content.Context

/**
 * In-app executor for strictly allowlisted, non-destructive voice commands.
 * An opaque owner session is mandatory; wake-word detection and ASR are not identity.
 * Build operations are intentionally not run from the Android app.
 */
object RayanVoiceCommandRuntime {
    data class Result(
        val accepted: Boolean,
        val message: String,
        val action: RayanVoiceCommandRouter.Action? = null
    )

    fun execute(
        context: Context,
        request: RayanVoiceCommandRouter.Request,
        ownerSession: RayanOwnerVoiceVerifier.Session? = null,
        parkedConfirmation: Boolean = false
    ): Result {
        if (ownerSession?.isValid() != true) {
            return Result(false, "هویت گوینده تأیید نشده؛ فرمان اجرا نشد.", request.action)
        }
        if (request.requiresParkedConfirmation && !parkedConfirmation) {
            return Result(false, "برای این فرمان تأیید جداگانه در حالت توقف لازم است.", request.action)
        }

        return try {
            when (request.action) {
                RayanVoiceCommandRouter.Action.HELP -> Result(
                    true,
                    "فرمان‌های فعال: «رایان وضعیت»، «رایان گزارش»، «رایان آزمون حافظه»، «رایان راهنما».",
                    request.action
                )

                RayanVoiceCommandRouter.Action.STATUS -> {
                    val messages = RayanConversationArchive.readAll(context)
                    Result(
                        true,
                        "رایان آماده است. تعداد پیام‌های بایگانی‌شده: ${messages.size}. " +
                            "قالب صدای مالک: ${if (RayanVoiceTemplateStore.isEnrolled(context)) "ثبت شده" else "ثبت نشده"}.",
                        request.action
                    )
                }

                RayanVoiceCommandRouter.Action.REPORT -> {
                    val messages = RayanConversationArchive.readAll(context)
                    val users = messages.count { it.role == "user" }
                    val assistants = messages.count { it.role == "assistant" }
                    Result(
                        true,
                        "گزارش حافظه: $users پیام کاربر و $assistants پاسخ دستیار؛ " +
                            "شناسه گفت‌وگو: ${RayanConversationArchive.currentConversationId(context)}.",
                        request.action
                    )
                }

                RayanVoiceCommandRouter.Action.MEMORY_TEST -> {
                    val messages = RayanConversationArchive.readAll(context)
                    val valid = messages.all {
                        it.messageId.isNotBlank() &&
                            it.conversationId.isNotBlank() &&
                            it.text.isNotBlank() &&
                            (it.role == "user" || it.role == "assistant")
                    }
                    Result(
                        valid,
                        if (valid) "آزمون رمزگشایی حافظه موفق بود؛ ${messages.size} پیام خوانده شد."
                        else "آزمون حافظه ناموفق بود؛ ساختار یک یا چند پیام معتبر نیست.",
                        request.action
                    )
                }

                RayanVoiceCommandRouter.Action.SAFE_AUDIT -> {
                    val messages = RayanConversationArchive.readAll(context)
                    val enrolled = RayanVoiceTemplateStore.isEnrolled(context)
                    Result(
                        true,
                        "بازبینی فقط‌خواندنی انجام شد. پیام‌های قابل رمزگشایی: ${messages.size}. " +
                            "قالب صدای مالک: ${if (enrolled) "موجود" else "موجود نیست"}. " +
                            "این بررسی، احراز هویت گوینده یا سلامت کامل دستگاه را اثبات نمی‌کند.",
                        request.action
                    )
                }

                RayanVoiceCommandRouter.Action.BUILD_APK -> Result(
                    false,
                    "ساخت APK از داخل برنامه فعال نیست؛ اجرای Gradle باید از ابزار ساخت امن Termux انجام شود.",
                    request.action
                )
            }
        } catch (error: Exception) {
            Result(false, "اجرای فرمان ناموفق بود: ${error.javaClass.simpleName}.", request.action)
        }
    }
}
