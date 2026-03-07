package digital.binari.bridge.gateway.controller

import digital.binari.bridge.gateway.config.GatewayRoutesProperties
import digital.binari.bridge.gateway.config.RouteDefinition
import digital.binari.bridge.gateway.plugin.PluginRegistry
import digital.binari.bridge.gateway.plugin.PluginStatus
import org.slf4j.LoggerFactory
import org.springframework.boot.info.BuildProperties
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.RestController
import reactor.core.publisher.Mono
import java.time.Instant
import java.util.Optional

@RestController
@RequestMapping("/gateway/admin")
@PreAuthorize("hasRole('ADMIN')")
class GatewayAdminController(
    private val routesProperties: GatewayRoutesProperties,
    private val pluginRegistry: PluginRegistry,
    private val buildProperties: Optional<BuildProperties>
) {

    private val logger = LoggerFactory.getLogger(GatewayAdminController::class.java)

    /**
     * List all configured routes.
     */
    @GetMapping("/routes")
    fun listRoutes(): Mono<ResponseEntity<RoutesResponse>> {
        val routes = routesProperties.routes.map { (id, definition) ->
            RouteInfo(
                id = id,
                enabled = definition.enabled,
                path = definition.path,
                uri = definition.uri,
                stripPrefix = definition.stripPrefix,
                methods = definition.methods,
                plugins = definition.plugins
            )
        }

        return Mono.just(
            ResponseEntity.ok(
                RoutesResponse(
                    totalRoutes = routes.size,
                    enabledRoutes = routes.count { it.enabled },
                    routes = routes
                )
            )
        )
    }

    /**
     * Gateway health summary including plugin status.
     */
    @GetMapping("/health")
    fun healthSummary(): Mono<ResponseEntity<GatewayHealthResponse>> {
        return pluginRegistry.getPluginStatusReactive()
            .map { pluginStatuses ->
                val enabledPlugins = pluginStatuses.filter { it.enabled }
                val healthyPlugins = pluginStatuses.filter { it.enabled && it.healthy }
                val allHealthy = enabledPlugins.isEmpty() || enabledPlugins.all { it.healthy }

                ResponseEntity.ok(
                    GatewayHealthResponse(
                        status = if (allHealthy) "UP" else "DEGRADED",
                        timestamp = Instant.now().toString(),
                        totalRoutes = routesProperties.routes.size,
                        enabledRoutes = routesProperties.routes.count { (_, def) -> def.enabled },
                        plugins = PluginsSummary(
                            total = pluginStatuses.size,
                            enabled = enabledPlugins.size,
                            healthy = healthyPlugins.size,
                            details = pluginStatuses
                        )
                    )
                )
            }
    }

    /**
     * Current gateway configuration (sanitized, no secrets).
     */
    @GetMapping("/config")
    fun getConfig(): Mono<ResponseEntity<GatewayConfigResponse>> {
        val routesSummary = routesProperties.routes.map { (id, definition) ->
            RouteConfigSummary(
                id = id,
                enabled = definition.enabled,
                path = definition.path,
                uri = sanitizeUri(definition.uri),
                methods = definition.methods,
                plugins = definition.plugins
            )
        }

        return Mono.just(
            ResponseEntity.ok(
                GatewayConfigResponse(
                    version = buildProperties.map { it.version }.orElse("unknown"),
                    timestamp = Instant.now().toString(),
                    routes = routesSummary,
                    pluginCount = pluginRegistry.getPluginStatus().size
                )
            )
        )
    }

    /**
     * Sanitize URIs to avoid exposing internal service addresses in full.
     * Keeps the scheme and host but removes any query parameters or credentials.
     */
    private fun sanitizeUri(uri: String): String {
        return try {
            val parsed = java.net.URI(uri)
            "${parsed.scheme}://${parsed.host}${if (parsed.port > 0) ":${parsed.port}" else ""}${parsed.path ?: ""}"
        } catch (e: Exception) {
            "***"
        }
    }
}

data class RoutesResponse(
    val totalRoutes: Int,
    val enabledRoutes: Int,
    val routes: List<RouteInfo>
)

data class RouteInfo(
    val id: String,
    val enabled: Boolean,
    val path: String,
    val uri: String,
    val stripPrefix: Int,
    val methods: List<String>,
    val plugins: List<String>
)

data class GatewayHealthResponse(
    val status: String,
    val timestamp: String,
    val totalRoutes: Int,
    val enabledRoutes: Int,
    val plugins: PluginsSummary
)

data class PluginsSummary(
    val total: Int,
    val enabled: Int,
    val healthy: Int,
    val details: List<PluginStatus>
)

data class GatewayConfigResponse(
    val version: String,
    val timestamp: String,
    val routes: List<RouteConfigSummary>,
    val pluginCount: Int
)

data class RouteConfigSummary(
    val id: String,
    val enabled: Boolean,
    val path: String,
    val uri: String,
    val methods: List<String>,
    val plugins: List<String>
)
