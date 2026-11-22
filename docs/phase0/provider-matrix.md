# Provider Enablement Matrix (Phase 0 draft)

Every provider is independently feature-flagged (`provider.<key>.enabled`) and kill-switchable (`provider.<key>.kill`). **No provider is enabled in production** until its approval record (audit v1.2 addendum) is complete. All values marked TBD are unknown and require validation in the feasibility spike; none are assumed.

| Key | Provider | Category | Cart automation | History import | Cart deep link | Official API | Policy review | Terms review | Default flag | Fallback |
|---|---|---|---|---|---|---|---|---|---|---|
| `uber` | Uber | Travel | Ride setup only | Required | TBD | TBD | Pending | Pending | off | Manual |
| `ola` | Ola | Travel | Ride setup only | Required | TBD | TBD | Pending | Pending | off | Manual |
| `rapido` | Rapido | Travel | Ride setup only | Required | TBD | TBD | Pending | Pending | off | Manual |
| `zomato` | Zomato | Food | Required | Required | TBD | TBD | Pending | Pending | off | Manual |
| `swiggy` | Swiggy | Food | Required | Required | TBD | TBD | Pending | Pending | off | Manual |
| `blinkit` | Blinkit | Grocery | Required | Required | TBD | TBD | Pending | Pending | off | Manual |
| `bigbasket` | BigBasket | Grocery | Required | Required | TBD | TBD | Pending | Pending | off | Manual |
| `swiggy_instamart` | Swiggy Instamart | Grocery | Required | Required | TBD | TBD | Pending | Pending | off | Manual |
| `amazon` | Amazon | Shopping | Required | Required | TBD | TBD | Pending | Pending | off | Manual |
| `flipkart` | Flipkart | Shopping | Required | Required | TBD | TBD | Pending | Pending | off | Manual |
| `zepto` | Zepto | Grocery | — | — | — | — | — | — | off, hidden (D-005) | — |

Android package names are intentionally **not** recorded here until verified on a device during the spike (PRD Part B rule 8: do not invent provider details).

In Phase 1 mock builds, all providers except Zepto are shown with fixture capability data so every UI state can be exercised.

## Provider approval record (template)

```text
Provider:                      Adapter version:          Owner:
Capabilities:                  Known limitations:
Package + signature verified:  [ ] date / device
Accessibility/policy review:   [ ] reviewer / date / outcome
Terms review:                  [ ] reviewer / date / outcome
Privacy / data-flow review:    [ ] reviewer / date / outcome
Security review:               [ ] reviewer / date / outcome
Test evidence (app × Android): [ ] link
Kill switch tested:            [ ] date
Manual fallback tested:        [ ] date
Monitoring + alerts:           [ ] dashboard link
Incident runbook:              [ ] link
Sign-off: product / eng / security / privacy-legal / ops
```
