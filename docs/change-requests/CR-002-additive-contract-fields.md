# CR-002 — Additive contract fields and endpoints found missing in Phase 3

```text
CR ID:        CR-002
Title:        Optional fields and new read/write operations the backend needs to describe reality
Owner:        Akash (project owner) — drafted by Claude      Date raised: 2026-11-26
Reason:       Defects and gaps found in Phase 3 (docs/phase3/PLAN.md gaps 2, 4, 7, 8, 9, 10, 11, 15).
```

## Change description

Seven additions. All are **optional/nullable fields or new operations** — no existing field changes meaning, nothing becomes required — so the frozen app keeps working without an update (it decodes leniently and ignores what it doesn't know).

| # | Gap | Change | Why |
|---|---|---|---|
| A | 10, 8 | **`GET /v1/config`** (no sign-in needed): client-visible feature flags and the current terms / privacy / consent text versions. | The app can't see server flags today (Shopping card, AI chat, comparison, history import) and can't tell when consent texts changed. Only flags in an explicit allow-list are exposed; kill switches and provider flags stay server-side. |
| B | 2 | **`DeviceInfo.device_model`** (optional, ≤ 64 chars, e.g. "Pixel 8"). | The sessions screen was designed with model names; the server can only show "Android 15". |
| C | 4 | **`RegisterRequest.remember_device`** (optional, default `true`). | Registration currently always creates a 30-day session; a shared-device user can't choose a 12-hour one. |
| D | 7 | **`PUT /v1/me/privacy-mode`** with `{privacy_mode}` → `Profile`. | `Profile.privacy_mode` exists but nothing sets it; the app keeps the choice on the device only. |
| E | 9 | **`ActivityEvent.summary_key` + `summary_args`** (optional). `summary` stays as the English fallback. | D-014 keeps copy in the app; with a stable key and arguments the app can show the activity feed in its own strings and languages. |
| F | 11 | **`Provider.hidden`** (optional, default `false`). | Zepto is registry-only (D-005); the app hides it by hard-coded key. |
| G | 15 | **`CartTask.approval_nonce`** (nullable): issued by the server while the task is `AWAITING_APPROVAL`; `POST …/approve` must echo it. A wrong or reused nonce → 409 `task_state_conflict`. | The request already requires a nonce, but nothing issues one. A server-issued, single-use nonce proves the approval matches the plan the user was shown and stops replayed approvals (stronger than the current client-generated assumption). |

### OpenAPI patch

```yaml openapi-patch
tags:
  - name: config
    description: Client configuration readable before sign-in.
paths:
  /v1/config:
    get:
      operationId: getClientConfig
      tags: [config]
      summary: Client-visible feature flags and current policy text versions.
      description: >
        Public (no sign-in) so the app can read it before registration. Only flags on the server's
        client allow-list appear; provider flags and kill switches never do. Cache for at most 5 minutes.
      security: []
      parameters:
        - $ref: '#/components/parameters/CorrelationId'
      responses:
        '200':
          description: Current configuration.
          content:
            application/json:
              schema: { $ref: '#/components/schemas/ClientConfig' }
        default: { $ref: '#/components/responses/Error' }
  /v1/me/privacy-mode:
    put:
      operationId: setPrivacyMode
      tags: [me]
      summary: Choose whether normalised order history may be uploaded.
      parameters:
        - $ref: '#/components/parameters/CorrelationId'
      requestBody:
        required: true
        content:
          application/json:
            schema: { $ref: '#/components/schemas/SetPrivacyModeRequest' }
      responses:
        '200':
          description: Updated profile.
          content:
            application/json:
              schema: { $ref: '#/components/schemas/Profile' }
        '400': { $ref: '#/components/responses/Error' }
        '401': { $ref: '#/components/responses/Error' }
        default: { $ref: '#/components/responses/Error' }
components:
  schemas:
    ClientConfig:
      type: object
      additionalProperties: false
      required: [flags, policy_versions]
      properties:
        flags:
          type: object
          description: >
            Allow-listed flags only: home.category.travel, home.category.food, home.category.grocery,
            home.category.shopping, chat.ai.enabled, comparison.enabled, history.import.enabled.
          additionalProperties: { type: boolean }
        policy_versions:
          type: object
          additionalProperties: false
          required: [terms, privacy, consent]
          properties:
            terms: { type: string }
            privacy: { type: string }
            consent: { type: string, description: Version of the consent texts; a granted consent with an older version should be asked again. }
    SetPrivacyModeRequest:
      type: object
      additionalProperties: false
      required: [privacy_mode]
      properties:
        privacy_mode: { $ref: '#/components/schemas/PrivacyMode' }
    DeviceInfo:
      properties:
        device_model:
          type: [string, 'null']
          maxLength: 64
          description: Marketing model name from the OS (e.g. "Pixel 8"), shown in the sessions list. Optional.
    RegisterRequest:
      properties:
        remember_device:
          type: boolean
          default: true
          description: false gives a 12-hour session instead of 30 days, as on login.
    ActivityEvent:
      properties:
        summary_key:
          type: [string, 'null']
          pattern: '^[a-z_.]+$'
          description: >
            Stable template key for the app's own copy, e.g. consent_granted.automation,
            consent_revoked.ai_processing, session_revoked.one, session_revoked.others, data_exported,
            data_deleted.chat, data_deleted.history. `summary` remains the English fallback.
        summary_args:
          type: [object, 'null']
          additionalProperties: { type: string }
          description: Values for the template, e.g. {"provider_name":"Blinkit","count":"2"}. No personal data.
    Provider:
      properties:
        hidden:
          type: boolean
          default: false
          description: Registry-only provider the app must not show (e.g. Zepto, D-005).
    CartTask:
      properties:
        approval_nonce:
          type: [string, 'null']
          description: >
            Single-use value issued while status is AWAITING_APPROVAL; POST /approve must send it back.
            A wrong or reused nonce returns 409 task_state_conflict. Null in every other state.
```

## Impact analysis

| Area | Impact |
|---|---|
| UX / screens / states | No screen changes in this CR. The app *may* start using the new data (show model names, hide by `hidden`, map `summary_key`) under CR-003 or a later copy update; until then nothing changes on screen. |
| API contract | 2 new operations (`GET /v1/config`, `PUT /v1/me/privacy-mode`), 2 new schemas, 7 optional properties. No required lists change. Contract version → 1.1.0. |
| Compatibility & migration | Backward compatible both ways: old apps ignore new fields; the new server accepts requests without them (`remember_device` defaults to true; `device_model` absent → derived label). G changes the approval rule, but no released client approves tasks over HTTP yet (Phase 4), so there is nobody to migrate. Fixtures gain the new fields; the app's strict fixture test (`FixtureContractTest`) needs the model updated in the same change. |
| Analytics events | `privacy_mode_changed` (existing `privacy_action` value) is now backed by a server call. No new events. |
| Tests to add/update | Backend: contract tests for both new operations; config never exposes non-allow-listed flags; device model shown in sessions; short registration sessions; privacy mode persisted and in export; summary keys stable per event type; nonce issued only in AWAITING_APPROVAL, single-use, mismatch → 409. App: models + fixtures; strict fixture test. |
| Security / privacy | `GET /v1/config` is public: it must expose only the allow-list (test enforces). `device_model` is low-sensitivity device data — add to data-classification.md (Personal, account lifetime, not sent to Claude or analytics). `summary_args` must never carry personal data (test). The nonce strengthens threat-model A-06 (silent cart mutation): an approval can only apply to the plan it was issued for, once. |
| Accessibility | None. |
| Delivery | About 2–3 engineering days (backend + models + fixtures + tests). Proposed: before Phase 4, since G and D are Phase 4 prerequisites. |

## Revised acceptance criteria

- Patch applied; Spectral and fixture validation clean; every new operation and field covered by a backend contract test.
- `GET /v1/config` returns exactly the allow-listed flags and the current policy versions.
- A registration with `remember_device: false` has a 12-hour session; `device_model` appears as `device_label`.
- An approval without the issued nonce, or with a reused one, is refused with 409.

```text
Approvals:  Product [x]  Design [x]  Engineering [x]  Security [x]  QA [x]
            (all five confirmed by Akash, project owner, 26 Nov 2026)
Decision: approved     Decision date: 2026-11-26
Linked commits / tag:
```
