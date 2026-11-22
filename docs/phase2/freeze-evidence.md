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
| 7 | Accessibility review: TalkBack, touch targets, focus order, contrast, reduced motion | 🟡 | Automated: 48dp touch-target test on Home; content descriptions on icons; merged KPI semantics; live regions on status changes; reduced-motion token. **Manual TalkBack pass on a device has not been done.** |
| 8 | Device testing: Android versions, screen sizes, orientation, lifecycle interruption | ⛔ | Robolectric only (API 34, 411×891dp and 411×2400dp). **No physical device or emulator matrix run yet.** |
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
| Screen readers and focus order tested | 🟡 automated semantics only; manual TalkBack pending |
| Analytics event schemas frozen | ✅ |
| Deep-link and notification states tested | 🟡 schemas + handler + notifier written; notification delivery not exercised on a device |
| Approvals recorded | ⛔ |

## 3. Automated evidence (run 22 Nov 2026)

| Check | Command | Result |
|---|---|---|
| OpenAPI lint (Spectral, custom correlation-ID rule) | `cd tools/contract-check && npm run lint` | 0 warnings/errors |
| Fixture + schema validation, contract invariants | `npm run validate` | 71 fixtures valid, 6 schemas compile, invariants hold |
| Kotlin unit tests (`core:data`) | `./gradlew :core:data:test` | 23/23 pass |
| Compose UI acceptance tests (Robolectric) | `./gradlew :app:testDebugUnitTest` | 8/8 pass, plus screenshot tour |
| Debug APK | `./gradlew :app:assembleDebug` | builds |
| Claude API key / secret scan of APK and repo | grep for `sk-ant-`, `ANTHROPIC_API_KEY`, `x-api-key`, `api.anthropic.com` | no matches |

Contract invariants enforced by `validate.mjs`: "cheapest" only with complete coverage and no missing fees; every provider total equals its components; no negative totals; `price_changed` carries the previous total; `CART_READY` requires a verified cart; prohibited actions are never planned; handoff URLs are opaque tokens; recovery responses have identical shapes.

Safety behaviour covered by `FakeCartTaskRepositoryTest` / `AutomationPolicyTest`: no action before approval; partial approval rejected; duplicate approval idempotent; emergency stop halts with no later events; checkpoint approve/reject; unanswered checkpoint times out to `FAILED_SAFE`; sign-in prompt handed to the user then resumed; payment screen → `BLOCKED_POLICY`; unknown screen → `FAILED_SAFE`; consent, provider gate and global pause enforced; handoff only after a verified cart; terminal states never restart.

Screenshots: `docs/phase2/screenshots/` (welcome, login, home light/dark, comparison complete/partial/incomparable, consent, approve, checkpoint, cart ready).

## 4. Known gaps before sign-off

1. **Manual accessibility pass**: TalkBack walkthrough, focus order, 200% font scale, contrast spot-check on device.
2. **Device matrix**: at least API 26, 30, 34 and 36; small phone, large phone, tablet; rotation; process death during a task.
3. **Copy lives in Kotlin**, not `strings.xml`. Post-freeze change control allows "copy and translation corrections", which needs resources. **Recommend moving user-facing strings to resources before freezing.**
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
