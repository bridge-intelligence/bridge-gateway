package digital.binari.bridge.gateway.filter

import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.cloud.gateway.filter.GatewayFilterChain
import org.springframework.http.HttpHeaders
import org.springframework.mock.http.server.reactive.MockServerHttpRequest
import org.springframework.mock.web.server.MockServerWebExchange
import org.springframework.web.server.ServerWebExchange
import reactor.core.publisher.Mono

class CorrelationIdFilterTest {

    private lateinit var filter: CorrelationIdFilter
    private lateinit var chain: GatewayFilterChain

    @BeforeEach
    fun setUp() {
        filter = CorrelationIdFilter()
        chain = mockk()
    }

    @Test
    fun `generates correlation ID when not present in request`() {
        val request = MockServerHttpRequest.get("/test").build()
        val exchange = MockServerWebExchange.from(request)

        val capturedExchange = slot<ServerWebExchange>()
        every { chain.filter(capture(capturedExchange)) } returns Mono.empty()

        filter.filter(exchange, chain).block()

        val mutatedExchange = capturedExchange.captured
        val correlationId = mutatedExchange.request.headers
            .getFirst(CorrelationIdFilter.CORRELATION_ID_HEADER)

        assertNotNull(correlationId, "Correlation ID should be generated")
        assertTrue(correlationId!!.matches(Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")),
            "Correlation ID should be a valid UUID")

        // Verify it was also added to the response headers
        val responseCorrelationId = mutatedExchange.response.headers
            .getFirst(CorrelationIdFilter.CORRELATION_ID_HEADER)
        assertEquals(correlationId, responseCorrelationId,
            "Response should contain the same correlation ID")
    }

    @Test
    fun `passes through existing correlation ID from request`() {
        val existingId = "existing-correlation-id-12345"
        val request = MockServerHttpRequest.get("/test")
            .header(CorrelationIdFilter.CORRELATION_ID_HEADER, existingId)
            .build()
        val exchange = MockServerWebExchange.from(request)

        val capturedExchange = slot<ServerWebExchange>()
        every { chain.filter(capture(capturedExchange)) } returns Mono.empty()

        filter.filter(exchange, chain).block()

        val mutatedExchange = capturedExchange.captured
        val correlationId = mutatedExchange.request.headers
            .getFirst(CorrelationIdFilter.CORRELATION_ID_HEADER)

        assertEquals(existingId, correlationId,
            "Should pass through the existing correlation ID")

        val responseCorrelationId = mutatedExchange.response.headers
            .getFirst(CorrelationIdFilter.CORRELATION_ID_HEADER)
        assertEquals(existingId, responseCorrelationId,
            "Response should contain the same existing correlation ID")
    }

    @Test
    fun `filter has correct order`() {
        assertEquals(-2, filter.order, "CorrelationIdFilter should have order -2")
    }

    @Test
    fun `generates new correlation ID when header is blank`() {
        val request = MockServerHttpRequest.get("/test")
            .header(CorrelationIdFilter.CORRELATION_ID_HEADER, "")
            .build()
        val exchange = MockServerWebExchange.from(request)

        val capturedExchange = slot<ServerWebExchange>()
        every { chain.filter(capture(capturedExchange)) } returns Mono.empty()

        filter.filter(exchange, chain).block()

        val mutatedExchange = capturedExchange.captured
        val correlationId = mutatedExchange.request.headers
            .getFirst(CorrelationIdFilter.CORRELATION_ID_HEADER)

        assertNotNull(correlationId, "Should generate a new correlation ID for blank header")
        assertTrue(correlationId!!.isNotBlank(), "Correlation ID should not be blank")
    }
}
