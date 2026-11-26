# CR-001 — Document the error responses the server already returns

```text
CR ID:        CR-001
Title:        Document existing error responses in the OpenAPI contract
Owner:        Akash (project owner) — drafted by Claude      Date raised: 2026-11-26
Reason:       Defect (contract accuracy). Found while building Phase 3: gaps 5, 6, 13, 16 in
              docs/phase3/PLAN.md, plus two found while drafting (login/refresh 400s; no `default`).
```

## Change description

The contract omits error statuses that the frozen behaviour already requires. Nothing new is invented here: every status below is returned today by the Phase 3 backend and is already handled by the app's generic error path (it maps any `ErrorEnvelope` by `code`). This CR makes the contract say so.

1. **A `default` error response on all 39 operations** (`$ref: '#/components/responses/Error'`). Covers `internal_error` (500) and `service_unavailable` (503) — the latter is how Phase 4 operations answer when their feature is off (D-020).
2. **400 `validation_failed`** where the request has a body, query or idempotency key that can be rejected:

   | Operation | What can be invalid |
   |---|---|
   | `POST /v1/auth/login`, `POST /v1/auth/refresh` | malformed or incomplete body |
   | `PUT /v1/me/consents/{consentKey}` | unknown consent key, blank version |
   | `GET /v1/me/activity`, `GET /v1/chat/sessions` | `limit` outside 1–50, unreadable `cursor` |
   | `POST /v1/me/data/export`, `POST /v1/me/data/delete` | missing `Idempotency-Key`, unknown scope |
   | `POST /v1/chat/sessions` | unknown category |
   | `POST /v1/chat/messages` | empty/oversized text, bad context, stale clarification answer |
   | `GET /v1/dashboard/summary`, `GET /v1/dashboard/monthly` | month not `YYYY-MM`, unknown provider/category |
   | `POST /v1/comparison/quotes`, `…/confirm`, `POST /v1/cart-tasks`, `…/approve`, `…/checkpoint`, `…/stop`, `POST /v1/deep-links` | invalid body values |
   | `POST /v1/history/import`, `GET /v1/history/orders`, `GET /v1/recommendations`, `POST /v1/recommendations/{id}/feedback` | invalid body or query values |

3. **Other missing statuses:** `POST /v1/me/data/delete` → 429 (step-up lockout, as export already lists); `POST /v1/chat/sessions` → 403 `forbidden` (category flag off, e.g. Shopping, D-004); `POST /v1/chat/messages` → 404 (session not found or not the caller's); `POST /v1/comparison/quotes/{quoteId}/confirm` and `POST /v1/cart-tasks/{taskId}/stop` → 404 (unknown or another user's ID).

### OpenAPI patch

Applied to every operation:

```yaml openapi-patch all-operations
responses:
  default: { $ref: '#/components/responses/Error' }
```

```yaml openapi-patch
paths:
  /v1/auth/login:
    post: { responses: { '400': { $ref: '#/components/responses/Error' } } }
  /v1/auth/refresh:
    post: { responses: { '400': { $ref: '#/components/responses/Error' } } }
  /v1/me/consents/{consentKey}:
    put: { responses: { '400': { $ref: '#/components/responses/Error' } } }
  /v1/me/activity:
    get: { responses: { '400': { $ref: '#/components/responses/Error' } } }
  /v1/me/data/export:
    post: { responses: { '400': { $ref: '#/components/responses/Error' } } }
  /v1/me/data/delete:
    post:
      responses:
        '400': { $ref: '#/components/responses/Error' }
        '429': { $ref: '#/components/responses/RateLimited' }
  /v1/dashboard/summary:
    get: { responses: { '400': { $ref: '#/components/responses/Error' } } }
  /v1/dashboard/monthly:
    get: { responses: { '400': { $ref: '#/components/responses/Error' } } }
  /v1/chat/sessions:
    get: { responses: { '400': { $ref: '#/components/responses/Error' } } }
    post:
      responses:
        '400': { $ref: '#/components/responses/Error' }
        '403': { $ref: '#/components/responses/Error' }
  /v1/chat/messages:
    post:
      responses:
        '400': { $ref: '#/components/responses/Error' }
        '404': { $ref: '#/components/responses/Error' }
  /v1/comparison/quotes:
    post: { responses: { '400': { $ref: '#/components/responses/Error' } } }
  /v1/comparison/quotes/{quoteId}/confirm:
    post:
      responses:
        '400': { $ref: '#/components/responses/Error' }
        '404': { $ref: '#/components/responses/Error' }
  /v1/cart-tasks:
    post: { responses: { '400': { $ref: '#/components/responses/Error' } } }
  /v1/cart-tasks/{taskId}/approve:
    post: { responses: { '400': { $ref: '#/components/responses/Error' } } }
  /v1/cart-tasks/{taskId}/checkpoint:
    post: { responses: { '400': { $ref: '#/components/responses/Error' } } }
  /v1/cart-tasks/{taskId}/stop:
    post:
      responses:
        '400': { $ref: '#/components/responses/Error' }
        '404': { $ref: '#/components/responses/Error' }
  /v1/deep-links:
    post: { responses: { '400': { $ref: '#/components/responses/Error' } } }
  /v1/history/import:
    post: { responses: { '400': { $ref: '#/components/responses/Error' } } }
  /v1/history/orders:
    get: { responses: { '400': { $ref: '#/components/responses/Error' } } }
  /v1/recommendations:
    get: { responses: { '400': { $ref: '#/components/responses/Error' } } }
  /v1/recommendations/{recommendationId}/feedback:
    post: { responses: { '400': { $ref: '#/components/responses/Error' } } }
```

## Impact analysis

| Area | Impact |
|---|---|
| UX / screens / states | None. The app already renders any `ErrorEnvelope` by code (error copy in string resources, D-014). |
| API contract | Responses added only; no schema, field, error code or status removed or changed. Contract version 1.0.0-draft → 1.0.1. |
| Compatibility & migration | Fully backward compatible: the server already behaves this way; the app already handles it. No fixture changes. |
| Analytics events | None. |
| Tests to add/update | Backend: replace the `assertErrorEnvelope()` workarounds with `assertContract(...)` for these cases (MeTest, DataRequestsTest, ChatTest, GatewaySafetyTest, Phase4StubsTest, AdminApiTest unaffected). Spectral lint and fixture validation must stay clean. |
| Security / privacy | None. Documenting 404 for foreign IDs states existing behaviour: another user's object is indistinguishable from a missing one (threat model A-15). |
| Accessibility | None. |
| Delivery | Contract edit and test updates: under a day of engineering. Proposed: before Phase 4 starts. |

## Revised acceptance criteria

- Every operation lists `default`; the operations above list the added statuses.
- `npm run check` (Spectral + fixtures) passes; backend `ExitGateTest` and `RouteCoverageTest` pass; no backend test uses `assertErrorEnvelope()` for a status that is now documented.

```text
Approvals:  Product [x]  Design [x]  Engineering [x]  Security [x]  QA [x]
            (all five confirmed by Akash, project owner, 26 Nov 2026)
Decision: approved     Decision date: 2026-11-26
Linked commits / tag: commit "CR-001: …" on main (contract 1.0.1)
```
