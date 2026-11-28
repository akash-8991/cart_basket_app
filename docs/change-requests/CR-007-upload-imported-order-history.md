# CR-007 — Upload imported order history

```text
CR ID:        CR-007
Title:        Let the phone report a history import and, with consent, upload the orders it normalised
Owner:        Akash (project owner) — drafted by Claude      Date raised: 2026-11-28
Reason:       Phase 4.6 contract gap. The contract can start an import (startHistoryImport), show its
              progress (getHistoryImport) and list or delete orders, but has no operation for the phone to send
              what it read. Orders are parsed on the phone (threat model A-10), so without one the server's
              import never progresses, and "Save and sync" on the review screen (S-HIST-03) has nothing to call.
```

## Change description

The frozen journey stays as it is. You choose the apps, the fields and the privacy mode (S-HIST-01). The phone reads each app's order history. You review the result (S-HIST-03), then either save it on the phone or save and sync. This CR adds the one missing step: the phone telling the server what happened, and, only with the person's choice and consent, the normalised orders themselves.

1. **New operation `POST /v1/history/import/{sessionId}/batches`.** The phone sends one batch per app, in order (`batch_seq`). A batch says:
   - how far the import got for that app: `running`, `completed`, `failed` or `skipped`;
   - how many orders were found;
   - the confidence, and a reason code if it failed;
   - which adapter (and version) read the app.

   The batch that finishes the import says whether it was **completed or cancelled**.

2. **Orders go up only when everything allows it.**
   - Orders may be included only when all of these hold:
     - the session's `upload_mode` is `normalized_upload`;
     - the person has confirmed on the review screen (`reviewed: true`);
     - `history.<provider>` consent is still granted;
     - the new flag `history.upload.enabled` is on.
   - In `device_only` mode, a batch carries counts only. A batch with orders is refused (409), so nothing personal can reach the server by mistake.

3. **Only the fields the person chose.** Each uploaded order carries only the fields listed in the session's `fields`:
   - order date, items, quantity, amounts, status, location label;
   - plus its confidence and a `source_ref`.

   A field that wasn't chosen must be absent or null, or the batch is refused (400). There is no free text beyond item names (≤120 characters) and the location label (≤40), and no screen text, addresses, phone numbers or payment details.

4. **Duplicates are found without the provider's order ID.**
   - For an upload session, `ImportSession` gains `dedup_salt`, a random value per session.
   - The phone sends `source_ref = SHA-256(dedup_salt + provider + ":" + provider order ID)`.
   - The server keeps only its own keyed hash of that, and treats an order it already has as an update, not a new order. The provider's order ID never leaves the phone.

5. **Totals are reconciled by code, not taken on trust** (PRD §6).
   - The server checks each order's items against its total.
   - A mismatch lowers `extraction_confidence` and is noted. The server never makes up the difference.
   - Statuses must be one of the canonical order states.

6. **Uploaded history affects only that person's account.** As with CR-005, a modified app could send false orders. They change only that user's own dashboard and recommendations, and are never pooled across users.

7. **The switch to turn upload on.** A new flag, `history.upload.enabled` (default off, owner: privacy), is added to `contracts/feature-flags.yaml` and to `GET /v1/config`. It stays off until the privacy review of history import (R-05, DPDP legal basis) is complete. Device-only import does not need it.

### OpenAPI patch

```yaml openapi-patch
paths:
  /v1/history/import/{sessionId}/batches:
    post:
      tags: [history]
      operationId: uploadHistoryBatch
      summary: Report one app's import result and, with consent, its normalised orders.
      description: >
        One batch per app, in order. Orders only for a normalized_upload session the person has reviewed on the
        phone, with that app's history consent and history.upload.enabled on; only the fields the session lists.
        In device_only mode a batch carries counts only.
      parameters:
        - $ref: '#/components/parameters/CorrelationId'
        - $ref: '#/components/parameters/IdempotencyKey'
        - { name: sessionId, in: path, required: true, schema: { type: string } }
      requestBody:
        required: true
        content:
          application/json:
            schema: { $ref: '#/components/schemas/HistoryBatch' }
      responses:
        '200':
          description: The import as the server now holds it.
          content:
            application/json:
              schema: { $ref: '#/components/schemas/ImportSession' }
        '400': { $ref: '#/components/responses/Error' }
        '401': { $ref: '#/components/responses/Error' }
        '403': { $ref: '#/components/responses/Error' }
        '404': { $ref: '#/components/responses/Error' }
        '409': { $ref: '#/components/responses/Error' }
        '429': { $ref: '#/components/responses/RateLimited' }
        default: { $ref: '#/components/responses/Error' }
components:
  schemas:
    ImportSession:
      properties:
        dedup_salt:
          type: [string, 'null']
          description: >
            Upload sessions only (CR-007): the phone sends source_ref = SHA-256(dedup_salt + provider + ":" +
            provider order ID), so duplicates are found without the provider's order ID leaving the phone.
    HistoryBatch:
      type: object
      additionalProperties: false
      required: [batch_seq, provider, status, orders_found, adapter, reviewed]
      properties:
        batch_seq: { type: integer, minimum: 1 }
        provider: { $ref: '#/components/schemas/ProviderKey' }
        status: { type: string, enum: [running, completed, failed, skipped] }
        orders_found: { type: integer, minimum: 0, maximum: 5000 }
        confidence: { oneOf: [{ $ref: '#/components/schemas/Confidence' }, { type: 'null' }] }
        failure_reason:
          type: [string, 'null']
          enum: [login_required, unknown_screen, package_mismatch, app_not_installed, timeout, stopped, null]
        adapter: { $ref: '#/components/schemas/AdapterRef' }
        reviewed: { type: boolean, description: True once the person confirmed on the review screen; orders need it. }
        orders:
          type: array
          maxItems: 200
          items: { $ref: '#/components/schemas/UploadedOrder' }
        import_outcome:
          type: [string, 'null']
          enum: [completed, cancelled, null]
          description: Set on the batch that ends the whole import.
    UploadedOrder:
      type: object
      additionalProperties: false
      required: [source_ref, extraction_confidence]
      properties:
        source_ref: { type: string, pattern: '^[0-9a-f]{64}$' }
        ordered_at: { type: [string, 'null'], format: date-time }
        status: { oneOf: [{ $ref: '#/components/schemas/OrderStatus' }, { type: 'null' }] }
        total: { oneOf: [{ $ref: '#/components/schemas/Money' }, { type: 'null' }] }
        items:
          type: [array, 'null']
          maxItems: 100
          items:
            type: object
            additionalProperties: false
            required: [name]
            properties:
              name: { type: string, maxLength: 120 }
              quantity: { type: [integer, 'null'], minimum: 0, maximum: 999 }
              amount: { oneOf: [{ $ref: '#/components/schemas/Money' }, { type: 'null' }] }
        location_label: { type: [string, 'null'], maxLength: 40 }
        extraction_confidence: { $ref: '#/components/schemas/Confidence' }
```

## Impact analysis

| Area | Impact |
|---|---|
| UX / screens / states | **None on frozen screens.** S-HIST-02's progress and S-HIST-03's review sample come from the phone's own reading; "Save and sync" calls the new operation, "Save on this phone" sends counts only. The review screen's existing "correct/delete rows" applies before anything is uploaded. |
| API contract | One new operation, two new schemas, one optional field on `ImportSession`, one new flag in `feature-flags.yaml` (and `GET /v1/config`). No new error codes: `validation_failed`, `consent_required`, `forbidden`, `not_found`, `conflict`, `policy_blocked` and `rate_limited` cover every case. Contract version → **1.6.0**. |
| Compatibility & migration | Additive. Older apps keep importing on the phone only. The server stores orders only from this operation; the dashboard and recommendations read them once 4.6 lands. No existing data to migrate. |
| Analytics events | Existing `history_import_*` events gain counts only: no order content, item names or amounts in analytics. |
| Tests to add/update | **Server:** `device_only` batches with orders refused; fields outside the session's allowlist refused; orders before review refused; refused when consent is revoked or the flag is off; duplicates by `source_ref` update instead of duplicating; totals reconciled with mismatches flagged; batches in order and idempotent; one user's batch never touches another's history (A-15). **App:** the phone's parser keeps only allowlisted fields; no raw screen text leaves the phone; `source_ref` hashing; device-only sends no orders. **Privacy tests (A-10)** inspect request bodies, logs and analytics for anything beyond the allowlist. |
| Security / privacy | **A-10:** parsing stays on the phone, with a field allowlist, no raw screens, explicit upload consent, and the upload flag gated on R-05. **New A-17, *forged or poisoned history*:** affects only that user's own dashboard and recommendations; totals are reconciled; strict schemas and size limits. **Data classification:** "Order history (normalized)" already says "server only with upload consent"; it gains the `source_ref` (a salted hash; the provider order ID stays on the phone). Deleting an order or the account removes uploaded history (existing operations). |
| Accessibility | None (no UI change). |
| Delivery | Backend ~2 days; app (on-device history parser for the Test Store, review from local data, upload) ~3 days; privacy tests ~1 day. **Needs the privacy review (R-05) before `history.upload.enabled` is turned on anywhere but development.** |

## Revised acceptance criteria

- An import in `device_only` mode sends the server counts and statuses only; no order content reaches it, and tests inspect every request body to prove it.
- In `normalized_upload` mode, orders reach the server only after the person confirms on the review screen. They carry only the fields the person chose, and only while consent and the upload flag allow it.
- The same order imported twice is stored once. A total that doesn't add up is flagged, never silently corrected.
- Revoking history consent mid-import stops further uploads (403).

```text
Approvals:  Product [x]  Design [x]  Engineering [x]  Security [x]  QA [x]
            (all five confirmed by Akash, project owner, 28 Nov 2026)
Decision: approved     Decision date: 2026-11-28
Linked commits / tag:
```

## Not in this CR

- **The legal basis for processing order history under DPDP (R-05).** That is the privacy review's decision. This CR only keeps upload switched off until then.
- **Using uploaded history for anyone but its owner** (for example, aggregate demand signals). That would need its own CR and privacy review.
