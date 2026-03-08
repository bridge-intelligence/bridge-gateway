package digital.binari.bridge.gateway.filter

import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.crypto.RSASSAVerifier
import com.nimbusds.jwt.SignedJWT
import digital.binari.bridge.gateway.config.JwtValidationProperties
import org.slf4j.LoggerFactory
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.cloud.gateway.filter.GatewayFilterChain
import org.springframework.cloud.gateway.filter.GlobalFilter
import org.springframework.core.Ordered
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import org.springframework.util.AntPathMatcher
import org.springframework.web.server.ServerWebExchange
import reactor.core.publisher.Mono
import java.util.Date

@Component
@EnableConfigurationProperties(JwtValidationProperties::class)
class JwtValidationFilter(
    private val jwtProps: JwtValidationProperties,
    private val jwksKeyProvider: JwksKeyProvider,
) : GlobalFilter, Ordered {

    private val log = LoggerFactory.getLogger(JwtValidationFilter::class.java)
    private val pathMatcher = AntPathMatcher()

    companion object {
        const val IDENTITY_HEADER = "X-Bridge-Identity"
        const val ROLES_HEADER = "X-Bridge-Roles"
        const val PERMISSIONS_HEADER = "X-Bridge-Permissions"
        const val PLATFORMS_HEADER = "X-Bridge-Platforms"
        const val AUTH_METHOD_HEADER = "X-Bridge-Auth-Method"
    }

    override fun getOrder(): Int = -1

    override fun filter(exchange: ServerWebExchange, chain: GatewayFilterChain): Mono<Void> {
        if (!jwtProps.enabled) {
            return chain.filter(exchange)
        }

        val path = exchange.request.uri.path

        if (isPublicPath(path)) {
            return chain.filter(exchange)
        }

        val authHeader = exchange.request.headers.getFirst("Authorization")
        val apiKeyHeader = exchange.request.headers.getFirst("X-API-Key")

        // If no Authorization header but has API key, let the API key filter handle it
        if (authHeader.isNullOrBlank() && !apiKeyHeader.isNullOrBlank()) {
            return chain.filter(exchange)
        }

        // No auth at all
        if (authHeader.isNullOrBlank()) {
            return unauthorized(exchange, "Missing Authorization header")
        }

        if (!authHeader.startsWith("Bearer ", ignoreCase = true)) {
            return unauthorized(exchange, "Authorization header must use Bearer scheme")
        }

        val token = authHeader.substring(7).trim()
        if (token.isBlank()) {
            return unauthorized(exchange, "Empty bearer token")
        }

        return try {
            val claims = validateToken(token)
            if (claims == null) {
                unauthorized(exchange, "Invalid or expired token")
            } else {
                val mutatedRequest = exchange.request.mutate()
                    .header(IDENTITY_HEADER, claims.brdgId)
                    .header(ROLES_HEADER, claims.roles.joinToString(","))
                    .header(PERMISSIONS_HEADER, claims.permissions.joinToString(","))
                    .header(PLATFORMS_HEADER, claims.platformsJson)
                    .header(AUTH_METHOD_HEADER, claims.method)
                    .build()

                val mutatedExchange = exchange.mutate().request(mutatedRequest).build()

                log.debug(
                    "JWT validated: brdg_id={}, roles={}, path={}",
                    claims.brdgId, claims.roles, path,
                )

                chain.filter(mutatedExchange)
            }
        } catch (e: Exception) {
            log.warn("JWT validation failed for path {}: {}", path, e.message)
            unauthorized(exchange, "Token validation failed")
        }
    }

    private fun validateToken(token: String): JwtClaims? {
        val jwt = SignedJWT.parse(token)
        val header = jwt.header

        if (header.algorithm != JWSAlgorithm.RS256) {
            log.debug("Unsupported JWT algorithm: {}", header.algorithm)
            return null
        }

        val kid = header.keyID
        if (kid.isNullOrBlank()) {
            log.debug("JWT missing kid header")
            return null
        }

        val publicKey = jwksKeyProvider.getPublicKey(kid) ?: run {
            log.debug("No public key found for kid={}", kid)
            return null
        }

        val verifier = RSASSAVerifier(publicKey)
        if (!jwt.verify(verifier)) {
            log.debug("JWT signature verification failed")
            return null
        }

        val claims = jwt.jwtClaimsSet

        // Validate issuer
        if (claims.issuer != jwtProps.issuer) {
            log.debug("JWT issuer mismatch: expected={}, got={}", jwtProps.issuer, claims.issuer)
            return null
        }

        // Validate expiry
        if (claims.expirationTime == null || claims.expirationTime.before(Date())) {
            log.debug("JWT expired")
            return null
        }

        val brdgId = claims.subject ?: return null

        @Suppress("UNCHECKED_CAST")
        val roles = (claims.getClaim("roles") as? List<String>) ?: emptyList()

        @Suppress("UNCHECKED_CAST")
        val permissions = (claims.getClaim("permissions") as? List<String>) ?: emptyList()

        val method = claims.getStringClaim("method") ?: "UNKNOWN"

        val platforms = claims.getClaim("platforms")
        val platformsJson = if (platforms != null) {
            try {
                com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(platforms)
            } catch (_: Exception) {
                "{}"
            }
        } else "{}"

        return JwtClaims(
            brdgId = brdgId,
            roles = roles,
            permissions = permissions,
            method = method,
            platformsJson = platformsJson,
        )
    }

    private fun isPublicPath(path: String): Boolean {
        return jwtProps.publicPaths.any { pattern -> pathMatcher.match(pattern, path) }
    }

    private fun unauthorized(exchange: ServerWebExchange, message: String): Mono<Void> {
        exchange.response.statusCode = HttpStatus.UNAUTHORIZED
        exchange.response.headers.add("Content-Type", "application/json")
        val body = """{"error":"unauthorized","message":"$message"}"""
        val buffer = exchange.response.bufferFactory().wrap(body.toByteArray())
        return exchange.response.writeWith(Mono.just(buffer))
    }

    data class JwtClaims(
        val brdgId: String,
        val roles: List<String>,
        val permissions: List<String>,
        val method: String,
        val platformsJson: String,
    )
}
