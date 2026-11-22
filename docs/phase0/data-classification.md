# Data Classification and Data Map (Phase 0 draft)

India-first (DPDP-aware) baseline. Source: audit §7, §13; PRD v1.4 §11.

## 1. Classification

| Data | Class | Where it lives | Leaves device? | Retention (default) | Never in |
|---|---|---|---|---|---|
| Password | Secret | Typed in auth UI only; sent once over TLS | Yes, to `/auth/*` only | Not stored client-side; Argon2id hash server-side | Logs, analytics, Claude, local storage |
| Access / refresh token | Secret | Android Keystore-backed encrypted prefs | Yes, as auth header | Access ≤15 min; refresh rotating | Logs, analytics, URLs, crash reports |
| User ID | Personal | Server DB; client session | Yes | Account lifetime | Analytics (use pseudonymous ID) |
| Email | Personal | Server DB (encrypted / access-controlled) | Yes, at registration/recovery | Account lifetime | Logs, analytics, Claude |
| Chat content | Personal / sensitive | Server (redacted), client memory | Yes | 30 days unless saved | Logs (raw), analytics |
| Order history (normalized) | Sensitive personal | Device; server only with upload consent | Only with consent | Until user deletes | Claude (raw), logs |
| Raw partner screen text | Sensitive | Device memory during parse only | **No** (default) | Discarded after parse | Anywhere persistent |
| Addresses / destinations | Sensitive / location | Device; server only for active task | Task-scoped | Task lifetime | Logs, analytics, notifications |
| Comparison snapshot | Business / personal | Server (encrypted) | Yes | 90 days | Analytics (except aggregate) |
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
