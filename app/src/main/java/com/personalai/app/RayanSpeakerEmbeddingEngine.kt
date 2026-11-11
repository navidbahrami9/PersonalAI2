package com.personalai.app

import android.content.Context
import com.k2fsa.sherpa.onnx.OnlineStream
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractor
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractorConfig

/**
 * Offline speaker-embedding engine.
 *
 * Persists only a normalized embedding through RayanVoiceTemplateStore.
 * Raw microphone samples remain in memory and are never written by this class.
 * Scores are diagnostic until device-specific owner/family calibration exists.
 */
class RayanSpeakerEmbeddingEngine(context: Context) : AutoCloseable {
    companion object {
        const val MODEL_ID = "3dspeaker-campplus-en-voxceleb-16k-v1"
        const val MODEL_FILE = "3dspeaker_speech_campplus_sv_en_voxceleb_16k.onnx"
        const val SAMPLE_RATE = 16_000
        private const val MIN_SAMPLES = SAMPLE_RATE * 3
        private const val ENROLLMENT_SAMPLE_COUNT = 3
    }

    private val appContext = context.applicationContext
    private val lock = Any()
    private var extractor: SpeakerEmbeddingExtractor? = null
    @Volatile private var closed = false

    private fun getExtractor(): SpeakerEmbeddingExtractor = synchronized(lock) {
        check(!closed) { "Speaker engine is closed" }
        extractor ?: run {
            appContext.assets.open(MODEL_FILE).use { }
            SpeakerEmbeddingExtractor(
                assetManager = appContext.assets,
                config = SpeakerEmbeddingExtractorConfig(
                    model = MODEL_FILE,
                    numThreads = 2,
                    debug = false,
                    provider = "cpu"
                )
            ).also { extractor = it }
        }
    }

    /** Returns a normalized embedding; at least three seconds of clean speech is required. */
    fun computeEmbedding(samples: FloatArray, sampleRate: Int = SAMPLE_RATE): FloatArray {
        require(sampleRate == SAMPLE_RATE) { "Audio must be resampled to 16 kHz" }
        require(samples.size >= MIN_SAMPLES) { "At least three seconds of speech are required" }
        require(samples.all { it.isFinite() }) { "Audio contains invalid samples" }

        val model = getExtractor()
        val stream: OnlineStream = model.createStream()
        return try {
            stream.acceptWaveform(samples, sampleRate)
            stream.inputFinished()
            check(model.isReady(stream)) { "Speaker model needs more speech samples" }
            normalize(model.compute(stream))
        } finally {
            stream.release()
        }
    }

    /**
     * Enroll from three separately captured speech samples.
     * Only their averaged embedding is stored; raw samples are not retained.
     */
    fun enroll(samples: List<FloatArray>): Boolean {
        require(samples.size == ENROLLMENT_SAMPLE_COUNT) { "Exactly three enrollment samples are required" }
        val vectors = samples.map { computeEmbedding(it) }
        val dimensions = vectors.first().size
        require(vectors.all { it.size == dimensions }) { "Speaker embedding dimensions differ" }
        val mean = FloatArray(dimensions) { index ->
            vectors.sumOf { it[index].toDouble() }.div(vectors.size).toFloat()
        }
        return RayanVoiceTemplateStore.save(appContext, MODEL_ID, normalize(mean))
    }

    /** Cosine similarity in [-1, 1]; never treats a score as authentication. */
    fun score(samples: FloatArray): Float {
        val stored = RayanVoiceTemplateStore.load(appContext)
            ?: throw IllegalStateException("Owner voice has not been enrolled")
        require(stored.modelId == MODEL_ID) { "Stored voice template belongs to another model" }
        val candidate = computeEmbedding(samples)
        require(stored.embedding.size == candidate.size) { "Speaker embedding dimensions differ" }
        var dot = 0.0
        var left = 0.0
        var right = 0.0
        for (i in candidate.indices) {
            val a = stored.embedding[i].toDouble()
            val b = candidate[i].toDouble()
            dot += a * b
            left += a * a
            right += b * b
        }
        if (left <= 0.0 || right <= 0.0) return -1f
        return (dot / (kotlin.math.sqrt(left) * kotlin.math.sqrt(right)))
            .toFloat()
            .coerceIn(-1f, 1f)
    }

    private fun normalize(vector: FloatArray): FloatArray {
        require(vector.isNotEmpty() && vector.all { it.isFinite() }) {
            "Speaker model returned an invalid embedding"
        }
        val norm = kotlin.math.sqrt(vector.sumOf { it.toDouble() * it.toDouble() })
        require(norm > 1e-12) { "Speaker embedding has zero magnitude" }
        return FloatArray(vector.size) { (vector[it] / norm).toFloat() }
    }

    override fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
            extractor?.release()
            extractor = null
        }
    }
}
