package com.personalai.app

import android.content.Context
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineWhisperModelConfig

/**
 * Offline Persian speech recognition using the multilingual Whisper-tiny model.
 * Audio is processed on-device and is not persisted or transmitted.
 */
class RayanOfflinePersianRecognizer(context: Context) : AutoCloseable {
    companion object {
        const val SAMPLE_RATE = 16_000
        private const val ENCODER = "whisper-tiny/tiny-encoder.int8.onnx"
        private const val DECODER = "whisper-tiny/tiny-decoder.int8.onnx"
        private const val TOKENS = "whisper-tiny/tiny-tokens.txt"
        private const val MIN_SAMPLES = SAMPLE_RATE
    }

    private val assets = context.applicationContext.assets
    private val lock = Any()
    private var recognizer: OfflineRecognizer? = null
    @Volatile private var closed = false

    private fun getRecognizer(): OfflineRecognizer = synchronized(lock) {
        check(!closed) { "Speech recognizer is closed" }
        recognizer ?: run {
            assets.open(ENCODER).use { }
            assets.open(DECODER).use { }
            assets.open(TOKENS).use { }
            val model = OfflineModelConfig(
                whisper = OfflineWhisperModelConfig(
                    encoder = ENCODER,
                    decoder = DECODER,
                    language = "fa",
                    task = "transcribe"
                ),
                tokens = TOKENS,
                numThreads = 4,
                debug = false,
                provider = "cpu",
                modelType = "whisper"
            )
            OfflineRecognizer(
                assetManager = assets,
                config = OfflineRecognizerConfig(
                    featConfig = FeatureConfig(sampleRate = SAMPLE_RATE, featureDim = 80),
                    modelConfig = model,
                    decodingMethod = "greedy_search"
                )
            ).also { recognizer = it }
        }
    }

    @Synchronized
    fun transcribe(samples: FloatArray, sampleRate: Int = SAMPLE_RATE): String {
        require(sampleRate == SAMPLE_RATE) { "Audio must be 16 kHz mono" }
        require(samples.size >= MIN_SAMPLES) { "At least one second of speech is required" }
        require(samples.all { it.isFinite() }) { "Audio contains invalid samples" }

        val model = getRecognizer()
        val stream = model.createStream()
        return try {
            stream.acceptWaveform(samples, sampleRate)
            model.decode(stream)
            model.getResult(stream).text
                .replace('ي', 'ی')
                .replace('ك', 'ک')
                .replace(Regex("\\s+"), " ")
                .trim()
        } finally {
            stream.release()
        }
    }

    override fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
            recognizer?.release()
            recognizer = null
        }
    }
}
