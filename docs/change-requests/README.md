# Change requests after `frontend-freeze-v1.4`

Raised from the contract gaps found while building Phase 3 (`docs/phase3/PLAN.md` §"Contract gaps found"). Each follows `docs/phase2/change-request-template.md` and needs the approvals it lists before anything in the frozen contract or screens changes.

| CR | Title | Kind | Status | Depends on |
|---|---|---|---|---|
| [CR-001](CR-001-document-error-responses.md) | Document the error responses the server already returns | Contract documentation only | Approved and implemented 26 Nov 2026 (contract 1.0.1) | — |
| [CR-002](CR-002-additive-contract-fields.md) | Additive contract fields and endpoints | Optional fields + 2 new operations | Approved and implemented 26 Nov 2026 (contract 1.1.0) | — |
| [CR-003](CR-003-ai-consent-email-verification-reconsent.md) | AI-processing consent, email verification, re-consent | New screen states, 1 new screen, 2 new operations | Approved and implemented 26 Nov 2026 (contract 1.2.0) | CR-002 (`GET /v1/config`) |
| [CR-004](CR-004-user-entered-delivery-addresses.md) | User-entered delivery addresses (from D1) | New settings screen, cart-card picker, 1 optional request field | Approved and implemented 27 Nov 2026 (contract 1.3.0) | — |
| [CR-005](CR-005-device-reported-prices-and-task-progress.md) | Device-reported prices and task progress (Phase 4) | 2 new operations, 5 new schemas, 2 optional fields; no screen change | Approved and implemented 28 Nov 2026 (contract 1.4.0) | — |
| [CR-006](CR-006-redeem-handoff-links.md) | Redeem cart handoff links (Phase 4.5) | 1 new operation, 2 new schemas; handoff link and notification behaviour; App Links when a domain exists | **Proposed 28 Nov 2026** — awaiting approval | CR-005 |

Suggested order: CR-001 and CR-002 together (no visible change), then CR-003. All three are proposed for before Phase 4: Phase 4 turns on live Claude calls (which need users who have consented) and HTTP task approval (which needs the server-issued nonce).

## Where each Phase 3 gap went

| Gap | Summary | Handled by |
|---|---|---|
| 1 | No email-verification endpoint | CR-003 §2 |
| 2 | No device model in `DeviceInfo` | CR-002 B |
| 3 | `approximate_location` always null | D1: stays empty by design; user-entered addresses in CR-004 |
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
| 14 | No way to keep a chat beyond 30 days | D2: flat 30 days, wording corrected |
| 15 | Nothing issues `approval_nonce` | CR-002 G |
| 16 | 503/400/404 undocumented on Phase 4 operations | CR-001 |
| 17 | Dashboard, orders, recommendations are the same canned data for everyone | Phase 4 scope (real history import and metrics); no CR |
| 18 | Installed apps unknown | Phase 4 device spike (verified package names); no CR |
| — | Login/refresh 400 and a `default` error on every operation (found while drafting) | CR-001 |

## Implementation notes

- **CR-003 §3, terms and privacy.** The CR assumed the app could re-record acceptance, but no operation existed. Implemented without a new operation: `terms` and `privacy` are consent keys (accept-only, current version only), backfilled from registration, so `PUT /v1/me/consents/{consentKey}` records re-acceptance and `GET /v1/me/consents` reports what was accepted.
- **CR-003 §3, consent texts.** Enforced on the server: a grant counts only for the current consent-text version, so AI chat, provider consent and the Phase 4 gates stop honouring old grants and the app's existing just-in-time screens ask again. The app records grants against the version from `GET /v1/config`.
- **CR-003 §1, "not now" on this device.** The server can't tell "never asked" from "said no" (both are `granted: false`), so the app remembers per user and device that it asked; the decision itself is stored on the server.
- **CR-004, labels are unique.** Only the label leaves the phone and the cart picker chooses by label, so two saved addresses can't share one (ignoring case).
- **CR-004, "non-remembered" sign-out.** Addresses survive sign-out only when the user ticked *Keep me signed in on this device*; otherwise, and always on account deletion, they're removed.
- **CR-004, on-device fakes.** Comparisons are still on-device fakes until Phase 4, so the fake mirrors the backend stub: the chosen label replaces the generic delivery assumption.
- **CR-005, which transitions the phone may make.** Exactly the D-002 transitions into a state the device may set, plus reports that stay in the same state: APPROVED → RUNNING, CANCELLED or BLOCKED_POLICY; RUNNING → any device state; CHECKPOINT → RUNNING, CANCELLED or FAILED_SAFE; NEEDS_USER_ACTION → RUNNING, CANCELLED, FAILED_SAFE or BLOCKED_POLICY. The CR's table listed only the main path; the extra moves all end the task safely. D-002 has no APPROVED → FAILED_SAFE, so a phone whose check fails before the first step reports RUNNING first.
- **CR-005, a report after the task has ended on the server** (stopped elsewhere, expired, already ready) is answered with the task as it is, not 409, so the phone sees the stop and halts. Progress reports are idempotent by `report_seq` (no Idempotency-Key); readings use the Idempotency-Key as the CR says.
- **CR-005 in the development stubs.** A task whose provider the phone read (`device_providers`) is run by the phone: approval leaves it APPROVED, and only the phone's reports move it; checkpoint answers, stop and handoff keep its timeline. Every other task keeps the canned run. Fees an app doesn't state stay unavailable, except surge and discounts, which mean none when unstated (as in the fixtures).
- **CR-005 fixtures.** Request bodies (`device/observation.*.json`, `device/progress.*.json`) are listed in the manifest with status 0; the app decodes them strictly like every other fixture. One unquoted description in the patch split into a stray schema key when applied; fixed in the contract and the CR text, and the contract is now scanned for unexpected keywords.
- **CR-005, totals with a missing fee (corrected in 4.4).** The CR text first said a total needs every fee known and called that the existing rule; it isn't. The PRD and the frozen fixtures give a total from the known fees and list the missing one, which rules out "cheapest". The CR text, the server and the app's no-backend merge now follow the PRD (D-031).
- **CR-005, a phone that finishes before its first report.** D-002 has no APPROVED → CART_READY, so the phone reports RUNNING first, then where it is (4.5).
- **Found while implementing:** a token-refresh race in the app's HTTP client (concurrent expired calls could refresh twice), fixed; and the 2×-font layout fix from the device run had made the KPI figures stack at normal size on a standard phone — fixed, with a regression test.

## Decisions (settled 27 Nov 2026 by the project owner)

- **D1 — Location.** No automatic location: the server never derives where someone is (no IP geolocation), so the sessions list keeps `approximate_location` empty. Where an address matters, the user enters it themselves — proposed as [CR-004](CR-004-user-entered-delivery-addresses.md).
- **D2 — Chat retention.** A flat 30 days after last activity; there are no "saved" chats. `data-classification.md` corrected; the backend already behaves this way.
- **D3 — Recovery for unverified emails.** Unchanged: reset codes go to the registered address whether or not it was verified. Revisit when there is data on how many users verify.

## Checking the proposed contract text

Every ` ```yaml openapi-patch ` block in a CR is meant to be merged into the contract exactly as written. To check them without touching the frozen file:

```bash
python3 tools/cr-preview.py docs/change-requests/CR-00*.md --out /tmp/openapi.cr-preview.yaml
cd tools/contract-check && npx spectral lint /tmp/openapi.cr-preview.yaml --ruleset .spectral.yaml --fail-severity warn
OPENAPI=/tmp/openapi.cr-preview.yaml node validate.mjs     # today's fixtures must stay valid (compatibility)
```

Last checked 26 Nov 2026 with all three CRs applied: Spectral clean; 43 operations, all with a `default` error response; all 71 existing fixtures valid.
