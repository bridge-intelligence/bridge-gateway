# Bridge Gateway -- Product Report

**Document Version:** 1.0
**Date:** 2026-02-27
**Classification:** Internal -- Stakeholder Review
**Repository:** `bridge-intelligence/bridge-gateway`
**Vendor:** Binari Digital

---

## Table of Contents

1. [Executive Summary](#1-executive-summary)
2. [Purpose & Mission](#2-purpose--mission)
3. [Core Features](#3-core-features)
4. [Architecture](#4-architecture)
5. [API Endpoints](#5-api-endpoints)
6. [Configuration Management](#6-configuration-management)
7. [Deployment Architecture](#7-deployment-architecture)
8. [CI/CD Pipeline](#8-cicd-pipeline)
9. [Current Capabilities](#9-current-capabilities)
10. [Opportunities](#10-opportunities)
11. [Future Roadmap](#11-future-roadmap)
12. [Risk Assessment](#12-risk-assessment)
13. [Technical Specifications](#13-technical-specifications)

---

## 1. Executive Summary

Bridge Gateway is a reactive API gateway purpose-built for the Bridge financial ecosystem. It serves as the single entry point for all external traffic destined for Bridge platform services. Ingress is exposed at three TLS-terminated hosts via Kubernetes ingress:

- **`gateway.bridgeintelligence.ltd`** — main gateway (all routes)
- **`api.custody.app.d.bridgeintelligence.ltd`** — custody API (same backend; use for API-only access)
- **`custody.app.d.bridgeintelligence.ltd`** — custody UI / console (same backend; use for dashboard access)

The gateway is built on **Spring Cloud Gateway** with **Kotlin** and runs on the **reactive WebFlux/Reactor** stack, providing non-blocking request processing capable of handling high-concurrency workloads without thread-per-request overhead. It currently routes traffic to two backend services -- **bridge-orchestra-api** (the orchestration layer) and **bridge-custody-api** (the custody service) -- with a third route for **bridge-wallet** defined but disabled pending that service's readiness.

Key architectural differentiators include:

- **Plugin-driven extensibility** via a custom SPI (Service Provider Interface) that allows cross-cutting concerns to be composed as independent, lifecycle-managed plugins.
- **Resilience-first design** with per-route circuit breakers, retry policies, time limiters, and structured fallback responses powered by Resilience4j.
- **Observability built in** through Prometheus metrics, OpenTelemetry distributed tracing, structured JSON audit logging, and reactive health indicators that aggregate plugin health.
- **Security layering** via global filters that enforce request size limits, inject security headers (HSTS, CSP, X-Frame-Options), and support API key-based authentication.

The application is containerized as a multi-stage Docker image (Alpine JRE 17), deployed to the `bridge-service-stack` Kubernetes namespace via Kustomize overlays and ArgoCD GitOps, with images hosted on Harbor at `harbor.binari.digital`.

---

## 2. Purpose & Mission

### 2.1 Primary Objectives

| Objective | Description |
|-----------|-------------|
| **Single Entry Point** | All external traffic enters the Bridge ecosystem through this gateway. No backend service is exposed directly to the internet. |
| **Centralized Authentication** | API key validation is handled at the gateway layer, with OIDC/JWT validation planned for Phase 2. |
| **Centralized Rate Limiting** | Token-bucket rate limiting is enforced per client (by API key or IP address) at the gateway, protecting all downstream services. |
| **Compliance Enforcement** | The compliance plugin (currently disabled) is designed to integrate with the Orbit compliance gateway for KYC/AML screening at the request level. |
| **Audit Trail** | Every request passing through the gateway is logged as a structured JSON audit event, with future Kafka integration for downstream analytics. |
| **Plugin-Driven Extensibility** | New cross-cutting concerns (monetization, compliance, fraud detection) can be added as independent plugins without modifying the gateway core. |
| **Service Mesh Connectivity** | The gateway connects to orchestra, custody, wallet, and (future) bridge-id services via internal Kubernetes DNS, providing a unified external API surface. |

### 2.2 Design Principles

1. **Non-blocking by default.** All request processing is reactive (Project Reactor), ensuring the gateway can sustain high concurrency under load without thread exhaustion.
2. **Fail-safe routing.** Every route has circuit breaker protection with structured fallback responses, so downstream outages produce clean error responses rather than cascading failures.
3. **Configuration-driven.** Routes, plugins, resilience parameters, and security settings are all externalized to YAML configuration, with environment variable overrides for deployment-time customization.
4. **Secrets never in code.** All sensitive values (API keys, Redis passwords) are injected at runtime via Kubernetes Secrets and (when enabled) HashiCorp Vault.
5. **Observable from day one.** Prometheus metrics, distributed tracing, structured logging, and health endpoints are wired into the application from the foundation.

---

## 3. Core Features

### 3.1 Reactive Request Routing

Bridge Gateway routes external requests to internal backend services based on URL path predicates. The routing engine is built on Spring Cloud Gateway's `RouteLocator` abstraction, with a custom `GatewayRoutesConfig` that reads route definitions from YAML configuration and registers them programmatically at startup.

#### Configured Routes

| Route ID | Path Predicate | Target Service | Port | Status | Circuit Breaker | Retry Policy |
|----------|---------------|----------------|------|--------|-----------------|--------------|
| `orchestra-api` | `/api/v1/**` | `bridge-orchestra-api` | 8080 | **Enabled** | `orchestra` (50% threshold) | 3 retries, GET only, 100ms-1s backoff |
| `custody-api` | `/api/v1/custody/**` | `bridge-custody-api` | 8081 | **Enabled** | `custody` (30% threshold) | 2 retries, GET only, 200ms-2s backoff |
| `wallet` | `/api/v1/wallet/**` | `bridge-wallet` | 8080 | **Disabled** | N/A | N/A |

**Route precedence:** The `custody-api` route matches first for `/api/v1/custody/**` paths because Spring Cloud Gateway evaluates more specific path predicates before broader ones. All other `/api/v1/**` traffic is routed to `orchestra-api`.

**Custom route headers:** Each routed request is augmented with an `X-Gateway-Route` header containing the route ID, enabling downstream services to identify which gateway route was matched.

**Dynamic route configuration:** The `GatewayRoutesProperties` configuration class supports runtime route definitions via YAML. Each route definition includes:
- `enabled` -- boolean toggle for enable/disable without code changes
- `path` -- URL path predicate
- `uri` -- target service URI (overridable via environment variables)
- `stripPrefix` -- number of path segments to strip before forwarding
- `methods` -- optional HTTP method filter
- `plugins` -- list of plugin IDs to activate for this route

#### Route Target URIs (Dev Profile)

| Route | Default URI | Dev Override (K8s DNS) |
|-------|-------------|----------------------|
| Orchestra | `http://bridge-orchestra-api:8080` | `http://bridge-orchestra-api.bridge-service-stack.svc.cluster.local:8080` |
| Custody | `http://bridge-custody-api:8081` | `http://bridge-custody-api.bridge-service-stack.svc.cluster.local:8081` |
| Wallet | `http://bridge-wallet:8080` | `http://bridge-wallet.bridge-service-stack.svc.cluster.local:8080` |

---

### 3.2 Global Filter Chain

The gateway implements five global filters that execute on every request in a strict order. These filters are implemented as Spring Cloud Gateway `GlobalFilter` components with `Ordered` interface to control execution sequence.

#### Filter Execution Order

| Order | Filter | Responsibility |
|-------|--------|---------------|
| **-4** | `RequestSizeLimitFilter` | Rejects requests exceeding the configured maximum body size (default: 10 MB / 10,485,760 bytes). Returns `413 Payload Too Large` with `X-Max-Request-Size` header. |
| **-3** | `SecurityHeadersFilter` | Injects security response headers on every response: HSTS, X-Frame-Options, X-Content-Type-Options, X-XSS-Protection, Referrer-Policy, Permissions-Policy, Cache-Control, Pragma, and X-Gateway-Version. |
| **-2** | `CorrelationIdFilter` | Ensures every request has an `X-Correlation-Id` header. If the incoming request already carries one, it is preserved and propagated. If not, a new UUID v4 is generated. The correlation ID is added to both the forwarded request and the response. |
| **-1** | `RequestLoggingFilter` | Logs structured request/response information including HTTP method, path, correlation ID, client IP, response status code, and request duration in milliseconds. |
| **0** | `PluginChainFilter` | Executes the plugin chain in two phases: PRE_ROUTE plugins run before downstream forwarding (and can short-circuit the request), POST_ROUTE plugins run after the response is received. |

#### Security Headers Applied

| Header | Value |
|--------|-------|
| `X-Content-Type-Options` | `nosniff` |
| `X-Frame-Options` | `DENY` |
| `X-XSS-Protection` | `1; mode=block` |
| `Strict-Transport-Security` | `max-age=31536000; includeSubDomains` |
| `Referrer-Policy` | `strict-origin-when-cross-origin` |
| `Permissions-Policy` | `camera=(), microphone=(), geolocation=()` |
| `Cache-Control` | `no-store, no-cache, must-revalidate` |
| `Pragma` | `no-cache` |
| `X-Gateway-Version` | `1.0.0` |

---

### 3.3 Plugin System (SPI Architecture)

The plugin system is the gateway's primary extensibility mechanism. It follows a Service Provider Interface (SPI) pattern where plugins implement a common `GatewayPlugin` interface and are automatically discovered, registered, and managed by the `PluginRegistry`.

#### GatewayPlugin Interface

```kotlin
interface GatewayPlugin {
    val id: String          // Unique identifier
    val name: String        // Human-readable name
    val version: String     // Semantic version
    val phase: PluginPhase  // PRE_ROUTE, POST_ROUTE, or BOTH
    val order: Int          // Execution order within phase

    fun initialize(config: Map<String, Any>)
    fun execute(exchange: ServerWebExchange, context: PluginContext): Mono<PluginResult>
    fun shutdown()
    fun isEnabled(): Boolean
    fun healthCheck(): Mono<PluginHealth>
}
```

#### Plugin Lifecycle

1. **Discovery** -- At application startup, Spring component scanning discovers all `@Component` classes implementing `GatewayPlugin`.
2. **Registration** -- The `PluginRegistry` registers each discovered plugin by its `id` in a `ConcurrentHashMap`.
3. **Initialization** -- For each plugin with `enabled: true` in the YAML configuration, `initialize(config)` is called with the plugin's settings map.
4. **Execution** -- During request processing, the `PluginChainFilter` retrieves enabled plugins for the current phase (sorted by `order`) and executes them sequentially.
5. **Short-Circuiting** -- Any PRE_ROUTE plugin can return `PluginResult(proceed = false)` to short-circuit the request with a custom status code and response body.
6. **Error Isolation** -- Plugin execution errors are caught and logged; a failing plugin does not break the request chain (it is treated as `proceed = true`).
7. **Shutdown** -- On application shutdown (or plugin disable), `shutdown()` is called to release resources.
8. **Health Checking** -- Each plugin exposes a reactive `healthCheck()` method that is aggregated by the `GatewayHealthIndicator` into the `/actuator/health` endpoint.

#### Plugin Execution Context

Each plugin receives a `PluginContext` containing:
- `routeId` -- The matched route identifier
- `correlationId` -- The request's correlation ID
- `metadata` -- A mutable map for passing data between plugins in the chain

#### Plugin Result

Each plugin returns a `PluginResult` containing:
- `proceed` -- Whether to continue the chain or short-circuit
- `statusCode` -- HTTP status code (used when `proceed = false`)
- `responseBody` -- Response body string (used when `proceed = false`)
- `headers` -- Additional response headers to inject
- `metadata` -- Data to merge into the plugin context

#### Built-in Plugins

| Plugin ID | Name | Phase | Order | Default State | Description |
|-----------|------|-------|-------|---------------|-------------|
| `audit` | Audit Logging Plugin | `POST_ROUTE` | 10 | **Enabled** | Logs structured JSON audit events for every request, including timestamp, correlation ID, route ID, HTTP method, path, status code, client IP, user agent, and masked API key. Designed for future Kafka publishing to `gateway.audit.events`. |
| `compliance` | Compliance Plugin | `PRE_ROUTE` | 50 | **Disabled** | Skeleton for KYC/AML compliance checks via the Orbit compliance gateway. When fully implemented, will validate entity compliance status and block sanctioned or unverified users. Currently operates in passthrough mode. |
| `monetization` | Monetization Plugin | `PRE_ROUTE` | 100 | **Disabled** | Skeleton for API monetization. When fully implemented, will check usage quotas against a billing service, enforce plan-based rate limits, and track API call metering. Currently applies a free-tier header to all requests. |

#### Plugin Admin API

Runtime plugin management is exposed via the `PluginAdminController` at `/gateway/admin/plugins`:

| Method | Path | Description |
|--------|------|-------------|
| `GET` | `/gateway/admin/plugins` | List all plugins with status (id, name, version, phase, order, enabled, healthy) |
| `GET` | `/gateway/admin/plugins/{id}` | Get detailed information for a specific plugin including health status |
| `POST` | `/gateway/admin/plugins/{id}/enable` | Enable a plugin with provided configuration (JSON body) |
| `POST` | `/gateway/admin/plugins/{id}/disable` | Disable a running plugin and release its resources |
| `GET` | `/gateway/admin/plugins/{id}/health` | Get health status of a specific plugin |

---

### 3.4 Authentication & Authorization

#### API Key Authentication

The `ApiKeyAuthGatewayFilterFactory` provides API key validation as a route-level gateway filter. It supports:

- **Header-based key extraction** -- Reads the API key from the `X-API-Key` header (configurable header name).
- **Configurable enforcement** -- The `required` flag controls whether the filter rejects requests without a key (`401 Unauthorized`) or allows them through.
- **Key validation** -- Submitted keys are checked against a configured whitelist loaded from the `GATEWAY_API_KEYS` environment variable (sourced from Vault in production).
- **Structured error responses** -- Returns JSON error bodies with appropriate HTTP status codes:
  - `401 Unauthorized` -- Missing API key when required
  - `403 Forbidden` -- Invalid API key

#### Rate Limiting

The `RateLimitGatewayFilterFactory` implements token-bucket rate limiting as a route-level filter:

- **Per-client buckets** -- Each unique client (identified by API key, or by IP address if no key is present) gets an independent token bucket.
- **Configurable parameters** -- `requestsPerSecond` (default: 10.0) and `burstCapacity` (default: 20).
- **Token refill** -- Tokens are refilled based on elapsed time since the last refill, using atomic operations for thread safety.
- **Response headers** -- Every response includes `X-RateLimit-Limit` and `X-RateLimit-Remaining` headers.
- **Rate limit exceeded** -- Returns `429 Too Many Requests` with a `Retry-After: 1` header and JSON error body.

**Current limitation:** Token buckets are stored in-memory (`ConcurrentHashMap`), which means rate limits are per-instance and not shared across gateway replicas. Distributed rate limiting via Redis is planned.

---

### 3.5 Resilience

The gateway implements a comprehensive resilience strategy using Resilience4j, configured both programmatically (via `ResilienceConfig`) and declaratively (via `application.yml`).

#### Circuit Breaker Configuration

| Parameter | Default | Custody Override |
|-----------|---------|-----------------|
| Failure rate threshold | 50% | 30% |
| Slow call rate threshold | 80% | (inherits default) |
| Slow call duration threshold | 5 seconds | (inherits default) |
| Wait duration in open state | 30 seconds | 60 seconds |
| Permitted calls in half-open | 5 | (inherits default) |
| Sliding window size | 20 calls | 10 calls |
| Sliding window type | COUNT_BASED | (inherits default) |
| Minimum number of calls | 10 | (inherits default) |

**Rationale for tighter custody thresholds:** The custody service handles cryptographic key operations and signing -- operations where failures may indicate security-critical issues. The lower failure threshold (30%) and longer wait duration (60s) ensure the gateway is more conservative about sending traffic to a potentially compromised custody service.

#### Time Limiter Configuration

| Service | Timeout Duration |
|---------|-----------------|
| Default (all routes) | 10 seconds |
| Custody | 15 seconds |

#### Retry Configuration

| Route | Retries | Methods | First Backoff | Max Backoff | Factor |
|-------|---------|---------|---------------|-------------|--------|
| Orchestra | 3 | GET | 100ms | 1,000ms | 2x |
| Custody | 2 | GET | 200ms | 2,000ms | 2x |

**Note:** Retries are only applied to GET requests to avoid retrying non-idempotent operations.

#### Fallback Endpoints

When a circuit breaker opens, traffic is forwarded to internal fallback controllers that return structured JSON responses:

| Fallback Path | Service | Retry After |
|---------------|---------|-------------|
| `/fallback/orchestra` | bridge-orchestra-api | 30 seconds |
| `/fallback/custody` | bridge-custody-api | 60 seconds |
| `/fallback/default` | Generic | 30 seconds |

**Fallback response format:**
```json
{
    "error": "SERVICE_UNAVAILABLE",
    "message": "Orchestra service is temporarily unavailable. Please retry shortly.",
    "service": "bridge-orchestra-api",
    "timestamp": "2026-02-27T12:00:00Z",
    "retryAfter": 30
}
```

---

### 3.6 Observability

#### Prometheus Metrics

- **Endpoint:** `/actuator/prometheus`
- **Registry:** Micrometer with Prometheus exporter
- **Common tags:** `application=bridge-gateway`, `component=gateway`
- **Histograms:** `http.server.requests` with percentile histograms enabled
- **SLA buckets:** 50ms, 100ms, 200ms, 500ms, 1s, 5s
- **Circuit breaker metrics:** Registered as health indicators with metrics exposed per instance

#### OpenTelemetry Distributed Tracing

- **Tracing bridge:** `micrometer-tracing-bridge-otel`
- **Exporter:** `opentelemetry-exporter-otlp` (OTLP protocol)
- **Correlation:** Trace context is propagated via the `X-Correlation-Id` header through all downstream services

#### Structured Logging

- **Encoder:** Logstash JSON encoder (`logstash-logback-encoder:7.4`)
- **Pattern:** `%d{ISO8601} [%thread] [%X{correlationId}] %-5level %logger{36} - %msg%n`
- **Audit events:** Structured JSON objects logged by the AuditPlugin with fields: timestamp, correlationId, routeId, method, path, status, clientIp, userAgent, apiKey (masked)

#### Health Indicators

- **Custom health indicator:** `GatewayHealthIndicator` aggregates health from all enabled plugins
- **Health details include:** totalPlugins, enabledPlugins, healthyPlugins, per-plugin status (id, name, version, phase, enabled, healthy)
- **Health status:** `UP` when all enabled plugins report healthy; `DOWN` when any enabled plugin is unhealthy

#### Actuator Endpoints Exposed

| Endpoint | Purpose |
|----------|---------|
| `/actuator/health` | Aggregated health status |
| `/actuator/health/readiness` | Kubernetes readiness probe |
| `/actuator/health/liveness` | Kubernetes liveness probe |
| `/actuator/info` | Application information |
| `/actuator/metrics` | Micrometer metrics |
| `/actuator/prometheus` | Prometheus scrape endpoint |
| `/actuator/gateway` | Spring Cloud Gateway route information |

---

### 3.7 Security

#### WebFlux Security Configuration

The `SecurityConfig` class configures Spring Security for the reactive WebFlux stack:

- **CSRF protection:** Disabled (API gateway, not a browser-rendered application)
- **HTTP Basic:** Disabled
- **Form Login:** Disabled
- **Authorization:** All exchanges currently `permitAll()` (authentication is handled at the filter/plugin level, not the Spring Security level)

#### Security Headers

All responses include the hardened security headers listed in Section 3.2. These headers provide:

- **Clickjacking protection** via `X-Frame-Options: DENY`
- **MIME type sniffing protection** via `X-Content-Type-Options: nosniff`
- **XSS protection** via `X-XSS-Protection: 1; mode=block`
- **Transport security** via HSTS with 1-year max-age and includeSubDomains
- **Referrer leakage prevention** via `Referrer-Policy: strict-origin-when-cross-origin`
- **Feature restriction** via `Permissions-Policy` disabling camera, microphone, geolocation
- **Cache prevention** via `Cache-Control: no-store, no-cache, must-revalidate`

#### Request Size Limiting

The `RequestSizeLimitFilter` rejects requests with `Content-Length` exceeding 10 MB (10,485,760 bytes), returning `413 Payload Too Large`.

#### CORS Configuration

CORS is configured at two levels:

1. **Spring Cloud Gateway global CORS** -- Applied via `spring.cloud.gateway.globalcors` in YAML
2. **Custom CorsConfig** -- A programmatic `CorsWebFilter` bean with the following settings:

| Parameter | Value |
|-----------|-------|
| Allowed Origins | `https://orchestrator.binari.digital`, `http://localhost:3000`, `http://localhost:5173` |
| Allowed Methods | GET, POST, PUT, DELETE, PATCH, OPTIONS |
| Allowed Headers | `*` |
| Exposed Headers | `X-Correlation-Id`, `X-RateLimit-Remaining`, `X-RateLimit-Limit`, `X-Monetization-Plan` |
| Allow Credentials | `true` (main config), `false` (CORS bean default) |
| Max Age | 3600 seconds |

#### TLS Termination

TLS is terminated at the Kubernetes ingress layer:
- **Ingress class:** nginx
- **Certificate issuer:** `letsencrypt-prod` (cert-manager)
- **TLS secret:** `bridge-gateway-tls`
- **SSL redirect:** Enforced via annotation

---

## 4. Architecture

### 4.1 Technology Stack

| Layer | Technology | Version |
|-------|-----------|---------|
| **Framework** | Spring Boot | 3.2.5 |
| **Cloud** | Spring Cloud | 2023.0.1 |
| **Gateway** | Spring Cloud Gateway | (managed by Spring Cloud BOM) |
| **Language** | Kotlin | 1.9.23 |
| **JVM** | Eclipse Temurin | 17 (JRE Alpine) |
| **Reactive** | Project Reactor / WebFlux | (managed by Spring Boot BOM) |
| **Resilience** | Resilience4j (reactor) | (managed by Spring Cloud BOM) |
| **Metrics** | Micrometer + Prometheus | (managed by Spring Boot BOM) |
| **Tracing** | OpenTelemetry (OTLP) | (managed by Spring Boot BOM) |
| **Logging** | Logstash Logback Encoder | 7.4 |
| **Cache/Rate Limit** | Redis (reactive) | (managed by Spring Boot BOM) |
| **Secrets** | Spring Cloud Vault | (managed by Spring Cloud BOM) |
| **Security** | Spring Security (WebFlux) | (managed by Spring Boot BOM) |
| **Serialization** | Jackson (Kotlin module) | (managed by Spring Boot BOM) |
| **Coroutines** | kotlinx-coroutines-reactor | (managed by Kotlin BOM) |
| **Build** | Gradle | 8.4 |
| **Container** | Docker (multi-stage) | Alpine JRE 17 |
| **Testing** | JUnit 5, Reactor Test, MockK, SpringMockK | 1.13.10 / 4.0.2 |

### 4.2 Module Structure

```
bridge-gateway/
+-- src/main/kotlin/digital/binari/bridge/gateway/
|   +-- BridgeGatewayApplication.kt          # Application entry point
|   +-- config/
|   |   +-- GatewayRoutesConfig.kt           # Route locator + properties
|   |   +-- CorsConfig.kt                    # CORS filter bean
|   |   +-- ResilienceConfig.kt              # Circuit breaker + time limiter
|   |   +-- MetricsConfig.kt                 # Micrometer common tags
|   |   +-- SecurityConfig.kt                # WebFlux security chain
|   +-- filter/
|   |   +-- RequestSizeLimitFilter.kt        # Global: request size guard
|   |   +-- SecurityHeadersFilter.kt         # Global: security response headers
|   |   +-- CorrelationIdFilter.kt           # Global: correlation ID propagation
|   |   +-- RequestLoggingFilter.kt          # Global: structured request/response logging
|   |   +-- ApiKeyAuthGatewayFilterFactory.kt    # Route: API key validation
|   |   +-- RateLimitGatewayFilterFactory.kt     # Route: token-bucket rate limiting
|   |   +-- CircuitBreakerFallbackController.kt  # Fallback REST endpoints
|   +-- plugin/
|   |   +-- GatewayPlugin.kt                 # SPI interface + data classes
|   |   +-- PluginConfigProperties.kt        # Plugin configuration properties
|   |   +-- PluginRegistry.kt                # Plugin lifecycle management
|   |   +-- PluginChainFilter.kt             # Global: plugin chain execution
|   |   +-- impl/
|   |       +-- AuditPlugin.kt              # Audit logging plugin
|   |       +-- CompliancePlugin.kt          # Compliance/Orbit plugin (skeleton)
|   |       +-- MonetizationPlugin.kt        # Monetization plugin (skeleton)
|   +-- controller/
|   |   +-- PluginAdminController.kt         # Plugin CRUD admin API
|   |   +-- GatewayAdminController.kt        # Gateway admin (routes, health, config)
|   +-- health/
|       +-- GatewayHealthIndicator.kt        # Actuator health aggregation
+-- src/main/resources/
|   +-- application.yml                      # Main configuration
|   +-- application-dev.yml                  # Dev profile overrides
|   +-- bootstrap.yml                        # Vault integration config
+-- src/test/kotlin/digital/binari/bridge/gateway/
|   +-- BridgeGatewayApplicationTest.kt      # Application context test
|   +-- plugin/PluginRegistryTest.kt         # Plugin registry unit tests
|   +-- filter/CorrelationIdFilterTest.kt    # Correlation ID filter tests
+-- k8s/
|   +-- base/                                # Kustomize base manifests
|   +-- overlays/dev/                        # Dev environment overlay
+-- .github/workflows/
|   +-- cd-dev.yml                           # CD pipeline (dev)
|   +-- ci.yaml                              # CI pipeline (placeholder)
+-- Dockerfile                               # Multi-stage build
+-- build.gradle.kts                         # Gradle build configuration
+-- settings.gradle.kts                      # Project settings
+-- gradle.properties                        # Build properties
```

### 4.3 Request Flow

```
Client Request
    |
    v
[Ingress: gateway.bridgeintelligence.ltd]  -- TLS termination (cert-manager)
    |
    v
[K8s Service: bridge-gateway:4000]
    |
    v
[RequestSizeLimitFilter (order -4)]  -- Reject if Content-Length > 10MB
    |
    v
[SecurityHeadersFilter (order -3)]   -- Inject HSTS, CSP, etc. on response
    |
    v
[CorrelationIdFilter (order -2)]     -- Generate/propagate X-Correlation-Id
    |
    v
[RequestLoggingFilter (order -1)]    -- Log request start + duration on response
    |
    v
[PluginChainFilter (order 0)]
    |-- PRE_ROUTE phase
    |   |-- CompliancePlugin (order 50) [if enabled]
    |   |-- MonetizationPlugin (order 100) [if enabled]
    |   |-- (short-circuit if any plugin returns proceed=false)
    |
    v
[Spring Cloud Gateway Route Matching]
    |-- /api/v1/custody/** --> bridge-custody-api:8081
    |-- /api/v1/**         --> bridge-orchestra-api:8080
    |
    v
[Circuit Breaker + Retry + Time Limiter]
    |-- On failure: forward to /fallback/{service}
    |
    v
[Downstream Service Response]
    |
    v
[PluginChainFilter -- POST_ROUTE phase]
    |-- AuditPlugin (order 10) [if enabled]
    |
    v
[Response to Client]
```

---

## 5. API Endpoints

### 5.1 Proxied Routes (External API)

| Method | Path | Target Service | Description |
|--------|------|----------------|-------------|
| `*` | `/api/v1/custody/**` | bridge-custody-api:8081 | Custody operations (key management, signing, vault) |
| `*` | `/api/v1/**` | bridge-orchestra-api:8080 | All other API v1 operations (transactions, accounts, assets, etc.) |
| `*` | `/api/v1/wallet/**` | bridge-wallet:8080 | Wallet operations (**disabled**) |

### 5.2 Gateway Admin API

| Method | Path | Description | Auth Required |
|--------|------|-------------|---------------|
| `GET` | `/gateway/admin/routes` | List all configured routes with status | No (open) |
| `GET` | `/gateway/admin/health` | Gateway health summary with plugin aggregation | No (open) |
| `GET` | `/gateway/admin/config` | Current gateway configuration (sanitized, no secrets) | No (open) |
| `GET` | `/gateway/admin/plugins` | List all plugins with status | No (open) |
| `GET` | `/gateway/admin/plugins/{id}` | Get plugin detail + health | No (open) |
| `POST` | `/gateway/admin/plugins/{id}/enable` | Enable plugin with config (JSON body) | No (open) |
| `POST` | `/gateway/admin/plugins/{id}/disable` | Disable plugin | No (open) |
| `GET` | `/gateway/admin/plugins/{id}/health` | Plugin health check | No (open) |

### 5.3 Actuator Endpoints

| Method | Path | Description |
|--------|------|-------------|
| `GET` | `/actuator/health` | Aggregated health (gateway + plugins) |
| `GET` | `/actuator/health/readiness` | Kubernetes readiness probe |
| `GET` | `/actuator/health/liveness` | Kubernetes liveness probe |
| `GET` | `/actuator/info` | Application info |
| `GET` | `/actuator/metrics` | Micrometer metrics index |
| `GET` | `/actuator/metrics/{metric}` | Specific metric detail |
| `GET` | `/actuator/prometheus` | Prometheus scrape endpoint |
| `GET` | `/actuator/gateway` | Spring Cloud Gateway routes |

### 5.4 Fallback Endpoints

| Method | Path | Triggered By | Response |
|--------|------|-------------|----------|
| `GET` | `/fallback/orchestra` | Orchestra circuit breaker open | JSON: SERVICE_UNAVAILABLE, retryAfter=30 |
| `GET` | `/fallback/custody` | Custody circuit breaker open | JSON: SERVICE_UNAVAILABLE, retryAfter=60 |
| `GET` | `/fallback/default` | Any other circuit breaker | JSON: SERVICE_UNAVAILABLE, retryAfter=30 |

---

## 6. Configuration Management

### 6.1 Configuration Files

| File | Purpose | Profile |
|------|---------|---------|
| `src/main/resources/application.yml` | Main application configuration: server port, routes, plugins, resilience, metrics, CORS, logging | Default |
| `src/main/resources/application-dev.yml` | Dev profile overrides: relaxed CORS, K8s FQDN service URIs, debug logging | `dev` |
| `src/main/resources/bootstrap.yml` | Vault integration: authentication method, KV backend, secret context | Bootstrap |

### 6.2 Key Configuration Properties

| Property | Default | Source | Description |
|----------|---------|--------|-------------|
| `server.port` | `4000` | application.yml | HTTP listen port |
| `ORCHESTRA_BASE_URL` | `http://bridge-orchestra-api:8080` | Env / ConfigMap | Orchestra service URL |
| `CUSTODY_BASE_URL` | `http://bridge-custody-api:8081` | Env / ConfigMap | Custody service URL |
| `WALLET_BASE_URL` | `http://bridge-wallet:8080` | Env / ConfigMap | Wallet service URL |
| `REDIS_HOST` | `bridge-redis-master...svc.cluster.local` | Env / ConfigMap | Redis host for rate limiting |
| `REDIS_PORT` | `6379` | Env / ConfigMap | Redis port |
| `REDIS_PASSWORD` | (empty) | Secret / Vault | Redis authentication password |
| `GATEWAY_API_KEYS` | (empty) | Secret / Vault | Comma-separated valid API keys |
| `VAULT_ENABLED` | `false` | Env / ConfigMap | Enable Vault secret injection |
| `VAULT_ADDR` | `https://vault.binari.digital` | Env / ConfigMap | Vault server address |
| `gateway.security.max-request-size` | `10485760` (10 MB) | application.yml | Maximum request body size |

### 6.3 Vault Integration

The bootstrap configuration establishes Vault connectivity using Kubernetes authentication:

| Parameter | Value |
|-----------|-------|
| Vault URI | `https://vault.binari.digital` (overridable via `VAULT_ADDR`) |
| Auth method | KUBERNETES |
| Vault role | `bridge-gateway` |
| Token file | `/var/run/secrets/kubernetes.io/serviceaccount/token` |
| KV backend | `secret` |
| Default context | `bridge-gateway` |
| Profile separator | `/` |

**Current state:** Vault auto-configuration is explicitly excluded in `application.yml` (`VaultAutoConfiguration`, `VaultReactiveAutoConfiguration`, `VaultObservationAutoConfiguration`). Vault integration is structurally prepared but not yet activated (`VAULT_ENABLED=false`).

### 6.4 Kubernetes Configuration Resources

| Resource | Purpose |
|----------|---------|
| `ConfigMap: bridge-gateway-config` | Non-sensitive environment variables (service URLs, ports, feature flags) |
| `Secret: bridge-gateway-secrets` | Sensitive values (API keys, Redis password) with Vault placeholder references |

---

## 7. Deployment Architecture

### 7.1 Kubernetes Resources

| Resource | Name | Details |
|----------|------|---------|
| **Namespace** | `bridge-service-stack` | Shared namespace with all Bridge ecosystem services |
| **Deployment** | `bridge-gateway` | 1 replica, `harbor.binari.digital/bridge-gateway/bridge-gateway:dev-latest` |
| **Service** | `bridge-gateway` | ClusterIP, port 4000 |
| **Ingress** | `bridge-gateway` | Host: `gateway.bridgeintelligence.ltd`, TLS via cert-manager |
| **ServiceAccount** | `bridge-gateway` | For Vault Kubernetes auth (when enabled) |
| **ConfigMap** | `bridge-gateway-config` | Environment variables |
| **Secret** | `bridge-gateway-secrets` | Sensitive environment variables |

### 7.2 Resource Allocation

| Environment | CPU Request | CPU Limit | Memory Request | Memory Limit |
|-------------|-------------|-----------|----------------|--------------|
| **Base** | 200m | 1 core | 384 Mi | 1 Gi |
| **Dev** (overlay) | 100m | 500m | 256 Mi | 512 Mi |

### 7.3 Health Probes

| Probe | Path | Initial Delay | Period | Failure Threshold |
|-------|------|---------------|--------|-------------------|
| **Startup** | `/actuator/health` | 10s | 5s | 30 |
| **Readiness** | `/actuator/health/readiness` | 30s | 10s | 3 (default) |
| **Liveness** | `/actuator/health/liveness` | 45s | 15s | 3 (default) |

### 7.4 Ingress Configuration

| Parameter | Value |
|-----------|-------|
| Hosts | `gateway.bridgeintelligence.ltd`, `api.custody.app.d.bridgeintelligence.ltd`, `custody.app.d.bridgeintelligence.ltd` |
| TLS Secrets | `bridge-gateway-tls`, `bridge-gateway-api-custody-tls`, `bridge-gateway-custody-ui-tls` |
| Certificate Issuer | `letsencrypt-prod` |
| Ingress Class | `nginx` |
| SSL Redirect | `true` |
| Proxy Body Size | `50m` |
| Proxy Read Timeout | `120s` |
| Proxy Send Timeout | `120s` |
| Proxy Connect Timeout | `30s` |
| CORS Enabled | `true` |

### 7.5 Kustomize Overlays

| Overlay | Purpose | Key Patches |
|---------|---------|-------------|
| `base` | Shared manifests | Deployment, Service, Ingress, ConfigMap, Secret, ServiceAccount |
| `dev` | Development environment | Reduced resource limits (100m/256Mi - 500m/512Mi), dev-latest image tag |
| `stage` | Staging environment | (Planned) |
| `prod` | Production environment | (Planned) |

### 7.6 Docker Build

The Dockerfile implements a multi-stage build:

| Stage | Base Image | Purpose |
|-------|-----------|---------|
| **Builder** | `gradle:8.4-jdk17` | Compile Kotlin, run Gradle build, produce fat JAR |
| **Runtime** | `eclipse-temurin:17-jre-alpine` | Minimal runtime image with `curl` for healthchecks |

**Build arguments:** `BUILD_VERSION`, `BUILD_DATE`, `GIT_COMMIT` -- embedded as OCI labels.

**Container healthcheck:** `curl -f http://localhost:4000/actuator/health` every 30s with 30s startup period.

---

## 8. CI/CD Pipeline

### 8.1 Pipeline Overview

```
Developer pushes to dev branch
    |
    v
[GitHub Actions: CD -- Dev]
    |
    +-- Build & Push to Harbor
    |   |-- Checkout code
    |   |-- Setup Java 17 (Temurin + Gradle cache)
    |   |-- Gradle build (skip tests)
    |   |-- Compute image tag: dev-{SHA8}
    |   |-- Vault JWT auth (role: github-bridge-gateway-dev)
    |   |-- Retrieve Harbor credentials from Vault
    |   |-- Docker login to harbor.binari.digital
    |   |-- Docker buildx: build + push
    |   |   |-- Tag: dev-{SHA8}
    |   |   |-- Tag: dev-latest
    |   |-- GitHub Step Summary
    |
    +-- Open PR dev -> stage
        |-- Check for existing PR
        |-- Create/update PR with build details
        |-- Label: deployment
```

### 8.2 CI Workflow

| Workflow | File | Trigger | Status |
|----------|------|---------|--------|
| CI -- Build & Test | `.github/workflows/ci.yaml` | Push/PR to dev, stage, main | **Placeholder** (validates repo structure only) |
| CD -- Dev | `.github/workflows/cd-dev.yml` | Push to dev | **Active** |

### 8.3 Vault JWT Role

| Parameter | Value |
|-----------|-------|
| Role name | `github-bridge-gateway-dev` |
| Vault secrets path | `kv/cicd/harbor/bridge-gateway/data/harbor-registry` |
| Secrets retrieved | `username`, `password` (Harbor robot account) |

### 8.4 Image Registry

| Parameter | Value |
|-----------|-------|
| Registry | `harbor.binari.digital` |
| Project | `bridge-gateway` |
| Image | `bridge-gateway` |
| Tags | `dev-{SHA8}`, `dev-latest` |
| Build cache | GitHub Actions cache (gha) |

### 8.5 Deployment Flow

After the image is pushed to Harbor:
1. The CD pipeline automatically opens (or updates) a PR from `dev` to `stage`.
2. The dev Kustomize overlay references `dev-latest` (or a specific tag).
3. ArgoCD auto-syncs the deployment in the `bridge-service-stack` namespace.
4. Stage and production deployments require PR review and approval.

---

## 9. Current Capabilities

The following capabilities are fully implemented and operational in the current codebase:

| # | Capability | Status | Notes |
|---|-----------|--------|-------|
| 1 | Reactive HTTP request routing to orchestra and custody services | Working | Spring Cloud Gateway route locator |
| 2 | URL path-based route matching with configurable predicates | Working | Supports path, method, and custom predicates |
| 3 | Route enable/disable via configuration | Working | Wallet route disabled as example |
| 4 | Per-route circuit breaker with configurable thresholds | Working | Resilience4j, separate configs for orchestra and custody |
| 5 | Per-route retry with exponential backoff | Working | GET-only retries with configurable backoff |
| 6 | Per-route time limiter | Working | 10s default, 15s for custody |
| 7 | Structured fallback responses for circuit-broken services | Working | JSON responses with service name, retry-after |
| 8 | Request size limiting (10 MB max) | Working | Global filter, order -4 |
| 9 | Security response headers (HSTS, CSP, X-Frame-Options, etc.) | Working | Global filter, order -3 |
| 10 | Correlation ID generation and propagation | Working | UUID v4, preserves existing IDs |
| 11 | Structured request/response logging with duration tracking | Working | Global filter with method, path, status, duration |
| 12 | Plugin SPI architecture with lifecycle management | Working | Interface, registry, chain filter |
| 13 | Plugin auto-discovery via Spring component scanning | Working | All @Component GatewayPlugin implementations |
| 14 | Plugin enable/disable at startup via configuration | Working | YAML-driven plugin configuration |
| 15 | Runtime plugin enable/disable via Admin API | Working | POST /gateway/admin/plugins/{id}/enable\|disable |
| 16 | Plugin health checking with aggregation | Working | Per-plugin health + GatewayHealthIndicator |
| 17 | PRE_ROUTE plugin chain with short-circuit capability | Working | Compliance and monetization plugins (skeleton) |
| 18 | POST_ROUTE plugin chain with error isolation | Working | Audit plugin (active) |
| 19 | Audit logging plugin (structured JSON) | Working | Logs: timestamp, correlationId, route, method, path, status, IP, UA, API key |
| 20 | API key authentication filter factory | Working | Header extraction, whitelist validation, structured error responses |
| 21 | Token-bucket rate limiting filter factory | Working | Per-client (key or IP), configurable rate and burst |
| 22 | Rate limit response headers | Working | X-RateLimit-Limit, X-RateLimit-Remaining |
| 23 | CORS configuration (programmatic + YAML) | Working | Multiple origins, credential support, header exposure |
| 24 | Prometheus metrics endpoint with histogram SLA buckets | Working | /actuator/prometheus |
| 25 | OpenTelemetry distributed tracing (OTLP) | Working | micrometer-tracing-bridge-otel |
| 26 | Spring Actuator health, info, metrics, gateway endpoints | Working | Health with plugin aggregation |
| 27 | Kubernetes deployment manifests (Kustomize) | Working | Base + dev overlay |
| 28 | Docker multi-stage build with healthcheck | Working | Alpine JRE 17, curl healthcheck |
| 29 | GitHub Actions CD pipeline (dev) | Working | Vault JWT, Harbor push, auto PR |
| 30 | Gateway Admin API (routes, health, config) | Working | Sanitized config, route listing, health summary |
| 31 | Redis reactive client wiring for rate limiting | Working | Spring Data Redis Reactive dependency configured |
| 32 | Vault bootstrap configuration (Kubernetes auth) | Prepared | Config present, auto-configuration excluded, VAULT_ENABLED=false |
| 33 | WebFlux security filter chain | Working | CSRF/basic/form disabled, permitAll for all paths |

---

## 10. Opportunities

The following enhancements represent concrete opportunities to expand the gateway's capabilities:

### 10.1 Authentication & Identity

| Opportunity | Description | Priority |
|-------------|-------------|----------|
| **OIDC/Keycloak Integration** | Integrate with Keycloak at `keycloak.keycloak.svc.cluster.local:80` for JWT-based authentication. Validate access tokens, extract claims (roles, permissions, tenant), and propagate identity headers to downstream services. | High |
| **bridge-id Service** | Connect to the bridge-id identity service for user profile resolution, social login (Google, Apple, GitHub), and multi-factor authentication orchestration. | High |
| **JWT Claim Propagation** | Extract JWT claims (sub, roles, permissions, tenant_id) and inject them as standardized headers (X-User-Id, X-User-Roles, X-Tenant-Id) for downstream consumption. | High |
| **Scoped API Keys** | Enhance API key validation with scopes, rate limit tiers, and expiration dates. Associate API keys with tenants for multi-tenant isolation. | Medium |

### 10.2 Event Architecture

| Opportunity | Description | Priority |
|-------------|-------------|----------|
| **Kafka Event Bus** | Publish gateway audit events to `gateway.audit.events` Kafka topic (already configured in AuditPlugin settings). Enables downstream analytics, compliance reporting, and real-time monitoring. Kafka is available at `bridge-kafka.bridge-service-stack.svc.cluster.local:9092`. | High |
| **Redis Streams** | Use Redis Streams for real-time event propagation to the console UI (WebSocket bridge). | Medium |
| **Event Sourcing** | Record all gateway decisions (route, auth, rate limit, compliance) as immutable events for audit trail and replay capability. | Low |

### 10.3 Rate Limiting & Traffic Management

| Opportunity | Description | Priority |
|-------------|-------------|----------|
| **Distributed Rate Limiting** | Replace in-memory token buckets with Redis-backed rate limiting using Spring Cloud Gateway's built-in `RequestRateLimiter` filter with `RedisRateLimiter`. Enables consistent limits across multiple gateway replicas. | High |
| **Tiered Rate Limiting** | Different rate limits per API key tier (free, starter, professional, enterprise) driven by the monetization plugin. | Medium |
| **Request Throttling** | Gradual request slowdown (via delayed responses) instead of hard rejection at rate limits. | Low |

### 10.4 API Monetization

| Opportunity | Description | Priority |
|-------------|-------------|----------|
| **Usage Metering** | Track per-client API call counts by endpoint, method, and response status. Publish to analytics pipeline for billing. | Medium |
| **Plan Enforcement** | The monetization plugin skeleton is already in place. Connect it to a billing service to enforce quota limits and return `402 Payment Required` when exceeded. | Medium |
| **Usage Dashboard** | Expose per-client usage statistics via an admin API endpoint. | Low |

### 10.5 Protocol Support

| Opportunity | Description | Priority |
|-------------|-------------|----------|
| **WebSocket Support** | Add WebSocket route support for real-time features (trade execution, balance updates, notification streams). | Medium |
| **gRPC Gateway** | Add gRPC-to-REST transcoding for internal gRPC services that need external REST exposure. | Low |
| **GraphQL Federation** | Implement a GraphQL gateway that federates schemas from orchestra, custody, and wallet services into a unified graph. | Low |
| **Server-Sent Events** | SSE support for long-lived notification streams. | Low |

### 10.6 Compliance & Security

| Opportunity | Description | Priority |
|-------------|-------------|----------|
| **Orbit Compliance Integration** | Activate the compliance plugin and connect it to the Orbit compliance gateway for real-time KYC/AML screening. | High |
| **IP Geolocation** | Enrich requests with geolocation data for compliance (sanctions screening, jurisdictional routing). | Medium |
| **Request/Response Encryption** | End-to-end payload encryption for sensitive operations (key material, signing requests). | Medium |
| **Admin API Authentication** | Secure the `/gateway/admin/**` endpoints with authentication (currently open). | High |
| **Vault Activation** | Enable Vault auto-configuration for runtime secret injection, removing the need for Kubernetes Secrets with placeholder values. | High |

### 10.7 Caching & Performance

| Opportunity | Description | Priority |
|-------------|-------------|----------|
| **Response Caching** | Redis-backed response caching for idempotent GET endpoints with configurable TTLs per route. | Medium |
| **Request Deduplication** | Detect and collapse duplicate concurrent requests (same key + path + body hash). | Low |
| **Connection Pooling** | Tune Reactor Netty connection pool settings for each downstream service. | Medium |

### 10.8 Flows & Journeys Engine

| Opportunity | Description | Priority |
|-------------|-------------|----------|
| **Composable Operations** | Allow clients to define multi-step "flows" (e.g., create account -> fund account -> initiate transfer) as a single API call. | Medium |
| **Saga Orchestration** | Gateway-level saga coordination for multi-service operations with compensation logic. | Low |
| **Journey Templates** | Predefined journey templates for common workflows (onboarding, trading, settlement). | Low |

---

## 11. Future Roadmap

### Phase 1: Infrastructure Hardening (Near-Term)

| Item | Description | Dependencies |
|------|-------------|-------------|
| Activate Vault integration | Remove Vault auto-configuration exclusion, enable `VAULT_ENABLED=true`, provision `bridge-gateway` Vault role and policy | Vault admin access |
| Kafka producer | Integrate Kafka producer for AuditPlugin event publishing to `gateway.audit.events` | Kafka SASL credentials in Vault |
| Redis-backed rate limiting | Replace in-memory `ConcurrentHashMap` token buckets with `RedisRateLimiter` | Redis connectivity (already wired) |
| Redis Streams | Set up Redis Streams consumer/producer for real-time event bridging | Redis |
| CI pipeline completion | Replace placeholder CI workflow with full build + test + lint pipeline | None |

### Phase 2: Authentication & OIDC (Short-Term)

| Item | Description | Dependencies |
|------|-------------|-------------|
| Keycloak JWT validation | Add OIDC resource server configuration to validate Keycloak-issued JWTs | Keycloak realm configuration |
| bridge-id integration | Connect to bridge-id service for identity resolution and profile enrichment | bridge-id service deployment |
| Social login passthrough | Route social login flows (Google, Apple, GitHub) through the gateway to bridge-id | bridge-id service |
| Admin API authentication | Secure `/gateway/admin/**` with JWT or API key requirement | Keycloak or API key infrastructure |
| Scoped API keys | Associate API keys with clients, scopes, and rate limit tiers | Database or Vault storage |

### Phase 3: Flows & Journeys (Medium-Term)

| Item | Description | Dependencies |
|------|-------------|-------------|
| Flow definition DSL | Define composable multi-step operations as declarative flows | Design specification |
| Flow execution engine | Execute flows with step-by-step orchestration, error handling, and compensation | Orchestra service support |
| Journey templates | Predefined templates for common user journeys | Flow engine |
| Flow monitoring | Real-time flow execution monitoring and analytics | Kafka, observability stack |

### Phase 4: Multi-Environment CI/CD (Medium-Term)

| Item | Description | Dependencies |
|------|-------------|-------------|
| Stage pipeline | CD workflow for stage branch with integration tests | Stage environment |
| Prod pipeline | CD workflow for prod branch with canary/blue-green support | Prod environment |
| Kustomize overlays | Stage and prod Kustomize overlays with appropriate resource limits | K8s cluster capacity |
| ArgoCD applications | ArgoCD app definitions for stage and prod environments | ArgoCD admin access |
| Smoke tests | Automated smoke tests post-deployment | Test framework |

### Phase 5: Service Mesh & Advanced Features (Long-Term)

| Item | Description | Dependencies |
|------|-------------|-------------|
| Event bus federation | Gateway-level event bus connecting all ecosystem services via Kafka | Kafka infrastructure |
| Health aggregation | Aggregate health from all downstream services into a unified dashboard | Service health endpoints |
| WebSocket routing | WebSocket-aware routing for real-time services | WebSocket-capable services |
| gRPC transcoding | gRPC-to-REST transcoding for internal gRPC services | gRPC service definitions |
| GraphQL federation | Unified GraphQL schema federating orchestra, custody, wallet | GraphQL schema definitions |
| API monetization | Full billing integration with the monetization plugin | Billing service |
| Compliance activation | Full KYC/AML integration with the Orbit compliance gateway | Orbit compliance gateway |

---

## 12. Risk Assessment

### 12.1 Security Risks

| Risk | Severity | Current State | Mitigation |
|------|----------|---------------|------------|
| **No JWT validation** | **High** | The gateway does not validate JWTs. All authorization is via API key or fully open. Any client with network access can call any endpoint. | Phase 2: Integrate Keycloak OIDC resource server with JWT validation. |
| **Admin endpoints open** | **High** | `/gateway/admin/**` endpoints are exposed without authentication. Anyone who can reach the gateway can list routes, view configuration, and enable/disable plugins. | Phase 2: Secure admin endpoints with JWT or dedicated admin API key. |
| **Vault not activated** | **Medium** | Vault integration is configured but disabled. Secrets are currently managed via Kubernetes Secrets with Vault placeholder references that are not being resolved. | Phase 1: Enable Vault auto-configuration and provision the `bridge-gateway` Vault role. |
| **CORS wide open in dev** | **Medium** | The dev profile sets `allowedOriginPatterns: "*"` with `allowCredentials: true`, which could allow cross-origin attacks if the dev profile is inadvertently used in production. | Ensure profile-specific CORS is enforced per environment. Stage/prod profiles should have restrictive origin lists. |
| **Permissive security chain** | **Low** | Spring Security is configured with `anyExchange().permitAll()`. This is intentional (authentication is at the filter/plugin level), but it means Spring Security provides no protection. | Document this design decision. Consider adding path-based restrictions as authentication matures. |

### 12.2 Scalability Risks

| Risk | Severity | Current State | Mitigation |
|------|----------|---------------|------------|
| **In-memory rate limiting** | **High** | Rate limit token buckets are stored in a local `ConcurrentHashMap`. With multiple gateway replicas, each instance maintains independent counters, allowing clients to exceed intended limits by a factor of N (replicas). | Phase 1: Migrate to Redis-backed `RequestRateLimiter` for distributed rate limiting. |
| **Single replica** | **Medium** | The deployment specifies `replicas: 1`. No horizontal scaling or high availability. | Increase replicas and implement session affinity or stateless design for rate limiting. |
| **Unbounded bucket map** | **Low** | The in-memory rate limit bucket map grows without bound as new client IPs/keys are seen. Over time, this could cause memory pressure. | Implement TTL-based eviction or migrate to Redis. |

### 12.3 Operational Risks

| Risk | Severity | Current State | Mitigation |
|------|----------|---------------|------------|
| **Placeholder CI pipeline** | **Medium** | The CI workflow (`ci.yaml`) is a placeholder that only runs `ls -la`. There is no automated testing, linting, or build verification on pull requests. | Complete the CI pipeline with Gradle build, test execution, and code quality checks. |
| **No automated integration tests** | **Medium** | Only basic unit tests exist (application context, plugin registry, correlation filter). No integration tests verify routing, circuit breaking, or plugin execution. | Add integration tests using Spring Cloud Contract or WebTestClient. |
| **No stage/prod overlays** | **Medium** | Only the dev Kustomize overlay exists. Stage and production environments lack deployment configurations. | Phase 4: Create stage and prod overlays with appropriate resource limits and configurations. |
| **Wallet route disabled** | **Low** | The wallet route is defined but disabled. This is intentional but could cause confusion if someone expects wallet endpoints to be available. | Document the wallet route status. Enable when bridge-wallet service is ready. |

### 12.4 Reliability Risks

| Risk | Severity | Current State | Mitigation |
|------|----------|---------------|------------|
| **Redis dependency** | **Medium** | Redis is wired as a dependency for rate limiting (reactive client), but if Redis is unavailable, the application behavior depends on Spring auto-configuration fallback. | Add Redis health indicator; implement graceful degradation to in-memory rate limiting when Redis is unavailable. |
| **Plugin failure isolation** | **Low** | Plugin execution errors are caught and logged, but a plugin that hangs (does not complete the Mono) could block the entire request chain. | Add per-plugin timeout enforcement in the PluginChainFilter. |
| **No request body logging** | **Low** | The audit plugin does not log request/response bodies (`log-body: false`). This limits forensic investigation capability. | Make body logging configurable per route with appropriate size limits and PII masking. |

---

## 13. Technical Specifications

### 13.1 Dependencies

| Dependency | Group | Version | Purpose |
|-----------|-------|---------|---------|
| spring-cloud-starter-gateway | org.springframework.cloud | 2023.0.1 (BOM) | Reactive API gateway core |
| spring-boot-starter-actuator | org.springframework.boot | 3.2.5 (BOM) | Health, metrics, info endpoints |
| spring-cloud-starter-vault-config | org.springframework.cloud | 2023.0.1 (BOM) | HashiCorp Vault secret injection |
| spring-boot-starter-data-redis-reactive | org.springframework.boot | 3.2.5 (BOM) | Reactive Redis client for rate limiting |
| spring-boot-starter-security | org.springframework.boot | 3.2.5 (BOM) | WebFlux security filter chain |
| spring-cloud-starter-circuitbreaker-reactor-resilience4j | org.springframework.cloud | 2023.0.1 (BOM) | Reactive circuit breaker + time limiter |
| micrometer-registry-prometheus | io.micrometer | (managed) | Prometheus metrics exporter |
| micrometer-tracing-bridge-otel | io.micrometer | (managed) | OpenTelemetry tracing bridge |
| opentelemetry-exporter-otlp | io.opentelemetry | (managed) | OTLP trace exporter |
| logstash-logback-encoder | net.logstash.logback | 7.4 | Structured JSON logging |
| kotlinx-coroutines-reactor | org.jetbrains.kotlinx | (managed) | Kotlin coroutine-Reactor bridge |
| jackson-module-kotlin | com.fasterxml.jackson.module | (managed) | Kotlin-aware JSON serialization |
| kotlin-reflect | org.jetbrains.kotlin | 1.9.23 | Kotlin reflection support |
| spring-boot-starter-test | org.springframework.boot | 3.2.5 (BOM) | Testing framework |
| reactor-test | io.projectreactor | (managed) | Reactive stream testing |
| mockk | io.mockk | 1.13.10 | Kotlin mocking library |
| springmockk | com.ninja-squad | 4.0.2 | Spring + MockK integration |

### 13.2 Ports & Protocols

| Component | Port | Protocol | Exposure |
|-----------|------|----------|----------|
| Gateway HTTP | 4000 | HTTP/1.1 | Internal (ClusterIP) |
| Gateway Ingress | 443 | HTTPS (TLS) | External via `gateway.bridgeintelligence.ltd` |
| Orchestra API (target) | 8080 | HTTP | Internal |
| Custody API (target) | 8081 | HTTP | Internal |
| Wallet (target) | 8080 | HTTP | Internal (disabled) |
| Redis | 6379 | RESP | Internal |
| Vault | 8200 | HTTPS | External (`vault.binari.digital`) |

### 13.3 Build Specifications

| Parameter | Value |
|-----------|-------|
| Build tool | Gradle 8.4 |
| JVM target | 17 |
| Kotlin JVM target | 17 |
| Kotlin compiler flags | `-Xjsr305=strict` |
| Gradle JVM args | `-Xmx1024m` |
| Gradle parallel | `true` |
| Test framework | JUnit Platform |
| Docker builder base | `gradle:8.4-jdk17` |
| Docker runtime base | `eclipse-temurin:17-jre-alpine` |

### 13.4 Resilience Specifications

| Parameter | Orchestra | Custody | Default |
|-----------|-----------|---------|---------|
| Circuit breaker failure rate threshold | 50% | 30% | 50% |
| Slow call rate threshold | 80% | 80% | 80% |
| Slow call duration threshold | 5s | 5s | 5s |
| Wait duration in open state | 30s | 60s | 30s |
| Permitted calls in half-open state | 5 | 5 | 5 |
| Sliding window size | 20 | 10 | 20 |
| Sliding window type | COUNT_BASED | COUNT_BASED | COUNT_BASED |
| Minimum number of calls | 10 | 10 | 10 |
| Time limiter timeout | 10s | 15s | 10s |
| Retry count (GET only) | 3 | 2 | N/A |
| Retry first backoff | 100ms | 200ms | N/A |
| Retry max backoff | 1,000ms | 2,000ms | N/A |
| Retry backoff factor | 2x | 2x | N/A |

### 13.5 Plugin Specifications

| Plugin | ID | Phase | Order | Default Enabled | Version | Key Settings |
|--------|----|-------|-------|-----------------|---------|-------------|
| Audit Logging | `audit` | POST_ROUTE | 10 | Yes | 1.0.0 | `kafka-topic: gateway.audit.events`, `log-headers: true`, `log-body: false` |
| Compliance | `compliance` | PRE_ROUTE | 50 | No | 1.0.0 | `orbit-url: ${ORBIT_GATEWAY_URL}`, `timeout-ms: 5000` |
| Monetization | `monetization` | PRE_ROUTE | 100 | No | 1.0.0 | `billing-service-url: ${BILLING_SERVICE_URL}`, `free-tier-limit: 1000` |

---

*This report was prepared on 2026-02-27 for stakeholder review. All technical details are sourced directly from the bridge-gateway codebase at the time of writing.*
