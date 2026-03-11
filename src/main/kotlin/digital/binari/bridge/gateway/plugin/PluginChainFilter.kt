package digital.binari.bridge.gateway.plugin

import digital.binari.bridge.gateway.filter.CorrelationIdFilter
import org.slf4j.LoggerFactory
import org.springframework.cloud.gateway.filter.GatewayFilterChain
import org.springframework.cloud.gateway.filter.GlobalFilter
import org.springframework.cloud.gateway.route.Route
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils
import org.springframework.core.Ordered
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import org.springframework.web.server.ServerWebExchange
import reactor.core.publisher.Mono

/**
 * Global filter that executes the plugin chain for each request.
 *
 * PRE_ROUTE plugins run before the request is forwarded downstream.
 * POST_ROUTE plugins run after the response is received.
 *
 * If any PRE_ROUTE plugin returns [PluginResult.proceed] = false,
 * the chain is short-circuited and the plugin's response is returned directly.
 */
@Component
class PluginChainFilter(
    private val pluginRegistry: PluginRegistry
) : GlobalFilter, Ordered {

    private val logger = LoggerFactory.getLogger(PluginChainFilter::class.java)

    override fun getOrder(): Int = 0

    override fun filter(exchange: ServerWebExchange, chain: GatewayFilterChain): Mono<Void> {
        val correlationId = exchange.request.headers
            .getFirst(CorrelationIdFilter.CORRELATION_ID_HEADER) ?: "unknown"

        val route = exchange.getAttribute<Route>(ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR)
        val routeId = route?.id ?: "unknown"

        val context = PluginContext(
            routeId = routeId,
            correlationId = correlationId
        )

        val preRoutePlugins = pluginRegistry.getEnabledPlugins(PluginPhase.PRE_ROUTE)

        return executePreRoutePlugins(exchange, context, preRoutePlugins, 0)
            .flatMap { result ->
                if (!result.proceed) {
                    shortCircuit(exchange, result)
                } else {
                    chain.filter(exchange).then(
                        executePostRoutePlugins(exchange, context)
                    )
                }
            }
    }

    /**
     * Recursively execute PRE_ROUTE plugins in order.
     * Stops immediately if any plugin returns proceed = false.
     */
    private fun executePreRoutePlugins(
        exchange: ServerWebExchange,
        context: PluginContext,
        plugins: List<GatewayPlugin>,
        index: Int
    ): Mono<PluginResult> {
        if (index >= plugins.size) {
            return Mono.just(PluginResult(proceed = true))
        }

        val plugin = plugins[index]
        logger.debug(
            "Executing PRE_ROUTE plugin '{}' (order={}) for correlationId={}",
            plugin.id, plugin.order, context.correlationId
        )

        return plugin.execute(exchange, context)
            .onErrorResume { error ->
                logger.error(
                    "Error executing PRE_ROUTE plugin '{}': {}",
                    plugin.id, error.message, error
                )
                Mono.just(PluginResult(proceed = true))
            }
            .flatMap { result ->
                // Merge plugin result headers into the response
                result.headers.forEach { (key, value) ->
                    exchange.response.headers.add(key, value)
                }

                // Merge plugin metadata into context
                context.metadata.putAll(result.metadata)

                if (!result.proceed) {
                    logger.info(
                        "PRE_ROUTE plugin '{}' short-circuited request with status={} for correlationId={}",
                        plugin.id, result.statusCode, context.correlationId
                    )
                    Mono.just(result)
                } else {
                    executePreRoutePlugins(exchange, context, plugins, index + 1)
                }
            }
    }

    /**
     * Execute all POST_ROUTE plugins sequentially.
     * Errors in post-route plugins are logged but do not affect the response.
     */
    private fun executePostRoutePlugins(
        exchange: ServerWebExchange,
        context: PluginContext
    ): Mono<Void> {
        val postRoutePlugins = pluginRegistry.getEnabledPlugins(PluginPhase.POST_ROUTE)

        if (postRoutePlugins.isEmpty()) {
            return Mono.empty()
        }

        return executePostRoutePluginsSequentially(exchange, context, postRoutePlugins, 0)
    }

    private fun executePostRoutePluginsSequentially(
        exchange: ServerWebExchange,
        context: PluginContext,
        plugins: List<GatewayPlugin>,
        index: Int
    ): Mono<Void> {
        if (index >= plugins.size) {
            return Mono.empty()
        }

        val plugin = plugins[index]
        logger.debug(
            "Executing POST_ROUTE plugin '{}' (order={}) for correlationId={}",
            plugin.id, plugin.order, context.correlationId
        )

        return plugin.execute(exchange, context)
            .onErrorResume { error ->
                logger.error(
                    "Error executing POST_ROUTE plugin '{}': {}",
                    plugin.id, error.message, error
                )
                Mono.just(PluginResult(proceed = true))
            }
            .then(executePostRoutePluginsSequentially(exchange, context, plugins, index + 1))
    }

    /**
     * Short-circuit the request with the plugin's specified response.
     */
    private fun shortCircuit(exchange: ServerWebExchange, result: PluginResult): Mono<Void> {
        val statusCode = result.statusCode ?: 403
        exchange.response.statusCode = HttpStatus.valueOf(statusCode)

        result.headers.forEach { (key, value) ->
            exchange.response.headers.add(key, value)
        }

        val body = result.responseBody
        return if (body != null) {
            exchange.response.headers.add("Content-Type", "application/json")
            val buffer = exchange.response.bufferFactory().wrap(body.toByteArray())
            exchange.response.writeWith(Mono.just(buffer))
        } else {
            exchange.response.setComplete()
        }
    }
}
