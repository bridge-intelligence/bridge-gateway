package digital.binari.bridge.gateway.filter

import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import reactor.core.publisher.Mono
import java.time.Instant

@RestController
@RequestMapping("/fallback")
class CircuitBreakerFallbackController {

    @GetMapping("/orchestra")
    fun orchestraFallback(): Mono<Map<String, Any>> = Mono.just(
        mapOf(
            "error" to "SERVICE_UNAVAILABLE",
            "message" to "Orchestra service is temporarily unavailable. Please retry shortly.",
            "service" to "bridge-orchestra-api",
            "timestamp" to Instant.now().toString(),
            "retryAfter" to 30
        )
    )

    @GetMapping("/custody")
    fun custodyFallback(): Mono<Map<String, Any>> = Mono.just(
        mapOf(
            "error" to "SERVICE_UNAVAILABLE",
            "message" to "Custody service is temporarily unavailable. Please retry shortly.",
            "service" to "bridge-custody-api",
            "timestamp" to Instant.now().toString(),
            "retryAfter" to 60
        )
    )

    @GetMapping("/portfolio")
    fun portfolioFallback(): Mono<Map<String, Any>> = Mono.just(
        mapOf(
            "error" to "SERVICE_UNAVAILABLE",
            "message" to "Portfolio service is temporarily unavailable. Please retry shortly.",
            "service" to "bridge-portfolio-service",
            "timestamp" to Instant.now().toString(),
            "retryAfter" to 30
        )
    )

    @GetMapping("/execution")
    fun executionFallback(): Mono<Map<String, Any>> = Mono.just(
        mapOf(
            "error" to "SERVICE_UNAVAILABLE",
            "message" to "Execution service is temporarily unavailable. Please retry shortly.",
            "service" to "bridge-execution-service",
            "timestamp" to Instant.now().toString(),
            "retryAfter" to 30
        )
    )

    @GetMapping("/marketdata")
    fun marketdataFallback(): Mono<Map<String, Any>> = Mono.just(
        mapOf(
            "error" to "SERVICE_UNAVAILABLE",
            "message" to "Market data service is temporarily unavailable. Please retry shortly.",
            "service" to "bridge-marketdata-service",
            "timestamp" to Instant.now().toString(),
            "retryAfter" to 30
        )
    )

    @GetMapping("/ledger")
    fun ledgerFallback(): Mono<Map<String, Any>> = Mono.just(
        mapOf(
            "error" to "SERVICE_UNAVAILABLE",
            "message" to "Ledger service is temporarily unavailable. Please retry shortly.",
            "service" to "bridge-ledger-service",
            "timestamp" to Instant.now().toString(),
            "retryAfter" to 30
        )
    )

    @GetMapping("/default")
    fun defaultFallback(): Mono<Map<String, Any>> = Mono.just(
        mapOf(
            "error" to "SERVICE_UNAVAILABLE",
            "message" to "The requested service is temporarily unavailable.",
            "timestamp" to Instant.now().toString(),
            "retryAfter" to 30
        )
    )
}
