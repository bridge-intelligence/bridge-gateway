package digital.binari.bridge.gateway.filter

import org.slf4j.LoggerFactory
import org.springframework.cloud.gateway.filter.GatewayFilter
import org.springframework.cloud.gateway.filter.factory.AbstractGatewayFilterFactory
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import reactor.core.publisher.Mono
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

@Component
class RateLimitGatewayFilterFactory :
    AbstractGatewayFilterFactory<RateLimitGatewayFilterFactory.Config>(Config::class.java) {

    private val logger = LoggerFactory.getLogger(RateLimitGatewayFilterFactory::class.java)

    private val buckets = ConcurrentHashMap<String, TokenBucket>()

    override fun name(): String = "RateLimit"

    override fun apply(config: Config): GatewayFilter {
        return GatewayFilter { exchange, chain ->
            val apiKey = exchange.request.headers.getFirst("X-API-Key")
            val clientIp = exchange.request.remoteAddress?.address?.hostAddress ?: "unknown"
            val bucketKey = apiKey ?: clientIp

            val bucket = buckets.computeIfAbsent(bucketKey) {
                TokenBucket(
                    capacity = config.burstCapacity.toLong(),
                    refillRate = config.requestsPerSecond
                )
            }

            val allowed = bucket.tryConsume()
            val remaining = bucket.getAvailableTokens()

            exchange.response.headers.add("X-RateLimit-Limit", config.burstCapacity.toString())
            exchange.response.headers.add("X-RateLimit-Remaining", remaining.coerceAtLeast(0).toString())

            if (!allowed) {
                logger.warn(
                    "Rate limit exceeded for client '{}' on path '{}'",
                    bucketKey, exchange.request.uri.path
                )
                exchange.response.statusCode = HttpStatus.TOO_MANY_REQUESTS
                exchange.response.headers.add("Content-Type", "application/json")
                exchange.response.headers.add("Retry-After", "1")
                val body = """{"error":"too_many_requests","message":"Rate limit exceeded. Please retry later."}"""
                val buffer = exchange.response.bufferFactory().wrap(body.toByteArray())
                return@GatewayFilter exchange.response.writeWith(Mono.just(buffer))
            }

            chain.filter(exchange)
        }
    }

    override fun shortcutFieldOrder(): List<String> = listOf("requestsPerSecond", "burstCapacity")

    class Config {
        var requestsPerSecond: Double = 10.0
        var burstCapacity: Int = 20
    }

    /**
     * Simple token bucket implementation for rate limiting.
     * Tokens are refilled based on elapsed time since last refill.
     */
    internal class TokenBucket(
        private val capacity: Long,
        private val refillRate: Double
    ) {
        private val availableTokens = AtomicLong(capacity)
        private val lastRefillTimestamp = AtomicLong(System.nanoTime())

        fun tryConsume(): Boolean {
            refill()
            while (true) {
                val current = availableTokens.get()
                if (current <= 0) return false
                if (availableTokens.compareAndSet(current, current - 1)) return true
            }
        }

        fun getAvailableTokens(): Long {
            refill()
            return availableTokens.get()
        }

        private fun refill() {
            val now = System.nanoTime()
            val lastRefill = lastRefillTimestamp.get()
            val elapsedNanos = now - lastRefill

            if (elapsedNanos <= 0) return

            val elapsedSeconds = elapsedNanos.toDouble() / 1_000_000_000.0
            val tokensToAdd = (elapsedSeconds * refillRate).toLong()

            if (tokensToAdd > 0) {
                if (lastRefillTimestamp.compareAndSet(lastRefill, now)) {
                    val current = availableTokens.get()
                    val newTokens = (current + tokensToAdd).coerceAtMost(capacity)
                    availableTokens.set(newTokens)
                }
            }
        }
    }
}
