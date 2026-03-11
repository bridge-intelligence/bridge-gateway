package digital.binari.bridge.gateway.filter

import org.slf4j.LoggerFactory
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.cloud.gateway.filter.GatewayFilter
import org.springframework.cloud.gateway.filter.factory.AbstractGatewayFilterFactory
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import reactor.core.publisher.Mono

@ConfigurationProperties(prefix = "gateway.auth")
data class ApiKeyProperties(
    val apiKeys: List<String> = emptyList()
)

@Component
@EnableConfigurationProperties(ApiKeyProperties::class)
class ApiKeyAuthGatewayFilterFactory(
    private val apiKeyProperties: ApiKeyProperties
) : AbstractGatewayFilterFactory<ApiKeyAuthGatewayFilterFactory.Config>(Config::class.java) {

    private val logger = LoggerFactory.getLogger(ApiKeyAuthGatewayFilterFactory::class.java)

    override fun name(): String = "ApiKeyAuth"

    override fun apply(config: Config): GatewayFilter {
        return GatewayFilter { exchange, chain ->
            val headerName = config.headerName
            val apiKey = exchange.request.headers.getFirst(headerName)

            if (config.required && apiKey.isNullOrBlank()) {
                logger.warn(
                    "Missing API key in header '{}' for request: {}",
                    headerName, exchange.request.uri.path
                )
                exchange.response.statusCode = HttpStatus.UNAUTHORIZED
                exchange.response.headers.add("Content-Type", "application/json")
                val body = """{"error":"unauthorized","message":"Missing API key in header '$headerName'"}"""
                val buffer = exchange.response.bufferFactory().wrap(body.toByteArray())
                return@GatewayFilter exchange.response.writeWith(Mono.just(buffer))
            }

            if (config.required && apiKey != null && !apiKeyProperties.apiKeys.contains(apiKey)) {
                logger.warn(
                    "Invalid API key provided for request: {}",
                    exchange.request.uri.path
                )
                exchange.response.statusCode = HttpStatus.FORBIDDEN
                exchange.response.headers.add("Content-Type", "application/json")
                val body = """{"error":"forbidden","message":"Invalid API key"}"""
                val buffer = exchange.response.bufferFactory().wrap(body.toByteArray())
                return@GatewayFilter exchange.response.writeWith(Mono.just(buffer))
            }

            if (apiKey != null) {
                logger.debug("API key validated successfully for request: {}", exchange.request.uri.path)
            }

            chain.filter(exchange)
        }
    }

    override fun shortcutFieldOrder(): List<String> = listOf("headerName", "required")

    class Config {
        var headerName: String = "X-API-Key"
        var required: Boolean = true
    }
}
