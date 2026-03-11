# Bridge Gateway — Architecture Document

**Version:** 0.1.0
**Date:** 2026-02-27
**Status:** Initial release

---

## Table of Contents

1. [System Context](#1-system-context)
2. [Component Architecture](#2-component-architecture)
3. [Plugin System Architecture](#3-plugin-system-architecture)
4. [Data Flow](#4-data-flow)
5. [Deployment Architecture](#5-deployment-architecture)
6. [Security Architecture](#6-security-architecture)
7. [Resilience Architecture](#7-resilience-architecture)

---

## 1. System Context

### Position in the Bridge Ecosystem

Bridge Gateway is the unified API entry point for the Bridge ecosystem. It sits between external clients (web frontends, mobile applications, third-party integrations) and the internal service mesh, providing routing, authentication, rate limiting, observability, and extensibility through a plugin system.

```
                          Internet
                             |
                    [TLS Termination]
                             |
                   gateway.bridgeintelligence.ltd
                             |
                     +-------+-------+
                     | NGINX Ingress |
                     +-------+-------+
                             |
                    +--------+--------+
                    | bridge-gateway  |
                    |   (port 4000)   |
                    +--------+--------+
                             |
          +------------------+------------------+
          |                  |                  |
    +-----+-----+     +-----+-----+     +-----+-----+
    | orchestra  |     |  custody  |     |   wallet  |
    |  (8080)    |     |  (8081)   |     |  (8080)   |
    +-----+-----+     +-----+-----+     +-----+-----+
          |                  |                  |
    +-----+-----+     +-----+-----+     +-----+-----+
    | PostgreSQL |     | PostgreSQL |     | PostgreSQL |
    | Kafka      |     | Vault      |     | Redis      |
    | Redis      |     | Kafka      |     |            |
    | Keycloak   |     | Redis      |     |            |
    +------------+     +------------+     +------------+
```

### Downstream Services

| Service | Internal DNS | Port | Route Pattern | Description |
|---------|-------------|------|---------------|-------------|
| bridge-orchestra-api | `bridge-orchestra-api.bridge-service-stack.svc.cluster.local` | 8080 | `/api/v1/**` | API orchestration layer: transactions, assets, accounts, DLT operations |
| bridge-custody-api | `bridge-custody-api.bridge-service-stack.svc.cluster.local` | 8081 | `/api/v1/custody/**` | Custody service: key management, signing, vault integration |
| bridge-wallet | `bridge-wallet.bridge-service-stack.svc.cluster.local` | 8080 | `/api/v1/wallet/**` | Wallet service: address management, balance tracking (currently disabled) |
| bridge-id | (planned) | 8083 | `/api/v1/identity/**` | Identity service: KYC/AML, entity verification (not yet routed) |

### Infrastructure Dependencies

| Component | DNS / Address | Port | Purpose |
|-----------|--------------|------|---------|
| Redis | `bridge-redis-master.bridge-service-stack.svc.cluster.local` | 6379 | Rate limiting state, session caching (reactive) |
| Kafka | `bridge-kafka.bridge-service-stack.svc.cluster.local` | 9092 | Audit event publishing (planned via AuditPlugin) |
| Vault (platform) | `vault.binari.digital` | 8200 | Secret management, K8s auth, KV v2 at `secret/bridge-gateway` |
| Keycloak | `keycloak.keycloak.svc.cluster.local` | 80 | JWT validation, OIDC provider (planned integration) |

---

## 2. Component Architecture

### Package Structure

```
digital.binari.bridge.gateway
|
+-- BridgeGatewayApplication.kt          # Spring Boot entry point
|
+-- config/
|   +-- GatewayRoutesConfig.kt            # Programmatic route registration from YAML
|   +-- CorsConfig.kt                     # Reactive CORS filter with configurable origins
|   +-- ResilienceConfig.kt               # Resilience4j circuit breaker + time limiter config
|   +-- SecurityConfig.kt                 # WebFlux security chain (CSRF/httpBasic/formLogin disabled)
|   +-- MetricsConfig.kt                  # Micrometer common tags (application, component)
|
+-- filter/
|   +-- SecurityHeadersFilter.kt          # Global filter: security response headers (order -3)
|   +-- CorrelationIdFilter.kt            # Global filter: X-Correlation-Id propagation (order -2)
|   +-- RequestLoggingFilter.kt           # Global filter: structured request/response logging (order -1)
|   +-- ApiKeyAuthGatewayFilterFactory.kt # Route filter factory: X-API-Key validation
|   +-- RateLimitGatewayFilterFactory.kt  # Route filter factory: token bucket rate limiting
|   +-- CircuitBreakerFallbackController.kt # Fallback endpoints for circuit breaker scenarios
|
+-- plugin/
|   +-- GatewayPlugin.kt                  # SPI interface + PluginPhase, PluginContext, PluginResult, PluginHealth
|   +-- PluginRegistry.kt                 # Plugin lifecycle management + runtime enable/disable
|   +-- PluginChainFilter.kt              # Global filter: executes plugin chain (order 0)
|   +-- PluginConfigProperties.kt         # YAML config binding for gateway.plugins.configs.*
|   +-- impl/
|       +-- AuditPlugin.kt               # POST_ROUTE: structured audit logging
|       +-- CompliancePlugin.kt           # PRE_ROUTE: Orbit compliance gateway skeleton
|       +-- MonetizationPlugin.kt         # PRE_ROUTE: billing/metering skeleton
|
+-- controller/
|   +-- GatewayAdminController.kt         # Admin API: /gateway/admin/routes, /health, /config
|   +-- PluginAdminController.kt          # Admin API: /gateway/admin/plugins/* (CRUD, health)
|
+-- health/
    +-- GatewayHealthIndicator.kt         # Actuator health indicator aggregating plugin health
```

### Request Lifecycle

```
Client Request
     |
     v
[Nginx Ingress] --- TLS termination, body size limit, connect/read/send timeouts
     |
     v
[Spring WebFlux Netty] --- port 4000
     |
     v
[Spring Security WebFilterChain] --- CSRF disabled, all exchanges permitted
     |
     v
[CorsWebFilter] --- origin validation, exposed headers
     |
     v
[SecurityHeadersFilter]     order = -3   response headers (HSTS, CSP, X-Frame-Options, etc.)
     |
     v
[CorrelationIdFilter]       order = -2   generate/propagate X-Correlation-Id
     |
     v
[RequestLoggingFilter]      order = -1   log incoming request, capture start time
     |
     v
[PluginChainFilter]         order = 0    execute PRE_ROUTE plugins
     |
     v  (if all plugins return proceed=true)
[Route Matching + Per-Route Filters]     ApiKeyAuth, RateLimit, CircuitBreaker, Retry, StripPrefix
     |
     v
[Downstream Service]        orchestra / custody / wallet
     |
     v  (response flows back)
[PluginChainFilter]                      execute POST_ROUTE plugins
     |
     v
[RequestLoggingFilter]                   log response status + duration
     |
     v
[SecurityHeadersFilter]                  inject security headers into response
     |
     v
Client Response
```

### Filter Execution Order

| Order | Filter | Phase | Responsibility |
|-------|--------|-------|----------------|
| -3 | `SecurityHeadersFilter` | Response (post-chain) | Adds security headers to every response |
| -2 | `CorrelationIdFilter` | Request + Response | Generates or propagates correlation ID |
| -1 | `RequestLoggingFilter` | Request + Response | Logs request ingress and response egress with timing |
| 0 | `PluginChainFilter` | Request (PRE_ROUTE) + Response (POST_ROUTE) | Orchestrates the plugin chain |
| N/A | Per-route filters | Request | ApiKeyAuth, RateLimit, CircuitBreaker, Retry (applied per-route in YAML) |

> **Note:** Spring Cloud Gateway global filters with lower order values execute earlier in the request path. Response-phase logic (e.g., `Mono.fromRunnable` in `then()`) executes in reverse order after the downstream call returns.

---

## 3. Plugin System Architecture

### Design Principles

The plugin system follows the **Service Provider Interface (SPI)** pattern, enabling the gateway to be extended with cross-cutting concerns without modifying core routing logic. Plugins are discovered at startup, configured via YAML, and can be managed at runtime through the admin API.

### GatewayPlugin Interface

```kotlin
interface GatewayPlugin {
    val id: String                          // Unique identifier (e.g., "audit")
    val name: String                        // Human-readable name
    val version: String                     // Semantic version
    val phase: PluginPhase                  // PRE_ROUTE, POST_ROUTE, or BOTH
    val order: Int                          // Execution order within phase (lower = first)

    fun initialize(config: Map<String, Any>)  // Called on enable
    fun execute(exchange: ServerWebExchange, context: PluginContext): Mono<PluginResult>
    fun shutdown()                             // Called on disable or app shutdown
    fun isEnabled(): Boolean
    fun healthCheck(): Mono<PluginHealth>
}
```

### Plugin Discovery and Lifecycle

```
Application Startup
     |
     v
[Spring Component Scan] ---> discovers all @Component classes implementing GatewayPlugin
     |
     v
[PluginRegistry @PostConstruct]
     |
     +-- For each discovered plugin:
     |       |
     |       +-- Register in pluginsById map (ConcurrentHashMap)
     |       |
     |       +-- Check gateway.plugins.configs.<plugin-id>.enabled in YAML
     |       |       |
     |       |       +-- [enabled=true] ---> call plugin.initialize(settings) ---> add to enabledPluginIds
     |       |       |
     |       |       +-- [enabled=false] ---> skip, log "not enabled"
     |       |
     |       +-- Log discovery: id, name, version, phase
     |
     v
[Ready] --- PluginRegistry serves getEnabledPlugins(phase) to PluginChainFilter
     |
     v (on shutdown)
[PluginRegistry @PreDestroy] ---> calls shutdown() on each enabled plugin
```

### Plugin Configuration (YAML)

```yaml
gateway:
  plugins:
    configs:
      audit:
        enabled: true
        order: 10
        settings:
          kafka-topic: gateway.audit.events
          log-headers: true
          log-body: false
      compliance:
        enabled: false
        order: 50
        settings:
          orbit-url: http://orbit-gateway:3001
          timeout-ms: 5000
      monetization:
        enabled: false
        order: 100
        settings:
          billing-service-url: http://billing-service:8080
          free-tier-limit: 1000
```

### Plugin Execution Chain

The `PluginChainFilter` (global filter at order 0) orchestrates plugin execution in two phases:

**PRE_ROUTE Phase** (before downstream call):
1. Retrieve enabled plugins for `PRE_ROUTE` and `BOTH` phases, sorted by `order` ascending
2. Execute plugins recursively in order
3. If any plugin returns `PluginResult(proceed=false)`, short-circuit immediately
4. Merge plugin response headers and metadata into the exchange
5. On plugin error: log and continue (fail-open)

**POST_ROUTE Phase** (after downstream response):
1. Retrieve enabled plugins for `POST_ROUTE` and `BOTH` phases, sorted by `order` ascending
2. Execute plugins sequentially
3. Errors are logged but do not affect the response (fire-and-forget semantics)

### Built-in Plugins

| Plugin | ID | Phase | Order | Status | Description |
|--------|----|-------|-------|--------|-------------|
| AuditPlugin | `audit` | POST_ROUTE | 10 | Active | Structured JSON audit logging of every request. Production: publishes to Kafka topic `gateway.audit.events`. |
| CompliancePlugin | `compliance` | PRE_ROUTE | 50 | Skeleton | Orbit compliance gateway integration. Designed for KYC/AML checks, sanctions screening. Currently passthrough. |
| MonetizationPlugin | `monetization` | PRE_ROUTE | 100 | Skeleton | Billing service integration. Designed for quota checking, plan-based rate limits, API metering. Currently passthrough with free-tier header. |

### Runtime Management

Plugins can be enabled/disabled at runtime via the admin API without restarting the gateway:

- `POST /gateway/admin/plugins/{id}/enable` with JSON config body
- `POST /gateway/admin/plugins/{id}/disable`
- `GET /gateway/admin/plugins/{id}/health` for individual health checks

The `PluginRegistry` uses `ConcurrentHashMap` for thread-safe plugin state management.

---

## 4. Data Flow

### Standard Request Flow (all plugins proceed)

```
Client
  |
  | HTTP request + X-API-Key + optional X-Correlation-Id
  v
[SecurityHeadersFilter] --- (no request-phase action, registers response callback)
  |
  v
[CorrelationIdFilter] --- generates UUID or propagates existing X-Correlation-Id
  |                        mutates request to include header
  |                        adds header to response
  v
[RequestLoggingFilter] --- logs: method, path, correlationId, clientIp
  |                        captures startTime
  v
[PluginChainFilter - PRE_ROUTE]
  |
  +-- CompliancePlugin (order 50, if enabled)
  |     +-- checks compliance status
  |     +-- returns PluginResult(proceed=true, headers={"X-Compliance-Status": "passthrough"})
  |
  +-- MonetizationPlugin (order 100, if enabled)
  |     +-- checks billing quota
  |     +-- returns PluginResult(proceed=true, headers={"X-Monetization-Plan": "free"})
  |
  v  (all returned proceed=true)
[Route Matching] --- /api/v1/custody/** -> custody route, /api/v1/** -> orchestra route
  |
  v
[Per-Route Filters]
  +-- ApiKeyAuth --- validates X-API-Key against configured list
  +-- RateLimit --- token bucket check (per API key or IP)
  +-- CircuitBreaker --- Resilience4j state check
  +-- Retry --- retry policy for GET requests
  +-- StripPrefix --- if configured
  +-- AddRequestHeader --- X-Gateway-Route: <routeId>
  |
  v
[Downstream Service] --- e.g., bridge-orchestra-api:8080
  |
  v (response)
[PluginChainFilter - POST_ROUTE]
  |
  +-- AuditPlugin (order 10, if enabled)
  |     +-- logs structured JSON audit entry
  |     +-- returns PluginResult(proceed=true, metadata={"audit.timestamp": "...", "audit.logged": true})
  |
  v
[RequestLoggingFilter] --- logs: method, path, status, duration, correlationId
  |
  v
[SecurityHeadersFilter] --- adds: HSTS, X-Frame-Options, X-XSS-Protection, etc.
  |
  v
Client Response (with security headers, correlation ID, rate limit headers, plugin headers)
```

### Short-Circuit Flow (plugin blocks request)

```
Client
  |
  v
[Filters order -3 to -1] --- (normal processing)
  |
  v
[PluginChainFilter - PRE_ROUTE]
  |
  +-- CompliancePlugin (order 50)
  |     +-- determines entity is non-compliant
  |     +-- returns PluginResult(proceed=false, statusCode=403, responseBody={"error": "compliance_blocked", ...})
  |
  v  (proceed=false detected, chain stops immediately)
[PluginChainFilter - shortCircuit()]
  |   +-- sets response status to 403
  |   +-- writes JSON response body
  |   +-- NO downstream call is made
  |   +-- POST_ROUTE plugins are NOT executed
  v
Client receives 403 with compliance error details
```

### Circuit Breaker Fallback Flow

```
Client
  |
  v
[Filters + PRE_ROUTE plugins] --- (normal processing)
  |
  v
[Route: orchestra-api]
  |
  +-- [CircuitBreaker filter: state=OPEN]
  |     +-- circuit is open (downstream failures exceeded threshold)
  |     +-- forwards to fallbackUri: /fallback/orchestra
  |
  v
[CircuitBreakerFallbackController.orchestraFallback()]
  |   +-- returns {"error": "SERVICE_UNAVAILABLE", "service": "bridge-orchestra-api", "retryAfter": 30, ...}
  v
Client receives structured fallback response
```

---

## 5. Deployment Architecture

### Kubernetes Topology

```
Namespace: bridge-service-stack
+--------------------------------------------------------------------------+
|                                                                          |
|  +----------------------------+                                          |
|  | Ingress                    |                                          |
|  | gateway.bridgeintelligence |                                          |
|  | .ltd                       |                                          |
|  | - nginx ingress class      |                                          |
|  | - TLS: letsencrypt-prod    |                                          |
|  | - 50MB body limit          |                                          |
|  | - 120s read/send timeout   |                                          |
|  +------------+---------------+                                          |
|               |                                                          |
|  +------------v---------------+     +-------------------------------+    |
|  | Service: bridge-gateway    |     | ConfigMap: bridge-gateway-    |    |
|  | ClusterIP :4000            |     | config                       |    |
|  +------------+---------------+     | - SPRING_PROFILES_ACTIVE=dev |    |
|               |                     | - SERVICE URLs               |    |
|  +------------v---------------+     | - REDIS_HOST/PORT            |    |
|  | Deployment: bridge-gateway |     +-------------------------------+    |
|  | replicas: 1                |                                          |
|  | +------------------------+ |     +-------------------------------+    |
|  | | Pod                    | |     | Secret: bridge-gateway-       |    |
|  | | bridge-gateway         | |     | secrets                       |    |
|  | | container              | |     | - GATEWAY_API_KEYS (Vault)   |    |
|  | | image: harbor/bridge-  | |     | - REDIS_PASSWORD (Vault)     |    |
|  | |   gateway:dev-latest   | |     +-------------------------------+    |
|  | | port: 4000             | |                                          |
|  | | resources:             | |     +-------------------------------+    |
|  | |   req: 200m/384Mi     | |     | ServiceAccount:               |    |
|  | |   lim: 1/1Gi          | |     | bridge-gateway                |    |
|  | | probes:                | |     | (Vault K8s auth)              |    |
|  | |   startup: /health    | |     +-------------------------------+    |
|  | |   ready: /readiness   | |                                          |
|  | |   live: /liveness     | |                                          |
|  | +------------------------+ |                                          |
|  +----------------------------+                                          |
|                                                                          |
|  +----------------------------+  +---------------------------+           |
|  | bridge-orchestra-api:8080  |  | bridge-custody-api:8081   |           |
|  +----------------------------+  +---------------------------+           |
|                                                                          |
|  +----------------------------+  +---------------------------+           |
|  | bridge-wallet:8080         |  | bridge-redis-master:6379  |           |
|  +----------------------------+  +---------------------------+           |
|                                                                          |
+--------------------------------------------------------------------------+
```

### Kustomize Structure

```
k8s/
+-- base/
|   +-- kustomization.yaml      # namespace: bridge-service-stack, labels, all resources
|   +-- deployment.yaml          # 1 replica, probes, env, resources
|   +-- service.yaml             # ClusterIP :4000
|   +-- ingress.yaml             # TLS, nginx annotations
|   +-- configmap.yaml           # Non-sensitive env vars
|   +-- secret.yaml              # Vault-referenced sensitive values
|   +-- serviceaccount.yaml      # bridge-gateway SA for Vault K8s auth
|
+-- overlays/
    +-- dev/
        +-- kustomization.yaml   # Patches: reduced resources (100m/256Mi -> 500m/512Mi), image tag
```

### Container Image

- **Registry:** `harbor.binari.digital/bridge-gateway/bridge-gateway`
- **Build:** Multistage Dockerfile
  - Stage 1: `gradle:8.4-jdk17` builds the application, renames JAR to `app.jar`
  - Stage 2: `eclipse-temurin:17-jre-alpine` runtime with curl for health checks
- **Tags:** `dev-{SHA8}` (immutable) and `dev-latest` (floating)
- **Port:** 4000
- **Health check:** `curl -f http://localhost:4000/actuator/health`

### CI/CD Pipeline

```
Developer pushes to dev branch
         |
         v
[GitHub Actions: cd-dev.yml]
         |
         +-- Checkout + Setup JDK 17 (Temurin) + Gradle cache
         |
         +-- Build: ./gradlew build -x test
         |
         +-- Compute tag: dev-{SHA8}
         |
         +-- Vault JWT auth (role: github-bridge-gateway-dev)
         |       +-- Fetches Harbor credentials from kv/cicd/harbor/bridge-gateway/data/harbor-registry
         |
         +-- Docker login to harbor.binari.digital
         |
         +-- Build & push image (buildx, GHA cache)
         |       +-- Tags: dev-{SHA8}, dev-latest
         |       +-- Build args: BUILD_VERSION, BUILD_DATE, GIT_COMMIT
         |
         +-- Open/update PR: dev -> stage
         |
         v
[ArgoCD] auto-syncs from k8s/overlays/dev/ in bridge-gateway repo
         |
         v
[bridge-service-stack namespace] --- deployment rolls out with new image
```

---

## 6. Security Architecture

### Current Implementation

#### API Key Authentication
- Route-level filter factory (`ApiKeyAuthGatewayFilterFactory`)
- Validates `X-API-Key` header against a configured list of valid keys
- Keys stored in `GATEWAY_API_KEYS` environment variable, sourced from Vault at `secret/bridge-gateway/dev#gateway_api_keys`
- Returns 401 (missing key) or 403 (invalid key) with JSON error body
- API key is masked in audit logs (first 8 chars + `***`)

#### Security Headers
- Applied to every response via `SecurityHeadersFilter` (order -3):

| Header | Value | Purpose |
|--------|-------|---------|
| `Strict-Transport-Security` | `max-age=31536000; includeSubDomains` | Enforce HTTPS |
| `X-Content-Type-Options` | `nosniff` | Prevent MIME sniffing |
| `X-Frame-Options` | `DENY` | Prevent clickjacking |
| `X-XSS-Protection` | `1; mode=block` | Legacy XSS filter |
| `Referrer-Policy` | `strict-origin-when-cross-origin` | Limit referrer leakage |
| `Permissions-Policy` | `camera=(), microphone=(), geolocation=()` | Disable device APIs |
| `Cache-Control` | `no-store, no-cache, must-revalidate` | Prevent response caching |
| `Pragma` | `no-cache` | HTTP/1.0 cache prevention |

#### Rate Limiting
- Token bucket algorithm per client (identified by API key or IP address)
- Configurable `requestsPerSecond` and `burstCapacity` per route
- Returns 429 Too Many Requests with `Retry-After: 1` header when exceeded
- Response headers: `X-RateLimit-Limit`, `X-RateLimit-Remaining`
- Current implementation: in-memory (single instance); Redis-backed distributed rate limiting is available via Spring Cloud Gateway's built-in `RequestRateLimiter` filter

#### Spring Security Configuration
- WebFlux security with CSRF, httpBasic, and formLogin disabled
- All exchanges currently set to `permitAll()` (gateway-level auth handled by ApiKeyAuth filter, not Spring Security)
- Actuator endpoints explicitly permitted: `/actuator/health`, `/actuator/info`, `/actuator/prometheus`
- Admin endpoints explicitly permitted: `/gateway/admin/**`
- Fallback endpoints explicitly permitted: `/fallback/**`

### Vault Integration

```
vault.binari.digital (platform Vault)
|
+-- auth/kubernetes
|   +-- role: bridge-gateway
|       +-- bound_service_account_names: bridge-gateway
|       +-- bound_service_account_namespaces: bridge-service-stack
|       +-- policies: bridge-gateway-app
|
+-- secret/bridge-gateway/
|   +-- dev#gateway_api_keys          --> GATEWAY_API_KEYS env var
|
+-- secret/bridge/
    +-- redis#password                --> REDIS_PASSWORD env var
```

- Spring Cloud Vault configured in `bootstrap.yml` with Kubernetes authentication
- Vault autoconfig excluded from default profile to allow local development without Vault
- Secret references in K8s Secret manifest use `${VAULT:path#key}` placeholder pattern

### Planned Security Enhancements

| Feature | Description | Status |
|---------|-------------|--------|
| Keycloak JWT validation | Validate access tokens issued by Keycloak OIDC provider | Planned |
| OIDC token relay | Forward validated JWT to downstream services | Planned |
| Vault Agent sidecar | Inject secrets directly into pod via Vault Agent (annotation `vault.hashicorp.com/agent-inject` currently set to `false`) | Planned |
| Admin API authentication | Restrict `/gateway/admin/**` to authorized operators | Planned |
| CompliancePlugin activation | KYC/AML pre-route checks via Orbit compliance gateway | Planned |
| IP allowlisting | Restrict access by source IP range (`gateway.security.allowed-ip-ranges`) | Configured, not enforced |
| Request size enforcement | `gateway.security.max-request-size` (10MB configured) | Configured, not enforced at gateway level (50MB at ingress) |

---

## 7. Resilience Architecture

### Circuit Breaker Configuration

Bridge Gateway uses Resilience4j circuit breakers with two configurations:

#### Default Configuration (applied to orchestra)

| Parameter | Value | Description |
|-----------|-------|-------------|
| `failureRateThreshold` | 50% | Percentage of failures to trip the breaker |
| `slowCallRateThreshold` | 80% | Percentage of slow calls to trip the breaker |
| `slowCallDurationThreshold` | 5 seconds | Calls exceeding this are considered slow |
| `slidingWindowSize` | 20 | Number of calls in the sliding window |
| `slidingWindowType` | COUNT_BASED | Window based on call count |
| `minimumNumberOfCalls` | 10 | Minimum calls before failure rate is calculated |
| `waitDurationInOpenState` | 30 seconds | How long the breaker stays open |
| `permittedNumberOfCallsInHalfOpenState` | 5 | Test calls allowed in half-open state |
| `timeoutDuration` | 10 seconds | Per-call time limit |

#### Custody Configuration (stricter thresholds)

| Parameter | Value | Description |
|-----------|-------|-------------|
| `failureRateThreshold` | 30% | Lower threshold due to custody criticality |
| `slidingWindowSize` | 10 | Smaller window for faster response to issues |
| `waitDurationInOpenState` | 60 seconds | Longer cooldown for custody service recovery |
| `timeoutDuration` | 15 seconds | Longer timeout for custody operations (signing, vault calls) |

### Circuit Breaker State Machine

```
              success rate >= threshold
         +--------------------------------+
         |                                |
         v                                |
    +---------+    failure rate     +------+------+
    |  CLOSED  | ----------------> |    OPEN      |
    | (normal) |   >= threshold    | (all calls   |
    +---------+                    |  rejected)   |
         ^                         +------+------+
         |                                |
         |    all test calls succeed      | wait duration expires
         |                                |
    +----+--------+                       |
    | HALF_OPEN   | <---------------------+
    | (limited    |
    |  test calls)|
    +-------------+
         |
         | test calls fail
         |
         +-----> back to OPEN
```

### Retry Policies

| Route | Retries | Methods | First Backoff | Max Backoff | Factor |
|-------|---------|---------|---------------|-------------|--------|
| orchestra-api | 3 | GET only | 100ms | 1000ms | 2 (exponential) |
| custody-api | 2 | GET only | 200ms | 2000ms | 2 (exponential) |

> **Note:** Only GET requests are retried to prevent duplicate side effects on non-idempotent operations. POST, PUT, DELETE, and PATCH are never retried.

### Fallback Chain

When a circuit breaker is open or a downstream call fails after all retries, the request is forwarded to a fallback controller:

| Route | Fallback URI | Controller Method | Retry-After |
|-------|-------------|-------------------|-------------|
| orchestra-api | `/fallback/orchestra` | `orchestraFallback()` | 30 seconds |
| custody-api | `/fallback/custody` | `custodyFallback()` | 60 seconds |
| (catch-all) | `/fallback/default` | `defaultFallback()` | 30 seconds |

All fallback responses return a consistent JSON structure:

```json
{
  "error": "SERVICE_UNAVAILABLE",
  "message": "Orchestra service is temporarily unavailable. Please retry shortly.",
  "service": "bridge-orchestra-api",
  "timestamp": "2026-02-27T12:00:00.000Z",
  "retryAfter": 30
}
```

### Resilience Flow Summary

```
Client Request
     |
     v
[CircuitBreaker State Check]
     |
     +-- CLOSED: proceed to downstream
     |     |
     |     +-- Success: record success, return response
     |     +-- Failure/Timeout:
     |           +-- record failure
     |           +-- [Retry policy applies?]
     |                 +-- YES: retry with exponential backoff
     |                 +-- NO (or retries exhausted): forward to fallback
     |
     +-- OPEN: reject immediately, forward to fallback
     |
     +-- HALF_OPEN: allow limited test calls
           +-- Success: transition to CLOSED
           +-- Failure: transition back to OPEN, forward to fallback
```

---

## Appendix: Configuration Reference

### Environment Variables

| Variable | Source | Description |
|----------|--------|-------------|
| `SPRING_PROFILES_ACTIVE` | ConfigMap | Active Spring profile (`dev`) |
| `SERVER_PORT` | ConfigMap | Application port (`4000`) |
| `ORCHESTRA_BASE_URL` | ConfigMap | Orchestra service URL |
| `CUSTODY_BASE_URL` | ConfigMap | Custody service URL |
| `WALLET_BASE_URL` | ConfigMap | Wallet service URL |
| `REDIS_HOST` | ConfigMap | Redis hostname |
| `REDIS_PORT` | ConfigMap | Redis port |
| `VAULT_ADDR` | ConfigMap | Vault server URL |
| `VAULT_ENABLED` | ConfigMap | Enable Vault integration (`true`/`false`) |
| `GATEWAY_API_KEYS` | Secret (Vault) | Comma-separated valid API keys |
| `REDIS_PASSWORD` | Secret (Vault) | Redis authentication password |

### Admin API Endpoints

| Method | Path | Description |
|--------|------|-------------|
| GET | `/gateway/admin/routes` | List all configured routes |
| GET | `/gateway/admin/health` | Gateway health with plugin summary |
| GET | `/gateway/admin/config` | Sanitized gateway configuration |
| GET | `/gateway/admin/plugins` | List all plugins with status |
| GET | `/gateway/admin/plugins/{id}` | Plugin detail with health |
| POST | `/gateway/admin/plugins/{id}/enable` | Enable plugin (JSON config body) |
| POST | `/gateway/admin/plugins/{id}/disable` | Disable plugin |
| GET | `/gateway/admin/plugins/{id}/health` | Plugin health check |

### Actuator Endpoints

| Path | Description |
|------|-------------|
| `/actuator/health` | Application health (includes GatewayHealthIndicator) |
| `/actuator/health/readiness` | Readiness probe |
| `/actuator/health/liveness` | Liveness probe |
| `/actuator/info` | Build info |
| `/actuator/metrics` | Micrometer metrics |
| `/actuator/prometheus` | Prometheus scrape endpoint |
| `/actuator/gateway` | Spring Cloud Gateway route info |
