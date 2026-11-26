# SmartCart backend (Phase 3)

Kotlin/Ktor service implementing the frozen contract in `../contracts/openapi/openapi.yaml`. Plan: [`docs/phase3/PLAN.md`](../docs/phase3/PLAN.md); decisions D-015 – D-020 in [`docs/DECISIONS.md`](../docs/DECISIONS.md).

## Test

```bash
./gradlew test
```

Needs only JDK 21. Tests start an embedded PostgreSQL 16 (one per test JVM, a fresh database per test), so Docker is not required.

What the suite guarantees:

- **Contract:** handlers are tested with `assertContract(...)`, which validates the real response (status, content type, body) against the OpenAPI schemas with format checks on and write-only fields (passwords) forbidden. `ContractValidatorTest` proves the checker rejects bad responses.
- **Coverage:** `RouteCoverageTest` fails if a contract operation is neither routed nor listed in `src/test/resources/pending-operations.txt`, or if a `/v1` route isn't in the contract. The pending list must be empty at the end of Phase 3.
- **Errors:** `ErrorCatalogueTest` keeps `ErrorCode`, `contracts/error-codes.yaml` and the OpenAPI enum identical.
- **Logs:** `LogRedactionTest` runs the production `logback.xml` and checks that passwords, tokens, emails and API keys are masked.

## Run locally

Needs PostgreSQL 16 (Homebrew: `brew services start postgresql@16`); Redis is optional in development (`brew services start redis`). One-time setup:

```bash
createuser smartcart && createdb -O smartcart smartcart
```

Then:

```bash
./gradlew run      # http://127.0.0.1:8080 — migrations run at startup
```

| Variable | Default | Notes |
|---|---|---|
| `SMARTCART_ENV` | `dev` | `dev`, `test` or `prod`. `prod` refuses development defaults. |
| `SMARTCART_HOST` / `SMARTCART_PORT` | `127.0.0.1` / `8080` | |
| `SMARTCART_DB_URL` | `jdbc:postgresql://localhost:5432/smartcart` | |
| `SMARTCART_DB_USER` / `SMARTCART_DB_PASSWORD` | `smartcart` / empty | Password required in `prod`. |
| `SMARTCART_STUBS_PHASE4` | `true` in dev, else `false` | Fixture-backed Phase 4 endpoints (D-020); rejected in `prod`. |
| `SMARTCART_METRICS` | `true` | Prometheus text at `/metrics`. Keep it off the public network. |
| `SMARTCART_SECRET_KEY` | random per process | Base64, ≥ 32 bytes. Keys IP hashes, recovery codes, idempotency fingerprints, challenge IDs. Required in `prod`. |
| `SMARTCART_JWT_PRIVATE_KEY` / `SMARTCART_JWT_PUBLIC_KEY` | generated per process | Base64 DER (PKCS#8 / X.509) ES256 pair. Required in `prod`; dev tokens die on restart. |
| `SMARTCART_REDIS_URL` | unset → in-memory | e.g. `redis://localhost:6379`. Rate limits, lockouts, idempotency keys. Required in `prod`. |

Generate production keys:

```bash
openssl ecparam -name prime256v1 -genkey -noout -out jwt.pem
openssl pkcs8 -topk8 -nocrypt -in jwt.pem -outform DER | base64     # SMARTCART_JWT_PRIVATE_KEY
openssl ec -in jwt.pem -pubout -outform DER | base64                 # SMARTCART_JWT_PUBLIC_KEY
openssl rand -base64 48                                              # SMARTCART_SECRET_KEY
```

In development, password-reset codes and user-ID reminders are written to the `email_outbox` table instead of being emailed (no mail provider yet); the service refuses to start in `prod` with that sink:

```bash
psql -U smartcart -d smartcart -c "select recipient, template, params from email_outbox order by id desc limit 5"
```

| `SMARTCART_ADMIN_TOKEN` | unset → no admin API | ≥ 32 chars. Enables the operator API under `/admin`. |
| `SMARTCART_REQUIRE_PROVIDER_APPROVAL` | `false` in dev, else `true` | Providers need an approval record to be `enabled` (D-021). Cannot be `false` in `prod`. |
| `SMARTCART_FLAG_CACHE_SECONDS` | `5` | Longest time an instance serves a stale flag. |
| `SMARTCART_AI_LIVE` | `false` | Live Claude calls for chat intent extraction (D-022). Off until Phase 4; when on, the Anthropic SDK reads `ANTHROPIC_API_KEY` from the server environment. Off → a deterministic stand-in model. |

### Operator API (feature flags, kill switches, provider approvals)

Not part of the public contract; keep it on an internal network. Every call needs the admin token and an `X-Operator` name; every change needs a reason and is written to the audit log.

```bash
A="Authorization: Bearer $SMARTCART_ADMIN_TOKEN"; O="X-Operator: your.name"; J="Content-Type: application/json"
curl -H "$A" -H "$O" localhost:8080/admin/flags
# Kill switch (takes effect on every instance within SMARTCART_FLAG_CACHE_SECONDS):
curl -X PUT -H "$A" -H "$O" -H "$J" localhost:8080/admin/flags/provider.blinkit.kill -d '{"enabled":true,"reason":"INC-123"}'
# Record a completed provider approval (docs/phase0/provider-matrix.md template):
curl -X PUT -H "$A" -H "$O" -H "$J" localhost:8080/admin/providers/blinkit/approval \
  -d '{"adapter_version":"1.0.0","approved_by":"product, security, legal","record_url":"https://…","reason":"approval complete"}'
```

Operational endpoints (not in the public contract): `/healthz` (process up), `/readyz` (database reachable), `/metrics`.

Every response carries `X-Correlation-Id` (the client's UUID, or a generated one) and `Cache-Control: no-store`. Logs are one JSON object per line with the correlation ID.
