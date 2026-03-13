package digital.binari.bridge.gateway.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpMethod
import org.springframework.security.config.annotation.method.configuration.EnableReactiveMethodSecurity
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity
import org.springframework.security.config.web.server.ServerHttpSecurity
import org.springframework.security.web.server.SecurityWebFilterChain

@Configuration
@EnableWebFluxSecurity
@EnableReactiveMethodSecurity
class SecurityConfig {

    @Bean
    fun securityFilterChain(http: ServerHttpSecurity): SecurityWebFilterChain {
        return http
            .csrf { it.disable() }
            .httpBasic { it.disable() }
            .formLogin { it.disable() }
            .authorizeExchange { exchanges ->
                exchanges
                    // Public infrastructure endpoints
                    .pathMatchers("/actuator/health", "/actuator/info", "/actuator/health/**").permitAll()
                    .pathMatchers("/actuator/prometheus").permitAll()
                    .pathMatchers("/fallback/**").permitAll()
                    // Public auth endpoints (login, register, token refresh)
                    .pathMatchers("/api/v1/register", "/api/v1/login", "/api/v1/refresh").permitAll()
                    .pathMatchers("/api/v1/auth/**").permitAll()
                    .pathMatchers("/api/v1/identity/register", "/api/v1/identity/login", "/api/v1/identity/refresh").permitAll()
                    .pathMatchers("/api/v1/identity/auth/**").permitAll()
                    .pathMatchers("/api/v1/authorize", "/api/v1/token").permitAll()
                    .pathMatchers("/.well-known/**", "/jwks").permitAll()
                    // CORS preflight — must be permitted before auth
                    .pathMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                    // Admin endpoints require ADMIN role
                    .pathMatchers("/gateway/admin/**").hasRole("ADMIN")
                    // All other exchanges are permitted at the Spring Security level.
                    // Auth is enforced by JwtValidationFilter (GlobalFilter, order=-1)
                    // which runs before routing and returns 401 for unauthenticated requests.
                    .anyExchange().permitAll()
            }
            .build()
    }
}
