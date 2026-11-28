# Data Classification and Data Map (Phase 0 draft)

India-first (DPDP-aware) baseline. Source: audit §7, §13; PRD v1.4 §11.

## 1. Classification

| Data | Class | Where it lives | Leaves device? | Retention (default) | Never in |
|---|---|---|---|---|---|
| Password | Secret | Typed in auth UI only; sent once over TLS | Yes, to `/auth/*` only | Not stored client-side; Argon2id hash server-side | Logs, analytics, Claude, local storage |
| Access / refresh token | Secret | Android Keystore-backed encrypted prefs | Yes, as auth header | Access ≤15 min; refresh rotating | Logs, analytics, URLs, crash reports |
| User ID | Personal | Server DB; client session | Yes | Account lifetime | Analytics (use pseudonymous ID) |
| Email | Personal | Server DB (encrypted / access-controlled) | Yes, at registration/recovery | Account lifetime | Logs, analytics, Claude |
| Device model (CR-002) | Personal (low) | Server DB, per session | Yes, at sign-in | Session lifetime, then with the account | Logs, analytics, Claude |
| Chat content | Personal / sensitive | Server (redacted), client memory | Yes | 30 days after last activity (D-025) | Logs (raw), analytics |
| Order history (normalized) | Sensitive personal | Device; server only with upload consent | Only with consent | Until user deletes | Claude (raw), logs |
| Raw partner screen text | Sensitive | Device memory during parse only | **No** (default) | Discarded after parse | Anywhere persistent |
| Addresses / destinations | Sensitive / location | Device only, typed in by the user, encrypted with a Keystore key per user (CR-004); cleared on sign-out of a non-remembered device, on account deletion, or with *Delete all* | Only the chosen label (e.g. "Home", ≤40 chars) with a comparison; street address only to on-device automation in Phase 4 | Until the user deletes it (label: quote lifetime) | Logs, analytics, notifications, Claude, server export |
| Comparison snapshot | Business / personal | Server (encrypted) | Yes | 90 days | Analytics (except aggregate) |
| Device readings and task progress (CR-005) | Business / personal | Server, inside that user's comparison snapshot and cart task | Yes: product names as shown, prices, stated fees, stock, fixed-phrase task events (≤200 chars), adapter key and version. No screen text beyond these fields, no address, no credentials | With the snapshot / task (90 days) | Analytics, Claude, logs (event text), any other user's data |
| Handoff token | Secret bearer | Notification intent extras / app link | Yes | ≤10 min, one-time | Logs |
| Claude API key | Secret | Backend secret manager | Never reaches device | Rotation schedule | APK, repo, client config, logs |
| Payment / card / UPI / OTP / biometric | Prohibited | **Not collected** | — | — | Everywhere |

## 2. Data flow (target state)

```text
[Partner app screens] --(on-device parse, field allowlist)--> [Local normalized orders]
        |                                                            |
   AccessibilityService (task-scoped)                    upload only with consent
        |                                                            v
[Android app] --TLS, bearer token--> [API gateway] --> [Services] --> [PostgreSQL / Redis]
                                                           |
                                               redacted, structured context
                                                           v
                                                    [Claude gateway] --> Claude API
```

## 3. Analytics rules

- Pseudonymous install ID only; no user ID, email, address, order ID, item names or free text.
- Payload values are enums, counts, durations, provider keys and category keys.
- Event schema: `contracts/schemas/analytics-event.schema.json`. Unknown properties are rejected.
