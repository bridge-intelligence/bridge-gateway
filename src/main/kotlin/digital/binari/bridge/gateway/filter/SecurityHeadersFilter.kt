package digital.binari.bridge.gateway.filter

import org.springframework.cloud.gateway.filter.GatewayFilterChain
import org.springframework.cloud.gateway.filter.GlobalFilter
import org.springframework.core.Ordered
import org.springframework.stereotype.Component
import org.springframework.web.server.ServerWebExchange
import reactor.core.publisher.Mono

@Component
class SecurityHeadersFilter : GlobalFilter, Ordered {

    override fun getOrder(): Int = -3

    override fun filter(exchange: ServerWebExchange, chain: GatewayFilterChain): Mono<Void> {
        return chain.filter(exchange).then(Mono.fromRunnable {
            val headers = exchange.response.headers
            headers.set("X-Content-Type-Options", "nosniff")
            headers.set("X-Frame-Options", "DENY")
            headers.set("X-XSS-Protection", "1; mode=block")
            headers.set("Strict-Transport-Security", "max-age=31536000; includeSubDomains")
            headers.set("Referrer-Policy", "strict-origin-when-cross-origin")
            headers.set("Permissions-Policy", "camera=(), microphone=(), geolocation=()")
            headers.set("Cache-Control", "no-store, no-cache, must-revalidate")
            headers.set("Pragma", "no-cache")
            headers.set("X-Gateway-Version", "1.0.0")
        })
    }
}
