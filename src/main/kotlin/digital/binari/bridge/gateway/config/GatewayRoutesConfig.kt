package digital.binari.bridge.gateway.config

import digital.binari.bridge.gateway.filter.RateLimitGatewayFilterFactory
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
    val routes: Map<String, RouteDefinition> = emptyMap(),
    val rateLimit: RateLimitProperties = RateLimitProperties()
)

data class RouteDefinition(
    val enabled: Boolean = true,
    val path: String = "",
    val uri: String = "",
    val stripPrefix: Int = 0,
    val order: Int = 0,
    val methods: List<String> = emptyList(),
    val plugins: List<String> = emptyList(),
    val rewritePath: String = ""
)

data class RateLimitProperties(
    val defaultRequestsPerMinute: Long = 100,
    val defaultRequestsPerHour: Long = 1000,
    val defaultRequestsPerSecond: Double = 10.0,
    val defaultBurstCapacity: Int = 20,
    val routes: Map<String, RateLimitRouteConfig> = emptyMap()
)

data class RateLimitRouteConfig(
    val requestsPerMinute: Long = 100,
    val requestsPerHour: Long = 1000,
    val requestsPerSecond: Double = 10.0,
    val burstCapacity: Int = 20
)

@Configuration
@EnableConfigurationProperties(GatewayRoutesProperties::class)
class GatewayRoutesConfig(
    private val routesProperties: GatewayRoutesProperties,
    private val rateLimitFilterFactory: RateLimitGatewayFilterFactory
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
                routeId, definition.path, definition.uri, definition.order, definition.stripPrefix,
                definition.methods, definition.plugins
            )

            // Split comma-separated path patterns for Spring path() varargs
            val pathPatterns = definition.path.split(",").map { it.trim() }.toTypedArray()

            routeBuilder.route(routeId) { predicateSpec ->
                var predicate = predicateSpec.order(definition.order).path(*pathPatterns)

                if (definition.methods.isNotEmpty()) {
                    val httpMethods = definition.methods.map { HttpMethod.valueOf(it.uppercase()) }
                    predicate = predicate.and()
                        .method(*httpMethods.toTypedArray())
                }

                predicate.filters { filterSpec ->
                    if (definition.rewritePath.isNotBlank()) {
                        val parts = definition.rewritePath.split(",").map { it.trim() }
                        if (parts.size == 2) {
                            filterSpec.rewritePath(parts[0], parts[1])
                            logger.info("Applied RewritePath to route '{}': {} -> {}", routeId, parts[0], parts[1])
                        }
                    } else if (definition.stripPrefix > 0) {
                        filterSpec.stripPrefix(definition.stripPrefix)
                    }
                    filterSpec.addRequestHeader("X-Gateway-Route", routeId)

                    // Apply Redis-backed RateLimit filter to routes configured in gateway.rate-limit.routes
                    val rateLimitConfig = routesProperties.rateLimit.routes[routeId]
                    if (rateLimitConfig != null) {
                        val config = RateLimitGatewayFilterFactory.Config().apply {
                            requestsPerMinute = rateLimitConfig.requestsPerMinute
                            requestsPerHour = rateLimitConfig.requestsPerHour
                            requestsPerSecond = rateLimitConfig.requestsPerSecond
                            burstCapacity = rateLimitConfig.burstCapacity
                        }
                        filterSpec.filter(rateLimitFilterFactory.apply(config))
                        logger.info(
                            "Applied RateLimit filter to route '{}': {}req/min, {}req/hr",
                            routeId, rateLimitConfig.requestsPerMinute, rateLimitConfig.requestsPerHour
                        )
                    }

                    filterSpec
                }.uri(definition.uri)
            }
        }

        return routeBuilder.build()
    }
}
