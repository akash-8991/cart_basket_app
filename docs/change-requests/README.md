# Change requests after `frontend-freeze-v1.4`

Raised from the contract gaps found while building Phase 3 (`docs/phase3/PLAN.md` §"Contract gaps found"). Each follows `docs/phase2/change-request-template.md` and needs the approvals it lists before anything in the frozen contract or screens changes.

| CR | Title | Kind | Status | Depends on |
|---|---|---|---|---|
| [CR-001](CR-001-document-error-responses.md) | Document the error responses the server already returns | Contract documentation only | Approved and implemented 26 Nov 2026 (contract 1.0.1) | — |
| [CR-002](CR-002-additive-contract-fields.md) | Additive contract fields and endpoints | Optional fields + 2 new operations | Approved and implemented 26 Nov 2026 (contract 1.1.0) | — |
| [CR-003](CR-003-ai-consent-email-verification-reconsent.md) | AI-processing consent, email verification, re-consent | New screen states, 1 new screen, 2 new operations | Approved 26 Nov 2026 | CR-002 (`GET /v1/config`) |

Suggested order: CR-001 and CR-002 together (no visible change), then CR-003. All three are proposed for before Phase 4: Phase 4 turns on live Claude calls (which need users who have consented) and HTTP task approval (which needs the server-issued nonce).

## Where each Phase 3 gap went

| Gap | Summary | Handled by |
|---|---|---|
| 1 | No email-verification endpoint | CR-003 §2 |
| 2 | No device model in `DeviceInfo` | CR-002 B |
| 3 | `approximate_location` always null | Decision D1 below (no CR proposed) |
| 4 | Registration can't choose a short session | CR-002 C |
| 5 | Undocumented 400s on `/v1/me/*` | CR-001 |
| 6 | Delete lists no 429 | CR-001 |
| 7 | Nothing sets `privacy_mode` | CR-002 D |
| 8 | No current consent version | CR-002 A (versions in config) + CR-003 §3 (asking again) |
| 9 | Activity summaries are server English | CR-002 E |
| 10 | App can't read server flags | CR-002 A |
| 11 | No `hidden` on `Provider` | CR-002 F |
| 12 | App never asks for AI consent | CR-003 §1 |
| 13 | Undocumented statuses on chat | CR-001 |
| 14 | No way to keep a chat beyond 30 days | Decision D2 below |
| 15 | Nothing issues `approval_nonce` | CR-002 G |
| 16 | 503/400/404 undocumented on Phase 4 operations | CR-001 |
| 17 | Dashboard, orders, recommendations are the same canned data for everyone | Phase 4 scope (real history import and metrics); no CR |
| 18 | Installed apps unknown | Phase 4 device spike (verified package names); no CR |
| — | Login/refresh 400 and a `default` error on every operation (found while drafting) | CR-001 |

## Decisions needed (no CR drafted; still open)

- **D1 — Approximate location on the sessions screen.** Recommendation: keep `approximate_location` null. Showing a city needs IP geolocation, a new processing purpose and a third-party database, for little user value. If accepted, no change is needed (the field is already nullable).
- **D2 — "Kept 30 days unless saved".** Recommendation: change `docs/phase0/data-classification.md` to a flat 30 days after last activity and drop "unless saved". A save feature would be a new screen state and a longer retention purpose to justify. If product wants saved chats instead, that becomes its own CR.
- **D3 — Account recovery for unverified emails** (raised by CR-003). Today a reset code goes to the registered address whether or not it was verified. Restricting recovery to verified addresses is safer against typo'd or mistyped emails but can lock out users who never verified. Recommendation: keep current behaviour until CR-003 has been live long enough to know the verification rate.

## Checking the proposed contract text

Every ` ```yaml openapi-patch ` block in a CR is meant to be merged into the contract exactly as written. To check them without touching the frozen file:

```bash
python3 tools/cr-preview.py docs/change-requests/CR-00*.md --out /tmp/openapi.cr-preview.yaml
cd tools/contract-check && npx spectral lint /tmp/openapi.cr-preview.yaml --ruleset .spectral.yaml --fail-severity warn
OPENAPI=/tmp/openapi.cr-preview.yaml node validate.mjs     # today's fixtures must stay valid (compatibility)
```

Last checked 26 Nov 2026 with all three CRs applied: Spectral clean; 43 operations, all with a `default` error response; all 71 existing fixtures valid.
