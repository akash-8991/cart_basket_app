# Phase 4 — Vertical integrations

**Started:** 27 November 2026, on the project owner's go-ahead ("Start Phase 4").  
**Exit gate (plan §2):** one end-to-end slice per category without payment. **Sign-off:** product owner + legal/policy.  
**Hard limits (plan rule 3):** no payment, OTP, password, CAPTCHA or biometric automation; every task has an emergency stop, timeout, maximum action count and unknown-screen halt; each provider has its own kill switch.

## 1. What can be built now, and what waits for people

Real partner apps can only be automated once their facts are measured on a real phone with team-owned test accounts (feasibility spike Q1–Q9) and their policy and terms reviews are done. None of those facts are invented here (PRD Part B rule 8). So Phase 4 is built in two layers:

1. **Provider-independent machinery**, built and tested now: the on-device automation runtime and safety model, the adapter format, the comparison engine, server-side cart tasks, signed handoff tokens, history import and live Claude calls behind their flag. It is exercised end to end against **SmartCart Test Store** (`com.smartcart.teststore`), a small app in this repository that exists only to be automated in tests: it has search, results, a cart, and on request a login, OTP, payment screen, unexpected dialog, redesigned screen or jump to another app (D-027).
2. **Real partner adapters**, one per provider, written only from the spike's measurements and switched on only when the provider's approval record is complete.

| Needs a person | Who | Blocks |
|---|---|---|
| Google Play AccessibilityService policy review (R-01) | Policy owner | Any release build containing the service (debug builds only until then) |
| Terms review per provider (R-02) | Legal | That provider's adapter |
| Privacy review of history import (R-05, DPDP) | Privacy / legal | Turning on history upload |
| Device spike Q1–Q9 per provider, with team test accounts | Project owner / QA on a real phone | That provider's adapter (4.8) |
| Anthropic API key in the server environment | Project owner | Live Claude calls (4.7); the key never enters the app or the repository |
| Approval of CR-005 (below) — **done 28 Nov 2026** | Project owner | 4.4 onward |

## 2. Contract gap found at the start (→ CR-005)

Automation runs on the phone, but the frozen contract has no way for the phone to tell the server what happened:

- **Task progress.** `CartTask` has events, `actions_used`, `checkpoint`, `needs_user_action`, `cart` and `failure`, but no operation lets the device report them. Today only the server's own stub writes them.
- **Captured prices.** `POST /v1/comparison/quotes` builds the comparison on the server, but the prices are read from partner apps on the phone, and nothing carries them to the server.

These need a change request (post-freeze rule) before the server-side engine and tasks (4.4–4.5). Slices 4.1–4.3 don't change the contract. [CR-005](../change-requests/CR-005-device-reported-prices-and-task-progress.md) was approved and implemented on 28 Nov 2026 (contract 1.4.0, D-030): the server accepts both reports in the development stubs, and the app builds both bodies. History upload for 4.6 is a separate gap, likely CR-006 with the privacy review.

## 3. Slices (each is one commit, pushed)

| # | Slice | Contents | Done when |
|---|---|---|---|
| 4.1 | **Automation runtime and safety model** | `:core:automation` (pure Kotlin): screen snapshots, semantic selectors (no coordinates), adapter format, task runner with full-plan approval, package + signing-certificate allowlist, prohibited-screen detection (payment, OTP, password, CAPTCHA, biometric, login), refusal to touch payment controls, unknown-screen halt, max actions / runtime / inactivity, checkpoints for high-risk actions, emergency stop, kill switch. Android `AccessibilityService` (debug builds only, Test Store only), the Test Store app and its adapter | JVM safety tests for every halt condition; on the emulator a real 2-item cart is built and verified in the Test Store, and each hazard scenario halts in the right state without touching the hazard |
| 4.2 | **App tasks on the runtime** | Approved tasks run on-device through the service; timeline, checkpoints, needs-you, emergency stop and cart-ready driven by the runner; turning the service on from the consent screen; debug-only binding of a category's providers to the Test Store adapter | Device journey: chat → compare → approve → real automation in the Test Store → cart ready |
| 4.3 | **On-device price capture** | Adapters read prices, fees and availability from search/cart screens into the existing `ProviderResult` shape; capture runs as a background task with progress | Test Store prices captured and shown on the comparison screen |
| 4.4 | **CR-005 + comparison engine** | Device-reported observations and task progress (after approval); deterministic full-cart engine on the server: matching, fees, totals, coverage, staleness, labels; replaces the quote stub | Engine golden tests; contract tests |
| 4.5 | **Server-side cart tasks and handoff** | Real task state machine with the approval nonce and device progress; signed one-time handoff tokens (≤10 min, package-bound, single use) and app-link verification | A-06, A-07, A-08 tests |
| 4.6 | **History import** | On-device parsing of order-history screens (Test Store), field allowlist, device-only by default, normalized upload only with consent; dashboard and recommendations from real data | A-10 privacy tests |
| 4.7 | **Live Claude calls** | `SMARTCART_AI_LIVE` on in development with the key from the environment; prompt-injection smoke set | Live call validated against the intent schema; fallback on any failure |
| 4.8 | **Partner adapters** | Per provider, from spike measurements; one per category for the exit gate | Provider approval record complete |

## 4. Progress

| Slice | Status | Notes |
|---|---|---|
| 4.1 Automation runtime | ✅ 27 Nov 2026 | `:core:automation` with 18 JVM safety tests (full-plan approval, prohibited/unsupported steps, app missing or wrongly signed, kill switch before and during, OTP/login/CAPTCHA, payment screen, commit controls and secret fields refused even if an adapter asks, wording vs fields, unexpected dialog, unknown screen, another app in front, out of stock, cart drift, action and runtime limits, emergency stop, checkpoints approve/reject with deciding time excluded). On the emulator the AccessibilityService builds and verifies a real 2-item cart (₹101.00) in the Test Store, and every hazard scenario halts correctly (9 device tests). Device matrix 45/45 on API 26, 30, 34, 36 and the API 36 tablet, plus the 2× font audit |
| 4.2 App tasks on the runtime | ✅ 28 Nov 2026 | `DeviceCartTaskRepository` + `RoutingCartTaskRepository` in `:core:data` (D-028); debug-only Test Store binding behind a developer switch. 10 JVM tests (real cart after full approval, nothing runs while the service is off, full-plan approval, login hand-back then resume, dialog mid-run resumes from the halted step, emergency stop immediate and final, payment blocks, failed cart check, checkpoint answers, routing). Device journey through the real app: chat → compare → approve → cart built in the Test Store (₹233.00, payment never touched) → cart-ready notification → cart-ready screen → handoff; and login → needs-you → resume → cart ready. Device matrix 47/47 on API 26, 30, 34, 36 and the API 36 tablet, plus the 2× font audit |
| 4.3 On-device price capture | ✅ 28 Nov 2026 | `PriceCapture` (read-only price-check plans on the 4.1 runner) and `DeviceComparisonRepository` + `CapturedQuote` (merge under the existing comparison rules), D-029. The Test Store states its fees (₹5 delivery; no packaging, platform or small-cart fees; prices include taxes). 7 runtime tests (prices and fees read, out of stock and not found reported, login/OTP/CAPTCHA end the check, unknown screen, cart controls refused even if an adapter asks, price-check plans only open and search, cart tasks unchanged) and 6 repository tests (column replaced with progress then the reading, re-ranking and savings, out of stock leaves the cart unranked, an unreadable app means no "Cheapest", nothing read without the service or consent, unbound comparisons unchanged, tasks add only what was found in stock). On the emulator: the comparison shows Blinkit at ₹238 read in the Test Store, ₹26 below BigBasket. Device matrix 48/48 on API 26, 30, 34, 36 and the API 36 tablet, plus the 2× font audit |
| 4.4 Comparison engine | ✅ 28 Nov 2026 | `ComparisonEngine` on the server (D-031) replaces the canned quote stub; in development a sample catalogue built from the frozen fixtures stands in for providers the phone doesn't read. 14 engine tests, 9 of them golden: the engine reproduces every frozen comparison fixture from its raw readings. 4 API tests (any cart priced line by line, an item no app has, bad carts refused, shopping manual, choosing without a pick). The app sends comparisons to the server when a backend is set (`HttpComparisonRepository`), and the phone's price check goes to the server as a reading (CR-005). Against a real backend: 7 JVM integration tests, and on the emulator both journeys (the server's comparison through to the approved cart; the phone's Test Store reading in the server's comparison, ₹210). Device matrix 49/49 on API 26, 30, 34, 36 and the API 36 tablet, plus the 2× font audit |
| 4.5 Server-side cart tasks and handoff | ✅ 28 Nov 2026; link redemption with CR-006 (D-033) | `CartTasks` + `TaskStateMachine` on the server replace the canned tasks (D-032); `HandoffTokens` issues opaque, signed, ≤10-minute, provider-bound tokens, once per ready cart. The app holds tasks on the server when there is a backend (`HttpCartTaskRepository`), and a task the phone runs reports each step there. Tests: 6 for A-06/A-07/A-08 (`TaskThreatsTest`), 4 for the phone's server mode (reports in order, a stop on the server halts the phone, the nonce isn't spent while the service is off, login then resume), 2 more against a real backend (a server-run task to a handoff link; a phone-run task the server checks), and on the emulator both journeys (server-run task to cart ready; Blinkit read and built in the Test Store with every step reported and the cart checked by the server). Device matrix 49/49 on API 26, 30, 34, 36 and the API 36 tablet, plus the 2× font audit |

### Notes from 4.1

- **Off-screen rows.** The Test Store's cart is a plain scroll view, so lines scrolled out of view are still readable. Real apps usually use recycled lists that expose only visible rows; partner adapters will need to scroll the cart and read it in pages (spike Q2).
- **Payment detection is two-layered.** Fields decide on any screen (password, OTP, card, CVV, UPI); wording alone only counts on screens the adapter doesn't recognise, so a cart that says "share the OTP with the delivery partner" isn't mistaken for an OTP prompt. Independently, commit controls ("Pay", "Place order", "Proceed to pay", "Book", "Confirm ride"…) are never tapped, whatever an adapter asks.
- **The service sees only allowlisted apps.** When another app comes to the front, the service can't read it, and the runner treats that as a package mismatch after a short grace period.
- **Rebinding after a reinstall.** If the app is reinstalled while the service is on, the system drops the service, and writing the same setting value again doesn't bring it back; it has to be turned off and on. The device tests do that. In 4.2 the app should notice an enabled-but-unconnected service and tell the person to switch it off and on again.
- **API 26 test hang (fixed).** `am start -W` on the Test Store's translucent configure activity never returned on API 26 and froze the matrix for hours. Scenarios are now set through a broadcast receiver, and `tools/device-matrix.sh` puts a time limit on each device's run (45 min suite, 15 min audit), reported as TIMED OUT.
- **A CR-004 test fixed on the API 36 phone.** Tapping a clarification chip while the keyboard was still resizing the window was sometimes lost; the test now invokes the chip's click action, and waits for the address menu's popup before choosing from it.

### Notes from 4.2

- **Nothing on the frozen screens changed.** Showing whether the accessibility service is on, or blocking "Allow" until it is, would change the automation consent screen, which needs a CR. Today the existing "Open Accessibility settings" button turns the service on, and if it is off when the person taps Approve they get the existing "Permission needed" error and nothing runs. A CR for a live service status (including "turned on but not connected — switch it off and on") is worth raising before real adapters ship.
- **The screens name the provider, not the Test Store.** With the debug binding, a Blinkit task really runs in the Test Store. The plan and timeline say "Test Store" (they come from the runner), but frozen copy such as "I checked the Blinkit cart" names the provider. This is acceptable only because the binding is debug-only and off by default.
- **Handoff is a placeholder.** "Open cart" for an on-device task names the Test Store package and the steps. Signed, single-use handoff tokens come in 4.5.
- **Device tasks stay on the phone.** They aren't reported to the server until CR-005.
- **One line per halt.** The runner now writes a halt once in the timeline, and the hand-back messages say what to do: "Test Store wants you to sign in. Sign in yourself in Test Store, then come back and resume. I never type passwords or codes."
- **Device tests and the default UiAutomation.** The instrumentation's default UiAutomation switches off every other accessibility service. Device helpers now always use `FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES`, so screenshots and permission grants don't silently disconnect SmartCart's service.

### Notes from 4.3

- **The other app comes to the front while prices are read.** That is how on-device reading works: the person sees the Test Store open, searches happen, and SmartCart comes back on its own when the check ends. How long this takes on real apps, and whether people accept it, is a question for the device spike and usability testing.
- **One provider is bound.** Binding every grocery provider to the one Test Store would read the same prices three times. The debug switch is now "Blinkit runs in SmartCart Test Store" (D-029).
- **Fees are only what the app states.** The Test Store says it has no packaging, platform or small-cart fees and that prices include taxes, so those are zero; an app that doesn't say leaves them unavailable, and that provider gets no total. ETA isn't shown by the Test Store, so the screen lists it as "Not visible".
- **Fresh launches start at the store's home.** The Test Store now opens on its first screen when launched fresh, as most shop apps do, so a cart task straight after a price check doesn't start on a results screen.
- **"Checked … at" on the comparison footnote** still shows the fixture comparison's time; the captured column shows its own capture time. The server-side engine (4.4) gives the whole quote real times.

### Notes from 4.4

- **Totals follow the PRD.** A provider whose app doesn't show a fee still gets a total from the known fees; the missing fee is listed and "cheapest" isn't claimed. CR-005's text had said otherwise; corrected (see the CR index).
- **The server's comparison is only as wide as the enabled providers.** With only Blinkit enabled (as in the integration run), the comparison has one app, "checked 1 of 1"; the app compares the providers the server says are enabled.
- **A bug the canned quotes hid.** Confirming a provider when the person had picked none was treated as a switch needing acknowledgement; the app (rightly) doesn't ask then. The server now matches the app: no pick, no switch.
- **Not yet:** `price_changed` (needs a reference to the earlier quote), a definition of best value and fewest providers (ranked by total for now), and cart tasks on the server (4.5).

### Notes from 4.5

- **The link can't be used up yet.** *(Done with CR-006, D-033.)* The contract can issue a handoff link but has no operation to check one, so single use can't happen end to end, and the app still opens home for `smartcart://handoff/…`. [CR-006](../change-requests/CR-006-redeem-handoff-links.md) proposes the redeem operation, the app following the link, the notification carrying it, and App Links (which also need a domain and the release certificate from the project owner). The server-side check (`HandoffTokens.redeem`) is built and tested.
- **"Handed off" too early.** The cart-ready screen asks for the link as it opens, and issuing a link moves the task to HANDED_OFF. CR-006 moves that to when the link is used.
- **No partner package names yet.** Handoffs are bound to the provider and use the `manual` strategy until the device spike verifies package names (provider-matrix.md).
- **Reports pass through RUNNING.** A phone that finishes before its first report would jump from APPROVED to CART_READY, which D-002 doesn't allow; it reports RUNNING first. Found by the real-backend test.
- **Tasks live in memory in development.** Like the comparison stubs, server tasks and tokens are kept in memory and production still answers Phase 4 operations with `service_unavailable` until the approvals in §1; persisting them is part of turning Phase 4 on in production.
- **Integration runs record a test approval for Blinkit** through the operator API (`tools/backend-integration.sh`), since tasks need one.

### Notes from CR-006

- **"Open cart" now uses the link.** The cart-ready screen makes a link when it opens; tapping "Open cart" redeems it (the cart is handed off now), opens the app if its package is verified, otherwise shows the steps. A link that was used or replaced shows "This link has expired — Make a new link".
- **In debug builds with the Test Store switch on, "Open cart" opens the Test Store** — the one package this project has verified (its adapter's signer). Partner apps are opened only after the device spike verifies their packages.
- **The integration script restarts the backend before the device journeys,** so the JVM tests' sign-ups don't use up the device journeys' registration rate limit.
- **Verified:** backend 175/175, app JVM 123/123, real-backend integration 9/9 and both backend journeys on the emulator, device matrix 52/52 on API 26, 30, 34, 36 and the API 36 tablet plus the 2× font audit.
