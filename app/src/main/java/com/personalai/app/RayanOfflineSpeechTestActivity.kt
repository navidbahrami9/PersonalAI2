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

class RayanOfflineSpeechTestActivity : ComponentActivity() {
    private var statusCallback: ((String) -> Unit)? = null
    private var resultCallback: ((String) -> Unit)? = null

    private val requestAudioPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) runSpeechTest()
            else updateStatus("مجوز میکروفون صادر نشد.")
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                SpeechTestPanel(
                    onCallbacks = { status, result ->
                        statusCallback = status
                        resultCallback = result
                    },
                    onStart = {
                        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                            == PackageManager.PERMISSION_GRANTED
                        ) runSpeechTest()
                        else requestAudioPermission.launch(Manifest.permission.RECORD_AUDIO)
                    }
                )
            }
        }
    }

    private fun updateStatus(value: String) {
        runOnUiThread { statusCallback?.invoke(value) }
    }

    private fun updateResult(value: String) {
        runOnUiThread { resultCallback?.invoke(value) }
    }

    private fun runSpeechTest() {
        Thread({
            var recognizer: RayanOfflinePersianRecognizer? = null
            var samples: FloatArray? = null
            try {
                updateStatus("شش ثانیه گفتار فارسی ضبط می‌شود؛ پیوسته صحبت کن.")
                samples = recordSixSeconds()
                updateStatus("در حال تشخیص آفلاین فارسی روی دستگاه...")
                recognizer = RayanOfflinePersianRecognizer(this)
                val text = recognizer.transcribe(samples)
                updateResult(if (text.isBlank()) "گفتاری تشخیص داده نشد." else text)
                updateStatus("آزمون آفلاین تمام شد؛ هیچ صوتی ذخیره یا ارسال نشد.")
            } catch (error: Exception) {
                updateStatus("آزمون گفتار ناموفق بود: ${error.javaClass.simpleName}")
            } finally {
                samples?.fill(0f)
                recognizer?.close()
            }
        }, "RayanOfflinePersianASR").start()
    }

    private fun recordSixSeconds(): FloatArray {
        val rate = RayanOfflinePersianRecognizer.SAMPLE_RATE
        val count = rate * 6
        val minBytes = AudioRecord.getMinBufferSize(
            rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        check(minBytes > 0) { "AudioRecord buffer unavailable" }
        val audio = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            rate,
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
private fun SpeechTestPanel(
    onCallbacks: (((String) -> Unit, (String) -> Unit) -> Unit),
    onStart: () -> Unit
) {
    var status by remember { mutableStateOf("برای آزمون، دکمه را بزن و شش ثانیه فارسی صحبت کن.") }
    var result by remember { mutableStateOf("") }
    SideEffect { onCallbacks({ status = it }, { result = it }) }
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text("آزمون تشخیص گفتار فارسی آفلاین", style = MaterialTheme.typography.headlineSmall)
        Text(status)
        Button(onClick = onStart, modifier = Modifier.fillMaxWidth()) {
            Text("شروع آزمون شش‌ثانیه‌ای")
        }
        Text("متن تشخیص‌داده‌شده:")
        Text(result.ifBlank { "—" })
        Text("مدل روی گوشی اجرا می‌شود؛ صوت خام نگهداری یا به اینترنت ارسال نمی‌شود.")
    }
}
