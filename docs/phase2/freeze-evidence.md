# Frontend Freeze Evidence Pack — candidate for `frontend-freeze-v1.4`

**Prepared:** 22 November 2026 by Claude (engineering assistant)  
**Status:** **Candidate, not frozen.** The freeze needs the human sign-offs in §4. Phase 3 (backend) does not start until they are recorded.

Status key: ✅ evidence exists · 🟡 partial, see note · ⛔ not done, needs people or devices

## 1. PRD v1.3 §2 freeze exit criteria

| # | Criterion | Status | Evidence |
|---|---|---|---|
| 1 | IA and navigation map approved | 🟡 | `docs/phase0/screen-state-catalogue.md` §2; implemented in `android/app/.../ui/AppNavHost.kt`. **Needs product approval.** |
| 2 | All listed screens implemented | ✅ | 31 screens in the catalogue §3: setup/login, remembered device, reinstall, forgot password/user ID, KPI home, categories, chat + clarification, comparison, full-cart optimisation, cart-task progress, notification + handoff, history import, monthly summary, suggestions, permissions, emergency stop, failures, manual fallback |
| 3 | Loading / empty / success / partial / error / offline / permission-denied / retry states | ✅ | Every state reachable through Settings → Developer: mock scenarios; driven by `contracts/fixtures/**` |
| 4 | Design system with tokens (type, colour, spacing, motion, accessibility, dark mode) | ✅ | `android/core/designsystem/.../Theme.kt` (`Tokens`, `SemanticColors`, `Spacing`, `Motion` honouring "remove animations"); light and dark screenshots |
| 5 | Mock APIs and fixtures cover all major responses | ✅ | 71 fixtures, Ajv-validated against `contracts/openapi/openapi.yaml`; Prism mock server (`npm run mock`); strict Kotlin decode test |
| 6 | Analytics event names and payload schemas frozen | ✅ | `contracts/schemas/analytics-event.schema.json` (27 events, closed property list); `LocalAnalyticsCollector` drops non-allowlisted keys and free text (tested) |
| 7 | Accessibility review: TalkBack, touch targets, focus order, contrast, reduced motion | 🟡 | Automated: Google Accessibility Test Framework over 15 screens/states in light and dark, at 1× and 2× font, on API 34 and 36 — 0 errors (§3a); 48dp touch-target test; content descriptions on icons; merged KPI semantics; live regions on status changes; reduced-motion token. **Manual TalkBack walkthrough and focus-order check on a device have not been done.** |
| 8 | Device testing: Android versions, screen sizes, orientation, lifecycle interruption | 🟡 | Emulator matrix: Pixel 6 on API 26, 30, 34, 36 and Pixel Tablet on API 36 — full journey, Keystore, notifications, rotation, accessibility audit (§3a). **Not yet covered:** small phone, foldable, physical devices, process death during a task. |
| 9 | Product, design, engineering, security, QA sign-off | ⛔ | §4 below |
| 10 | Tagged baseline `frontend-freeze-v1.4` | ⛔ | Created only after §4 is complete (command in §5) |

## 2. Audit addendum v1.1 §3 checklist

| Item | Status |
|---|---|
| `frontend-freeze-v1` tag exists | ⛔ To be created as `frontend-freeze-v1.4` (D-008) after sign-off |
| Design-system tokens documented | ✅ `Theme.kt` + this pack |
| All categories: Travel, Food ordering, Grocery | ✅ plus Shopping behind `home.category.shopping` (D-004) |
| KPI summary above category selection | ✅ UI test `homeShowsKpisFirstThenCategoryCards` |
| Login, recovery, reinstall, consent, emergency-stop, failure, manual-fallback states | ✅ |
| Full-cart comparison and recommended-app switch flow | ✅ UI test `switchingFromSelectedAppAsksForConfirmation` |
| Fixtures: cheapest, unavailable, stale, partial, incomparable | ✅ plus price-changed, manual-only, in-progress |
| Screen readers and focus order tested | 🟡 automated ATF audit on API 34/36; manual TalkBack and focus order pending |
| Analytics event schemas frozen | ✅ |
| Deep-link and notification states tested | 🟡 cart-ready notification posted on the tasks channel with a generic lock-screen version (asserted on API 26–36); deep-link resolution needs the backend |
| Approvals recorded | ⛔ |

## 3. Automated evidence (run 22 Nov 2026)

| Check | Command | Result |
|---|---|---|
| OpenAPI lint (Spectral, custom correlation-ID rule) | `cd tools/contract-check && npm run lint` | 0 warnings/errors |
| Fixture + schema validation, contract invariants | `npm run validate` | 71 fixtures valid, 6 schemas compile, invariants hold |
| Kotlin unit tests (`core:data`) | `./gradlew :core:data:test` | 23/23 pass |
| Compose UI acceptance tests (Robolectric) | `./gradlew :app:testDebugUnitTest` | 8/8 pass, plus screenshot tour |
| No hardcoded copy in Kotlin (D-014) | `NoHardcodedCopyTest` | pass; 575 string/plural resources; verified to fail on a probe literal |
| Debug APK | `./gradlew :app:assembleDebug` | builds |
| Claude API key / secret scan of APK and repo | grep for `sk-ant-`, `ANTHROPIC_API_KEY`, `x-api-key`, `api.anthropic.com` | no matches |

### 3a. Device matrix (final run 25 Nov 2026, `tools/device-matrix.sh`, all entries in one run)

Emulators: Pixel 6 (1080×2400, 420dpi) and Pixel Tablet (2560×1600, 320dpi, landscape by default), `google_apis` arm64 images, animations off. Each level runs the full instrumented suite, then the accessibility audit again at 2× font size.

| API / device | Android | Device suite | A11y audit @1× | A11y audit @2× font |
|---|---|---|---|---|
| 26 | 8.0 | 15/15 pass, audit skipped¹ | skipped¹ | skipped¹ |
| 30 | 11 | 15/15 pass, audit skipped¹ | skipped¹ | skipped¹ |
| 34 | 14 | 16/16 pass | 0 errors, 15 screens/states | 0 errors |
| 36 | 16 | 16/16 pass | 0 errors, 15 screens/states | 0 errors |
| 36 Pixel Tablet | 16 | 16/16 pass | 0 errors, 15 screens/states | 0 errors |

The device suite (16 tests): the 8 shared Compose acceptance tests, 4 Android Keystore tests (round trip, no plaintext token, tampered ciphertext → signed out and wiped, clear), the end-to-end journey (register → home → grocery chat → clarification → full-cart comparison → switch confirmation → approval → automation → cart ready → notification → handoff) through the real `MainActivity`, two rotation tests (typed input survives; the emergency Stop control survives rotation and cancels the task), and the accessibility audit.

¹ On API 26 and 30 the Accessibility Test Framework cannot see Compose elements; a built-in canary (an unlabelled 20dp button that must be flagged) detects this and skips the audit rather than reporting a false pass. On API 34+ the canary must fire or the test fails.

Bugs the device run found and fixed:
1. **Emergency Stop partly hidden in landscape** behind the navigation bar (API 26). Side system-bar and cutout insets are now applied once in `MainActivity`.
2. **Tablet: "Compare full cart" hidden behind the on-screen keyboard.** The chat list ran behind the input bar, so scrolling to the button left it under the keyboard. The list now ends at the input bar. (Phone emulators use a hardware keyboard, so they never showed it.)
3. **2× font:** spend total broke mid-number ("₹12,47 / 0"); provider names broke mid-word ("BigBask / et"); the "Recommended" badge broke mid-word. KPIs now stack when columns are too narrow, spend bars switch to a two-line layout, and badges wrap as a unit.

Test-side fixes during the run: rotation now turns the display (as a user does) instead of forcing `requestedOrientation`, which hung intermittently on API 36; the keyboard is hidden without Espresso (which needs window focus); the journey waits for Send to be enabled; failures save a `FAILED-<test>` screenshot.

Observation for design: on the tablet the phone layout stretches to full width (cards and spend bars up to 2,400px wide in landscape). It is usable and passes every check, but there is no tablet-specific layout or maximum content width.

Screenshots of every step and screen, per API level, are written to `android/build/device-matrix/apiNN/` (not committed); a curated set is in `docs/phase2/screenshots/device/`.

Contract invariants enforced by `validate.mjs`: "cheapest" only with complete coverage and no missing fees; every provider total equals its components; no negative totals; `price_changed` carries the previous total; `CART_READY` requires a verified cart; prohibited actions are never planned; handoff URLs are opaque tokens; recovery responses have identical shapes.

Safety behaviour covered by `FakeCartTaskRepositoryTest` / `AutomationPolicyTest`: no action before approval; partial approval rejected; duplicate approval idempotent; emergency stop halts with no later events; checkpoint approve/reject; unanswered checkpoint times out to `FAILED_SAFE`; sign-in prompt handed to the user then resumed; payment screen → `BLOCKED_POLICY`; unknown screen → `FAILED_SAFE`; consent, provider gate and global pause enforced; handoff only after a verified cart; terminal states never restart.

Screenshots: `docs/phase2/screenshots/` (welcome, login, home light/dark, comparison complete/partial/incomparable, consent, approve, checkpoint, cart ready).

## 4. Known gaps before sign-off

1. **Manual accessibility pass**: TalkBack walkthrough and focus order on a device. (2× font and automated contrast/touch-target/label checks are done on API 34/36, §3a.)
2. **Device matrix**: API 26/30/34/36 on a Pixel 6 emulator done (§3a). Still needed: small phone, foldable, a physical device, and process death during a running task. Tablet (Pixel Tablet, API 36) done; whether tablets need their own layout is a design decision.
3. ~~Copy lives in Kotlin~~ **Resolved (D-014):** all user-facing copy is in `strings_*.xml` with plurals; a test blocks regressions. Translations themselves are not started.
4. Conversation history opens a fresh chat in that category; past messages are not replayed (needs `GET /v1/chat/sessions/{id}` wiring).
5. Encrypted local cache (Room + SQLCipher) deferred to Phase 3 (D-012).
6. Handoff tokens from notifications resolve to Home in the mock; real resolution needs the backend.
7. No usability testing with real users yet.

## 5. Sign-off record

| Role | Name | Decision | Date |
|---|---|---|---|
| Product | | | |
| Design | | | |
| Engineering | | | |
| Security | | | |
| QA | | | |

After all five approve:

```bash
git tag -a frontend-freeze-v1.4 -m "Frontend freeze v1.4 — see docs/phase2/freeze-evidence.md"
```

From then on, frontend changes follow `docs/phase2/change-request-template.md`.
