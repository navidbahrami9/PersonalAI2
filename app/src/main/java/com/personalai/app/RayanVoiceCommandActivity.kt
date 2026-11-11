package com.personalai.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import java.util.Locale
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class RayanVoiceCommandActivity : ComponentActivity() {
    @Volatile private var busy = false
    @Volatile private var ttsReady = false
    private var tts: TextToSpeech? = null
    private val ttsInit = CountDownLatch(1)
    private var statusCallback: ((String) -> Unit)? = null
    private var transcriptCallback: ((String) -> Unit)? = null

    private val permission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        if (it) startVoiceCommand() else updateStatus("مجوز میکروفون صادر نشد.")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        tts = TextToSpeech(this) { result ->
            val language = tts?.setLanguage(Locale("fa", "IR")) ?: TextToSpeech.LANG_NOT_SUPPORTED
            ttsReady = result == TextToSpeech.SUCCESS && language >= 0
            if (ttsReady) {
                tts?.setSpeechRate(0.96f)
                tts?.setPitch(1.0f)
            }
            ttsInit.countDown()
        }
        setContent {
            MaterialTheme {
                VoiceCommandPanel(
                    onCallbacks = { status, transcript ->
                        statusCallback = status
                        transcriptCallback = transcript
                    },
                    onStart = {
                        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                            == PackageManager.PERMISSION_GRANTED
                        ) startVoiceCommand() else permission.launch(Manifest.permission.RECORD_AUDIO)
                    }
                )
            }
        }
    }

    private fun startVoiceCommand() {
        if (busy) return
        busy = true
        updateStatus("در حال آماده‌سازی احراز هویت صوتی...")
        Thread({
            var challengeAudio: FloatArray? = null
            var commandAudio: FloatArray? = null
            try {
                check(ttsInit.await(8, TimeUnit.SECONDS) && ttsReady) { "موتور گفتار فارسی آماده نیست" }
                val challenge = RayanVoiceChallenge.next()
                updateStatus("عبارت تصادفی را گوش کن و پس از پایان صدا تکرار کن: $challenge")
                check(speakBlocking("برای تأیید هویت، این سه واژه را تکرار کن: $challenge")) {
                    "پخش عبارت تأیید ناموفق بود"
                }

                updateStatus("اکنون عبارت را تکرار کن؛ شش ثانیه ضبط می‌شود.")
                challengeAudio = RayanAudioRecorder.record(this, 6)
                updateStatus("در حال تطبیق صدای مالک و بررسی عبارت...")
                val session = RayanOwnerVoiceVerifier.verifyAndIssue(this, challengeAudio, challenge)
                    ?: error("صدای گوینده یا عبارت تصادفی تأیید نشد")

                updateStatus("هویت تأیید شد؛ فرمان را با «رایان» شروع کن.")
                check(speakBlocking("هویت تأیید شد. فرمان را بگو.")) { "پخش راهنمای فرمان ناموفق بود" }
                commandAudio = RayanAudioRecorder.record(this, 6)
                updateStatus("در حال تشخیص آفلاین فرمان...")
                val commandText = RayanOfflinePersianRecognizer(this).use { it.transcribe(commandAudio) }
                runOnUiThread { transcriptCallback?.invoke(commandText) }

                val request = RayanVoiceCommandRouter.parse(commandText)
                    ?: error("فرمان شناخته نشد؛ عبارت را با رایان شروع کن")
                val result = RayanVoiceCommandRuntime.execute(
                    context = this,
                    request = request,
                    ownerSession = session,
                    parkedConfirmation = false
                )
                updateStatus(result.message)
                speakBlocking(result.message)
            } catch (error: Exception) {
                val message = error.message ?: "خطای نامشخص"
                updateStatus("فرمان اجرا نشد: $message")
                if (ttsReady) speakBlocking("فرمان اجرا نشد. $message")
            } finally {
                challengeAudio?.fill(0f)
                commandAudio?.fill(0f)
                busy = false
            }
        }, "RayanVoiceCommandFlow").start()
    }

    private fun speakBlocking(text: String): Boolean {
        val engine = tts ?: return false
        val latch = CountDownLatch(1)
        val id = "rayan-" + UUID.randomUUID()
        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) { if (utteranceId == id) latch.countDown() }
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) { if (utteranceId == id) latch.countDown() }
            override fun onError(utteranceId: String?, errorCode: Int) { if (utteranceId == id) latch.countDown() }
        })
        return engine.speak(text.take(300), TextToSpeech.QUEUE_FLUSH, null, id) == TextToSpeech.SUCCESS &&
            latch.await(12, TimeUnit.SECONDS)
    }

    private fun updateStatus(value: String) {
        runOnUiThread { statusCallback?.invoke(value) }
    }

    override fun onDestroy() {
        tts?.stop()
        tts?.shutdown()
        tts = null
        super.onDestroy()
    }
}

@Composable
private fun VoiceCommandPanel(
    onCallbacks: (((String) -> Unit, (String) -> Unit) -> Unit),
    onStart: () -> Unit
) {
    var status by remember { mutableStateOf("برای اجرای فرمان، دکمه را بزن؛ رایان ابتدا هویت صوتی را بررسی می‌کند.") }
    var transcript by remember { mutableStateOf("") }
    SideEffect { onCallbacks({ status = it }, { transcript = it }) }
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text("فرمان صوتی امن رایان", style = MaterialTheme.typography.headlineSmall)
        Text(status)
        Button(onClick = onStart, modifier = Modifier.fillMaxWidth()) { Text("شروع احراز هویت و فرمان") }
        Text("گفتار تشخیص‌داده‌شده:")
        Text(transcript.ifBlank { "—" })
        Text("فرمان‌های حساس بدون تأیید جداگانهٔ توقف اجرا نمی‌شوند. صوت خام ذخیره یا ارسال نمی‌شود.")
    }
}
