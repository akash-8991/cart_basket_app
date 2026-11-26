# Screen-State Catalogue and Navigation Map

**Phase:** 0 (contract) — becomes frozen at `frontend-freeze-v1.4`.  
**Rule:** every screen implements each applicable state. Fixtures in `contracts/fixtures/` drive every state; the in-app **Mock scenario** switcher (debug builds only) selects them.

## 1. State legend

| Code | State | Meaning |
|---|---|---|
| L | Loading | Request in flight; skeleton or progress shown |
| E | Empty | Valid response with no data; explains next step |
| S | Success | Normal content |
| P | Partial | Some data missing (e.g. provider coverage incomplete); missing parts labelled |
| X | Error | Request failed; error envelope message shown, never raw detail |
| O | Offline | No connectivity; cached content shown where safe, actions disabled |
| D | Permission denied | Consent or OS permission missing; explains and links to grant |
| R | Retry | Retry action available after X/O; rate-limited responses show wait time |

## 2. Navigation map

```text
welcome ─┬─ auth/register ──────────────┐
         ├─ auth/login ─────────────────┤
         │    ├─ auth/forgot-password ─ auth/reset-password
         │    └─ auth/forgot-user-id    │
         └─ auth/reinstall ─ auth/login │
auth/unlock (remembered device) ────────┤
                                        ▼
                                      home ──┬─ insights/month/{month}
                                             ├─ chat/{category} ─┬─ comparison/{quoteId} ─┐
                                             │                   └─ (clarification inline) │
                                             │   consent/automation/{provider} ◄──────────┤
                                             │   task/{taskId}/approve ◄──────────────────┘
                                             │   task/{taskId} (timeline, emergency stop)
                                             │      ├─ task/{taskId}/ready (handoff)
                                             │      └─ task/{taskId}/manual (fallback)
                                             ├─ suggestions ─ chat/{category}?prefill
                                             ├─ history/connect ─ history/import/{id} ─ history/review/{id}
                                             ├─ history/orders
                                             └─ settings ─┬─ settings/providers
                                                          ├─ settings/privacy
                                                          ├─ settings/sessions
                                                          ├─ settings/notifications
                                                          ├─ settings/activity
                                                          └─ help (emergency stop, troubleshooting)
deep link: smartcart://handoff/{opaqueToken} → task/{taskId}/ready (after token validation)
```

**Global overlays:** active-task banner with **Stop** (visible on every screen while a task is `RUNNING`/`CHECKPOINT`), offline banner, rate-limit snackbar.

## 3. Screens

| ID | Route | Purpose | States | Key fixtures |
|---|---|---|---|---|
| S-AUTH-01 | `welcome` | Entry: create account, log in, reinstall help | S | — |
| S-AUTH-02 | `auth/register` | Create user ID, email, password; accept terms + privacy notice | L S X O R | `auth/register.*` |
| S-AUTH-03 | `auth/login` | User ID + password; remember device | L S X O R (lockout/backoff) | `auth/login.*` |
| S-AUTH-04 | `auth/unlock` | Remembered-device continue; session-expired fallback | L S X | `auth/refresh.*` |
| S-AUTH-05 | `auth/reinstall` | Explains fresh login / recovery after reinstall; never promises password recovery | S | — |
| S-AUTH-06 | `auth/forgot-password` | User ID + email → generic "if details match" | L S X O R | `auth/forgot-password.*` |
| S-AUTH-07 | `auth/reset-password` | Code from email + new password; revokes other sessions | L S X R (expired/replayed) | `auth/reset-password.*` |
| S-AUTH-08 | `auth/forgot-user-id` | Email → generic response; ID sent only by email | L S X O R | `auth/forgot-user-id.*` |
| S-AUTH-09 | `auth/verify-email` | Code from email; resend; later (CR-003) | L S X R (wrong/expired/locked) | — |
| S-HOME-01 | `home` | Header + security status, KPI summary (orders, amount, AOV, frequency, MoM delta, freshness), category cards, suggestions, recent comparisons, chat composer | L E S P X O R | `dashboard/summary.*` |
| S-HOME-02 | `insights/month/{month}` | Monthly detail with provider/category filters, breakdown | L E S X O R | `dashboard/monthly.*` |
| S-CHAT-01 | `chat/{category}` | Category-scoped chat; clarification chips; action-preview card; comparison trigger | L E S X O R | `chat/*` |
| S-CHAT-02 | `chat/history` | Past conversations; delete | L E S X O | `chat/sessions.*` |
| S-CMP-01 | `comparison/{quoteId}` | Full-cart totals by provider, cost components, ETA/availability, freshness, recommended badge + reason, "Use this app" / "Choose another app" | L S P X O R + domain: complete, partial_coverage, stale, incomparable, unavailable, price_changed, manual_only | `comparison/*` |
| S-CMP-02 | dialog | Provider switch confirmation when recommended ≠ selected | S | — |
| S-CMP-03 | sheet | Cost breakdown for one provider | S P | — |
| S-TASK-01 | `consent/automation/{provider}` | Just-in-time accessibility explanation: scope, allowed actions, prohibited actions, how to stop | S D | `providers/capabilities.*` |
| S-TASK-02 | `task/{taskId}/approve` | Action preview: provider, items, quantities, risk level per action, estimated total, max actions, timeout; Approve / Cancel | L S X R | `cart-tasks/created.*` |
| S-TASK-03 | `task/{taskId}` | Timeline of actions, current state, checkpoint confirmations, emergency stop | L S X O + domain: RUNNING, CHECKPOINT, NEEDS_USER_ACTION, FAILED_SAFE, BLOCKED_POLICY, CANCELLED, EXPIRED | `cart-tasks/*` |
| S-TASK-04 | `task/{taskId}/ready` | Verified cart summary; "Open cart in {provider}"; estimate-vs-final disclaimer | S X (handoff expired) | `deep-links/*` |
| S-TASK-05 | `task/{taskId}/manual` | Manual fallback steps; open provider app | S | — |
| S-HIST-01 | `history/connect` | Choose providers, fields collected, privacy mode (device-only / normalized upload) | S D | `providers/capabilities.*` |
| S-HIST-02 | `history/import/{id}` | Progress per provider; pause / cancel | L S P X | `history/import.*` |
| S-HIST-03 | `history/review/{id}` | Review sample, correct/delete rows, confirm upload | S E | `history/import.*` |
| S-HIST-04 | `history/orders` | Imported orders; delete | L E S X O R | `history/orders.*` |
| S-SUG-01 | `suggestions` | Confidence-labelled suggestions with "why"; "Not useful"; start order | L E S X O R | `recommendations.*` |
| S-SET-01 | `settings` | Hub | S | — |
| S-SET-02 | `settings/providers` | Installed / supported apps, capabilities, per-provider consent toggle, kill-switch status | L S X | `providers/capabilities.*` |
| S-SET-03 | `settings/privacy` | Export data, delete history, delete chat, delete account (step-up), clear cache, privacy mode | L S X | `me/data.*` |
| S-SET-04 | `settings/sessions` | Device list; revoke one / revoke all | L S X R | `auth/sessions.*` |
| S-SET-05 | `settings/notifications` | Per-category toggles; lock-screen privacy | S | — |
| S-SET-06 | `settings/activity` | Recent automation and consent events | L E S X | `me/activity.*` |
| S-SET-07 | `help` | Pause all automation, troubleshooting, contact | S | — |

## 4. Cross-cutting UI rules

- Never show "cheapest" unless `coverage.complete == true` and no fee fields are missing; otherwise show "lowest observed estimate".
- Every price carries a freshness timestamp and "Final price may change in {provider}".
- Payment never appears as an action. Handoff copy states that payment happens in the partner app.
- Notifications use generic text when lock-screen privacy is on.
- No password, token, email, address or order ID is sent to analytics.
- Minimum touch target 48dp; all icons have content descriptions; focus order follows reading order; motion respects system "remove animations".
