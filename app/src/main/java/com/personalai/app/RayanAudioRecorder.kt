package com.personalai.app

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.NoiseSuppressor

/** Captures short 16 kHz mono buffers in memory only; caller must zero the result. */
object RayanAudioRecorder {
    const val SAMPLE_RATE = 16_000

    fun record(context: Context, seconds: Int): FloatArray {
        require(seconds in 1..15)
        val count = SAMPLE_RATE * seconds
        val minimum = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        check(minimum > 0) { "AudioRecord buffer unavailable" }
        val audio = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            maxOf(minimum, count * 2)
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
        return FloatArray(count) { pcm[it].toFloat() / 32768f }.also { pcm.fill(0) }
    }
}
