# CR-003 — AI-processing consent, email verification and re-consent in the app

```text
CR ID:        CR-003
Title:        Ask for AI-processing consent, let users verify their email, and re-ask consent when texts change
Owner:        Akash (project owner) — drafted by Claude      Date raised: 2026-11-26
Reason:       Defect against requirements. Audit v1.2 §13.1 requires versioned AI-processing consent and the
              backend enforces it, but no frozen screen asks for it (gap 12), so chat never uses the AI.
              Registration reports email_verification_required but nothing can complete it (gap 1).
              Consent texts can change without anyone being asked again (gap 8). Depends on CR-002 (A).
```

## Change description

**1. AI-processing consent (gap 12)** — changed consent flow, new screen state.
- **Just-in-time sheet on `S-CHAT-01`**, shown before the first message is sent if `ai_processing` has never been decided: what is sent (the message, with contact and card numbers masked), what is not (account details, order history), that the AI suggests and never acts, and that the choice can be changed later. Buttons: **Use AI help** (grants `ai_processing`) / **Not now** (records a refusal; chat works in its limited mode).
- **Limited-mode hint** in chat when the reply is `degraded` because consent is off: one line, "AI help is off — turn it on in Settings", linking to the toggle.
- **Toggle on `S-SET-03` Privacy** ("AI help with your requests"), on/off at any time; turning it off stops future processing at once (the server checks on every message).

**2. Email verification (gap 1)** — new screen and contract operations.
- **Home banner** on `S-HOME-01` while `email_verified` is false: "Verify your email to recover your account" → new screen **`S-AUTH-09` `auth/verify-email`**: six-digit code field, *Resend code*, *Later*. Account recovery is unchanged (it still sends codes to unverified addresses); whether to restrict it is a separate decision, listed in the index.
- Contract: two operations (patch below). Codes are single-use, expire after 15 minutes, lock after 5 wrong attempts, rate-limited like recovery.

**3. Re-consent when texts change (gap 8)** — changed consent flow.
- At start, the app compares the versions in `GET /v1/config` (CR-002 A) with what the user accepted. **Terms or privacy changed** → blocking sheet with the summary of changes and *Accept* / *Sign out*. **Consent texts changed** → a granted consent at an older version is treated as not granted until re-confirmed; the existing just-in-time screens (automation consent `S-TASK-01`, history import, the new AI sheet) ask again when next needed.

### OpenAPI patch

```yaml openapi-patch
paths:
  /v1/auth/verify-email:
    post:
      operationId: verifyEmail
      tags: [auth]
      summary: Confirm the account email with the code sent to it.
      parameters:
        - $ref: '#/components/parameters/CorrelationId'
      requestBody:
        required: true
        content:
          application/json:
            schema: { $ref: '#/components/schemas/VerifyEmailRequest' }
      responses:
        '200':
          description: Email verified.
          content:
            application/json:
              schema: { $ref: '#/components/schemas/Profile' }
        '400': { $ref: '#/components/responses/Error' }
        '401': { $ref: '#/components/responses/Error' }
        '410': { $ref: '#/components/responses/Error' }
        '429': { $ref: '#/components/responses/RateLimited' }
        default: { $ref: '#/components/responses/Error' }
  /v1/auth/verify-email/resend:
    post:
      operationId: resendEmailVerification
      tags: [auth]
      summary: Send a new verification code to the account email.
      parameters:
        - $ref: '#/components/parameters/CorrelationId'
      responses:
        '202':
          description: A new code was sent (the previous one stops working).
          content:
            application/json:
              schema: { $ref: '#/components/schemas/RecoveryAccepted' }
        '401': { $ref: '#/components/responses/Error' }
        '429': { $ref: '#/components/responses/RateLimited' }
        default: { $ref: '#/components/responses/Error' }
components:
  schemas:
    VerifyEmailRequest:
      type: object
      additionalProperties: false
      required: [code]
      properties:
        code: { type: string, minLength: 6, maxLength: 8 }
```

Analytics (`contracts/schemas/analytics-event.schema.json`): add **`consent_changed`** with `consent_scope` (already allows `ai_processing`) and `granted`, for every consent decision including AI; keep `automation_consent_changed` for compatibility. Add **`email_verification_result`** with `result`. No free text or personal data, as for all events.

## Impact analysis

| Area | Impact |
|---|---|
| UX / screens / states | New state on S-CHAT-01 (AI consent sheet, limited-mode hint); new toggle on S-SET-03; new banner on S-HOME-01; new screen S-AUTH-09; new blocking re-consent sheet at start. Navigation map gains `auth/verify-email`. Design needed for the sheet copy and the banner. |
| API contract | Two auth operations, one schema; uses CR-002's `GET /v1/config`. Contract version → 1.2.0. |
| Compatibility & migration | Existing users have no `ai_processing` decision, so they see the sheet once. Users registered before this change are unverified and see the banner. Consents granted at older text versions are re-asked only when the version actually changes. |
| Analytics events | `consent_changed`, `email_verification_result` (above). |
| Tests to add/update | App: UI tests for the sheet (grant, refuse, later toggle), limited-mode hint, verify screen (success, wrong code, expired, locked, resend), re-consent sheet; strings via resources only (NoHardcodedCopyTest). Backend: verify/resend contract tests, single-use and expiry, lockout, rate limit, `email_verified` flips, recovery unchanged for unverified accounts. Device: extend `BackendEndToEndTest` so it grants AI consent through the sheet instead of the API workaround. Accessibility audit on the new sheet and screen. |
| Security / privacy | AI processing becomes explicit, versioned and revocable, as audit §13.1 requires; the sheet states exactly what goes to the model (D-022). Email codes reuse the recovery-code design (hashed, single-use, 15 min, 5 attempts). Data classification: no new data classes. |
| Accessibility | Sheet and screen follow existing patterns (48dp targets, TalkBack labels, focus moves to the sheet title, works at 2× font); added to the ATF audit list. |
| Delivery | Design ~2 days (copy + sheet + banner); app ~3–4 days; backend ~1 day; QA ~1 day. Proposed: with CR-002, before Phase 4 so live Claude calls (Phase 4) have consenting users. |

## Revised acceptance criteria

- A new user who opens chat is asked about AI help before the first message; *Use AI help* gives model-backed replies; *Not now* gives limited replies with a way to turn it on; turning it off in Privacy makes the next reply limited.
- An unverified user sees the banner; entering the emailed code verifies the account and removes it; wrong, expired and reused codes are refused with the documented errors.
- Raising the consent version on the server makes the next automation, history or AI use ask again; raising terms/privacy blocks the app until accepted or signed out.
- `BackendEndToEndTest` passes with consent given through the UI.

```text
Approvals:  Product [x]  Design [x]  Engineering [x]  Security [x]  QA [x]
            (all five confirmed by Akash, project owner, 26 Nov 2026)
Decision: approved     Decision date: 2026-11-26
Linked commits / tag: commits "CR-003 (backend): …" and "CR-003 (app): …" on main (contract 1.2.0)
```
