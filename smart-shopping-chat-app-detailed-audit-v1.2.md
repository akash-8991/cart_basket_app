# Smart Shopping Chat Assistant — Detailed Application Audit

**Document type:** Senior software engineering audit and production-readiness checklist  
**Base product document:** Smart Shopping Chat Assistant PRD and Claude Desktop Master Prompt v1.2  
**Platform:** Android mobile app + secure backend  
**Audit date:** 18 November 2026  
**Status:** Design-stage audit; implementation evidence is not yet available

> **Assumptions used because audit-scope questions were not answered:** full-stack audit, cloud-neutral architecture, India DPDP + GDPR-aware privacy controls, PCI-scope reduction, and combined architecture review plus implementation audit. The audit is written for a production-grade system using a secure backend for the Claude API and user-approved Android automation.

---

## 1. Executive audit conclusion

The product is feasible as a consent-driven Android assistant, but the highest risks are not the chat interface. They are:

1. Android Accessibility/automation policy and partner-app terms.
2. Credential, session, and personal purchase-data protection.
3. Safe automation when partner-app UI changes unexpectedly.
4. Accuracy and transparency of cross-app price comparison.
5. Preventing prompt injection or malicious content from partner apps and order history.
6. Preventing abuse of the Claude backend, app automation, deep links, and account-recovery flows.
7. Reinstall/device recovery without storing plaintext passwords.
8. Maintaining a secure boundary so the Claude API key never reaches the APK or client logs.

**Release recommendation:** do not launch broad automation until a limited provider pilot passes security review, abuse testing, accessibility-policy review, privacy review, and failure-injection testing. Start with read-only history import and one provider per category, then expand.

---

## 2. Audit scope

### 2.1 In scope

- Android application architecture and lifecycle.
- Backend APIs, authentication, authorization, rate limiting, and session management.
- Claude API gateway and prompt/data controls.
- Partner-app adapters and user-approved UI automation.
- Price comparison and entire-cart optimization.
- Order-history scraping/import and analytics.
- KPI dashboard, category navigation, notifications, and deep links.
- Data security, privacy, retention, deletion, and consent.
- Secure SDLC, dependency management, CI/CD, observability, incident response, and disaster recovery.
- Abuse cases, guardrails, threat model, testing, and production release gates.

### 2.2 Out of scope

- Payment processing and storage of payment credentials.
- Partner-app internal implementation.
- Legal sign-off or a formal certification.
- Full penetration-test execution.
- Formal accessibility certification.
- Final cloud-specific configuration.

---

## 3. System context and trust boundaries

### 3.1 Recommended logical components

1. **Android app**
   - Authentication UI.
   - Chat UI.
   - KPI dashboard.
   - Category launcher.
   - Consent and automation controls.
   - Local encrypted cache.
   - Local order-history parser.
   - Accessibility/automation controller.
   - Notification and deep-link handler.

2. **API gateway**
   - TLS termination.
   - Request authentication.
   - WAF/bot controls.
   - Schema validation.
   - Rate limits.
   - Correlation IDs.
   - Abuse detection.

3. **Identity service**
   - User ID/password authentication.
   - Password reset and user-ID retrieval.
   - Device/session management.
   - Step-up authentication for sensitive actions.
   - Account lockout and anomaly detection.

4. **Conversation/orchestration service**
   - Intent extraction.
   - Clarification state.
   - Task state machine.
   - Provider selection.
   - User confirmation enforcement.

5. **Claude gateway**
   - Server-side API key.
   - Model routing.
   - Prompt templates.
   - Input/output policy checks.
   - Token and cost limits.
   - Redaction.
   - Structured response validation.
   - Audit metadata.

6. **Provider adapter service**
   - Capability registry.
   - Comparison requests.
   - Provider-specific schemas.
   - Cart task instructions.
   - Deep-link generation.
   - Fallback handling.

7. **Analytics service**
   - Normalized order records.
   - Monthly aggregates.
   - Repurchase features.
   - Recommendation explanations.

8. **Data stores**
   - Relational transactional database.
   - Encrypted sensitive-data store.
   - Queue/event bus.
   - Cache.
   - Immutable audit-log store.
   - Metrics/logging/tracing platform.

### 3.2 Trust boundaries

| Boundary | Main risk | Required controls |
|---|---|---|
| Android app ↔ backend | Token theft, tampering, replay | TLS, certificate validation, short-lived access tokens, refresh rotation, device binding, replay protection |
| Android app ↔ partner app | Unauthorized actions, data leakage | Explicit consent, app allowlist, package verification, narrow action scopes, visible status, emergency stop |
| Backend ↔ Claude API | Secret exposure, prompt injection, data leakage | Server-side key, redaction, structured prompts, output validation, model gateway, egress restrictions |
| Backend ↔ provider adapters | Incorrect prices, malicious content, unstable interfaces | Adapter isolation, schema validation, timestamps, confidence, provider coverage, kill switches |
| Order history ↔ analytics | Excessive collection, sensitive inference | Data minimization, local parsing, consent, provenance, retention/deletion controls |
| Notification/deep link ↔ partner app | Link tampering, stale cart, unintended app | Signed short-lived token, package-targeted intent, no sensitive URL data, expiry and one-time use |

---

## 4. Threat model

### 4.1 Primary assets

- User ID and email address.
- Password-derived authentication material.
- Refresh tokens and device sessions.
- Order history and purchase patterns.
- Addresses, locations, destinations, and preferences.
- Chat content and generated plans.
- Cart task state.
- Provider pricing snapshots.
- Claude API key and model configuration.
- Audit logs and consent records.
- Notification/deep-link tokens.
- Analytics and recommendation features.

### 4.2 Adversaries

- Unauthenticated internet attacker.
- Credential-stuffing attacker.
- Malicious or compromised Android app.
- User with a rooted or instrumented device.
- Malicious partner-app screen text or order content.
- Prompt-injection content embedded in an item name, restaurant name, address, or order history.
- Insider with excessive backend access.
- Abusive user attempting to consume Claude or automation resources.
- Attacker replaying cart links or task approvals.
- Supply-chain attacker through dependencies or CI/CD.

### 4.3 High-priority abuse cases

| Abuse case | Severity | Mitigation |
|---|---:|---|
| Password spraying against user IDs | Critical | IP/device/account rate limits, progressive backoff, breached-password checks, anomaly detection |
| Account enumeration through reset/retrieval | High | Uniform responses, verified recovery channel, throttling, monitoring |
| Stolen refresh token used on another device | Critical | Rotation, reuse detection, device binding, revocation, secure storage |
| Claude API key extracted from APK | Critical | Never ship key; server-only gateway; CI secret scanning |
| Prompt injection in product/order text | High | Treat all external text as untrusted data; delimit fields; fixed tool policy; output schema validation |
| Silent cart mutation | Critical | Per-task explicit confirmation; signed approval; visible action preview; action log; emergency stop |
| Wrong-provider or wrong-cart recommendation | High | Complete-cart snapshot, timestamps, assumptions, confidence, final user confirmation |
| Replay of cart handoff link | High | Short TTL, one-time nonce, audience/package binding |
| Accessibility service abused for unrelated apps | Critical | Package allowlist, action allowlist, runtime checks, kill switch, consent logs |
| Data exfiltration from order-history screens | Critical | Local parsing, field allowlist, no raw screenshots by default, explicit upload consent |
| Notification leaks sensitive order details | Medium | Generic notification text; protected content behind device unlock |
| Queue flooding / Claude cost abuse | High | Per-user budgets, concurrency limits, quotas, circuit breakers |
| Partner UI change causes unintended action | Critical | Semantic selectors, checkpoints, stop-on-unknown-screen, canary tests |
| Malicious dependency or build artifact | High | Lockfiles, provenance, signed builds, SBOM, dependency scanning |

---

## 5. Authentication and account recovery audit

### 5.1 Required design

- User creates **user ID + password + email** at first setup.
- Server stores only a memory-hard password hash such as Argon2id or an approved equivalent.
- Never store plaintext password, reversible password encryption, password in analytics, or password in logs.
- Store email normalized and encrypted or access-controlled as appropriate.
- Login uses user ID + password.
- App updates use a secure remembered session.
- Reinstall must use a secure recovery or re-authentication flow; do not promise password recovery from local storage.
- Forgot password requires user ID + matching registered email, followed by a verified recovery step.
- Forgot user ID requires registered email and a verified recovery channel.
- Use generic responses to avoid account enumeration.

### 5.2 Session controls

- Access token: short lifetime, preferably 5–15 minutes.
- Refresh token: rotating, revocable, device-bound where practical.
- Store tokens only in Android Keystore-backed encrypted storage.
- Detect refresh-token reuse and revoke the token family.
- Maintain device/session list with last seen, platform, app version, approximate location, and revoke action.
- Revoke all sessions after password reset, suspicious recovery, or account takeover signal.
- Require step-up authentication before:
  - changing email;
  - changing password;
  - exporting data;
  - enabling broad automation;
  - deleting the account;
  - changing privacy mode.

### 5.3 Rate limits

Suggested initial limits; tune using production telemetry:

| Endpoint/action | Suggested limit |
|---|---:|
| Login per account | 5 failures / 15 min, then progressive backoff |
| Login per IP/device | 20 attempts / 15 min |
| Password reset request | 3 / hour per account and 10 / hour per IP |
| User-ID retrieval | 3 / hour per email and 10 / hour per IP |
| Recovery-code/token verification | 5 failures / 15 min |
| Session refresh | 30 / hour per device |
| Account creation | 3 / hour per IP/device |
| Sensitive profile changes | 5 / hour per account |
| Chat requests | 60 / hour per user initially |
| Automation tasks | 10 / hour and 30 / day per user initially |
| Order-history imports | 2 full imports / day per provider |
| Price comparisons | 30 / hour per user; cache identical requests |
| Claude tokens | Per-user daily budget plus system-wide budget |

Do not rely only on IP-based limits. Combine account, device, IP, ASN, region, endpoint, and behavioral signals.

---

## 6. Authorization model

Use least privilege and explicit scopes.

### 6.1 Suggested roles

- `user`
- `support_readonly`
- `privacy_operator`
- `security_operator`
- `provider_admin`
- `platform_admin`
- `service_account`

### 6.2 Suggested user scopes

- `profile:read`
- `profile:write`
- `history:read`
- `history:import`
- `history:delete`
- `chat:use`
- `comparison:use`
- `automation:preview`
- `automation:execute`
- `cart:handoff`
- `sessions:manage`
- `account:delete`

### 6.3 Controls

- Enforce authorization server-side on every request.
- Use object-level authorization checks for every user-owned resource.
- Never trust user-supplied `userId`, `accountId`, or `taskId` without ownership validation.
- Separate support access from production data access.
- Require approval and time-bound elevation for operator access.
- Log all privileged access with reason and ticket/reference.

---

## 7. Data classification and handling

| Data | Classification | Storage | Retention |
|---|---|---|---|
| Password hash | Highly sensitive | Identity store, strict access | Account lifetime + deletion workflow |
| Email | Personal | Encrypted/controlled DB | Account lifetime, then deletion/anonymization |
| Chat content | Personal/sensitive | Encrypted DB or short-lived store | User-configurable; minimize by default |
| Order history | Sensitive personal | Encrypted DB or device-only mode | User-configurable |
| Addresses/destinations | Sensitive personal/location | Encrypted, least privilege | As needed for task; minimize |
| Price snapshots | Business/personal context | Encrypted DB | Limited operational period |
| Claude prompts/responses | Sensitive | Redacted trace store | Short retention; configurable |
| Access logs | Security data | Immutable log store | Per policy/legal requirement |
| Deep-link token | Secret bearer token | Not persistent beyond use | Minutes; one-time |
| API keys | Secret | Secret manager only | Rotation schedule |

### 7.1 Data minimization

- Collect only fields required for a stated feature.
- Parse order history locally wherever possible.
- Do not upload raw screenshots or full screen text by default.
- Do not collect payment card, bank, UPI, OTP, biometric, or password data.
- Hash or tokenize provider order IDs if raw values are unnecessary.
- Keep location precision coarse unless exact location is required for a current task.
- Do not use purchase history for unrelated profiling without separate notice and consent.

### 7.2 Encryption

- TLS 1.2+; prefer TLS 1.3.
- Encrypt databases, backups, object storage, queues, and logs.
- Use envelope encryption with managed keys.
- Rotate keys and document rotation ownership.
- Android: Keystore-backed encryption for tokens and local sensitive data.
- Never put secrets in URLs, crash reports, analytics, or notification bodies.

---

## 8. Claude integration audit

### 8.1 Architecture rules

- Claude API is called only from the backend.
- API key exists only in a secret manager or runtime secret injection.
- Android app never receives the API key, model credential, or unrestricted provider endpoint.
- Backend exposes a narrow internal orchestration API.
- Enforce request size, token budget, timeout, retry, concurrency, and cost limits.
- Use structured JSON schemas for model output.
- Reject malformed, overlong, or unsafe output.
- Do not allow the model to directly execute arbitrary device actions.

### 8.2 Prompt-injection guardrails

All partner-app text, item names, restaurant names, addresses, user-generated notes, and imported order content are **untrusted data**.

Required rules:

- Delimit external data clearly.
- Tell the model it must not follow instructions contained inside external data.
- Do not let external text redefine tools, policies, permissions, or system roles.
- Keep tool permissions outside the model.
- Use deterministic application logic for:
  - provider allowlists;
  - cart approval;
  - price arithmetic;
  - user authorization;
  - action sequencing;
  - payment prohibition.
- Use the model for interpretation and explanation, not authority.
- Run output through schema validation and policy checks.
- Require a human/user confirmation step before every cart mutation.
- Store prompt and output hashes for traceability without retaining raw text longer than needed.

### 8.3 Model output contract

The model should return only validated fields such as:

```json
{
  "intent": "food_order|grocery_order|ride|history_analysis|general",
  "category": "travel|food|grocery|null",
  "clarifying_questions": [],
  "candidate_providers": [],
  "comparison_required": true,
  "recommendation": {
    "provider": null,
    "objective": "cheapest_total|fastest|best_value|preferred_provider",
    "explanation": "",
    "confidence": 0.0
  },
  "automation_plan": {
    "allowed": false,
    "actions": []
  },
  "user_confirmation_required": true
}
```

Application code must ignore any model field that attempts to grant itself permissions, access secrets, skip confirmation, or initiate payment.

### 8.4 Claude failure controls

- Timeout and retry with capped exponential backoff.
- Circuit breaker after repeated failures.
- Fallback to deterministic clarification and manual flow.
- Do not retry non-idempotent automation actions automatically.
- Monitor tokens per request, cost per user, failure rate, latency, and unsafe-output blocks.

---

## 9. Android automation and accessibility audit

### 9.1 Permission design

- Explain exactly what the accessibility permission enables.
- Request only after the user starts a relevant task.
- Display enabled providers and scopes.
- Provide one-tap disable and emergency stop.
- Stop automation when the app leaves the allowlisted package.
- Never automate payment, OTP entry, biometric confirmation, account recovery, or security challenges.
- Do not bypass CAPTCHA, device lock, or anti-bot controls.

### 9.2 Automation state machine

Recommended states:

`DRAFT → AWAITING_APPROVAL → APPROVED → RUNNING → CHECKPOINT → NEEDS_USER_ACTION → CART_READY → HANDED_OFF`

Terminal states:

`CANCELLED`, `EXPIRED`, `FAILED_SAFE`, `BLOCKED_POLICY`

Rules:

- Every transition is server-authorized where relevant.
- Each action has a unique idempotency key.
- Unknown screen = stop safely.
- Unexpected provider package = stop safely.
- Action timeout = stop and ask user.
- Cart verification must compare expected and observed item/quantity/variant.
- User can cancel at any point.

### 9.3 Provider adapter safety

Each adapter must define:

- Package name and signature verification strategy.
- Supported app versions.
- Screen/semantic-node fingerprints.
- Allowed actions.
- Required user inputs.
- Cart verification method.
- Failure and fallback behavior.
- Kill-switch flag.
- Test fixtures and screenshots/semantic trees in a secure test repository.

Use semantic accessibility nodes instead of screen coordinates whenever possible. Coordinate automation is fragile and must not be used for high-impact actions.

---

## 10. Price comparison and cart optimization audit

### 10.1 Comparison integrity

For every comparison snapshot, store:

- Provider coverage.
- Timestamp and timezone.
- Item/service matching method.
- Pack size, quantity, ride class, pickup, drop, and timing.
- Item subtotal.
- Delivery, packaging, platform, booking, surge, toll, and tax fields.
- Discounts and membership assumptions.
- Availability and substitution status.
- ETA.
- Confidence and missing-data flags.
- User-selected objective.

### 10.2 Guardrails

- Never claim “cheapest” unless all eligible providers were checked and relevant fees were available.
- Use “lowest observed estimate” when data is incomplete.
- Do not compare non-equivalent products or ride classes without clearly labeling differences.
- Recheck stale prices before automation when feasible.
- Show a final confirmation if price changed beyond a configured threshold.
- Entire-cart optimization is the default for multi-item requests.
- Do not automatically split carts in the first release.
- If the recommended app differs from the selected app, ask the user to approve the switch.
- The final partner-app cart is the source of truth for payment.

### 10.3 Arithmetic

- Use application code, not the model, for totals and ranking.
- Use decimal-safe currency arithmetic.
- Record currency and rounding policy.
- Test tax/fee/discount ordering.
- Prevent negative totals and duplicate discounts.
- Keep raw component values for auditability.

---

## 11. API security and contract requirements

### 11.1 General API controls

- OpenAPI contract maintained in source control.
- Strict request/response schemas.
- Content-type enforcement.
- Maximum body size.
- Pagination limits.
- Idempotency keys for task creation and mutations.
- Correlation ID on every request.
- Standard error shape without sensitive details.
- Versioned endpoints.
- Deprecation policy.
- CORS/CSRF controls where applicable.
- WAF and bot protection at the edge.

### 11.2 Example endpoint groups

```text
POST   /v1/auth/register
POST   /v1/auth/login
POST   /v1/auth/refresh
POST   /v1/auth/logout
POST   /v1/auth/forgot-password
POST   /v1/auth/retrieve-user-id
POST   /v1/auth/reset-password
GET    /v1/sessions
DELETE /v1/sessions/{sessionId}

GET    /v1/profile
PATCH  /v1/profile
DELETE /v1/account

POST   /v1/chat/sessions
POST   /v1/chat/sessions/{id}/messages
GET    /v1/chat/sessions/{id}

POST   /v1/comparisons
GET    /v1/comparisons/{id}

POST   /v1/automation/tasks
POST   /v1/automation/tasks/{id}/approve
POST   /v1/automation/tasks/{id}/cancel
GET    /v1/automation/tasks/{id}

POST   /v1/history/imports
GET    /v1/history/orders
DELETE /v1/history/orders/{id}

GET    /v1/dashboard/monthly
GET    /v1/suggestions
POST   /v1/handoffs
```

### 11.3 API acceptance checks

- Unauthorized request returns a generic 401.
- Authenticated but unauthorized resource access returns a safe 403/404 pattern.
- Replay of an idempotency key returns the original result, not a duplicate task.
- Expired handoff token cannot open a task.
- User A cannot access User B’s order, comparison, task, or session.
- Oversized or malformed payload is rejected before expensive processing.
- Provider data cannot inject arbitrary backend actions.

---

## 12. Database and storage audit

### 12.1 Core tables

- `users`
- `user_emails`
- `password_credentials`
- `devices`
- `sessions`
- `recovery_events`
- `consents`
- `provider_catalog`
- `provider_capabilities`
- `chat_sessions`
- `chat_messages`
- `comparison_requests`
- `comparison_snapshots`
- `comparison_lines`
- `automation_tasks`
- `automation_actions`
- `handoff_tokens`
- `orders`
- `order_items`
- `monthly_aggregates`
- `recommendation_features`
- `recommendations`
- `audit_events`
- `security_events`
- `deletion_requests`

### 12.2 Required database controls

- Tenant isolation by `user_id`.
- Foreign keys and restrictive deletes.
- Unique constraints for user ID and normalized email policy.
- Indexes that do not expose sensitive values in logs.
- Encryption at rest.
- Database roles separated by service.
- No direct database access from mobile app.
- Migrations reviewed and reversible where practical.
- Row-level security or equivalent application-enforced authorization.
- Backup encryption and restore testing.
- Retention jobs with audit records.
- Soft delete only where legally/operationally necessary; otherwise hard-delete personal data according to policy.

---

## 13. Privacy, consent, and compliance controls

### 13.1 Consent

Capture versioned, timestamped consent for:

- Account creation and terms.
- Privacy notice.
- Android accessibility automation.
- Each provider/app scope.
- Order-history import.
- Uploading normalized data to backend.
- AI processing.
- Notifications.
- Location use, if applicable.
- Optional personalization.

Consent must be revocable. Revocation must stop future processing and define what happens to existing data.

### 13.2 User rights

Provide mechanisms for:

- Access/export.
- Correction.
- Deletion.
- Consent withdrawal.
- Data retention settings.
- Explanation of recommendation inputs.
- Provider connection revocation.
- Session revocation.

### 13.3 PCI scope

- Do not collect, process, or store payment card, bank, UPI, CVV, OTP, or payment authentication data.
- Do not screen-scrape payment pages.
- Open the partner app for payment.
- Review logs, crash reports, screenshots, and analytics to ensure payment data cannot enter the platform.

### 13.4 Retention baseline

Define and publish retention periods. A reasonable starting policy:

- Raw chat: 30 days unless user saves it.
- Automation action logs: 90 days, with redacted content.
- Comparison snapshots: 90 days.
- Order history: until user deletes it or account is deleted.
- Security logs: according to incident/legal requirements.
- Failed recovery attempts: short operational retention with aggregated security metrics.
- Deleted-account data: purge from primary stores within a defined SLA, then purge backups according to backup lifecycle.

---

## 14. Guardrails and policy enforcement

### 14.1 Hard blocks

The system must block:

- Payment execution.
- Entry or storage of payment credentials.
- OTP entry for partner apps.
- Biometric confirmation.
- CAPTCHA solving/bypass.
- Security-question bypass.
- Account takeover actions.
- Automation outside allowlisted providers.
- Silent cart mutation.
- Unapproved data upload.
- Model-generated arbitrary code execution.
- Model-generated arbitrary device commands.
- Access to unrelated apps or private content.

### 14.2 Soft safeguards

- Ask for clarification when confidence is below threshold.
- Show comparison assumptions.
- Show stale-data warnings.
- Ask before switching providers.
- Require confirmation after material price or item changes.
- Explain missing provider data.
- Offer manual fallback.
- Limit recommendations to evidence-supported history.

### 14.3 Human override

- User can cancel automation.
- User can correct item, quantity, destination, or provider.
- User can disable a provider.
- Support can disable an adapter globally.
- Security team can revoke sessions and disable automation.
- Feature flags must permit rapid rollback.

---

## 15. Logging, monitoring, and observability

### 15.1 Structured logs

Every important event should include:

- timestamp;
- environment;
- service;
- request/correlation ID;
- user pseudonymous ID;
- device/session pseudonymous ID;
- endpoint/action;
- provider;
- task ID;
- result;
- latency;
- error code;
- policy decision.

Never log:

- passwords;
- access/refresh tokens;
- Claude API key;
- full addresses unless explicitly needed and protected;
- payment data;
- raw screenshots;
- full chat/order history by default.

### 15.2 Metrics

Track:

- Login success/failure and lockouts.
- Password reset and retrieval attempts.
- Refresh-token reuse detections.
- Chat latency and failure rate.
- Claude token usage and cost.
- Prompt/output guardrail blocks.
- Comparison coverage and stale-price rate.
- Recommendation acceptance.
- Automation success by provider/app version.
- Unknown-screen stops.
- Cart verification failures.
- Deep-link open success.
- History import success and extraction confidence.
- Data deletion SLA.
- Crash-free sessions.
- ANR rate.
- Battery impact.
- Notification delivery/open rate.

### 15.3 Alerts

Alert on:

- Sudden login failures.
- Account enumeration patterns.
- Token reuse.
- Abnormal Claude spend.
- Provider adapter failure spikes.
- Automation actions outside expected distribution.
- Increase in unknown screens.
- Unauthorized package detection.
- Data export/deletion failures.
- Queue backlog.
- Database replication lag.
- Backup/restore failure.

---

## 16. Secure SDLC and supply-chain controls

### 16.1 Source control

- Protected main branch.
- Mandatory peer review.
- CODEOWNERS for authentication, automation, privacy, and Claude gateway.
- Signed commits or equivalent identity controls.
- No secrets in repositories.
- Secret scanning on push and in CI history.
- Dependency lockfiles committed.

### 16.2 CI/CD gates

Minimum pipeline:

1. Format/lint.
2. Unit tests.
3. Static analysis.
4. Secret scanning.
5. Dependency vulnerability scan.
6. License policy scan.
7. SAST.
8. API contract tests.
9. Android manifest and permission review.
10. Build signing.
11. SBOM generation.
12. Container/image scan.
13. DAST against staging.
14. Migration validation.
15. Release approval.
16. Canary rollout.
17. Automated rollback signal.

### 16.3 Supply-chain controls

- Pin versions.
- Verify package checksums/signatures.
- Use private artifact registry.
- Generate SBOM for every release.
- Track transitive dependencies.
- Define vulnerability SLAs.
- Prefer minimal libraries.
- Review Android accessibility and networking libraries carefully.
- Protect signing keys in HSM or managed secure signing service.
- Separate development, staging, and production credentials.

---

## 17. Testing strategy

### 17.1 Unit testing

Cover:

- Auth validation and password policies.
- Token rotation.
- Rate limiting.
- Account recovery logic.
- Price arithmetic.
- Discount/fee/tax ordering.
- Cart equivalence.
- Recommendation feature calculations.
- Consent transitions.
- Task state transitions.
- Deep-link token expiry.
- Redaction and logging filters.

### 17.2 Integration testing

- Identity provider and database.
- Claude gateway with mocked model responses.
- Provider adapters with fixtures.
- Queue retry and dead-letter behavior.
- Notification and handoff services.
- History parser with representative screen structures.
- Data deletion across all stores.

### 17.3 Android testing

- Supported Android versions and screen sizes.
- App update and reinstall flows.
- Keystore/session behavior.
- Accessibility permission grant/revoke.
- Background/foreground transitions.
- Battery and memory usage.
- Offline and flaky-network behavior.
- Partner app version changes.
- Unknown-screen safe stop.
- Emergency stop.
- Notification deep links.
- Rooted/debug device behavior.

### 17.4 Security testing

- Threat-model review.
- SAST/DAST.
- API authorization tests.
- IDOR/BOLA testing.
- Credential stuffing simulation.
- Replay and token theft simulation.
- Prompt-injection testing.
- Malicious item/restaurant/address strings.
- Log and analytics leakage testing.
- Dependency and container scanning.
- Mobile reverse-engineering review.
- Runtime tamper and certificate-pinning evaluation.
- External penetration test before broad launch.

### 17.5 Chaos and failure testing

- Claude unavailable.
- Provider adapter unavailable.
- Stale prices.
- Partner app not installed.
- Partner app opens wrong screen.
- Network loss during automation.
- App killed mid-task.
- Device reboot mid-task.
- Notification delayed.
- Duplicate tap on approval.
- Queue duplicate delivery.
- Database failover.
- Partial history import.

Expected outcome: safe stop, no duplicate cart mutation, clear user recovery path.

---

## 18. Performance, scalability, and cost

### 18.1 Performance targets

Initial targets to validate:

- App cold start: p95 < 3 seconds on supported mid-range Android devices.
- Auth API: p95 < 500 ms excluding external recovery delivery.
- Chat first response: p95 < 4 seconds for non-comparison requests.
- Comparison response: p95 < 10 seconds, with progress status for slower providers.
- Dashboard load from cached aggregates: p95 < 1.5 seconds.
- Automation status update: visible within 2 seconds of backend event where connectivity permits.

### 18.2 Scalability controls

- Stateless APIs behind load balancing.
- Queue external/provider work.
- Cache provider capability metadata.
- Cache identical comparison requests for short periods.
- Per-user and global concurrency limits.
- Backpressure and circuit breakers.
- Separate interactive traffic from history-import jobs.
- Partition large order-history tables by time/user strategy.
- Use precomputed monthly aggregates.

### 18.3 Cost controls

- Token budgets by user, feature, and day.
- Deterministic preprocessing before model calls.
- Avoid sending raw history when derived features are sufficient.
- Summarize locally or with non-model code where practical.
- Cache repeated explanations.
- Alert on cost anomalies.
- Disable expensive features through feature flags.

---

## 19. Reliability and disaster recovery

### 19.1 Availability design

- Define SLOs for authentication, chat, comparison, history import, and handoff.
- Graceful degradation:
  - chat unavailable → manual category navigation;
  - comparison unavailable → selected provider/manual flow;
  - Claude unavailable → deterministic intent/clarification;
  - notification unavailable → in-app task status;
  - deep link unavailable → partner app + manual instructions.
- Use queues with retry and dead-letter queues.
- Ensure non-idempotent actions are never blindly retried.

### 19.2 Backup and recovery

- Encrypted database backups.
- Point-in-time recovery where supported.
- Separate backup credentials.
- Quarterly restore test at minimum.
- Document RPO/RTO.
- Test deletion propagation to backups according to policy.
- Maintain incident runbooks for:
  - credential leak;
  - provider adapter malfunction;
  - account takeover;
  - data exposure;
  - Claude cost spike;
  - database corruption;
  - signing-key compromise.

---

## 20. Release gates

### Gate 0 — Design approval

- Threat model approved.
- Data map and retention policy approved.
- Accessibility/partner policy review complete.
- API and database design reviewed.
- Claude prompt and output controls reviewed.

### Gate 1 — Internal alpha

- One provider per category.
- Read-only history import.
- No automated cart mutation outside test environments.
- Security logging active.
- Kill switch tested.
- All critical unit/integration tests passing.

### Gate 2 — Controlled pilot

- Explicit user consent.
- Limited allowlisted accounts.
- Automation success and safe-stop metrics monitored.
- External security review complete.
- Provider-specific legal/policy review complete.
- Data deletion and recovery tested.

### Gate 3 — Public beta

- All critical/high security findings closed or accepted by named owner.
- Crash-free and ANR targets met.
- Cost budgets active.
- Abuse monitoring active.
- Support and incident runbooks ready.
- Privacy notices and user controls live.

### Gate 4 — General availability

- Disaster recovery test passed.
- Penetration test completed.
- Adapter rollback tested.
- Release signing and SBOM controls operational.
- 24/7 alert ownership documented.
- Provider coverage and price-comparison claims accurately communicated.

---

## 21. Audit evidence checklist

Engineering must provide:

- Architecture diagram.
- Data-flow diagram.
- Threat model.
- API OpenAPI specification.
- Database schema and migration history.
- Authentication design.
- Password-hashing configuration.
- Token/session design.
- Rate-limit configuration.
- Consent records schema.
- Claude gateway code/configuration.
- Prompt and output schemas.
- Provider adapter registry.
- Automation action allowlist.
- Deep-link token design.
- Privacy notice and retention matrix.
- Data-deletion test evidence.
- CI/CD pipeline configuration.
- SBOM and dependency scan reports.
- Pen-test report.
- SAST/DAST results.
- Restore-test evidence.
- Incident runbooks.
- Provider policy/legal review records.
- Release approval record.

---

## 22. Priority remediation backlog

### P0 — Before any external pilot

1. Secure backend-only Claude API key.
2. Password hashing, session rotation, recovery throttling.
3. Explicit automation consent and hard payment/OTP/CAPTCHA blocks.
4. Provider allowlist and emergency stop.
5. Object-level authorization tests.
6. Data minimization and no raw screen upload by default.
7. Signed short-lived handoff tokens.
8. Prompt-injection and output-schema guardrails.
9. Centralized audit logging without secrets.
10. Kill switch for each provider adapter.

### P1 — Before public beta

1. External penetration test.
2. Full account/device session management.
3. Data export/deletion workflow.
4. Backup restore and disaster-recovery test.
5. Complete comparison snapshot/auditability.
6. Provider version compatibility and canary monitoring.
7. SBOM, signing-key protection, and supply-chain controls.
8. Cost quotas and Claude circuit breakers.
9. Accessibility/partner-policy review.
10. Operational dashboards and alerting.

### P2 — Before general availability

1. Formal SLOs and on-call ownership.
2. Regional data/retention review.
3. Advanced anomaly detection.
4. Automated adapter compatibility testing.
5. Privacy-preserving analytics.
6. Independent review of recommendation fairness and misleading claims.
7. Mature support tooling with time-bound, audited access.

---

## 23. Final audit position

The product should be built as a **permissioned orchestration platform**, not as an unrestricted device-control agent. Deterministic application code must control authorization, arithmetic, provider selection rules, cart approval, and safety boundaries. Claude should interpret, explain, and summarize within a constrained schema.

The safest launch sequence is:

1. Secure authentication and dashboard.
2. Read-only order-history import.
3. Deterministic monthly analytics.
4. Claude-generated explanations and suggestions.
5. Price comparison with transparent snapshots.
6. Manual partner-app handoff.
7. Controlled automation for one provider per category.
8. Broader adapter rollout only after safe-stop and policy evidence.

No production launch should claim guaranteed cheapest pricing or full automation until provider coverage, price freshness, equivalent-item matching, and failure behavior are measurable and visible to the user.



# Frontend Freeze and Delivery-Phase Audit Addendum

**Version:** 1.1  
**Date:** 18 November 2026  
**Purpose:** Add an auditable frontend-first delivery model to the detailed application audit.

## 1. Required delivery control

The project must freeze the Android frontend before full backend implementation. This is a release-governance control, not only a project-management preference.

The audit must verify that:

- the complete navigation and screen-state catalogue exists;
- all major flows run against mocks;
- API, error, analytics, and deep-link contracts are versioned;
- accessibility and device checks are complete;
- freeze sign-off is documented;
- post-freeze changes use formal change control.

## 2. Phase-aligned audit gates

| Phase | Frontend evidence | Backend evidence | Audit gate |
|---|---|---|---|
| 0. Contract | IA, user journeys, threat model, API draft | Domain model, trust boundaries, security baseline | Design review passed |
| 1. Mocked frontend | All screens, fixtures, state coverage, accessibility QA | Mock server and contract schemas | Frontend freeze approved |
| 2. Backend foundation | Frozen tag and integration defect log | Auth, sessions, Claude gateway, audit logs, core APIs | Contract tests passed |
| 3. Controlled integration | No unapproved UX changes | Provider adapters, automation, history import, comparison | Pilot/security gate passed |
| 4. Expansion | Only approved fixes | Provider scale, resilience, monitoring, DR | Production readiness passed |

## 3. Frontend freeze audit checklist

- [ ] `frontend-freeze-v1` tag exists.
- [ ] Design-system tokens are documented.
- [ ] All categories are represented: Travel, Food ordering, Grocery.
- [ ] KPI summary is present above category selection.
- [ ] Login, recovery, reinstall, consent, emergency-stop, failure, and manual-fallback states are implemented.
- [ ] Full-cart comparison and recommended-app update flow are represented.
- [ ] Mock fixtures include cheapest, unavailable, stale, partial, and incomparable results.
- [ ] Screen readers and focus order are tested.
- [ ] Analytics event schemas are frozen.
- [ ] Deep-link and notification states are tested.
- [ ] Product, design, QA, security, and engineering approvals are recorded.

## 4. Post-freeze change control

Allowed without full re-freeze:

- crash fixes;
- security fixes;
- accessibility fixes;
- copy and translation corrections;
- API compatibility fixes;
- telemetry fixes;
- provider-specific resilience fixes that do not alter the user journey.

Requires change request and impact review:

- new screen;
- changed navigation;
- changed category;
- changed authentication flow;
- changed comparison semantics;
- changed automation consent;
- changed data-collection purpose;
- changed notification or deep-link behaviour.

## 5. Audit findings to track

Any implementation that starts broad backend development before frontend freeze should be recorded as a delivery-control finding. Severity is:

- **High:** backend contracts are implemented without an approved frontend state catalogue.
- **High:** frontend changes after freeze bypass change control.
- **Medium:** mock fixtures do not cover failure and partial-success states.
- **Medium:** analytics or deep-link contracts are not versioned.
- **Low:** freeze evidence exists but is not linked to a release tag.

## 6. Definition of aligned delivery

The project is phase-aligned only when each phase has:

1. named frontend outputs;
2. named backend outputs;
3. shared contract artefacts;
4. entry and exit criteria;
5. test evidence;
6. security and privacy review;
7. a decision owner;
8. a recorded go/no-go decision.


---

# Version 1.2 — Confirmed Productionisation Audit Addendum

**Date:** 18 November 2026  
**Confirmed scope:** all listed providers; India-first compliance baseline; full frontend freeze; mandatory no-payment automation, risky-action confirmation, emergency stop, timeout, max-action, unknown-screen halt, and provider kill switches.

## Audit disposition

The following controls are now **mandatory production gates**, not recommendations:

- Provider-by-provider technical, policy, terms, privacy, and security enablement.
- Secure session continuation instead of plaintext password storage or password recovery.
- Frozen visual UI, journeys, UI/API contracts, states, accessibility behaviour, and analytics events.
- Deterministic full-cart price reconciliation.
- Formal automation risk levels and prohibited actions.
- Claude model governance, evaluation, cost controls, structured outputs, and rollback.
- OpenAPI, idempotency, correlation IDs, retries, versioning, and contract tests.
- SLOs, capacity planning, dashboards, alerts, runbooks, and incident ownership.
- Data lifecycle, deletion/export, consent revocation, and device controls.
- SBOM, dependency/secret scanning, signed builds, staged rollout, and rollback.
- Full test pyramid including lifecycle, offline, chaos, prompt-injection, and provider UI-change tests.

## Provider approval record

No provider may enter production without a completed record containing:

- Adapter owner and version.
- Supported capabilities and known limitations.
- Accessibility/policy review.
- Terms review.
- Privacy/data-flow review.
- Test evidence by app version and Android version.
- Kill switch and manual fallback.
- Monitoring dashboard and alert thresholds.
- Incident runbook.
- Product, engineering, security, privacy/legal, and operations sign-off.

## Required rate-limiter classes

Implement and test separate limits for:

- Login, registration, password reset, user-ID retrieval, and session refresh.
- Chat messages and Claude token usage.
- Comparison requests.
- Cart-task creation, approval, stop, and retries.
- Order-history import and rescan.
- Notifications.
- Admin/operational endpoints.

Use per-account, per-device, per-IP, per-provider, and global limits where appropriate. Return standard rate-limit headers, use exponential backoff, and apply circuit breakers to provider and model dependencies. Never reveal whether a user ID or email exists during recovery.

## Non-negotiable security findings

- No Claude API key in APK, logs, analytics, crash reports, or client configuration.
- No plaintext password, provider credential, payment data, OTP, CAPTCHA, or biometric data in app storage or backend.
- Android Keystore-backed secure session storage.
- Short-lived access tokens, rotating refresh tokens, device revocation, session listing, and suspicious-login controls.
- Encryption in transit and at rest with managed key rotation.
- Redaction of chat, provider screens, addresses, order identifiers, and tokens.
- Immutable audit events for consent, automation approval, stop, provider action, data export, and deletion.
- Least privilege for services, operators, and support tooling.

## Go-live recommendation

**Status: Not ready for broad public launch until evidence is produced.**  
Proceed only with a controlled internal build and provider-feasibility validation. All listed providers may remain in the target plan, but each must be independently feature-flagged and must pass the provider approval record before activation.

## Audit evidence package

The release candidate must include:

- Architecture and data-flow diagrams.
- Threat model and risk register.
- OpenAPI and event schemas.
- Database migrations and rollback scripts.
- SBOM and dependency scan.
- Secret-scan report.
- Penetration-test report and remediation evidence.
- Provider approval records.
- Frontend-freeze tag and change log.
- Device/accessibility test report.
- AI evaluation and prompt-injection report.
- SLO dashboard screenshots/export.
- Backup restore and disaster-recovery evidence.
- Incident runbooks.
- Support playbooks.
- Privacy notice, consent records, retention schedule, export/delete evidence.
