# Bridge Gateway — Changelog

All notable changes to the bridge-gateway project are documented here.
Format: [Keep a Changelog](https://keepachangelog.com/en/1.1.0/)

---

## [0.1.0] — 2026-02-27

### Added

#### Core Application
- Initial Spring Cloud Gateway application (Spring Boot 3.2.5, Kotlin 1.9.23, Spring Cloud 2023.0.1)
- Reactive request routing to backend services: orchestra (8080), custody (8081), wallet (8080)
- Application server running on port 4000
- Spring Security WebFlux configuration with CSRF, httpBasic, and formLogin disabled
- Spring profile support with `application.yml` (base) and `application-dev.yml` (dev overlay)

#### Global Filter Chain
- **SecurityHeadersFilter** (order -3): Injects X-Content-Type-Options (nosniff), X-Frame-Options (DENY), X-XSS-Protection, Strict-Transport-Security (31536000s), Referrer-Policy (strict-origin-when-cross-origin), Permissions-Policy (camera/microphone/geolocation denied), Cache-Control (no-store), X-Gateway-Version header
- **CorrelationIdFilter** (order -2): Generates or propagates X-Correlation-Id UUID header on both request and response
- **RequestLoggingFilter** (order -1): Structured request/response logging with method, path, status, duration, correlation ID, and client IP
- **PluginChainFilter** (order 0): Executes the plugin chain (PRE_ROUTE before downstream, POST_ROUTE after response)

#### Plugin System (SPI Architecture)
- **GatewayPlugin interface**: SPI contract with `id`, `name`, `version`, `phase`, `order`, `initialize()`, `execute()`, `shutdown()`, `isEnabled()`, `healthCheck()` methods
- **PluginPhase enum**: PRE_ROUTE, POST_ROUTE, BOTH
- **PluginContext**: Carries `routeId`, `correlationId`, and mutable metadata through the chain
- **PluginResult**: Supports `proceed` flag, custom `statusCode`, `responseBody`, `headers`, and `metadata` for short-circuit or passthrough
- **PluginHealth**: Health check data model with `healthy` boolean and detail map
- **PluginRegistry**: Spring component that discovers plugins via component scanning, manages lifecycle (init/shutdown), maintains enabled state in ConcurrentHashMap, supports runtime enable/disable via admin API, provides reactive health aggregation
- **PluginConfigProperties**: YAML-driven configuration at `gateway.plugins.configs.*` with `enabled`, `order`, and `settings` per plugin
- **PluginChainFilter**: Global filter that recursively executes PRE_ROUTE plugins, short-circuits on `proceed=false`, then executes POST_ROUTE plugins after downstream response; errors in plugins are caught and logged without failing the request

#### Built-in Plugins
- **AuditPlugin** (id: `audit`, phase: POST_ROUTE, order: 10): Structured JSON audit logging of every request (timestamp, correlationId, routeId, method, path, status, clientIp, userAgent, masked API key); production path would publish to Kafka topic `gateway.audit.events`
- **CompliancePlugin** (id: `compliance`, phase: PRE_ROUTE, order: 50): Orbit compliance gateway integration skeleton; passthrough mode with `X-Compliance-Status` header; designed for KYC/AML checks, sanctioned entity blocking; configurable `orbit-url` and `timeout-ms`
- **MonetizationPlugin** (id: `monetization`, phase: PRE_ROUTE, order: 100): Billing/metering skeleton; passthrough mode with `X-Monetization-Plan: free` header; designed for quota checking against billing service, plan-based rate limiting; configurable `billing-service-url` and `free-tier-limit`

#### Authentication and Rate Limiting
- **ApiKeyAuthGatewayFilterFactory**: Route-level filter validating X-API-Key header against configured key list (`gateway.auth.api-keys`); returns 401 for missing key, 403 for invalid key; configurable header name and required flag
- **RateLimitGatewayFilterFactory**: Token bucket rate limiter (in-memory, per-client); client identified by API key or IP address; configurable `requestsPerSecond` and `burstCapacity`; returns 429 with `Retry-After` header when exceeded; adds `X-RateLimit-Limit` and `X-RateLimit-Remaining` response headers

#### Resilience4j Circuit Breakers and Retries
- Default circuit breaker: 50% failure rate threshold, 80% slow call threshold, 5s slow call duration, 30s wait in open state, 5 half-open calls, 20-call sliding window (count-based), 10 minimum calls, 10s time limiter
- Custody-specific circuit breaker: 30% failure rate threshold, 60s wait in open state, 10-call sliding window, 15s time limiter
- Orchestra route retry: 3 retries on GET, exponential backoff 100ms-1000ms (factor 2)
- Custody route retry: 2 retries on GET, exponential backoff 200ms-2000ms (factor 2)
- **CircuitBreakerFallbackController**: Dedicated fallback endpoints at `/fallback/orchestra`, `/fallback/custody`, `/fallback/default` returning structured JSON with service name, error, timestamp, and `retryAfter` seconds

#### Admin API
- `GET /gateway/admin/routes` — Lists all configured routes with enabled status, path, URI, methods, plugins
- `GET /gateway/admin/health` — Gateway health summary with plugin status aggregation (UP/DEGRADED)
- `GET /gateway/admin/config` — Current configuration with sanitized URIs (no credentials)
- `GET /gateway/admin/plugins` — Lists all plugins with reactive health status
- `GET /gateway/admin/plugins/{id}` — Plugin detail with health check
- `POST /gateway/admin/plugins/{id}/enable` — Enable a plugin at runtime with config
- `POST /gateway/admin/plugins/{id}/disable` — Disable a plugin at runtime
- `GET /gateway/admin/plugins/{id}/health` — Individual plugin health check

#### Observability
- Prometheus metrics endpoint at `/actuator/prometheus` with SLA histograms (50ms, 100ms, 200ms, 500ms, 1s, 5s)
- Common Micrometer tags: `application=bridge-gateway`, `component=gateway`
- OpenTelemetry distributed tracing support (micrometer-tracing-bridge-otel, opentelemetry-exporter-otlp)
- Structured JSON logging via Logstash encoder (logstash-logback-encoder 7.4)
- Log pattern includes ISO8601 timestamp, thread, correlationId MDC, level, logger
- **GatewayHealthIndicator**: Custom reactive health indicator reporting plugin health aggregation to Actuator; UP when all enabled plugins are healthy, DOWN when any enabled plugin is unhealthy
- Actuator endpoints exposed: health, info, metrics, gateway, prometheus
- Health endpoint with `show-details: always`
- Resilience4j circuit breakers registered as health indicators

#### CORS Configuration
- **CorsConfig**: Programmatic CORS via reactive `CorsWebFilter` with configurable origins, methods, headers, credentials, max-age
- Exposed headers: X-Correlation-Id, X-RateLimit-Remaining, X-RateLimit-Limit, X-Monetization-Plan
- Spring Cloud Gateway global CORS with `DedupeResponseHeader` filter to prevent duplicate CORS headers
- Dev profile: `allowedOriginPatterns: *` with `allowCredentials: true` (avoids allowedOrigins/allowCredentials conflict)

#### Kubernetes Deployment
- Deployment: single replica, `bridge-gateway` ServiceAccount, Vault Agent inject annotation (disabled), resource requests (200m CPU/384Mi RAM), limits (1 CPU/1Gi RAM)
- Readiness probe: `/actuator/health/readiness` (30s initial, 10s period)
- Liveness probe: `/actuator/health/liveness` (45s initial, 15s period)
- Startup probe: `/actuator/health` (10s initial, 5s period, 30 failure threshold)
- Service: ClusterIP on port 4000
- Ingress: three hosts — `gateway.bridgeintelligence.ltd`, `api.custody.app.d.bridgeintelligence.ltd` (API), `custody.app.d.bridgeintelligence.ltd` (UI); nginx ingress class, TLS via cert-manager (letsencrypt-prod), 50MB body size limit, 120s read/send timeout, 30s connect timeout, CORS enabled at ingress level
- ConfigMap: environment variables for service URLs (orchestra, custody, wallet), Redis, Vault, Spring profile
- Secret: `GATEWAY_API_KEYS` and `REDIS_PASSWORD` with Vault reference placeholders
- ServiceAccount: `bridge-gateway`
- Kustomize base in `k8s/base/` with all 6 resources; dev overlay in `k8s/overlays/dev/` with reduced resource limits (100m/256Mi requests, 500m/512Mi limits)

#### CI/CD Pipeline
- GitHub Actions workflow (`cd-dev.yml`): triggers on push to `dev` branch
- Build: Gradle 8.4 + JDK 17 (Temurin), `./gradlew build -x test`
- Image tagging: `dev-{SHA8}` plus `dev-latest`
- Vault JWT authentication: role `github-bridge-gateway-dev`, fetches Harbor credentials from `kv/cicd/harbor/bridge-gateway/data/harbor-registry`
- Docker build and push: multistage Dockerfile to Harbor (`harbor.binari.digital/bridge-gateway/bridge-gateway`)
- Build args: BUILD_VERSION, BUILD_DATE, GIT_COMMIT
- Docker layer caching via GitHub Actions cache
- Automatic PR creation: `dev` -> `stage` with build details

#### Vault Integration
- Spring Cloud Vault configuration in `bootstrap.yml`: Kubernetes auth, role `bridge-gateway`, KV v2 at `secret/bridge-gateway`
- Vault autoconfig classes excluded from default profile to prevent crash when Vault is not available
- Vault enabled/disabled via `VAULT_ENABLED` environment variable

#### Dockerfile
- Multistage build: Gradle 8.4 JDK 17 builder, Eclipse Temurin 17 JRE Alpine runtime
- OCI image labels (version, date, revision, title, vendor)
- Docker HEALTHCHECK with curl against `/actuator/health`
- JAR artifact renamed to `app.jar` to handle multiple build artifacts

#### Testing
- Unit tests for **PluginRegistry**: 12 test cases covering initialization, enable/disable, phase filtering, health status, error handling
- Unit tests for **CorrelationIdFilter**: 4 test cases covering ID generation, propagation, header presence
- Test dependencies: spring-boot-starter-test, reactor-test, MockK 1.13.10, SpringMockK 4.0.2

### Security
- Removed all hardcoded API key defaults from `application.yml` (empty string fallback)
- Removed hardcoded Redis password from `application.yml` (environment variable with empty fallback)
- Moved `GATEWAY_API_KEYS` and `REDIS_PASSWORD` to K8s Secret resource with Vault reference annotations
- Enhanced `.gitignore` with patterns for Java/Kotlin build outputs, Gradle, IDE files, K8s secrets, Vault tokens, environment files
- All sensitive values sourced from environment variables linked to `vault.binari.digital`
- Admin API endpoints set to `permitAll()` in SecurityConfig (suitable for cluster-internal access; production hardening planned)

### Fixed
- Dockerfile COPY issue with multiple JAR artifacts resolved by `mv *[!-plain].jar app.jar` in build stage
- Vault autoconfiguration crash on startup when Vault is unreachable: excluded `VaultAutoConfiguration`, `VaultReactiveAutoConfiguration`, and `VaultObservationAutoConfiguration` from auto-configuration
- Redis connection: pointed to K8s DNS (`bridge-redis-master.bridge-service-stack.svc.cluster.local:6379`), added password via environment variable
- CORS `allowedOrigins`/`allowCredentials` conflict: dev profile uses `allowedOriginPatterns` instead of `allowedOrigins`
- Admin API auth deadlock: disabled `httpBasic` and `formLogin` in SecurityConfig to prevent Spring Security from blocking unauthenticated requests to admin endpoints
- MetricsConfig import: corrected from `actuate` to `actuator` package (`org.springframework.boot.actuate.autoconfigure.metrics`)
