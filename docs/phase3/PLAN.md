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

| 3.3 Me & consent | ✅ 26 Nov 2026 | 6 operations; 77 tests in total. Consents: allow-listed keys, current state + append-only history, activity only on real changes. Activity feed with stable cursor paging. Export/delete need the password again (step-up, locks after 5 wrong) and an Idempotency-Key; a background worker emails the export and erases data — account deletion signs out every device at once, then cascades; the audit log keeps only the user ID |

| 3.4 Flags & providers | ✅ 26 Nov 2026 | `/v1/providers` with status per D-021 and the caller's consents; flags in Postgres seeded from the catalogue plus a kill switch per provider; operator `/admin` API. 90 tests in total |

| 3.5 Chat & Claude gateway | ✅ 26 Nov 2026 | 5 operations; 115 tests in total. Gateway per D-022 with a deterministic stand-in model; the live Anthropic client is built and tested against a local stub (request shape, schema, delimiting) but off. Hostile-output tests: permission fields, unsafe claims, links, wrong provider, malformed JSON, refusals, budget, kill switch |

### Contract gaps found (for a change request; not changed silently)

1. **No email-verification endpoint.** `AuthSession.email_verification_required` is `true` after registration, but the contract has no way to complete verification. The flag is reported honestly; nothing is gated on it yet.
2. **`DeviceInfo` has no device model,** so `DeviceSession.device_label` is derived ("Android 15"). The app's sessions screen was designed with model names ("Pixel 8").
3. **`approximate_location` is always `null`:** no IP geolocation in Phase 3.
4. **Registration has no "remember this device" flag;** registration sessions use the remembered (30-day) lifetime.
5. **Undocumented 400 responses.** `PUT /v1/me/consents/{consentKey}` (unknown key, blank version), `GET /v1/me/activity` (bad `limit`/`cursor`), `POST /v1/me/data/export` and `/delete` (missing `Idempotency-Key`, bad scope) can only fail validation with a 400, but list none. The server returns the standard `validation_failed` envelope; tests check it is a valid `ErrorEnvelope`.
6. **`POST /v1/me/data/delete` lists no 429,** although step-up lockout applies to it as it does to export (which lists 429).
7. **Nothing sets `privacy_mode`.** `Profile` reports it (default `device_only`); presumably history import (Phase 4) will need an operation for it.
8. **No current consent version in any response.** The app sends the version it showed (`2026-11`); the server records whatever is sent, so there is no way to prompt re-consent when texts change.
9. **`ActivityEvent.summary` is server-written English,** whereas D-014 keeps all user-facing copy in the app. Either localise on the server or add a template key the app can map.
10. **No way for the app to read feature flags.** `home.category.shopping`, `chat.ai.enabled`, `comparison.enabled` and `history.import.enabled` are server-controlled (D-004, flag catalogue) but only `/v1/providers` reaches the app. The app still uses its built-in defaults. Needs e.g. `GET /v1/config` or flags on `Profile`.
11. **`hidden` isn't in the `Provider` schema.** Zepto is returned as `disabled`; the app must hide it by key rather than by a server field.
12. **The app never asks for AI-processing consent.** The audit (§13.1) requires it and the server enforces it, but no frozen screen sets `ai_processing`, so integrated chat will always use the deterministic path until a consent prompt is added (frontend change request).
13. **More undocumented statuses on chat:** `POST /v1/chat/messages` can return 400 (bad text/context, stale clarification answer) and 404 (session not found or not the caller's); `POST /v1/chat/sessions` can return 400 (bad category) and 403 (category flag off, e.g. Shopping). None are listed.
14. **No way to keep a chat.** Data classification says chat is kept "30 days unless saved", but there is no save operation, so every chat expires after 30 days without activity.

## 4. Running locally

```bash
cd backend && ./gradlew test        # embedded Postgres; no Docker needed
cd backend && ./gradlew run         # needs local Postgres + Redis (Homebrew) — see backend/README.md
```

## 5. Security notes carried from the threat model

A-01 credential stuffing → per-account/IP/device limits + backoff (3.2). A-02 enumeration → byte-identical recovery responses and equalised timing (3.2). A-03 refresh replay → rotation, reuse detection revokes the family, device binding (3.2). A-05 prompt injection → delimiting + schema validation + deterministic authority (3.5). A-15 BOLA → ownership check in every repository query, cross-user test suite (3.2–3.6). Secrets only from the environment; never logged (redaction test in 3.1).
