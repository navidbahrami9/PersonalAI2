package com.personalai.app

import android.Manifest
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.NoiseSuppressor
import android.os.Bundle
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

class RayanVoiceEnrollmentActivity : ComponentActivity() {
    private val requestAudioPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) beginEnrollment()
            else updateStatus("مجوز میکروفون صادر نشد؛ ثبت صدا انجام نشد.")
        }

    private var statusCallback: ((String) -> Unit)? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                EnrollmentPanel(
                    statusProvider = { statusCallback = it },
                    onEnroll = {
                        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                            == PackageManager.PERMISSION_GRANTED
                        ) beginEnrollment()
                        else requestAudioPermission.launch(Manifest.permission.RECORD_AUDIO)
                    }
                )
            }
        }
    }

    private fun updateStatus(value: String) {
        runOnUiThread { statusCallback?.invoke(value) }
    }

    private fun beginEnrollment() {
        Thread({
            val recordings = mutableListOf<FloatArray>()
            var engine: RayanSpeakerEmbeddingEngine? = null
            try {
                repeat(3) { index ->
                    updateStatus("نمونه ${index + 1} از ۳: سه ثانیه پیوسته و با صدای طبیعی صحبت کن.")
                    recordings += recordThreeSeconds()
                }
                updateStatus("در حال استخراج ویژگی‌های صدای مالک...")
                engine = RayanSpeakerEmbeddingEngine(this)
                val saved = engine.enroll(recordings)
                updateStatus(
                    if (saved) "قالب رمزگذاری‌شدهٔ صدا ثبت شد. احراز هویت هنوز تا پایان کالیبراسیون غیرفعال است."
                    else "ثبت قالب صدا ناموفق بود."
                )
            } catch (error: Exception) {
                updateStatus("ثبت صدا ناموفق بود: ${error.javaClass.simpleName}")
            } finally {
                recordings.forEach { it.fill(0f) }
                engine?.close()
            }
        }, "RayanVoiceEnrollment").start()
    }

    private fun recordThreeSeconds(): FloatArray {
        val sampleRate = RayanSpeakerEmbeddingEngine.SAMPLE_RATE
        val count = sampleRate * 3
        val minBytes = AudioRecord.getMinBufferSize(
            sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        check(minBytes > 0) { "AudioRecord buffer unavailable" }
        val audio = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            maxOf(minBytes, count * 2)
        )
        if (audio.state != AudioRecord.STATE_INITIALIZED) {
            audio.release()
            error("Microphone initialization failed")
        }
        val suppressor = try {
            if (NoiseSuppressor.isAvailable()) {
                NoiseSuppressor.create(audio.audioSessionId)?.apply { enabled = true }
            } else null
        } catch (_: Exception) { null }
        val pcm = ShortArray(count)
        try {
            audio.startRecording()
            var offset = 0
            while (offset < count) {
                val read = audio.read(pcm, offset, count - offset)
                check(read > 0) { "Microphone read failed: $read" }
                offset += read
            }
        } finally {
            try { audio.stop() } catch (_: Exception) {}
            suppressor?.release()
            audio.release()
        }
        return FloatArray(pcm.size) { pcm[it].toFloat() / 32768f }.also { pcm.fill(0) }
    }
}

@Composable
private fun EnrollmentPanel(
    statusProvider: (((String) -> Unit) -> Unit),
    onEnroll: () -> Unit
) {
    var status by remember { mutableStateOf("برای ثبت، محیط آرامی انتخاب کن و سه نمونهٔ گفتار ضبط می‌شود.") }
    SideEffect { statusProvider { status = it } }
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text("ثبت صدای مالک رایان", style = MaterialTheme.typography.headlineSmall)
        Text(status)
        Button(onClick = onEnroll, modifier = Modifier.fillMaxWidth()) {
            Text("شروع ثبت سه نمونهٔ صدا")
        }
        Text("فقط بردار عددی صدا با Android Keystore رمزگذاری و ذخیره می‌شود؛ فایل صوتی نگهداری نمی‌شود.")
        Text("تا زمانی که صدای اعضای دیگر و نمونه‌های مالک برای کالیبراسیون مقایسه نشده‌اند، فرمان‌ها قفل می‌مانند.")
    }
}
