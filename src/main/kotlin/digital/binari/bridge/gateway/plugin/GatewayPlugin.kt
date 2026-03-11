package digital.binari.bridge.gateway.plugin

import org.springframework.web.server.ServerWebExchange
import reactor.core.publisher.Mono

/**
 * Service Provider Interface for gateway plugins.
 *
 * Plugins are executed during request processing at specific phases
 * (PRE_ROUTE, POST_ROUTE, or BOTH). They can inspect, modify, or
 * short-circuit requests and responses.
 */
interface GatewayPlugin {

    /** Unique identifier for this plugin. */
    val id: String

    /** Human-readable name for this plugin. */
    val name: String

    /** Semantic version string for this plugin. */
    val version: String

    /** The phase in which this plugin executes. */
    val phase: PluginPhase

    /** Execution order within the phase. Lower values execute first. */
    val order: Int

    /**
     * Initialize the plugin with the provided configuration settings.
     * Called once when the plugin is enabled.
     */
    fun initialize(config: Map<String, Any>)

    /**
     * Execute the plugin logic for the given exchange.
     * Returns a [PluginResult] indicating whether to proceed or short-circuit.
     */
    fun execute(exchange: ServerWebExchange, context: PluginContext): Mono<PluginResult>

    /**
     * Shut down the plugin and release any resources.
     * Called when the plugin is disabled or the application is shutting down.
     */
    fun shutdown()

    /** Returns whether this plugin is currently enabled. */
    fun isEnabled(): Boolean

    /** Perform a health check and return the plugin's health status. */
    fun healthCheck(): Mono<PluginHealth>
}

/** The phase in the request lifecycle when a plugin executes. */
enum class PluginPhase {
    PRE_ROUTE,
    POST_ROUTE,
    BOTH
}

/** Context passed to a plugin during execution. */
data class PluginContext(
    val routeId: String,
    val correlationId: String,
    val metadata: MutableMap<String, Any> = mutableMapOf()
)

/** Result returned by a plugin after execution. */
data class PluginResult(
    val proceed: Boolean = true,
    val statusCode: Int? = null,
    val responseBody: String? = null,
    val headers: Map<String, String> = emptyMap(),
    val metadata: Map<String, Any> = emptyMap()
)

/** Health status of a plugin. */
data class PluginHealth(
    val healthy: Boolean,
    val details: Map<String, Any> = emptyMap()
)
