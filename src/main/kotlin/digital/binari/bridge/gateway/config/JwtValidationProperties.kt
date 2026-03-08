package digital.binari.bridge.gateway.config

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "gateway.auth.jwt")
data class JwtValidationProperties(
    val enabled: Boolean = true,
    val jwksUrl: String = "http://bridge-id:8083/jwks",
    val issuer: String = "http://bridge-id:8083",
    val cacheRefreshSeconds: Long = 300,
    val publicPaths: List<String> = listOf(
        "/actuator/**",
        "/fallback/**",
        "/.well-known/**",
        "/jwks",
        "/api/v1/register",
        "/api/v1/login",
        "/api/v1/refresh",
        "/api/v1/auth/**",
        "/api/v1/identity/register",
        "/api/v1/identity/login",
        "/api/v1/identity/refresh",
        "/api/v1/identity/auth/**",
    ),
)
