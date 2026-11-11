package com.personalai.app

import android.app.*
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.Process
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.runBlocking
import java.util.Locale
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.log10
import kotlin.math.sqrt

class RayanVoiceAssistantService : Service() {
    companion object {
        const val ACTION_START = "com.personalai.app.assistant.START"
        const val ACTION_STOP = "com.personalai.app.assistant.STOP"
        private const val CHANNEL = "rayan_voice_assistant"
        private const val ID = 7411
    }

    @Volatile private var running = false
    private var worker: Thread? = null
    private var tts: TextToSpeech? = null
    private val ttsReady = CountDownLatch(1)
    @Volatile private var ttsOk = false

    override fun onCreate() {
        super.onCreate()
        tts = TextToSpeech(this) { result ->
            val lang = tts?.setLanguage(Locale("fa", "IR")) ?: TextToSpeech.LANG_NOT_SUPPORTED
            ttsOk = result == TextToSpeech.SUCCESS && lang >= 0
            if (ttsOk) { tts?.setSpeechRate(0.96f); tts?.setPitch(1f) }
            ttsReady.countDown()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) stopAssistant()
        if (intent?.action == ACTION_START) startAssistant()
        return START_NOT_STICKY
    }

    private fun startAssistant() {
        if (running) return
        if (Build.VERSION.SDK_INT >= 26) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL, "دستیار صوتی رایان", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val stop = PendingIntent.getService(
            this, ID, Intent(this, RayanVoiceAssistantService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val builder = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CHANNEL)
            else @Suppress("DEPRECATION") Notification.Builder(this)
        val notification = builder.setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("رایان: میکروفون فعال")
            .setContentText("تشخیص آفلاین؛ تأیید هویت پیش از فرمان")
            .setOngoing(true)
            .addAction(android.R.drawable.ic_media_pause, "توقف", stop).build()
        if (Build.VERSION.SDK_INT >= 29)
            startForeground(ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        else startForeground(ID, notification)

        running = true
        status("رایان فعال شد؛ منتظر واژهٔ بیدارباش است.")
        worker = Thread({ listenLoop() }, "RayanVoiceAssistant").also { it.start() }
    }

    private fun listenLoop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
        var asr: RayanOfflinePersianRecognizer? = null
        try {
            check(ttsReady.await(8, TimeUnit.SECONDS) && ttsOk) { "موتور گفتار فارسی آماده نیست" }
            asr = RayanOfflinePersianRecognizer(this)
            while (running) {
                val audio = RayanAudioRecorder.record(this, 5)
                try {
                    var power = 0.0
                    audio.forEach { power += it.toDouble() * it.toDouble() }
                    val rms = sqrt(power / audio.size)
                    val db = if (rms <= 0.0) -90.0 else 20.0 * log10(rms)
                    sendBroadcast(Intent(RayanVoiceCaptureService.ACTION_LEVEL).setPackage(packageName)
                        .putExtra(RayanVoiceCaptureService.EXTRA_LEVEL_DB, db.toFloat()))
                    if (db > -55.0 && wake(asr.transcribe(audio)) &&
                        RayanOwnerVoiceVerifier.isLikelyOwner(this, audio)
                    ) handleCommand(asr)
                } finally { audio.fill(0f) }
            }
        } catch (e: Exception) {
            if (running) status("خطای صوتی: ${e.message ?: e.javaClass.simpleName}")
        } finally {
            running = false
            asr?.close()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun handleCommand(asr: RayanOfflinePersianRecognizer) {
        val challenge = RayanVoiceChallenge.next()
        status("رایان شنیده شد؛ عبارت تصادفی را تکرار کن: $challenge")
        check(speak("برای تأیید هویت، تکرار کن: $challenge"))
        val proof = RayanAudioRecorder.record(this, 6)
        try {
            val session = RayanOwnerVoiceVerifier.verifyAndIssue(this, proof, challenge)
            if (session == null) {
                status("هویت تأیید نشد؛ هیچ فرمانی اجرا نشد.")
                speak("هویت تأیید نشد.")
                return
            }
            check(speak("هویت تأیید شد. فرمان را بگو."))
            val audio = RayanAudioRecorder.record(this, 6)
            try {
                val phrase = asr.transcribe(audio)
                sendBroadcast(Intent(RayanVoiceCaptureService.ACTION_TRANSCRIPT).setPackage(packageName)
                    .putExtra(RayanVoiceCaptureService.EXTRA_TRANSCRIPT, phrase))
                val request = RayanVoiceCommandRouter.parse(phrase)
                if (request == null) {
                    RayanConversationArchive.append(
                        context = this,
                        role = "user",
                        text = phrase,
                        origin = "speech",
                        status = "sent"
                    )
                    val answer = runBlocking {
                        Api.chat(this@RayanVoiceAssistantService, listOf(Msg("user", phrase)))
                    }
                    RayanConversationArchive.append(
                        context = this,
                        role = "assistant",
                        text = answer,
                        origin = "assistant",
                        status = "complete"
                    )
                    status(answer)
                    speak(answer)
                    return
                }
                val result = RayanVoiceCommandRuntime.execute(this, request, session, false)
                status(result.message)
                speak(result.message)
            } finally { audio.fill(0f) }
        } finally { proof.fill(0f) }
    }

    private fun wake(text: String): Boolean {
        val normalized = text.replace('ي', 'ی').replace('ك', 'ک')
            .replace(Regex("[،؛:,.!?؟]+"), " ").replace(Regex("\\s+"), " ").trim()
        return normalized.split(' ').any { it == "رایان" }
    }

    private fun speak(text: String): Boolean {
        val engine = tts ?: return false
        val latch = CountDownLatch(1)
        val id = "rayan-" + UUID.randomUUID()
        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) { if (utteranceId == id) latch.countDown() }
            override fun onStop(utteranceId: String?, interrupted: Boolean) { if (utteranceId == id) latch.countDown() }
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) { if (utteranceId == id) latch.countDown() }
            override fun onError(utteranceId: String?, errorCode: Int) { if (utteranceId == id) latch.countDown() }
        })
        return engine.speak(text.take(700), TextToSpeech.QUEUE_FLUSH, null, id) == TextToSpeech.SUCCESS &&
            latch.await(12, TimeUnit.SECONDS)
    }

    private fun status(message: String) {
        sendBroadcast(Intent(RayanVoiceCaptureService.ACTION_STATUS).setPackage(packageName)
            .putExtra(RayanVoiceCaptureService.EXTRA_STATUS, message))
    }

    private fun stopAssistant() {
        running = false
        worker?.interrupt()
        tts?.stop()
        status("میکروفون رایان خاموش شد.")
        if (worker?.isAlive != true) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    override fun onDestroy() {
        stopAssistant()
        tts?.shutdown()
        tts = null
        super.onDestroy()
    }
}
