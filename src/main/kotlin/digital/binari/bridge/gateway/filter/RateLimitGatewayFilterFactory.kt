package digital.binari.bridge.gateway.filter

import org.slf4j.LoggerFactory
import org.springframework.cloud.gateway.filter.GatewayFilter
import org.springframework.cloud.gateway.filter.factory.AbstractGatewayFilterFactory
import org.springframework.data.redis.core.ReactiveStringRedisTemplate
import org.springframework.data.redis.core.script.RedisScript
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import reactor.core.publisher.Mono
import java.time.Duration
import java.time.Instant

/**
 * Redis-backed sliding window rate limiter for Spring Cloud Gateway.
 *
 * Uses Redis INCR + EXPIRE pattern with per-minute and per-hour windows.
 * Keys: rate:{clientKey}:{windowSeconds}:{windowId}
 *
 * Configurable per-tenant limits via application.yml gateway.rate-limit section.
 * Returns 429 with Retry-After and X-RateLimit-* headers when exceeded.
 */
@Component
class RateLimitGatewayFilterFactory(
    private val redisTemplate: ReactiveStringRedisTemplate
) : AbstractGatewayFilterFactory<RateLimitGatewayFilterFactory.Config>(Config::class.java) {

    private val logger = LoggerFactory.getLogger(RateLimitGatewayFilterFactory::class.java)

    // Lua script: atomically INCR and set EXPIRE if key is new. Returns the count.
    private val incrAndExpireScript = RedisScript.of(
        """
        local current = redis.call('INCR', KEYS[1])
        if current == 1 then
            redis.call('EXPIRE', KEYS[1], ARGV[1])
        end
        return current
        """.trimIndent(),
        Long::class.java
    )

    override fun name(): String = "RateLimit"

    override fun apply(config: Config): GatewayFilter {
        return GatewayFilter { exchange, chain ->
            val apiKey = exchange.request.headers.getFirst("X-API-Key")
            val clientIp = exchange.request.remoteAddress?.address?.hostAddress ?: "unknown"
            val clientKey = apiKey ?: clientIp

            val now = Instant.now()

            // Per-minute window
            val minuteWindowId = now.epochSecond / 60
            val minuteKey = "rate:$clientKey:60:$minuteWindowId"
            val minuteLimit = config.requestsPerMinute

            // Per-hour window
            val hourWindowId = now.epochSecond / 3600
            val hourKey = "rate:$clientKey:3600:$hourWindowId"
            val hourLimit = config.requestsPerHour

            // Check both windows with Redis Lua script
            val minuteCheck = redisTemplate.execute(
                incrAndExpireScript,
                listOf(minuteKey),
                listOf("60")
            ).next().defaultIfEmpty(1L)

            val hourCheck = redisTemplate.execute(
                incrAndExpireScript,
                listOf(hourKey),
                listOf("3600")
            ).next().defaultIfEmpty(1L)

            Mono.zip(minuteCheck, hourCheck).flatMap { counts ->
                val minuteCount = counts.t1
                val hourCount = counts.t2

                val minuteRemaining = (minuteLimit - minuteCount).coerceAtLeast(0)
                val hourRemaining = (hourLimit - hourCount).coerceAtLeast(0)

                // Use the more restrictive remaining count
                val effectiveLimit: Long
                val effectiveRemaining: Long
                val resetEpoch: Long

                if (minuteCount > minuteLimit) {
                    // Per-minute limit exceeded
                    effectiveLimit = minuteLimit
                    effectiveRemaining = 0
                    resetEpoch = (minuteWindowId + 1) * 60
                } else if (hourCount > hourLimit) {
                    // Per-hour limit exceeded
                    effectiveLimit = hourLimit
                    effectiveRemaining = 0
                    resetEpoch = (hourWindowId + 1) * 3600
                } else {
                    // Neither exceeded — report the tighter constraint
                    if (minuteRemaining < hourRemaining) {
                        effectiveLimit = minuteLimit
                        effectiveRemaining = minuteRemaining
                        resetEpoch = (minuteWindowId + 1) * 60
                    } else {
                        effectiveLimit = hourLimit
                        effectiveRemaining = hourRemaining
                        resetEpoch = (hourWindowId + 1) * 3600
                    }
                }

                // Set rate limit headers
                exchange.response.headers.set("X-RateLimit-Limit", effectiveLimit.toString())
                exchange.response.headers.set("X-RateLimit-Remaining", effectiveRemaining.toString())
                exchange.response.headers.set("X-RateLimit-Reset", resetEpoch.toString())

                val exceeded = minuteCount > minuteLimit || hourCount > hourLimit

                if (exceeded) {
                    val retryAfter = (resetEpoch - now.epochSecond).coerceAtLeast(1)
                    logger.warn(
                        "Rate limit exceeded for client '{}' on path '{}' (minute: {}/{}, hour: {}/{})",
                        clientKey, exchange.request.uri.path,
                        minuteCount, minuteLimit, hourCount, hourLimit
                    )
                    exchange.response.statusCode = HttpStatus.TOO_MANY_REQUESTS
                    exchange.response.headers.set("Content-Type", "application/json")
                    exchange.response.headers.set("Retry-After", retryAfter.toString())
                    val body = """{"error":"too_many_requests","message":"Rate limit exceeded. Please retry after $retryAfter seconds."}"""
                    val buffer = exchange.response.bufferFactory().wrap(body.toByteArray())
                    exchange.response.writeWith(Mono.just(buffer))
                } else {
                    chain.filter(exchange)
                }
            }.onErrorResume { e ->
                // If Redis is down, fall through (fail open) to avoid blocking all requests
                logger.error("Rate limit check failed, allowing request through: {}", e.message)
                chain.filter(exchange)
            }
        }
    }

    override fun shortcutFieldOrder(): List<String> = listOf("requestsPerMinute", "requestsPerHour")

    class Config {
        /** Maximum requests per minute (default: 100) */
        var requestsPerMinute: Long = 100

        /** Maximum requests per hour (default: 1000) */
        var requestsPerHour: Long = 1000

        // Keep legacy fields for backward compat with existing route config
        var requestsPerSecond: Double = 10.0
            set(value) {
                field = value
                // Auto-convert to per-minute if someone sets the old field
                if (requestsPerMinute == 100L) {
                    requestsPerMinute = (value * 60).toLong()
                }
            }

        var burstCapacity: Int = 20
            set(value) {
                field = value
                // Auto-convert burst to per-minute if someone sets the old field
                if (requestsPerMinute == 100L) {
                    requestsPerMinute = (requestsPerSecond * 60).toLong()
                }
            }
    }
}
