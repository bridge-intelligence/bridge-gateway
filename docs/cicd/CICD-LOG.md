# Bridge Gateway CI/CD Log

**Last updated:** 2026-02-28

## Summary

| Item | Status |
|------|--------|
| Vault JWT role | ✅ `github-bridge-gateway-dev` exists |
| Harbor secret | ✅ `kv/cicd/harbor/bridge-gateway` exists |
| CD failure cause | **JWT audience mismatch** (not path) |
| Fix required | Add `bound_audiences` to Vault role |

---

## Actual Root Cause (Run 22525507847)

**Failed step:** Get Harbor credentials from Vault (step 7)

**Error message:**
```
error validating token: invalid audience (aud) claim: 
audience claim does not match any expected audience
```

**Root cause (two conflicting constraints):**
1. With `jwtGithubAudience: https://github.com/bridge-intelligence` → Vault rejected: "audience claim does not match"
2. With `jwtGithubAudience: https://github.com/binaridigital` → GitHub rejected: "Can't issue ID_TOKEN for audience 'https://github.com/binaridigital'" (repo is under bridge-intelligence org)

**Fix:** Add `https://github.com/bridge-intelligence` to Vault role's `bound_audiences`. Keep workflow as `jwtGithubAudience: https://github.com/bridge-intelligence`.

**Vault update (run with root token):**
```bash
# Create role config JSON, then apply (adds bridge-intelligence to bound_audiences)
cat <<'EOF' > /tmp/gateway-role.json
{
  "bound_audiences": ["https://github.com/bridge-intelligence", "https://github.com/binaridigital"],
  "user_claim": "repository",
  "role_type": "jwt",
  "token_policies": ["bridge-gateway-cicd-dev"],
  "token_ttl": "1h",
  "bound_claims_type": "glob",
  "bound_claims": {
    "ref": "refs/heads/dev",
    "repository": "bridge-intelligence/bridge-gateway"
  }
}
EOF
vault write auth/jwt/role/github-bridge-gateway-dev -input=/tmp/gateway-role.json
```

---

## Vault Verification (with root token)

```bash
export VAULT_ADDR="https://vault.binari.digital"
export VAULT_TOKEN="<your-token>"

# Verify secret exists
vault kv get kv/cicd/harbor/bridge-gateway

# Verify JWT role exists and has bound_audiences
vault read auth/jwt/role/github-bridge-gateway-dev
```

---

## GitHub Actions Commands

```bash
# List recent runs
gh run list --repo bridge-intelligence/bridge-gateway --limit 10

# View failed run details
gh run view 22523307853 --repo bridge-intelligence/bridge-gateway

# Watch a run (use the ID number from run list - NOT the literal "<run-id>")
gh run watch 22525553974 --repo bridge-intelligence/bridge-gateway

# Re-run failed workflow
gh run rerun 22523307853 --repo bridge-intelligence/bridge-gateway --failed
```

---

## Next Steps

1. **Commit and push** the workflow fix to `dev`
2. **Monitor** the new CD run
3. **Verify** ArgoCD syncs after successful build
4. **Confirm** deployment at `bridge-gateway.service.d.bridgeintelligence.ltd`

---

## References

- [vault-action KV v2 path format](https://github.com/hashicorp/vault-action#kv-secrets-engine-version-2)
- Working example: `bridge-dlt-console` uses `kv/data/ci/harbor/bridge-dlt-console`
