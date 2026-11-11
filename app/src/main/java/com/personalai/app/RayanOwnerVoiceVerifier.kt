package com.personalai.app

/**
 * Opaque, short-lived proof that the on-device speaker verifier accepted the owner.
 * No public constructor or token factory exists until a real calibrated verifier
 * is installed. Consequently, speech commands remain fail-closed for now.
 */
object RayanOwnerVoiceVerifier {
    class Session private constructor(
        private val expiresAtElapsedRealtime: Long
    ) {
        internal fun isValid(): Boolean =
            android.os.SystemClock.elapsedRealtime() < expiresAtElapsedRealtime
    }

    // Session issuance will be added only inside this verifier after model scoring,
    // signal-quality checks, and device-specific threshold calibration are complete.
}
