# CR-005 — Device-reported prices and task progress

```text
CR ID:        CR-005
Title:        Let the phone report the prices it read and the progress of the tasks it runs
Owner:        Akash (project owner) — drafted by Claude      Date raised: 2026-11-28
Reason:       Phase 4 contract gap (docs/phase4/PLAN.md §2). Automation runs on the phone (4.1–4.3), but
              the frozen contract has no operation for the phone to tell the server what it read or did.
              Today device prices and device tasks live only on the phone, so the server-side comparison
              engine (4.4) and server-side cart tasks with signed handoffs (4.5) can't use them.
```

## Change description

The architecture is unchanged: partner apps are driven only on the phone, and the server stays the authority for comparisons and task state. This CR adds the two missing directions of travel, phone → server. In both, the server treats what the phone sends as **untrusted readings**, never as decisions.

### 1. Prices the phone read (`POST /v1/comparison/quotes/{quoteId}/observations`)

1. **The app says up front which providers it will read.** `QuoteRequest` gains optional `device_providers` (at most 3). The server keeps those providers `not_checked` in the quote until a reading arrives. If none arrives within 120 s, the server marks the provider `failed` and leaves coverage incomplete, so the quote never says "Cheapest" (audit §10.2).
2. **The phone sends what it read for one provider, once.** The body is `PriceObservation`:
   - per quote line: `found`, the product `name` as shown, `unit_price` and `in_stock`;
   - the fees the app **states** (`null` means "not stated", never zero);
   - `eta_minutes` if shown;
   - which adapter and version read it;
   - either `outcome: read`, or `outcome: not_read` with a reason code.

   A retry with the same `Idempotency-Key` returns the same quote. A second, different reading for the same provider is refused (409).
3. **The server does all the computing.** Line totals, fees, totals, ranks, coverage, recommendation and savings come from the 4.4 engine, under the existing rules:
   - totals only for complete carts with every fee known;
   - "Cheapest" only with full coverage;
   - unstated fees listed in `unavailable_fields`.

   Nothing computed on the phone is accepted.
4. **Results say where they came from.** `ProviderResult` gains optional `source` (`device` | `server`), so the app can say "Prices read on this phone" without parsing notes.
5. **Readings stay with that one user's comparison.** They are never pooled across users or reused for anyone else's prices. A modified app could send false prices, and they must only ever affect its own user. Pooling would need its own CR and abuse analysis.

### 2. Progress of a task the phone runs (`POST /v1/cart-tasks/{taskId}/progress`)

1. **Only the device session that approved the task can report on it.** That session is identified by the bearer token on `approve`. Any other session gets 403.
2. **The phone sends a `TaskProgressReport` after each step.** It carries:
   - an increasing `report_seq`;
   - the status the phone is now in;
   - `actions_used` and the new timeline events;
   - when relevant, the open checkpoint, the reason it needs the person, the failure, or the cart it read.

   Reports are idempotent by `report_seq`: a repeat gets the same answer, and an older one gets 409.
3. **The server checks every report against the task state machine (D-002).** The phone may move a task only along device transitions:

   | From | To |
   |---|---|
   | APPROVED | RUNNING |
   | RUNNING | CHECKPOINT, NEEDS_USER_ACTION, CART_READY, FAILED_SAFE, BLOCKED_POLICY, CANCELLED |
   | CHECKPOINT, NEEDS_USER_ACTION | RUNNING, CANCELLED |

   It can never set APPROVED, HANDED_OFF or EXPIRED; those stay with `approve`, the handoff and the server clock. An illegal move gets 409 `task_state_conflict`, and the task is not changed.
4. **The server enforces the limits itself.** If `actions_used` exceeds `max_actions`, or time since approval exceeds `max_runtime_seconds`, the server moves the task to FAILED_SAFE, whatever the phone says.
5. **The server checks the cart itself.** A report of CART_READY must include the cart lines the phone read. The server compares them with the approved plan's items and quantities, then sets `verified` and `differs_from_quote` itself. If they don't match, the task becomes FAILED_SAFE with `cart_verification_failed`, not CART_READY.
6. **The response is the server's view of the task, and the phone obeys it.** If the task was stopped elsewhere (another device, Settings → Pause all, an expired approval, or a provider kill switch), the response shows CANCELLED or FAILED_SAFE, and the phone's runner halts before its next interaction. This is how a server-side stop reaches a running task.
7. **Text from the phone is limited and untrusted.**
   - Event descriptions are capped at 200 characters, and reports at 60 events.
   - Product names are capped at 120 characters.
   - None of this text is rendered as markup, sent to Claude, or logged in full.
   - No personal data goes into event text: the runner writes fixed phrases plus product and app names.

### OpenAPI patch

```yaml openapi-patch
paths:
  /v1/comparison/quotes/{quoteId}/observations:
    post:
      tags: [comparison]
      operationId: submitPriceObservation
      summary: Report what the phone read in one provider app for this comparison.
      description: >
        Only for a provider listed in the quote's device_providers, once per provider. The server validates
        the readings and recomputes the quote; it never takes totals, ranks or labels from the device.
        Readings are used only for this user's comparison.
      parameters:
        - $ref: '#/components/parameters/CorrelationId'
        - $ref: '#/components/parameters/IdempotencyKey'
        - { name: quoteId, in: path, required: true, schema: { type: string } }
      requestBody:
        required: true
        content:
          application/json:
            schema: { $ref: '#/components/schemas/PriceObservation' }
      responses:
        '200':
          description: The comparison recomputed with this provider's readings.
          content:
            application/json:
              schema: { $ref: '#/components/schemas/ComparisonQuote' }
        '400': { $ref: '#/components/responses/Error' }
        '401': { $ref: '#/components/responses/Error' }
        '404': { $ref: '#/components/responses/Error' }
        '409': { $ref: '#/components/responses/Error' }
        '410': { $ref: '#/components/responses/Error' }
        '429': { $ref: '#/components/responses/RateLimited' }
        default: { $ref: '#/components/responses/Error' }
  /v1/cart-tasks/{taskId}/progress:
    post:
      tags: [cart-tasks]
      operationId: reportTaskProgress
      summary: Report what the phone did for an approved task.
      description: >
        Only from the device session that approved the task. The server applies the report only along device
        transitions of the task state machine, enforces the task's limits and verifies the cart itself. The
        response is the server's view; if it is CANCELLED or FAILED_SAFE the device halts at once.
      parameters:
        - $ref: '#/components/parameters/CorrelationId'
        - { name: taskId, in: path, required: true, schema: { type: string } }
      requestBody:
        required: true
        content:
          application/json:
            schema: { $ref: '#/components/schemas/TaskProgressReport' }
      responses:
        '200':
          description: The task as the server now holds it.
          content:
            application/json:
              schema: { $ref: '#/components/schemas/CartTask' }
        '400': { $ref: '#/components/responses/Error' }
        '401': { $ref: '#/components/responses/Error' }
        '403': { $ref: '#/components/responses/Error' }
        '404': { $ref: '#/components/responses/Error' }
        '409': { $ref: '#/components/responses/Error' }
        '429': { $ref: '#/components/responses/RateLimited' }
        default: { $ref: '#/components/responses/Error' }
components:
  schemas:
    QuoteRequest:
      properties:
        device_providers:
          type: array
          maxItems: 3
          items: { $ref: '#/components/schemas/ProviderKey' }
          description: >
            Providers whose prices this phone will read itself and report with submitPriceObservation. They stay
            not_checked until a reading arrives; after 120 s without one they are marked failed (CR-005).
    ProviderResult:
      properties:
        source:
          type: [string, 'null']
          enum: [device, server, null]
          description: Where this provider's figures were read; device means on the user's phone (CR-005).
    AdapterRef:
      type: object
      additionalProperties: false
      required: [key, version]
      properties:
        key: { type: string, maxLength: 40 }
        version: { type: string, maxLength: 20 }
    ObservedOffer:
      type: object
      additionalProperties: false
      required: [line_id, found]
      properties:
        line_id: { type: string, description: A line_id of this quote. }
        found: { type: boolean }
        name: { type: [string, 'null'], maxLength: 120, description: Product name as the app shows it. Untrusted text. }
        unit_price: { oneOf: [{ $ref: '#/components/schemas/Money' }, { type: 'null' }] }
        in_stock: { type: boolean, default: false }
    ObservedFees:
      type: object
      additionalProperties: false
      description: Fees the app states. Null means the app didn't state it; the server never assumes zero.
      properties:
        delivery_fee: { oneOf: [{ $ref: '#/components/schemas/Money' }, { type: 'null' }] }
        packaging_fee: { oneOf: [{ $ref: '#/components/schemas/Money' }, { type: 'null' }] }
        platform_fee: { oneOf: [{ $ref: '#/components/schemas/Money' }, { type: 'null' }] }
        small_cart_fee: { oneOf: [{ $ref: '#/components/schemas/Money' }, { type: 'null' }] }
        surge_or_booking_fee: { oneOf: [{ $ref: '#/components/schemas/Money' }, { type: 'null' }] }
        taxes: { oneOf: [{ $ref: '#/components/schemas/Money' }, { type: 'null' }] }
        discounts: { oneOf: [{ $ref: '#/components/schemas/Money' }, { type: 'null' }] }
    PriceObservation:
      type: object
      additionalProperties: false
      required: [provider, captured_at, outcome, adapter]
      properties:
        provider: { $ref: '#/components/schemas/ProviderKey' }
        captured_at: { type: string, format: date-time }
        outcome: { type: string, enum: [read, not_read] }
        not_read_reason:
          type: [string, 'null']
          enum: [service_off, consent_missing, app_not_installed, package_mismatch, login_required, otp_required, captcha, biometric, password_prompt, payment_screen, unknown_screen, unexpected_dialog, timeout, kill_switch, stopped, null]
          description: Required when outcome is not_read.
        adapter: { $ref: '#/components/schemas/AdapterRef' }
        offers:
          type: array
          maxItems: 50
          items: { $ref: '#/components/schemas/ObservedOffer' }
        fees: { oneOf: [{ $ref: '#/components/schemas/ObservedFees' }, { type: 'null' }] }
        eta_minutes: { type: [integer, 'null'], minimum: 0, maximum: 600 }
    TaskProgressReport:
      type: object
      additionalProperties: false
      required: [report_seq, status, actions_used, events, adapter]
      properties:
        report_seq: { type: integer, minimum: 1, description: 'Increases with every report for the task; a repeat gets the same answer, an older one 409.' }
        status:
          type: string
          enum: [RUNNING, CHECKPOINT, NEEDS_USER_ACTION, CART_READY, FAILED_SAFE, BLOCKED_POLICY, CANCELLED]
          description: The state the device is now in; applied only along device transitions of the task state machine.
        actions_used: { type: integer, minimum: 0 }
        events:
          type: array
          maxItems: 60
          description: Events since the last accepted report, continuing its seq numbers. Descriptions are at most 200 characters.
          items: { $ref: '#/components/schemas/TaskEvent' }
        checkpoint: { $ref: '#/components/schemas/CartTask/properties/checkpoint' }
        needs_user_action: { $ref: '#/components/schemas/CartTask/properties/needs_user_action' }
        failure: { $ref: '#/components/schemas/CartTask/properties/failure' }
        cart: { $ref: '#/components/schemas/CartTask/properties/cart' }
        adapter: { $ref: '#/components/schemas/AdapterRef' }
```

## Impact analysis

| Area | Impact |
|---|---|
| UX / screens / states | **None on frozen screens.** The comparison and task screens already show these states. The note "Prices read on this phone from …" can come from `source` instead of the note text. Showing the accessibility-service status on the consent screen is a separate CR (see the 4.2 notes). |
| API contract | Two new operations, five new schemas (`AdapterRef`, `ObservedOffer`, `ObservedFees`, `PriceObservation`, `TaskProgressReport`), and two optional fields (`QuoteRequest.device_providers`, `ProviderResult.source`). No changed or removed fields, and no new error codes: `validation_failed`, `forbidden`, `not_found`, `task_state_conflict`, `quote_expired` and `rate_limited` already cover every case. Contract version → **1.4.0**. |
| Compatibility & migration | Additive. The server must accept the new field and operations before an app that uses them ships. Older apps don't send `device_providers`, and their comparisons and tasks behave as today. No server data to migrate. |
| Analytics events | None new. Existing task and comparison events are unchanged; no prices, product names or event text go into analytics. |
| Tests to add/update | **Contract tests:** both operations, including every error path. **Server, observations:** a reading for a provider not in `device_providers` gets 409; a second reading 409; an expired quote 410; foreign `line_id`s or a wrong currency 400; unstated fees stay unavailable and block "Cheapest"; the 120 s deadline marks a provider failed; one user's readings never change another user's quote (A-15). **Server, progress:** a report from another session gets 403; illegal transitions 409, with the task unchanged; `report_seq` replay is idempotent; limits are enforced server-side; a mismatched cart becomes FAILED_SAFE; a stop elsewhere is returned to the device; event text is length-limited and never logged in full. **App:** HTTP repositories behind the existing interfaces; the runner halts when a report comes back CANCELLED; reports retry, and stop never waits for the network. |
| Security / privacy | **Threat model:** new entry A-16, *forged device readings or progress*. A modified app could report false prices or progress. Mitigations: readings and progress affect only that user's own quote and task; nothing is pooled across users; the server recomputes totals, verifies carts and enforces limits; only the approving session may report; strict schemas and size limits. A-06, A-07 and A-09 are unchanged, and are strengthened by server-side verification and a remote stop path. **Data classification:** observations and task events join "Comparison snapshot" and "Cart task" (server, encrypted, 90 days). They contain product names, prices and fixed phrases, never addresses, credentials or screen text beyond the fields listed. |
| Accessibility | None (no UI change). |
| Delivery | Backend ~3–4 days (engine integration is 4.4, task store is 4.5). App ~2 days (HTTP repositories, report queue). QA ~1 day. Unblocks slices 4.4 and 4.5. |

## Revised acceptance criteria

- With `device_providers` set, a comparison shows those providers as being checked until the phone reports, then shows the server-computed figures for them with `source: device`. A provider the phone couldn't read appears as `failed`, and the comparison never says "Cheapest" while it is missing.
- No total, rank, label or verification result from the phone is ever stored as the server's own; every one is recomputed.
- A device task's timeline, checkpoints, needs-you state, cart and outcome match on the phone and on the server. A task stopped on the server halts on the phone before its next interaction.
- Reports from another session, illegal transitions, replays and oversize bodies are refused, and tests cover each case.

```text
Approvals:  Product [x]  Design [x]  Engineering [x]  Security [x]  QA [x]
            (all five confirmed by Akash, project owner, 28 Nov 2026)
Decision: approved     Decision date: 2026-11-28
Linked commits / tag:
```

## Not in this CR

- **History import upload (slice 4.6).** The contract can start, poll and list imports, but has no operation for the phone to upload the normalised orders it parsed. That needs its own CR together with the privacy review (R-05), so the upload's consent and field allowlist are decided with it. Likely CR-006.
- **Pooling device readings across users** (e.g. a shared price cache). Excluded above; it would need its own CR and abuse analysis.
