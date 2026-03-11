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

/**
 * Monetization plugin skeleton.
 *
 * In production, this plugin would:
 * - Read the API key from the request
 * - Check usage quota against a billing service
 * - Enforce rate limits based on the customer's plan
 * - Track API call metering for billing
 *
 * For now, it passes through all requests with a free-tier header.
 */
@Component
class MonetizationPlugin : GatewayPlugin {

    private val logger = LoggerFactory.getLogger(MonetizationPlugin::class.java)

    override val id: String = "monetization"
    override val name: String = "Monetization Plugin"
    override val version: String = "1.0.0"
    override val phase: PluginPhase = PluginPhase.PRE_ROUTE
    override val order: Int = 100

    @Volatile
    private var enabled: Boolean = false
    private var billingServiceUrl: String = ""
    private var freeTierLimit: Int = 1000
    private var config: Map<String, Any> = emptyMap()

    override fun initialize(config: Map<String, Any>) {
        this.config = config
        this.billingServiceUrl = config["billing-service-url"]?.toString() ?: ""
        this.freeTierLimit = (config["free-tier-limit"] as? Number)?.toInt() ?: 1000
        this.enabled = true
        logger.info(
            "Monetization plugin initialized: billingServiceUrl={}, freeTierLimit={}",
            billingServiceUrl, freeTierLimit
        )
    }

    override fun execute(exchange: ServerWebExchange, context: PluginContext): Mono<PluginResult> {
        return Mono.fromCallable {
            val apiKey = exchange.request.headers.getFirst("X-API-Key")
            val path = exchange.request.uri.path

            if (apiKey != null) {
                logger.debug(
                    "Monetization check for apiKey={}***, path={}, correlationId={}",
                    apiKey.take(8), path, context.correlationId
                )
                // TODO: Call billing service at $billingServiceUrl to check quota
                // TODO: Determine customer plan and remaining quota
                // TODO: If quota exceeded, return proceed=false with 402 Payment Required
            } else {
                logger.debug(
                    "No API key present, applying free tier for path={}, correlationId={}",
                    path, context.correlationId
                )
            }

            // For now, always pass through with free-tier header
            PluginResult(
                proceed = true,
                headers = mapOf("X-Monetization-Plan" to "free"),
                metadata = mapOf(
                    "monetization.plan" to "free",
                    "monetization.limit" to freeTierLimit
                )
            )
        }
    }

    override fun shutdown() {
        this.enabled = false
        logger.info("Monetization plugin shut down")
    }

    override fun isEnabled(): Boolean = enabled

    override fun healthCheck(): Mono<PluginHealth> {
        return Mono.just(
            PluginHealth(
                healthy = enabled,
                details = mapOf(
                    "plugin" to id,
                    "enabled" to enabled,
                    "version" to version,
                    "billingServiceUrl" to billingServiceUrl,
                    "freeTierLimit" to freeTierLimit,
                    "billingServiceConfigured" to billingServiceUrl.isNotBlank()
                )
            )
        )
    }
}
