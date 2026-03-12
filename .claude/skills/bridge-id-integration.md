# Bridge-ID Integration Skill

> Reference for integrating Bridge-ID (identity, OIDC, JWT) into any UI or service in the BRIDGE ecosystem.

---

## 1. What Bridge-ID Is

Bridge-ID is the centralized identity provider for all BRIDGE services:
- **Protocol**: OAuth 2.0 Authorization Code + PKCE (for UIs), Bearer JWT (for services)
- **OIDC**: JWKS at `$BRIDGE_ID_BASE_URL/jwks`, discovery at `$BRIDGE_ID_BASE_URL/.well-known/openid-configuration`
- **JWT**: RS256, `iss = BRIDGE_ID_BASE_URL` = `https://api.id.service.d.bridgeintelligence.ltd`
- **UI (login page)**: `https://id.service.d.bridgeintelligence.ltd`
- **API**: `https://api.id.service.d.bridgeintelligence.ltd`

---

## 2. Key API Endpoints

| Method | Path | Auth | Purpose |
|--------|------|------|---------|
| POST | `/api/v1/login` | None | Email + password login → returns `access_token`, `refresh_token` |
| GET | `/api/v1/userinfo` | Bearer | Get current user info (subject, email, roles) |
| POST | `/api/v1/auth/refresh` | None | Refresh access token using `refresh_token` |
| GET | `/api/v1/auth/social/google` | None | Initiate Google SSO (requires Keycloak) |
| GET | `/api/v1/auth/callback` | None | OAuth callback from Keycloak → returns tokens as query params |
| GET | `/jwks` | None | JWK Set for JWT signature verification |
| POST | `/api/v1/logout` | Bearer | Invalidate session |

Login response fields: `access_token`, `refresh_token`, `token_type`, `expires_in`

---

## 3. Standard Auth Flow (UI Apps)

### 3.1 Direct OAuth Redirect (preferred for all UIs)

```
UI → redirect to id.service.d.bridgeintelligence.ltd/login?redirect_uri=<app>/auth/callback
  → user logs in (email/password or Google SSO)
  → bridge-id redirects to <app>/auth/callback?access_token=...&refresh_token=...
  → app stores tokens in localStorage
  → app calls GET /api/v1/userinfo with Bearer token to initialize session
```

Required OAuth client registration in bridge-id:
- `client_id`: app-specific (e.g. `bridge-console`, `bridge-dlt-console`)
- `redirect_uri`: must include the callback URL (registered in DB via Flyway migration)

### 3.2 Adding a New OAuth Client

Create a Flyway migration in `bridge-id/src/main/resources/db/migration/`:

```sql
-- V{N}__register_{app_name}_redirect_uri.sql
INSERT INTO oauth_clients (client_id, client_name, redirect_uris, scopes, grant_types, created_at)
VALUES (
  '{app-client-id}',
  '{Human App Name}',
  'https://{app-domain}/auth/callback',
  'openid profile email',
  'authorization_code refresh_token',
  NOW()
) ON CONFLICT (client_id) DO UPDATE
  SET redirect_uris = EXCLUDED.redirect_uris;
```

Or add to an existing client's redirect_uris:
```sql
UPDATE oauth_clients
SET redirect_uris = redirect_uris || ' https://{new-domain}/auth/callback'
WHERE client_id = '{client-id}'
  AND redirect_uris NOT LIKE '%{new-domain}%';
```

---

## 4. Frontend (React/TypeScript) Integration Pattern

### 4.1 Config Pattern

```typescript
// src/lib/config.ts
const rc = (window as any).__APP_RUNTIME_CONFIG__ ?? {}
export const config = {
  BRIDGE_ID_URL: rc.BRIDGE_ID_URL || '',        // '' = use nginx proxy at /auth
  BRIDGE_ID_UI_URL: rc.BRIDGE_ID_UI_URL || 'https://id.service.d.bridgeintelligence.ltd',
  GATEWAY_URL: rc.GATEWAY_URL || '',
}
export const getAuthBase = () => `${config.BRIDGE_ID_URL}/auth/api/v1`
```

### 4.2 Login Redirect

```typescript
// src/pages/LoginPage.tsx
const REDIRECT_URI = `${window.location.origin}/auth/callback`
const loginUrl = `${config.BRIDGE_ID_UI_URL}/login?redirect_uri=${encodeURIComponent(REDIRECT_URI)}`
window.location.href = loginUrl
```

### 4.3 Auth Callback Handler

```typescript
// src/pages/AuthCallbackPage.tsx
const params = new URLSearchParams(window.location.search)
const accessToken = params.get('access_token')
const refreshToken = params.get('refresh_token')
if (!accessToken) { /* show error */ return }
localStorage.setItem('access_token', accessToken)
if (refreshToken) localStorage.setItem('refresh_token', refreshToken)
// Then call userinfo to initialize session
```

### 4.4 Authenticated Fetch

```typescript
// Pattern for API calls that require auth
async function authFetch(url: string, options?: RequestInit) {
  const token = localStorage.getItem('access_token')
  const res = await fetch(url, {
    ...options,
    headers: { ...options?.headers, Authorization: `Bearer ${token}` }
  })
  if (res.status === 401) {
    // Clear tokens and redirect — do NOT retry
    localStorage.removeItem('access_token')
    localStorage.removeItem('refresh_token')
    window.location.href = '/login'
    throw new Error('Unauthorized')
  }
  return res
}
```

### 4.5 JWT Token Refresh

```typescript
async function refreshToken() {
  const rt = localStorage.getItem('refresh_token')
  if (!rt) { window.location.href = '/login'; return }
  const res = await fetch(`${getAuthBase()}/auth/refresh`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ refresh_token: rt })
  })
  if (!res.ok) { window.location.href = '/login'; return }
  const data = await res.json()
  localStorage.setItem('access_token', data.access_token)
}
```

---

## 5. nginx Proxy Pattern (for UI containers)

Every UI must proxy `/auth/` to bridge-id and `/api/v1/` to the gateway. This avoids CORS and keeps credentials server-side.

```nginx
# CRITICAL: Exact match first — prevents /auth/callback from being proxied
location = /auth/callback {
  try_files $uri /index.html;
}

# Proxy /auth/* → Bridge-ID API (strips /auth prefix)
location /auth/ {
  resolver kube-dns.kube-system.svc.cluster.local valid=10s;
  set $bid_backend "bridge-id.bridge-service-stack.svc.cluster.local:8083";
  rewrite ^/auth/(.*)$ /$1 break;
  proxy_pass http://$bid_backend;
  proxy_set_header Host $host;
  proxy_set_header X-Forwarded-Proto $scheme;
  proxy_hide_header WWW-Authenticate;  # Prevents browser Basic Auth dialog
  proxy_http_version 1.1;
  proxy_set_header Connection "";
}

# Proxy /api/v1/* → Bridge Gateway
location /api/v1/ {
  resolver kube-dns.kube-system.svc.cluster.local valid=10s;
  set $gw_backend "bridge-gateway.bridge-service-stack.svc.cluster.local:4000";
  proxy_pass http://$gw_backend;
  proxy_set_header Authorization $http_authorization;
  proxy_hide_header WWW-Authenticate;  # Prevents browser Basic Auth dialog
  proxy_http_version 1.1;
}
```

**Rules**:
- Always use `proxy_hide_header WWW-Authenticate` — gateway returns `WWW-Authenticate: Basic` on 401 which triggers browser dialog
- Use `location = /auth/callback` (exact match) before `location /auth/` to prevent the callback from being proxied
- Use DNS variable (`set $bid_backend`) to allow lazy resolution — prevents nginx crash if service is temporarily down

---

## 6. Backend Service Integration (Kotlin/Spring Boot)

### 6.1 JWT Validation via JWKS

```kotlin
// application.properties / application.yml
spring:
  security:
    oauth2:
      resourceserver:
        jwt:
          jwk-set-uri: ${BRIDGE_ID_BASE_URL}/jwks
          issuer-uri: ${BRIDGE_ID_BASE_URL}
```

**CRITICAL**: `issuer-uri` must match `JWT_ISSUER` in bridge-id config, which must equal `BRIDGE_ID_BASE_URL` = `https://api.id.service.d.bridgeintelligence.ltd`.

### 6.2 Security Config

```kotlin
@Bean
fun securityFilterChain(http: HttpSecurity): SecurityFilterChain {
    http
        .csrf { it.disable() }
        .authorizeHttpRequests {
            it.requestMatchers("/actuator/health", "/v3/api-docs/**").permitAll()
            it.anyRequest().authenticated()
        }
        .oauth2ResourceServer { it.jwt { jwt -> jwt.decoder(jwtDecoder()) } }
    return http.build()
}
```

### 6.3 Extracting User from JWT

```kotlin
@GetMapping("/me")
fun getMe(@AuthenticationPrincipal jwt: Jwt): UserInfo {
    val brdgId = jwt.subject
    val email = jwt.getClaimAsString("email")
    val roles = jwt.getClaimAsStringList("roles") ?: emptyList()
    return UserInfo(brdgId, email, roles)
}
```

---

## 7. K8s Runtime Config Pattern

For each UI app, set `BRIDGE_ID_URL` and `BRIDGE_ID_UI_URL` in the deployment or a runtime configmap:

```yaml
# k8s/base/deployment.yaml — empty = use nginx proxy (preferred for k8s deployments)
env:
  - name: BRIDGE_ID_URL
    value: ""                         # Falls back to /auth nginx proxy
  - name: BRIDGE_ID_UI_URL
    value: "https://id.service.d.bridgeintelligence.ltd"
```

For external/direct API access (non-k8s or external-facing):
```yaml
env:
  - name: BRIDGE_ID_URL
    value: "https://api.id.service.d.bridgeintelligence.ltd"
  - name: BRIDGE_ID_UI_URL
    value: "https://id.service.d.bridgeintelligence.ltd"
```

---

## 8. CORS Configuration

Add new UI origins to bridge-id configmap `CORS_ALLOWED_ORIGINS`:
```
https://console.service.d.bridgeintelligence.ltd,https://id.service.d.bridgeintelligence.ltd,https://api.id.service.d.bridgeintelligence.ltd,https://gateway.service.d.bridgeintelligence.ltd,https://dlt.service.d.bridgeintelligence.ltd,https://wallet.service.d.bridgeintelligence.ltd,http://localhost:3000,http://localhost:5173
```

Also add to `k8s/base/configmap.yaml` in bridge-id repo via PR.

---

## 9. Common Pitfalls

| Problem | Cause | Fix |
|---------|-------|-----|
| Browser shows "Sign in" Basic Auth dialog | `WWW-Authenticate: Basic` forwarded by nginx | Add `proxy_hide_header WWW-Authenticate` |
| Login loop (always redirected back to /login) | JWT issuer mismatch: `iss` ≠ `JWT_ISSUER` | Ensure `JWT_ISSUER = BRIDGE_ID_BASE_URL` in bridge-id |
| `/auth/callback` proxied to bridge-id | Missing `location = /auth/callback` exact match | Add exact match location BEFORE `/auth/` prefix match |
| 502 on Google SSO | Multiple ArgoCD apps (prod/stage) competing, bridge-id unstable | Disable automated sync on prod/stage ArgoCD apps |
| 503 on `/auth/callback` | Pod just started, not ready yet | Wait for readiness probe; check with `kubectl rollout status` |
| Tokens invalid after bridge-id restart | Ephemeral RSA keys (regenerated on startup) | Users must re-login; configure persistent key via Vault for prod |
| 401 on authenticated calls after login | JWT issuer mismatch (bridge-id rejects own tokens) | Fix `JWT_ISSUER` = `BRIDGE_ID_BASE_URL` |
| CORS error on API calls from browser | Browser calling API directly, not via nginx proxy | Never call bridge-id API directly — always route through nginx proxy |

---

## 10. Integration Checklist

When integrating a new app with Bridge-ID:

- [ ] Register OAuth client in bridge-id via Flyway migration (redirect_uri)
- [ ] Add app origin to `CORS_ALLOWED_ORIGINS` in bridge-id configmap
- [ ] Add nginx proxy locations: `= /auth/callback`, `/auth/`, `/api/v1/` with `proxy_hide_header WWW-Authenticate`
- [ ] Set `BRIDGE_ID_UI_URL` to `https://id.service.d.bridgeintelligence.ltd` in runtime config
- [ ] Set `BRIDGE_ID_URL` to `""` (uses nginx proxy) in k8s deployment
- [ ] Frontend: redirect to `BRIDGE_ID_UI_URL/login?redirect_uri=<app>/auth/callback`
- [ ] Frontend: handle `/auth/callback` — extract tokens from query params, store in localStorage
- [ ] Frontend: call `/auth/api/v1/userinfo` with Bearer token to validate session on app start
- [ ] Backend: configure `spring.security.oauth2.resourceserver.jwt.jwk-set-uri` pointing to bridge-id
- [ ] Backend: set `issuer-uri` = `BRIDGE_ID_BASE_URL` (must match exactly)
- [ ] Test: login works, no loop, userinfo returns 200, no browser auth dialog
