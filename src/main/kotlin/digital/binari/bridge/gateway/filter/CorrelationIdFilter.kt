package digital.binari.bridge.gateway.filter

import org.slf4j.LoggerFactory
import org.springframework.cloud.gateway.filter.GatewayFilterChain
import org.springframework.cloud.gateway.filter.GlobalFilter
import org.springframework.core.Ordered
import org.springframework.stereotype.Component
import org.springframework.web.server.ServerWebExchange
import reactor.core.publisher.Mono
import java.util.UUID

@Component
class CorrelationIdFilter : GlobalFilter, Ordered {

    private val logger = LoggerFactory.getLogger(CorrelationIdFilter::class.java)

    companion object {
        const val CORRELATION_ID_HEADER = "X-Correlation-Id"
    }

    override fun getOrder(): Int = -2

    override fun filter(exchange: ServerWebExchange, chain: GatewayFilterChain): Mono<Void> {
        val existingCorrelationId = exchange.request.headers.getFirst(CORRELATION_ID_HEADER)

        val correlationId = if (existingCorrelationId.isNullOrBlank()) {
            UUID.randomUUID().toString().also {
                logger.debug("Generated new correlation ID: {}", it)
            }
        } else {
            existingCorrelationId.also {
                logger.debug("Using existing correlation ID: {}", it)
            }
        }

        val mutatedRequest = exchange.request.mutate()
            .header(CORRELATION_ID_HEADER, correlationId)
            .build()

        val mutatedExchange = exchange.mutate()
            .request(mutatedRequest)
            .build()

        mutatedExchange.response.headers.add(CORRELATION_ID_HEADER, correlationId)

        return chain.filter(mutatedExchange)
    }
}
