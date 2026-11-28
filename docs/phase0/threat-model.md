# Threat Model and Risk Register (Phase 0 draft)

**Method:** STRIDE per trust boundary, then abuse cases ranked by severity. Source: audit v1.2 §3–4, PRD v1.4 §4, §7, §12.  
**Owner:** Engineering lead (to be named). **Review cadence:** every phase gate and every new provider adapter.

## 1. Assets

User ID, email, password-derived material, refresh tokens and device sessions, order history, addresses and locations, chat content, cart-task state, comparison snapshots, Claude API key, audit logs, consent records, handoff tokens, recommendation features.

## 2. Trust boundaries and STRIDE

| Boundary | S | T | R | I | D | E | Controls (phase) |
|---|---|---|---|---|---|---|---|
| App ↔ backend | Stolen access/refresh token | Tampered requests | Repudiated approvals | Token leak in logs | Request floods | Calling another user's resources | TLS 1.3, Keystore token storage (P1), short access token + rotating refresh with reuse detection (P3), device binding (P3), idempotency keys (P0 contract), object-level authz (P3), rate limits (P3) |
| App ↔ partner app | Fake partner package | Screen content injection | — | Reading unrelated screens | — | Automation outside allowlist | Package + signature allowlist (P4), action allowlist, unknown-screen halt, emergency stop (UI P1, runtime P4) |
| Backend ↔ Claude | — | Prompt injection via item/restaurant/address text | — | Key leak, PII sent to model | Cost abuse | Model output granting itself permissions | Server-only key (P3), delimited untrusted data, schema-validated output (schema P0), deterministic policy outside the model, per-user token budgets |
| Backend ↔ adapters | — | Wrong prices, malicious content | — | — | Adapter outage | — | Schema validation, timestamps, confidence, kill switch per provider (flag names P0) |
| History ↔ analytics | — | Corrupted imports | — | Sensitive inference | — | — | Local parsing, field allowlist, provenance, deletion (P4) |
| Deep link ↔ partner app | Forged handoff link | Link tampering | — | Sensitive data in URL | — | Open wrong app | Opaque signed one-time token, short TTL, package-targeted intent; no IDs in URL (contract P0) |

## 3. Abuse-case register

| ID | Abuse case | Severity | Mitigation | Verified by |
|---|---|---|---|---|
| A-01 | Password spraying / credential stuffing | Critical | Per-account, per-device, per-IP limits; progressive backoff; breached-password check | Rate-limit tests (P3), credential-stuffing simulation (P5) |
| A-02 | Account enumeration via recovery | High | Uniform responses (fixtures enforce identical bodies), throttling | Contract test: identical body for registered/unregistered (P0 fixture, P3 test) |
| A-03 | Refresh-token replay on another device | Critical | Rotation + family revocation on reuse, device binding | Integration test (P3) |
| A-04 | Claude key extracted from APK | Critical | Key never in client; CI secret scan of APK and repo | APK string scan in CI (P1 onward) |
| A-05 | Prompt injection in product/order text | High | Untrusted-data delimiting, output schema, deterministic authority | Red-team eval set (P5) |
| A-06 | Silent cart mutation | Critical | Per-task approval, risk-levelled checkpoints, visible timeline, emergency stop | UI tests (P1), runtime tests (P4), `TaskThreatsTest` a06_* (P4.5) |
| A-07 | Wrong provider / wrong cart | High | Comparison snapshot linked to task, explicit switch confirmation, cart verification | UI tests (P1), E2E (P4), `TaskThreatsTest` a07_* (P4.5) |
| A-08 | Handoff link replay | High | One-time nonce, ≤10 min TTL, package binding | Contract + integration (P3), `TaskThreatsTest` a08_* (P4.5), `Cr006Test` and `Cr006UiTest` (single use end to end, uniform refusals) |
| A-09 | Accessibility service used on unrelated apps | Critical | Package allowlist, stop on package change, kill switch | Runtime tests (P4) |
| A-10 | Exfiltration of order-history screens | Critical | On-device parsing, field allowlist, no raw screenshots, explicit upload consent | Privacy tests (P4) |
| A-11 | Notification leaks order details | Medium | Generic lock-screen text | UI test (P1) |
| A-12 | Claude / automation cost abuse | High | Per-user budgets, concurrency caps, circuit breakers | Load tests (P5) |
| A-13 | Partner UI change triggers unintended action | Critical | Semantic selectors, fingerprints, stop-on-unknown, canary tests | Adapter tests (P4) |
| A-14 | Malicious dependency / build artifact | High | Lockfiles, version catalog pinning, SBOM, signed builds | CI (P5) |
| A-15 | IDOR / BOLA across users | Critical | Ownership check on every resource ID | Authorization test suite (P3) |
| A-16 | Forged device readings or task progress (CR-005) | High | Readings and progress change only that user's own quote and task, never pooled across users; the server computes every figure, checks every transition against D-002, enforces limits and verifies the cart itself; only the session that approved a task may report on it; strict schemas and size limits | `Cr005Test` (P4) |

## 4. Automation action-safety model (PRD v1.4 §4)

| Risk | Examples | Control |
|---|---|---|
| Low | Open provider app, search | Task-level consent + visible status |
| Medium | Select item, change quantity, choose slot | Covered by explicit task approval listing each action |
| High | Change address/destination, remove item | Checkpoint: explicit confirmation **during** the task plus state re-check |
| Prohibited | Payment, OTP, password, CAPTCHA, biometric | Never automated; detection moves task to `NEEDS_USER_ACTION` or `BLOCKED_POLICY` |

Halt conditions: package mismatch, unknown screen fingerprint, unexpected dialog, address/quantity/price/context drift, login/OTP/CAPTCHA/password/biometric screen, max action count, max runtime, inactivity timeout, emergency stop, provider kill switch.

## 5. Open risks

| ID | Risk | Status |
|---|---|---|
| R-01 | Play Store AccessibilityService policy may not permit this use case | Open — policy review required before Phase 4 |
| R-02 | Partner terms may prohibit automation or scraping | Open — legal review per provider |
| R-03 | Cross-app price capture latency makes comparison impractical | Open — feasibility spike |
| R-04 | Partner deep links to cart may not exist | Open — spike per provider; fallback designed |
| R-05 | Legal basis for processing order history under DPDP | Open — privacy review |
