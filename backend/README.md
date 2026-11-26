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

Needs PostgreSQL 16 (Homebrew: `brew services start postgresql@16`). One-time setup:

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

Operational endpoints (not in the public contract): `/healthz` (process up), `/readyz` (database reachable), `/metrics`.

Every response carries `X-Correlation-Id` (the client's UUID, or a generated one) and `Cache-Control: no-store`. Logs are one JSON object per line with the correlation ID.
