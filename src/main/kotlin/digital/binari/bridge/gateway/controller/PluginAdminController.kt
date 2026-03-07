package digital.binari.bridge.gateway.controller

import digital.binari.bridge.gateway.plugin.PluginHealth
import digital.binari.bridge.gateway.plugin.PluginRegistry
import digital.binari.bridge.gateway.plugin.PluginStatus
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.RestController
import reactor.core.publisher.Mono

@RestController
@RequestMapping("/gateway/admin/plugins")
@PreAuthorize("hasRole('ADMIN')")
class PluginAdminController(
    private val pluginRegistry: PluginRegistry
) {

    private val logger = LoggerFactory.getLogger(PluginAdminController::class.java)

    /**
     * List all plugins with their current status.
     */
    @GetMapping
    fun listPlugins(): Mono<ResponseEntity<List<PluginStatus>>> {
        return pluginRegistry.getPluginStatusReactive()
            .map { ResponseEntity.ok(it) }
    }

    /**
     * Get details for a specific plugin.
     */
    @GetMapping("/{id}")
    fun getPlugin(@PathVariable id: String): Mono<ResponseEntity<PluginDetailResponse>> {
        val plugin = pluginRegistry.getPlugin(id)
            ?: return Mono.just(
                ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(PluginDetailResponse(error = "Plugin '$id' not found"))
            )

        return plugin.healthCheck()
            .map { health ->
                ResponseEntity.ok(
                    PluginDetailResponse(
                        id = plugin.id,
                        name = plugin.name,
                        version = plugin.version,
                        phase = plugin.phase.name,
                        order = plugin.order,
                        enabled = plugin.isEnabled(),
                        health = health
                    )
                )
            }
            .onErrorReturn(
                ResponseEntity.ok(
                    PluginDetailResponse(
                        id = plugin.id,
                        name = plugin.name,
                        version = plugin.version,
                        phase = plugin.phase.name,
                        order = plugin.order,
                        enabled = plugin.isEnabled(),
                        health = PluginHealth(healthy = false, details = mapOf("error" to "Health check failed"))
                    )
                )
            )
    }

    /**
     * Enable a plugin with the provided configuration.
     */
    @PostMapping("/{id}/enable")
    fun enablePlugin(
        @PathVariable id: String,
        @RequestBody config: Map<String, Any>
    ): Mono<ResponseEntity<PluginActionResponse>> {
        logger.info("Request to enable plugin '{}' with config keys: {}", id, config.keys)

        val success = pluginRegistry.enablePlugin(id, config)
        return if (success) {
            Mono.just(
                ResponseEntity.ok(
                    PluginActionResponse(
                        pluginId = id,
                        action = "enable",
                        success = true,
                        message = "Plugin '$id' enabled successfully"
                    )
                )
            )
        } else {
            Mono.just(
                ResponseEntity.badRequest().body(
                    PluginActionResponse(
                        pluginId = id,
                        action = "enable",
                        success = false,
                        message = "Failed to enable plugin '$id'. Check logs for details."
                    )
                )
            )
        }
    }

    /**
     * Disable a plugin.
     */
    @PostMapping("/{id}/disable")
    fun disablePlugin(@PathVariable id: String): Mono<ResponseEntity<PluginActionResponse>> {
        logger.info("Request to disable plugin '{}'", id)

        val success = pluginRegistry.disablePlugin(id)
        return if (success) {
            Mono.just(
                ResponseEntity.ok(
                    PluginActionResponse(
                        pluginId = id,
                        action = "disable",
                        success = true,
                        message = "Plugin '$id' disabled successfully"
                    )
                )
            )
        } else {
            Mono.just(
                ResponseEntity.badRequest().body(
                    PluginActionResponse(
                        pluginId = id,
                        action = "disable",
                        success = false,
                        message = "Failed to disable plugin '$id'. Check logs for details."
                    )
                )
            )
        }
    }

    /**
     * Get the health status of a specific plugin.
     */
    @GetMapping("/{id}/health")
    fun pluginHealth(@PathVariable id: String): Mono<ResponseEntity<PluginHealth>> {
        val plugin = pluginRegistry.getPlugin(id)
            ?: return Mono.just(
                ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(PluginHealth(healthy = false, details = mapOf("error" to "Plugin '$id' not found")))
            )

        return plugin.healthCheck()
            .map { ResponseEntity.ok(it) }
            .onErrorReturn(
                ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(PluginHealth(healthy = false, details = mapOf("error" to "Health check failed")))
            )
    }
}

data class PluginDetailResponse(
    val id: String? = null,
    val name: String? = null,
    val version: String? = null,
    val phase: String? = null,
    val order: Int? = null,
    val enabled: Boolean? = null,
    val health: PluginHealth? = null,
    val error: String? = null
)

data class PluginActionResponse(
    val pluginId: String,
    val action: String,
    val success: Boolean,
    val message: String
)
