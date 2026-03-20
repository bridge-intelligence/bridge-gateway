package digital.binari.bridge.gateway.filter

import digital.binari.bridge.gateway.config.HmacSigningProperties
import digital.binari.bridge.gateway.service.WebhookSigningService
import org.reactivestreams.Publisher
import org.slf4j.LoggerFactory
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.cloud.gateway.filter.GatewayFilterChain
import org.springframework.cloud.gateway.filter.GlobalFilter
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils
import org.springframework.core.Ordered
import org.springframework.core.io.buffer.DataBuffer
import org.springframework.core.io.buffer.DataBufferUtils
import org.springframework.http.server.reactive.ServerHttpRequestDecorator
import org.springframework.stereotype.Component
import org.springframework.web.server.ServerWebExchange
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import java.time.Instant

/**
 * Global filter that computes HMAC-SHA256 of the request body for outbound webhooks
 * and adds X-Bridge-Signature and X-Bridge-Timestamp headers.
 *
 * Only activates for routes listed in gateway.hmac-signing.sign-outbound-routes.
 */
@Component
@EnableConfigurationProperties(HmacSigningProperties::class)
class HmacSigningFilter(
    private val properties: HmacSigningProperties,
    private val signingService: WebhookSigningService,
) : GlobalFilter, Ordered {

    private val logger = LoggerFactory.getLogger(HmacSigningFilter::class.java)

    override fun getOrder(): Int = 10

    override fun filter(exchange: ServerWebExchange, chain: GatewayFilterChain): Mono<Void> {
        if (!properties.enabled) {
            return chain.filter(exchange)
        }

        // Check if this route requires outbound signing
        val routeId = exchange.getAttribute<org.springframework.cloud.gateway.route.Route>(
            ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR
        )?.id

        if (routeId == null || routeId !in properties.signOutboundRoutes) {
            return chain.filter(exchange)
        }

        // Determine tenant secret — use route-level or default secret
        val tenantId = exchange.request.headers.getFirst("X-Tenant-Id") ?: "default"
        val secret = properties.tenantSecrets[tenantId] ?: properties.tenantSecrets["default"]
        if (secret.isNullOrBlank()) {
            logger.warn("No webhook secret configured for tenant={} route={}, skipping HMAC signing", tenantId, routeId)
            return chain.filter(exchange)
        }

        // Read the request body, sign it, and add headers
        return DataBufferUtils.join(exchange.request.body)
            .defaultIfEmpty(exchange.response.bufferFactory().wrap(ByteArray(0)))
            .flatMap { dataBuffer ->
                val bodyBytes = ByteArray(dataBuffer.readableByteCount())
                dataBuffer.read(bodyBytes)
                DataBufferUtils.release(dataBuffer)

                val timestamp = Instant.now().epochSecond.toString()
                val signature = signingService.sign(bodyBytes, secret)

                logger.debug("HMAC signed outbound request: route={}, tenant={}, sig={}", routeId, tenantId, signature.take(16))

                // Create a modified request with signature headers and cached body
                val modifiedRequest = object : ServerHttpRequestDecorator(exchange.request) {
                    override fun getBody(): Flux<DataBuffer> {
                        val buffer = exchange.response.bufferFactory().wrap(bodyBytes)
                        return Flux.just(buffer)
                    }
                }

                val mutatedExchange = exchange.mutate()
                    .request(
                        modifiedRequest.mutate()
                            .header(properties.signatureHeader, signature)
                            .header(properties.timestampHeader, timestamp)
                            .build()
                    )
                    .build()

                chain.filter(mutatedExchange)
            }
    }
}
