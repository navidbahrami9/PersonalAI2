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

class RayanVoiceCalibrationActivity : ComponentActivity() {
    private val ownerScores = mutableListOf<Float>()
    private val otherScores = mutableListOf<Float>()
    private var statusCallback: ((String) -> Unit)? = null
    private var countsCallback: ((Int, Int) -> Unit)? = null
    @Volatile private var recording = false
    @Volatile private var speakerEngine: RayanSpeakerEmbeddingEngine? = null

    private val requestAudioPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) updateStatus("مجوز میکروفون صادر شد.")
            else updateStatus("مجوز میکروفون صادر نشد.")
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                CalibrationPanel(
                    onCallbacks = { status, counts ->
                        statusCallback = status
                        countsCallback = counts
                    },
                    onOwnerSample = { addCalibrationSample(isOwner = true) },
                    onOtherSample = { addCalibrationSample(isOwner = false) },
                    onSave = { saveCalibration() }
                )
            }
        }
    }

    private fun ensurePermission(): Boolean {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            == PackageManager.PERMISSION_GRANTED
        ) return true
        requestAudioPermission.launch(Manifest.permission.RECORD_AUDIO)
        return false
    }

    private fun addCalibrationSample(isOwner: Boolean) {
        if (!ensurePermission() || recording) return
        recording = true
        Thread({
            var samples: FloatArray? = null
            try {
                updateStatus(
                    if (isOwner) "نمونهٔ صدای مالک را ضبط کن؛ سه ثانیه صحبت پیوسته."
                    else "اکنون یکی از اعضای خانواده سه ثانیه صحبت کند."
                )
                samples = recordThreeSeconds()
                val engine = speakerEngine ?: synchronized(this) {
                    speakerEngine ?: RayanSpeakerEmbeddingEngine(this).also { speakerEngine = it }
                }
                val score = engine.score(samples)
                synchronized(ownerScores) {
                    if (isOwner) ownerScores.add(score) else otherScores.add(score)
                    val ownerCount = ownerScores.size
                    val otherCount = otherScores.size
                    runOnUiThread { countsCallback?.invoke(ownerCount, otherCount) }
                }
                updateStatus(
                    (if (isOwner) "امتیاز نمونهٔ مالک: " else "امتیاز نمونهٔ غیرمالک: ") +
                        "%.3f".format(java.util.Locale.US, score) +
                        "؛ صوت خام ذخیره نشد."
                )
            } catch (error: Exception) {
                updateStatus("ثبت نمونه ناموفق بود: ${error.javaClass.simpleName}")
            } finally {
                samples?.fill(0f)
                recording = false
            }
        }, "RayanVoiceCalibration").start()
    }

    private fun saveCalibration() {
        val owners: List<Float>
        val others: List<Float>
        synchronized(ownerScores) {
            owners = ownerScores.toList()
            others = otherScores.toList()
        }
        try {
            val calibration = RayanVoiceCalibrationStore.save(
                this,
                RayanSpeakerEmbeddingEngine.MODEL_ID,
                owners,
                others
            )
            updateStatus(
                "کالیبراسیون ثبت شد؛ آستانهٔ محافظه‌کارانه: " +
                    "%.3f".format(java.util.Locale.US, calibration.threshold) +
                    ". جلسهٔ احراز هویت فقط ۲۰ ثانیه اعتبار دارد."
            )
        } catch (error: Exception) {
            updateStatus(
                "کالیبراسیون ذخیره نشد: امتیاز صدای مالک و خانواده به اندازهٔ کافی جدا نیست. " +
                    "نمونه‌های بیشتری ضبط کن."
            )
        }
    }

    override fun onDestroy() {
        speakerEngine?.close()
        speakerEngine = null
        super.onDestroy()
    }

    private fun updateStatus(value: String) {
        runOnUiThread { statusCallback?.invoke(value) }
    }

    private fun recordThreeSeconds(): FloatArray {
        val rate = RayanSpeakerEmbeddingEngine.SAMPLE_RATE
        val count = rate * 3
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
private fun CalibrationPanel(
    onCallbacks: (((String) -> Unit, (Int, Int) -> Unit) -> Unit),
    onOwnerSample: () -> Unit,
    onOtherSample: () -> Unit,
    onSave: () -> Unit
) {
    var status by remember { mutableStateOf("سه نمونه از مالک و سه نمونه از صدای خانواده ضبط کن؛ برای هر فرد خانواده نمونهٔ جدا بگیر.") }
    var ownerCount by remember { mutableIntStateOf(0) }
    var otherCount by remember { mutableIntStateOf(0) }
    SideEffect {
        onCallbacks(
            { status = it },
            { owner, other -> ownerCount = owner; otherCount = other }
        )
    }
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("کالیبراسیون صدای رایان", style = MaterialTheme.typography.headlineSmall)
        Text(status)
        Text("نمونه‌های مالک: $ownerCount")
        Button(onClick = onOwnerSample, modifier = Modifier.fillMaxWidth()) {
            Text("ضبط یک نمونه از صدای مالک")
        }
        Text("نمونه‌های غیرمالک: $otherCount")
        Button(onClick = onOtherSample, modifier = Modifier.fillMaxWidth()) {
            Text("ضبط یک نمونه از صدای خانواده")
        }
        Button(
            onClick = onSave,
            enabled = ownerCount >= 3 && otherCount >= 3,
            modifier = Modifier.fillMaxWidth()
        ) { Text("محاسبه و ذخیره آستانه") }
        Text("فقط امتیازهای عددی رمزگذاری می‌شوند؛ صدای خام ذخیره نمی‌شود. اگر امتیازها هم‌پوشانی داشته باشند، احراز هویت فعال نخواهد شد.")
    }
}
