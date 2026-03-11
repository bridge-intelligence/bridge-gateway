package digital.binari.bridge.gateway.plugin.impl

import digital.binari.bridge.gateway.plugin.GatewayPlugin
import digital.binari.bridge.gateway.plugin.PluginContext
import digital.binari.bridge.gateway.plugin.PluginHealth
import digital.binari.bridge.gateway.plugin.PluginPhase
import digital.binari.bridge.gateway.plugin.PluginResult
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.web.server.ServerWebExchange
import reactor.core.publisher.Mono
import java.time.Instant

/**
 * Audit logging plugin.
 *
 * Logs structured audit information for every request that passes through
 * the gateway. In a production setup, this would publish events to Kafka
 * for downstream analytics and compliance systems.
 */
@Component
class AuditPlugin : GatewayPlugin {

    private val logger = LoggerFactory.getLogger(AuditPlugin::class.java)

    override val id: String = "audit"
    override val name: String = "Audit Logging Plugin"
    override val version: String = "1.0.0"
    override val phase: PluginPhase = PluginPhase.POST_ROUTE
    override val order: Int = 10

    @Volatile
    private var enabled: Boolean = false
    private var config: Map<String, Any> = emptyMap()

    override fun initialize(config: Map<String, Any>) {
        this.config = config
        this.enabled = true
        logger.info("Audit plugin initialized with config: {}", config.keys)
    }

    override fun execute(exchange: ServerWebExchange, context: PluginContext): Mono<PluginResult> {
        return Mono.fromCallable {
            val request = exchange.request
            val response = exchange.response

            val method = request.method?.name() ?: "UNKNOWN"
            val path = request.uri.path
            val statusCode = response.statusCode?.value() ?: 0
            val clientIp = request.remoteAddress?.address?.hostAddress ?: "unknown"
            val userAgent = request.headers.getFirst("User-Agent") ?: "unknown"
            val apiKey = request.headers.getFirst("X-API-Key")?.take(8)?.plus("***") ?: "none"
            val timestamp = Instant.now().toString()

            // Structured JSON log entry for audit trail
            logger.info(
                """{"audit":{"timestamp":"{}","correlationId":"{}","routeId":"{}","method":"{}","path":"{}","status":{},"clientIp":"{}","userAgent":"{}","apiKey":"{}"}}""",
                timestamp,
                context.correlationId,
                context.routeId,
                method,
                path,
                statusCode,
                clientIp,
                userAgent,
                apiKey
            )

            PluginResult(
                proceed = true,
                metadata = mapOf(
                    "audit.timestamp" to timestamp,
                    "audit.logged" to true
                )
            )
        }
    }

    override fun shutdown() {
        this.enabled = false
        logger.info("Audit plugin shut down")
    }

    override fun isEnabled(): Boolean = enabled

    override fun healthCheck(): Mono<PluginHealth> {
        return Mono.just(
            PluginHealth(
                healthy = enabled,
                details = mapOf(
                    "plugin" to id,
                    "enabled" to enabled,
                    "version" to version
                )
            )
        )
    }
}
