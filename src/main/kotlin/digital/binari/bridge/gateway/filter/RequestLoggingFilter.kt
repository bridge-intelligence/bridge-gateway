package digital.binari.bridge.gateway.filter

import org.slf4j.LoggerFactory
import org.springframework.cloud.gateway.filter.GatewayFilterChain
import org.springframework.cloud.gateway.filter.GlobalFilter
import org.springframework.core.Ordered
import org.springframework.stereotype.Component
import org.springframework.web.server.ServerWebExchange
import reactor.core.publisher.Mono

@Component
class RequestLoggingFilter : GlobalFilter, Ordered {

    private val logger = LoggerFactory.getLogger(RequestLoggingFilter::class.java)

    override fun getOrder(): Int = -1

    override fun filter(exchange: ServerWebExchange, chain: GatewayFilterChain): Mono<Void> {
        val request = exchange.request
        val method = request.method
        val path = request.uri.path
        val correlationId = request.headers.getFirst(CorrelationIdFilter.CORRELATION_ID_HEADER) ?: "unknown"
        val clientIp = request.remoteAddress?.address?.hostAddress ?: "unknown"
        val startTime = System.currentTimeMillis()

        logger.info(
            ">>> Incoming request: method={}, path={}, correlationId={}, clientIp={}",
            method, path, correlationId, clientIp
        )

        return chain.filter(exchange).then(Mono.fromRunnable {
            val duration = System.currentTimeMillis() - startTime
            val statusCode = exchange.response.statusCode?.value() ?: 0

            logger.info(
                "<<< Completed request: method={}, path={}, status={}, duration={}ms, correlationId={}",
                method, path, statusCode, duration, correlationId
            )
        })
    }
}
