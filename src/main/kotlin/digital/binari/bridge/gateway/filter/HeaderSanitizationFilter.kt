package digital.binari.bridge.gateway.filter

import org.springframework.cloud.gateway.filter.GatewayFilterChain
import org.springframework.cloud.gateway.filter.GlobalFilter
import org.springframework.core.Ordered
import org.springframework.stereotype.Component
import org.springframework.web.server.ServerWebExchange
import reactor.core.publisher.Mono

/**
 * Strips all inbound X-Bridge-* headers to prevent identity spoofing.
 * These headers are set by JwtValidationFilter after authentication —
 * any inbound values must be removed first.
 */
@Component
class HeaderSanitizationFilter : GlobalFilter, Ordered {

    override fun getOrder(): Int = -100  // Run before everything else

    override fun filter(exchange: ServerWebExchange, chain: GatewayFilterChain): Mono<Void> {
        val sanitized = exchange.request.mutate()
        // Strip all X-Bridge-* headers that could be used for identity spoofing
        val headersToStrip = exchange.request.headers.keys.filter {
            it.startsWith("X-Bridge-", ignoreCase = true)
        }
        headersToStrip.forEach { sanitized.headers { headers -> headers.remove(it) } }

        return chain.filter(exchange.mutate().request(sanitized.build()).build())
    }
}
