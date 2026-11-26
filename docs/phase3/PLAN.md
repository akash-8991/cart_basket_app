# Phase 3 — Backend foundation

**Started:** 26 November 2026, after the `frontend-freeze-v1.4` sign-off.  
**Exit gate (plan §2):** contract tests pass against the frozen OpenAPI (`contracts/openapi/openapi.yaml`).  
**Out of scope (Phase 4):** AccessibilityService runtime, partner adapters, the comparison engine, signed handoff tokens, history import, live Claude calls.

## 1. Architecture

```text
Android app ──HTTPS/JSON──▶ Ktor service (backend/) ──▶ PostgreSQL 16  (users, sessions, refresh-token families,
                                   │                                     consents, audit log, chat, flags)
                                   ├──▶ Redis  (rate limits, idempotency keys)
                                   └──▶ Claude gateway ──▶ fake model (Phase 3) │ Anthropic API (Phase 4, flag-gated)
```

- One deployable Ktor service; feature packages mirror the OpenAPI tags (`auth`, `me`, `providers`, `chat`, …).
- Every route is checked by a **contract test** that validates real responses (status, headers, body) against the frozen OpenAPI schemas. A route without one fails the build (route-coverage test).
- Deterministic code owns money, permissions and safety (plan rule 2). The Claude gateway returns schema-validated suggestions only.

## 2. Slices (each is one commit, pushed)

| # | Slice | Contents | Done when |
|---|---|---|---|
| 3.1 | **Scaffold** | Gradle project, config, `/healthz` `/readyz`, correlation IDs, error envelope from `error-codes.yaml`, JSON logs with redaction, Prometheus metrics, Flyway, embedded-Postgres test harness, OpenAPI contract validator, route-coverage test | Build + tests green; contract validator proven to reject a bad response |
| 3.2 | **Auth** | Register, login, refresh (rotation + family revocation on reuse, device-bound), logout, sessions list/revoke/revoke-all, forgot password / forgot user ID (uniform responses), reset password; Argon2id; per-account/IP/device rate limits with progressive backoff; audit log | Threat-model tests A-01, A-02, A-03, A-15 automated |
| 3.3 | **Me & consent** | `/v1/me`, consents (versioned, auditable), activity feed, export/delete requests (queued job records) | Contract + ownership (BOLA) tests |
| 3.4 | **Flags & providers** | Server-controlled flags seeded from `feature-flags.yaml`, provider registry with kill switches, `/v1/providers` | Kill switch flips a provider to `disabled` without redeploy |
| 3.5 | **Chat & Claude gateway** | Chat sessions/messages, gateway with server-only key, untrusted-data delimiting, output validated against `contracts/schemas/claude/*`, per-user token budget, deterministic fallback (`ai_unavailable`) | Malformed/hostile model output is rejected and falls back; no key reachable from any response or log |
| 3.6 | **Phase 4 stubs** | Comparison, cart-tasks, deep-links, history, dashboard, recommendations served from `contracts/fixtures` behind `stubs.phase4` (dev only; off in prod → `service_unavailable`) | Every OpenAPI operation has a handler and a passing contract test (exit gate) |
| 3.7 | **App integration** | Android HTTP repositories for auth/me/sessions/providers/chat behind a build flag, talking to the local backend; fixture fakes stay the default for tests | End-to-end device test passes against the real local backend |

## 3. Progress

| Slice | Status | Notes |
|---|---|---|
| 3.1 Scaffold | ✅ 26 Nov 2026 | 22 tests |
| 3.2 Auth | ✅ 26 Nov 2026 | 10 operations; 61 tests in total, including A-01 lockout/backoff and per-device/IP limits, A-02 identical recovery responses (and signed challenge IDs so real and made-up challenges expire and lock alike), A-03 refresh reuse → family revoked, A-15 cross-user session access → 404 |

### Contract gaps found (for a change request; not changed silently)

1. **No email-verification endpoint.** `AuthSession.email_verification_required` is `true` after registration, but the contract has no way to complete verification. The flag is reported honestly; nothing is gated on it yet.
2. **`DeviceInfo` has no device model,** so `DeviceSession.device_label` is derived ("Android 15"). The app's sessions screen was designed with model names ("Pixel 8").
3. **`approximate_location` is always `null`:** no IP geolocation in Phase 3.
4. **Registration has no "remember this device" flag;** registration sessions use the remembered (30-day) lifetime.

## 4. Running locally

```bash
cd backend && ./gradlew test        # embedded Postgres; no Docker needed
cd backend && ./gradlew run         # needs local Postgres + Redis (Homebrew) — see backend/README.md
```

## 5. Security notes carried from the threat model

A-01 credential stuffing → per-account/IP/device limits + backoff (3.2). A-02 enumeration → byte-identical recovery responses and equalised timing (3.2). A-03 refresh replay → rotation, reuse detection revokes the family, device binding (3.2). A-05 prompt injection → delimiting + schema validation + deterministic authority (3.5). A-15 BOLA → ownership check in every repository query, cross-user test suite (3.2–3.6). Secrets only from the environment; never logged (redaction test in 3.1).
