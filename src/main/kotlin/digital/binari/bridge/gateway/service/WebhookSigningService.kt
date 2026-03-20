package digital.binari.bridge.gateway.service

import org.springframework.stereotype.Service
import java.time.Instant
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * HMAC-SHA256 signing and verification for webhook requests.
 *
 * Used by gateway filters to:
 * - Sign outbound webhook payloads with per-tenant secrets
 * - Verify inbound signed requests for replay protection
 */
@Service
class WebhookSigningService {

    companion object {
        private const val HMAC_ALGORITHM = "HmacSHA256"
        /** Maximum age of a signed request before it's considered a replay (5 minutes). */
        const val MAX_TIMESTAMP_AGE_SECONDS: Long = 300
    }

    /**
     * Compute HMAC-SHA256 of [body] using [secret].
     *
     * @param body The request body bytes to sign
     * @param secret The per-tenant webhook secret
     * @return Hex-encoded HMAC signature
     */
    fun sign(body: ByteArray, secret: String): String {
        val mac = Mac.getInstance(HMAC_ALGORITHM)
        val keySpec = SecretKeySpec(secret.toByteArray(Charsets.UTF_8), HMAC_ALGORITHM)
        mac.init(keySpec)
        val hmacBytes = mac.doFinal(body)
        return hmacBytes.joinToString("") { "%02x".format(it) }
    }

    /**
     * Verify an HMAC signature against the computed value, with replay protection.
     *
     * @param body The request body bytes
     * @param signature The hex-encoded HMAC signature from the request header
     * @param secret The per-tenant webhook secret
     * @param timestamp The timestamp from the request header (epoch seconds)
     * @return true if signature is valid and timestamp is within the allowed window
     */
    fun verify(body: ByteArray, signature: String, secret: String, timestamp: Long): Boolean {
        // Replay protection: reject if timestamp is too old
        val now = Instant.now().epochSecond
        if (kotlin.math.abs(now - timestamp) > MAX_TIMESTAMP_AGE_SECONDS) {
            return false
        }

        val expected = sign(body, secret)
        return constantTimeEquals(expected, signature)
    }

    /**
     * Constant-time string comparison to prevent timing attacks.
     */
    private fun constantTimeEquals(a: String, b: String): Boolean {
        if (a.length != b.length) return false
        var result = 0
        for (i in a.indices) {
            result = result or (a[i].code xor b[i].code)
        }
        return result == 0
    }
}
