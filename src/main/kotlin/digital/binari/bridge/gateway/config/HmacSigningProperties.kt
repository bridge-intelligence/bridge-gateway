package digital.binari.bridge.gateway.config

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * Configuration properties for HMAC request signing.
 *
 * Configured under `gateway.hmac-signing` in application.yml.
 */
@ConfigurationProperties(prefix = "gateway.hmac-signing")
data class HmacSigningProperties(
    /** Global enable/disable for HMAC signing. */
    val enabled: Boolean = false,

    /** Signature header name. */
    val signatureHeader: String = "X-Bridge-Signature",

    /** Timestamp header name. */
    val timestampHeader: String = "X-Bridge-Timestamp",

    /** Maximum allowed timestamp age in seconds (replay protection). */
    val maxTimestampAgeSeconds: Long = 300,

    /** Per-tenant webhook secrets. Key = tenant ID, value = secret. */
    val tenantSecrets: Map<String, String> = emptyMap(),

    /** Route IDs where outbound signing is enabled. */
    val signOutboundRoutes: List<String> = emptyList(),

    /** Route IDs where inbound verification is required. */
    val verifyInboundRoutes: List<String> = emptyList(),
)
