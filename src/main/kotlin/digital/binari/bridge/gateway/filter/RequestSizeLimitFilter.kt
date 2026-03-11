package digital.binari.bridge.gateway.filter

import org.springframework.beans.factory.annotation.Value
import org.springframework.cloud.gateway.filter.GatewayFilterChain
import org.springframework.cloud.gateway.filter.GlobalFilter
import org.springframework.core.Ordered
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import org.springframework.web.server.ServerWebExchange
import reactor.core.publisher.Mono

@Component
class RequestSizeLimitFilter(
    @Value("\${gateway.security.max-request-size:10485760}") private val maxRequestSize: Long
) : GlobalFilter, Ordered {

    override fun getOrder(): Int = -4

    override fun filter(exchange: ServerWebExchange, chain: GatewayFilterChain): Mono<Void> {
        val contentLength = exchange.request.headers.contentLength
        if (contentLength > maxRequestSize) {
            exchange.response.statusCode = HttpStatus.PAYLOAD_TOO_LARGE
            exchange.response.headers.set("X-Max-Request-Size", maxRequestSize.toString())
            return exchange.response.setComplete()
        }
        return chain.filter(exchange)
    }
}
