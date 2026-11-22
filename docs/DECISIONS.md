# Decision Log

Each entry records a decision, the reason, and the source-document conflict it resolves. Changes after `frontend-freeze-v1.4` require a change request (`docs/phase2/change-request-template.md`).

| ID | Date | Decision | Reason / conflict resolved |
|---|---|---|---|
| D-001 | 2026-11-22 | Authentication is user ID + password + email only. All phone-OTP references in the PRD body (screen list, flow diagram, acceptance criteria, metrics) are void. | PRD FR-1 and Part B constraints supersede stale OTP text. |
| D-002 | 2026-11-22 | Automation task states are the union of PRD and audit: `DRAFT, AWAITING_APPROVAL, APPROVED, RUNNING, CHECKPOINT, NEEDS_USER_ACTION, CART_READY, HANDED_OFF`; terminal `CANCELLED, EXPIRED, FAILED_SAFE, BLOCKED_POLICY`. PRD `FAILED` maps to `FAILED_SAFE`. | Audit §9.2 is stricter than PRD canonical list. |
| D-003 | 2026-11-22 | Endpoint names follow PRD v1.4 §8 under `/v1`. Added: `/v1/auth/reset-password`, `/v1/auth/sessions`, `/v1/comparison/quotes/{id}/confirm`, `/v1/history/orders/{id}` (DELETE), `/v1/dashboard/monthly`, `/v1/me/consents`. | Audit §11.2 and PRD v1.3 §9 used different names; v1.4 takes precedence. |
| D-004 | 2026-11-22 | Home shows Travel, Food ordering, Grocery, plus a **Shopping** card (Amazon, Flipkart) behind feature flag `home.category.shopping` (default off). | v1.4 confirms Amazon/Flipkart in scope with category "Shopping"; v1.3 home spec lists only three categories. |
| D-005 | 2026-11-22 | Zepto stays in the provider registry, disabled and hidden (`provider.zepto.enabled = false`). | Present in v1.3 initial list, absent from v1.4 matrix. |
| D-006 | 2026-11-22 | v1.4 §9 SLOs are release gates; audit §18.1 tighter targets are internal goals. | Conflicting latency targets. |
| D-007 | 2026-11-22 | Phase numbering follows v1.4 §15 (0–6). | v1.3 used 0–4. |
| D-008 | 2026-11-22 | Freeze tag is `frontend-freeze-v1.4`. | Audit addendum used `frontend-freeze-v1`. |
| D-009 | 2026-11-22 | Android modules: `:core:model` (pure Kotlin), `:core:designsystem`, `:core:data` (repository interfaces + fixture-backed fakes), `:app` (features as packages). Manual constructor DI via an `AppContainer` instead of Hilt during Phase 1. | Fewer annotation-processing moving parts while everything is mocked; Hilt can be introduced in Phase 3 without UI changes. |
| D-010 | 2026-11-22 | Fixtures live once in `contracts/fixtures/` and are packaged into the app as assets. Ajv validates them against the OpenAPI schemas. | Single source of truth for mocks; prevents fixture drift from the contract. |
| D-011 | 2026-11-22 | Money is transported as integer minor units (`amount_minor`, paise) plus ISO 4217 `currency`. The client never computes comparison totals; it renders server-computed values. | Audit §10.3 decimal-safe arithmetic; deterministic totals live in the backend. |
| D-012 | 2026-11-22 | Encrypted local cache (Room + SQLCipher) is deferred to Phase 3 integration. Phase 1 keeps mock state in memory; only session tokens use Keystore-backed storage. | No real personal data exists in Phase 1. |
| D-014 | 2026-11-22 | All user-facing app copy lives in Android string resources (`app/src/main/res/values/strings_*.xml`, plurals included). Core model enums carry no English labels; the app maps them to `@StringRes` in `ui/common/Labels.kt`. Text that arrives as data (recommendation reasons, explanations, item and provider names, task-timeline events) is not copy. The debug-only Developer screen is exempt. `NoHardcodedCopyTest` enforces this. | Post-freeze change control allows copy and translation fixes; those must not require Kotlin changes. |
| D-013 | 2026-11-22 | Comparison outcomes that the UI must render as first-class states: `complete`, `partial_coverage`, `stale`, `incomparable`, `unavailable`, `price_changed`, `manual_only`. | Feasibility risk on cross-app price capture (see plan §5). |
