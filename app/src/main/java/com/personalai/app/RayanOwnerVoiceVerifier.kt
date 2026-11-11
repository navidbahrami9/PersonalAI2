package com.personalai.app

/**
 * Opaque, short-lived proof that the on-device speaker verifier accepted the owner.
 * No public constructor or token factory exists until a real calibrated verifier
 * is installed. Consequently, speech commands remain fail-closed for now.
 */
object RayanOwnerVoiceVerifier {
    private object Issuer
    class Session internal constructor(
        private val expiresAtElapsedRealtime: Long,
        private val issuer: Any
    ) {
        internal fun isValid(): Boolean =
            issuer === Issuer &&
                android.os.SystemClock.elapsedRealtime() < expiresAtElapsedRealtime
    }

    /**
     * Issues a short-lived owner session only when a randomized Persian phrase
     * is recognized exactly and the speaker score clears the locally calibrated
     * owner-vs-family threshold. Callers must discard the audio buffer afterward.
     */
    fun verifyAndIssue(
        context: android.content.Context,
        samples: FloatArray,
        expectedChallenge: String
    ): Session? {
        if (expectedChallenge.trim().length < 8) return null
        val calibration = RayanVoiceCalibrationStore.load(context) ?: return null
        if (calibration.modelId != RayanSpeakerEmbeddingEngine.MODEL_ID) return null

        val recognized = RayanOfflinePersianRecognizer(context).use {
            it.transcribe(samples)
        }
        if (normalizeChallenge(recognized) != normalizeChallenge(expectedChallenge)) return null

        val score = RayanSpeakerEmbeddingEngine(context).use { it.score(samples) }
        if (score < calibration.threshold) return null

        return Session(android.os.SystemClock.elapsedRealtime() + 20_000L, Issuer)
    }

    private fun normalizeChallenge(value: String): String =
        value.replace('ي', 'ی')
            .replace('ك', 'ک')
            .replace(Regex("[،؛:,.!?؟]+"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
}
