# Smart Shopping Chat Assistant — Development Plan

**Status:** Approved 22 November 2026  
**Sources:** `smart-shopping-chat-prd-and-claude-master-prompt-v1.4.md` (PRD v1.4, which takes precedence over v1.3) and `smart-shopping-chat-app-detailed-audit-v1.2.md` (audit v1.2).  
**Decision log:** [`docs/DECISIONS.md`](DECISIONS.md)

## 1. Governing rules

1. **Frontend-first.** The complete Android app is built against mocks and frozen (`frontend-freeze-v1.4` tag plus sign-offs) before real backend work. Until then the backend is limited to contracts and a mock server.
2. **Claude advises, code decides.** Claude handles intent, clarification and explanation wording only. Deterministic code owns prices, totals, permissions, cart approval and safety rules.
3. **Hard limits.** No payment, OTP, password, CAPTCHA or biometric automation. Every task has an emergency stop, timeout, maximum action count and unknown-screen halt. Each provider has its own kill switch.
4. **Provider gate.** No provider goes live without a completed provider approval record (policy, terms, privacy, security, fallback, monitoring).

## 2. Phases

| Phase | Output | Exit gate | Sign-off |
|---|---|---|---|
| **0. Feasibility & contracts** | Decision log, screen-state catalogue, navigation map, OpenAPI v1 draft, error-code catalogue, event / deep-link / notification / Claude-output schemas, fixture library, threat model, data-classification map, provider matrix, feasibility-spike test plan | Contracts lint clean; all fixtures validate | Product owner |
| **1. Frontend build (mocked)** | Complete Compose app; fixture-backed fake repositories; Prism mock server; local analytics collector; unit, UI and accessibility tests | Every screen × state covered; tests green | Product owner + design/QA |
| **2. Frontend freeze** | Evidence pack; `frontend-freeze-v1.4` tag; change-control template | Product, design, engineering, security, QA sign-off (human) | Team |
| **3. Backend foundation** | Kotlin/Ktor service, PostgreSQL + Redis; auth (Argon2id, rotating refresh, enumeration-safe recovery), sessions, consent, rate limiters, audit log, feature flags, Claude gateway (server-only key, schema-validated output), observability | Contract tests pass against frozen OpenAPI | Product owner |
| **4. Vertical integrations** | AccessibilityService runtime + safety model; one adapter per category; deterministic full-cart comparison engine; signed one-time handoff tokens; history import | One end-to-end slice per category without payment | Product owner + legal/policy |
| **5. Hardening** | Prompt-injection evals, abuse/load/chaos/DR tests, SBOM, secret scanning, signed builds, runbooks | Audit P0 + P1 backlog closed | Security |
| **6. Staged production** | Internal alpha → closed beta → provider-limited pilot → staged rollout | Provider approval records; pen test; launch gates | Team |

### Phase 1 build order

1. Scaffold, design system (tokens, dark mode, accessibility), navigation, fixture-backed fake repositories.
2. Auth: first-time setup, login, remembered device, reinstall, forgot password, forgot user ID, sessions.
3. Home: KPI summary (month selector, month-on-month, freshness) + category cards.
4. Category-scoped chat, clarification, action preview.
5. Comparison and full-cart optimisation: cheapest / unavailable / stale / partial / incomparable / price-changed, provider-switch confirmation.
6. Cart-task timeline, emergency stop, cart-ready, notification and deep-link handoff.
7. History import wizard and review, monthly detail, suggestions.
8. Privacy, consents, connected apps, export/delete, notification settings.
9. Analytics event taxonomy, full test pass.

## 3. Repository layout

```text
docs/                  Plan, decisions, Phase 0 design artefacts, freeze evidence
contracts/
  openapi/             OpenAPI 3.1 contract (source of truth for the API)
  schemas/             JSON Schemas: analytics events, deep links, notifications, Claude outputs
  fixtures/            Mock responses used by the Android app and the mock server
tools/contract-check/  Spectral lint, Ajv fixture validation, Prism mock server
android/               Gradle project (core modules + app with feature packages)
backend/               Added in Phase 3 only
```

## 4. Approved defaults

- Kotlin + Jetpack Compose, minSdk 26, targetSdk = current Play requirement.
- Backend in Kotlin/Ktor (Phase 3).
- Toolchain installed via Homebrew (OpenJDK 21, Gradle, Android command-line tools).
- Claude builds Phases 0–1 and prepares the Phase 2 evidence pack. **Phase 3 does not start until the freeze sign-off is confirmed.** No partner app or live Claude calls before Phase 4.

## 5. Key feasibility risk

Cross-app price comparison has no confirmed data source. Without official APIs, prices must be captured by driving each partner app on-device in turn, which is slow, visible to the user, and exposed to Play Store AccessibilityService policy and partner terms. Phase 0 includes a real-device spike plan (`docs/phase0/feasibility-spike-plan.md`). The UI is designed so that "partial coverage" and "manual-only" comparison outcomes are first-class states.
