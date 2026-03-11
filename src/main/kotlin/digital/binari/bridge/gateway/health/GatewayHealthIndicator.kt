package digital.binari.bridge.gateway.health

import digital.binari.bridge.gateway.plugin.PluginRegistry
import org.slf4j.LoggerFactory
import org.springframework.boot.actuate.health.Health
import org.springframework.boot.actuate.health.ReactiveHealthIndicator
import org.springframework.stereotype.Component
import reactor.core.publisher.Mono

/**
 * Actuator health indicator for the gateway.
 *
 * Reports the overall health of the gateway by checking the health
 * of all enabled plugins. The gateway is considered UP only when
 * all enabled plugins report healthy status.
 */
@Component
class GatewayHealthIndicator(
    private val pluginRegistry: PluginRegistry
) : ReactiveHealthIndicator {

    private val logger = LoggerFactory.getLogger(GatewayHealthIndicator::class.java)

    override fun health(): Mono<Health> {
        return pluginRegistry.getPluginStatusReactive()
            .map { pluginStatuses ->
                val builder = Health.Builder()
                val enabledPlugins = pluginStatuses.filter { it.enabled }
                val unhealthyPlugins = enabledPlugins.filter { !it.healthy }

                builder.withDetail("totalPlugins", pluginStatuses.size)
                builder.withDetail("enabledPlugins", enabledPlugins.size)
                builder.withDetail("healthyPlugins", enabledPlugins.size - unhealthyPlugins.size)

                val pluginDetails = pluginStatuses.map { status ->
                    mapOf(
                        "id" to status.id,
                        "name" to status.name,
                        "version" to status.version,
                        "phase" to status.phase.name,
                        "enabled" to status.enabled,
                        "healthy" to status.healthy
                    )
                }
                builder.withDetail("plugins", pluginDetails)

                if (unhealthyPlugins.isEmpty()) {
                    builder.up().build()
                } else {
                    val unhealthyIds = unhealthyPlugins.map { it.id }
                    logger.warn("Unhealthy plugins detected: {}", unhealthyIds)
                    builder.withDetail("unhealthyPlugins", unhealthyIds)
                    builder.down().build()
                }
            }
            .onErrorResume { error ->
                logger.error("Error checking gateway health: {}", error.message, error)
                Mono.just(
                    Health.down()
                        .withDetail("error", error.message ?: "Unknown error")
                        .build()
                )
            }
    }
}
