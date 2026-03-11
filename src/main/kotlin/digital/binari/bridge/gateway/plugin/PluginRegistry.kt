package digital.binari.bridge.gateway.plugin

import jakarta.annotation.PostConstruct
import jakarta.annotation.PreDestroy
import org.slf4j.LoggerFactory
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.stereotype.Component
import reactor.core.publisher.Mono
import java.util.concurrent.ConcurrentHashMap

data class PluginStatus(
    val id: String,
    val name: String,
    val version: String,
    val phase: PluginPhase,
    val order: Int,
    val enabled: Boolean,
    val healthy: Boolean
)

@Component
@EnableConfigurationProperties(PluginConfigProperties::class)
class PluginRegistry(
    private val plugins: List<GatewayPlugin>,
    private val pluginConfigProperties: PluginConfigProperties
) {

    private val logger = LoggerFactory.getLogger(PluginRegistry::class.java)

    private val pluginsById = ConcurrentHashMap<String, GatewayPlugin>()
    private val enabledPluginIds = ConcurrentHashMap.newKeySet<String>()

    @PostConstruct
    fun initialize() {
        logger.info("Initializing plugin registry with {} discovered plugins", plugins.size)

        plugins.forEach { plugin ->
            pluginsById[plugin.id] = plugin
            logger.info(
                "Discovered plugin: id={}, name={}, version={}, phase={}",
                plugin.id, plugin.name, plugin.version, plugin.phase
            )

            val config = pluginConfigProperties.configs[plugin.id]
            if (config != null && config.enabled) {
                try {
                    plugin.initialize(config.settings)
                    enabledPluginIds.add(plugin.id)
                    logger.info(
                        "Initialized and enabled plugin '{}' with {} settings",
                        plugin.id, config.settings.size
                    )
                } catch (e: Exception) {
                    logger.error("Failed to initialize plugin '{}': {}", plugin.id, e.message, e)
                }
            } else {
                logger.info("Plugin '{}' is not enabled in configuration", plugin.id)
            }
        }

        logger.info(
            "Plugin registry initialized: {} total, {} enabled",
            pluginsById.size, enabledPluginIds.size
        )
    }

    @PreDestroy
    fun shutdown() {
        logger.info("Shutting down plugin registry")
        enabledPluginIds.forEach { id ->
            try {
                pluginsById[id]?.shutdown()
                logger.info("Shut down plugin '{}'", id)
            } catch (e: Exception) {
                logger.error("Error shutting down plugin '{}': {}", id, e.message, e)
            }
        }
    }

    /**
     * Get a plugin by its unique identifier.
     */
    fun getPlugin(id: String): GatewayPlugin? = pluginsById[id]

    /**
     * Get all enabled plugins for a given phase, sorted by execution order.
     */
    fun getEnabledPlugins(phase: PluginPhase): List<GatewayPlugin> {
        return pluginsById.values
            .filter { plugin ->
                enabledPluginIds.contains(plugin.id) &&
                    plugin.isEnabled() &&
                    (plugin.phase == phase || plugin.phase == PluginPhase.BOTH)
            }
            .sortedBy { it.order }
    }

    /**
     * Enable a plugin with the given configuration.
     * Returns true if the plugin was successfully enabled.
     */
    fun enablePlugin(id: String, config: Map<String, Any>): Boolean {
        val plugin = pluginsById[id] ?: run {
            logger.warn("Cannot enable unknown plugin '{}'", id)
            return false
        }

        return try {
            plugin.initialize(config)
            enabledPluginIds.add(id)
            logger.info("Enabled plugin '{}' with {} settings", id, config.size)
            true
        } catch (e: Exception) {
            logger.error("Failed to enable plugin '{}': {}", id, e.message, e)
            false
        }
    }

    /**
     * Disable a plugin.
     * Returns true if the plugin was successfully disabled.
     */
    fun disablePlugin(id: String): Boolean {
        val plugin = pluginsById[id] ?: run {
            logger.warn("Cannot disable unknown plugin '{}'", id)
            return false
        }

        return try {
            plugin.shutdown()
            enabledPluginIds.remove(id)
            logger.info("Disabled plugin '{}'", id)
            true
        } catch (e: Exception) {
            logger.error("Failed to disable plugin '{}': {}", id, e.message, e)
            false
        }
    }

    /**
     * Get the status of all registered plugins, including health information.
     */
    fun getPluginStatus(): List<PluginStatus> {
        return pluginsById.values.map { plugin ->
            val isEnabled = enabledPluginIds.contains(plugin.id) && plugin.isEnabled()
            val isHealthy = if (isEnabled) {
                try {
                    plugin.healthCheck().block()?.healthy ?: false
                } catch (e: Exception) {
                    false
                }
            } else {
                false
            }

            PluginStatus(
                id = plugin.id,
                name = plugin.name,
                version = plugin.version,
                phase = plugin.phase,
                order = plugin.order,
                enabled = isEnabled,
                healthy = isHealthy
            )
        }
    }

    /**
     * Get the status of all registered plugins reactively.
     */
    fun getPluginStatusReactive(): Mono<List<PluginStatus>> {
        val statusList = pluginsById.values.map { plugin ->
            val isEnabled = enabledPluginIds.contains(plugin.id) && plugin.isEnabled()
            if (isEnabled) {
                plugin.healthCheck()
                    .map { health ->
                        PluginStatus(
                            id = plugin.id,
                            name = plugin.name,
                            version = plugin.version,
                            phase = plugin.phase,
                            order = plugin.order,
                            enabled = true,
                            healthy = health.healthy
                        )
                    }
                    .onErrorReturn(
                        PluginStatus(
                            id = plugin.id,
                            name = plugin.name,
                            version = plugin.version,
                            phase = plugin.phase,
                            order = plugin.order,
                            enabled = true,
                            healthy = false
                        )
                    )
            } else {
                Mono.just(
                    PluginStatus(
                        id = plugin.id,
                        name = plugin.name,
                        version = plugin.version,
                        phase = plugin.phase,
                        order = plugin.order,
                        enabled = false,
                        healthy = false
                    )
                )
            }
        }

        return Mono.zip(statusList) { results ->
            results.map { it as PluginStatus }
        }.defaultIfEmpty(emptyList())
    }
}
