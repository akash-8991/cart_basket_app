# Feasibility Spike Plan (Phase 0)

**Goal:** turn the TBD cells in the provider matrix into measured facts before Phase 4 investment. Run on real devices with test accounts owned by the team. **No payment, OTP, CAPTCHA or biometric step is automated during the spike.**

## Questions to answer per provider

| # | Question | Pass criterion | Method |
|---|---|---|---|
| Q1 | Package name and signing certificate | Recorded and verified | `adb shell pm list packages`, `apksigner verify --print-certs` on the installed APK |
| Q2 | Are search, item and cart screens exposed as semantic accessibility nodes? | Stable resource IDs or content descriptions for search box, result item, add button, cart total | Accessibility node dump on 2 app versions |
| Q3 | Can a cart line be added via semantic nodes only? | 10/10 runs without coordinates | Prototype AccessibilityService in a throwaway branch |
| Q4 | Time to capture a 3-item cart price | p50 and p95 recorded | Stopwatch over 10 runs |
| Q5 | Which fees are visible before payment? | Field list per provider | Manual inspection |
| Q6 | Is there a cart deep link or app link? | Documented URI or "none" | Manifest inspection of intent filters, manual test |
| Q7 | Where is order history and which fields are visible? | Field list | Manual inspection |
| Q8 | Security prompts encountered (login, OTP, CAPTCHA) | Frequency recorded | Spike runs |
| Q9 | Does the provider's terms of service restrict automation? | Legal note | Legal review |

## Decision rule

- Q3 fails, or Q9 prohibits automation → provider is **manual-only** (comparison via user-entered prices or none; handoff opens the app).
- Q4 p95 > 60 s for 3 providers combined → comparison runs as a **background task with notification**, not inline in chat (UI already supports `comparison.status = in_progress`).
- Q6 = none → handoff opens the app home with manual instructions (`deep_link.strategy = app_launch`).

## Policy review (blocking for Phase 4)

Google Play's AccessibilityService rules for apps that are not accessibility tools must be reviewed against current policy text by the person responsible for policy, with the prominent-disclosure and consent screens (S-TASK-01) as evidence. The outcome is recorded as risk R-01.
