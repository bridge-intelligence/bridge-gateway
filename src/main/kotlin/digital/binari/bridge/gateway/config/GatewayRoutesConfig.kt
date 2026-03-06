package digital.binari.bridge.gateway.config

import org.slf4j.LoggerFactory
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.cloud.gateway.route.RouteLocator
import org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder
import org.springframework.cloud.gateway.route.builder.filters
import org.springframework.cloud.gateway.route.builder.routes
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpMethod

@ConfigurationProperties(prefix = "gateway")
data class GatewayRoutesProperties(
    val routes: Map<String, RouteDefinition> = emptyMap()
)

data class RouteDefinition(
    val enabled: Boolean = true,
    val path: String = "",
    val uri: String = "",
    val stripPrefix: Int = 0,
    val order: Int = 0,
    val methods: List<String> = emptyList(),
    val plugins: List<String> = emptyList()
)

@Configuration
@EnableConfigurationProperties(GatewayRoutesProperties::class)
class GatewayRoutesConfig(
    private val routesProperties: GatewayRoutesProperties
) {

    private val logger = LoggerFactory.getLogger(GatewayRoutesConfig::class.java)

    @Bean
    fun customRouteLocator(builder: RouteLocatorBuilder): RouteLocator {
        val routeBuilder = builder.routes()

        // Sort routes by order so specific paths (lower order) register before catch-all
        routesProperties.routes.entries.sortedBy { it.value.order }.forEach { (routeId, definition) ->
            if (!definition.enabled) {
                logger.info("Route '{}' is disabled, skipping registration", routeId)
                return@forEach
            }

            if (definition.path.isBlank() || definition.uri.isBlank()) {
                logger.warn("Route '{}' has empty path or uri, skipping", routeId)
                return@forEach
            }

            logger.info(
                "Registering route '{}': path={}, uri={}, order={}, stripPrefix={}, methods={}, plugins={}",
                routeId, definition.path, definition.order, definition.uri, definition.stripPrefix,
                definition.methods, definition.plugins
            )

            routeBuilder.route(routeId) { predicateSpec ->
                var predicate = predicateSpec.order(definition.order).path(definition.path)

                if (definition.methods.isNotEmpty()) {
                    val httpMethods = definition.methods.map { HttpMethod.valueOf(it.uppercase()) }
                    predicate = predicate.and()
                        .method(*httpMethods.toTypedArray())
                }

                predicate.filters { filterSpec ->
                    if (definition.stripPrefix > 0) {
                        filterSpec.stripPrefix(definition.stripPrefix)
                    }
                    filterSpec.addRequestHeader("X-Gateway-Route", routeId)
                    filterSpec
                }.uri(definition.uri)
            }
        }

        return routeBuilder.build()
    }
}
