# Gateway Skill

## Authentication & Authorization
- JWT validation: verify signature, check expiry, extract roles from claims
- API key validation: lookup in Redis/config, check active status, extract client tier
- Auth chain: try JWT first, fall back to API key, reject if neither
- Admin endpoints: require ADMIN role in JWT claims

## Rate Limiting
- Per API key, not per IP (to support shared infrastructure)
- Tiers: FREE (100 req/min), STANDARD (1000 req/min), PREMIUM (10000 req/min)
- Response headers: X-RateLimit-Limit, X-RateLimit-Remaining, X-RateLimit-Reset
- 429 Too Many Requests with Retry-After header when exceeded

## Request Validation
- Validate Content-Type header for POST/PUT/PATCH
- Reject requests >1MB body size
- Validate required headers per route

## Correlation IDs
- Inject X-Correlation-ID if not present (UUID v4)
- Propagate existing X-Correlation-ID to downstream
- Include in all log entries and error responses

## Routing + Timeouts
- Default timeout: 30s
- Portfolio reads: 5s (fast, cached)
- Order submission: 15s
- Ledger reads: 10s
- Market data: 5s
- Admin endpoints: 60s
