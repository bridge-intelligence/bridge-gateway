package digital.binari.bridge.gateway.filter

import digital.binari.bridge.gateway.config.HmacSigningProperties
import digital.binari.bridge.gateway.service.WebhookSigningService
import org.slf4j.LoggerFactory
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.cloud.gateway.filter.GatewayFilterChain
import org.springframework.cloud.gateway.filter.GlobalFilter
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils
import org.springframework.core.Ordered
import org.springframework.core.io.buffer.DataBuffer
import org.springframework.core.io.buffer.DataBufferUtils
import org.springframework.http.HttpStatus
import org.springframework.http.server.reactive.ServerHttpRequestDecorator
import org.springframework.stereotype.Component
import org.springframework.web.server.ServerWebExchange
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono

/**
 * Global filter that verifies HMAC-SHA256 signatures on inbound signed requests.
 *
 * Checks X-Bridge-Signature header against computed HMAC of the request body.
 * Rejects requests with invalid signatures (401) or expired timestamps (replay protection).
 *
 * Only activates for routes listed in gateway.hmac-signing.verify-inbound-routes.
 */
@Component
@EnableConfigurationProperties(HmacSigningProperties::class)
class HmacVerificationFilter(
    private val properties: HmacSigningProperties,
    private val signingService: WebhookSigningService,
) : GlobalFilter, Ordered {

    private val logger = LoggerFactory.getLogger(HmacVerificationFilter::class.java)

    /** Run before most other filters to reject invalid requests early. */
    override fun getOrder(): Int = -5

    override fun filter(exchange: ServerWebExchange, chain: GatewayFilterChain): Mono<Void> {
        if (!properties.enabled) {
            return chain.filter(exchange)
        }

        // Check if this route requires inbound verification
        val routeId = exchange.getAttribute<org.springframework.cloud.gateway.route.Route>(
            ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR
        )?.id

        if (routeId == null || routeId !in properties.verifyInboundRoutes) {
            return chain.filter(exchange)
        }

        // Extract signature and timestamp headers
        val signature = exchange.request.headers.getFirst(properties.signatureHeader)
        val timestampStr = exchange.request.headers.getFirst(properties.timestampHeader)

        if (signature.isNullOrBlank() || timestampStr.isNullOrBlank()) {
            logger.warn("Missing HMAC signature or timestamp headers on route={}", routeId)
            return rejectRequest(exchange, "Missing signature or timestamp header")
        }

        val timestamp = timestampStr.toLongOrNull()
        if (timestamp == null) {
            logger.warn("Invalid timestamp header on route={}: {}", routeId, timestampStr)
            return rejectRequest(exchange, "Invalid timestamp header")
        }

        // Determine tenant secret
        val tenantId = exchange.request.headers.getFirst("X-Tenant-Id") ?: "default"
        val secret = properties.tenantSecrets[tenantId] ?: properties.tenantSecrets["default"]
        if (secret.isNullOrBlank()) {
            logger.warn("No webhook secret configured for tenant={} route={}, rejecting request", tenantId, routeId)
            return rejectRequest(exchange, "No signing secret configured")
        }

        // Read body and verify signature
        return DataBufferUtils.join(exchange.request.body)
            .defaultIfEmpty(exchange.response.bufferFactory().wrap(ByteArray(0)))
            .flatMap { dataBuffer ->
                val bodyBytes = ByteArray(dataBuffer.readableByteCount())
                dataBuffer.read(bodyBytes)
                DataBufferUtils.release(dataBuffer)

                val valid = signingService.verify(bodyBytes, signature, secret, timestamp)

                if (!valid) {
                    logger.warn(
                        "HMAC verification failed: route={}, tenant={}, sig={}",
                        routeId, tenantId, signature.take(16)
                    )
                    return@flatMap rejectRequest(exchange, "Invalid signature or expired timestamp")
                }

                logger.debug("HMAC verification passed: route={}, tenant={}", routeId, tenantId)

                // Re-wrap the body so downstream filters can read it
                val modifiedRequest = object : ServerHttpRequestDecorator(exchange.request) {
                    override fun getBody(): Flux<DataBuffer> {
                        val buffer = exchange.response.bufferFactory().wrap(bodyBytes)
                        return Flux.just(buffer)
                    }
                }

                val mutatedExchange = exchange.mutate()
                    .request(modifiedRequest)
                    .build()

                chain.filter(mutatedExchange)
            }
    }

    private fun rejectRequest(exchange: ServerWebExchange, reason: String): Mono<Void> {
        exchange.response.statusCode = HttpStatus.UNAUTHORIZED
        exchange.response.headers.set("Content-Type", "application/json")
        val body = """{"error":"Unauthorized","message":"$reason"}"""
        val buffer = exchange.response.bufferFactory().wrap(body.toByteArray(Charsets.UTF_8))
        return exchange.response.writeWith(Mono.just(buffer))
    }
}
