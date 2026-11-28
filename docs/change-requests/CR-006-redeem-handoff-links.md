# CR-006 — Redeem cart handoff links

```text
CR ID:        CR-006
Title:        Validate and use up a cart handoff link once, then open the cart's app
Owner:        Akash (project owner) — drafted by Claude      Date raised: 2026-11-28
Reason:       Phase 4.5 contract gap. The frozen design routes smartcart://handoff/{opaqueToken} to the
              cart-ready screen "after token validation" (screen-state catalogue §2), and threat model A-08
              needs handoff links to be single use. The contract can issue a link (createHandoff) but has no
              operation to check one, so a link can't be used up, and the app can't act on it.
```

## Change description

Slice 4.5 already issues handoff tokens that are opaque, signed, valid for at most 10 minutes, and bound to the user, the task and the provider's app (`HandoffTokens`, tests in `TaskThreatsTest`). This CR lets them be used.

1. **New operation `POST /v1/deep-links/redeem`.**
   - The app sends the token from the link, and the package it is about to open if it knows one.
   - The server checks the token and uses it up: one use, by its owner, for its app, before it expires.
   - It answers with the task ID, the provider, the target package (if verified), the strategy and the manual steps.
   - **Every refusal is the same 404:** malformed, forged, someone else's, expired, already used, or for another app. A link reveals nothing about why it failed.
2. **The app follows `smartcart://handoff/{token}`.** Today the app opens home instead.
   - After redeeming, it shows that task's cart-ready screen.
   - If a verified package is known, the app launches it with an explicit package intent and the strategy's steps. Otherwise it shows the steps for the person to follow.
   - If the link doesn't work, the app says so and offers to make a new one.
3. **"Handed off" means the link was used, not just made.**
   - The task moves to `HANDED_OFF` when a link is redeemed. Today it moves when a link is issued, and the cart-ready screen asks for a link as soon as it opens.
   - A new link can be made for a ready cart until the task expires. Each new link replaces the previous one, which stops working. Today `createHandoff` refuses a second link.
4. **The cart-ready notification carries the handoff link,** as PRD §5 asks for. If the link has expired by the time it is tapped, the app falls back to the task's cart-ready screen.
5. **Android App Links, when there is a domain.**
   - The backend serves `/.well-known/assetlinks.json` from configuration: the package name and SHA-256 signing-certificate fingerprints. This is outside the API contract.
   - The app declares a verified `https://<host>/h/{token}` link alongside the custom scheme.
   - **Needs the project owner:** a domain the team controls, and the release signing certificate. Until then, only `smartcart://` links are used.
6. **Copy fix.** The cart-ready dialog's "This mock build does not open partner apps" note is replaced by the strategy's real wording.

### OpenAPI patch

```yaml openapi-patch
paths:
  /v1/deep-links/redeem:
    post:
      tags: [deep-links]
      operationId: redeemHandoff
      summary: Check a cart handoff token and use it up.
      description: >
        One use, by the user it was issued to, for the app it was issued for, before it expires. Every refusal
        (malformed, forged, someone else's, expired, already used, another app) is the same 404, so a link
        reveals nothing about why it failed (threat model A-08).
      parameters:
        - $ref: '#/components/parameters/CorrelationId'
      requestBody:
        required: true
        content:
          application/json:
            schema: { $ref: '#/components/schemas/RedeemHandoffRequest' }
      responses:
        '200':
          description: The token was valid and is now used up.
          content:
            application/json:
              schema: { $ref: '#/components/schemas/HandoffRedemption' }
        '400': { $ref: '#/components/responses/Error' }
        '401': { $ref: '#/components/responses/Error' }
        '404': { $ref: '#/components/responses/Error' }
        '429': { $ref: '#/components/responses/RateLimited' }
        default: { $ref: '#/components/responses/Error' }
components:
  schemas:
    RedeemHandoffRequest:
      type: object
      additionalProperties: false
      required: [token]
      properties:
        token: { type: string, pattern: '^[A-Za-z0-9_-]{16,128}$' }
        package_name: { type: [string, 'null'], maxLength: 255 }
    HandoffRedemption:
      type: object
      additionalProperties: false
      required: [task_id, provider, strategy, manual_steps]
      properties:
        task_id: { type: string }
        provider: { $ref: '#/components/schemas/ProviderKey' }
        target_package: { type: [string, 'null'] }
        strategy: { type: string, enum: [cart_deep_link, app_launch, manual] }
        manual_steps:
          type: array
          items: { type: string }
```

## Impact analysis

| Area | Impact |
|---|---|
| UX / screens / states | No new screen. `S-TASK-04` gains a "link expired — make a new one" state. Its dialog copy changes. A handoff link opens the cart-ready screen rather than home. The notification's tap target changes from `smartcart://task/{id}` to the handoff link. |
| API contract | One new operation, two new schemas, no new error codes. `createHandoff` also accepts a task in `HANDED_OFF` (a new link replaces the old one). Contract version → **1.5.0**. |
| Compatibility & migration | Additive. Older apps keep opening home for handoff links. No data to migrate: tokens live for at most 10 minutes. |
| Analytics events | `notification_opened` already exists. Add the `handoff_strategy` property to `handoff_opened` (already in the schema). No token or package in analytics. |
| Tests to add/update | **Server:** redemption once; a replay, someone else's token, another app, a tampered or expired token each get the same 404; a new link revokes the old one. **App:** link → redeem → cart-ready screen; explicit package intent only for a verified package; the expired-link state; notification tap. **Device:** the journey from the cart-ready notification. |
| Security / privacy | A-08 is completed (replay blocked end to end). Links carry no IDs. Refusals are uniform. The package intent is explicit, never implicit, so no other app can catch it. Package names are used only after the device spike verifies them. |
| Accessibility | The new state's text and button are announced. No layout change. |
| Delivery | Backend < 1 day (tokens already exist). App ~1–2 days. App Links need the domain and the release certificate from the project owner. |

## Revised acceptance criteria

- A handoff link works once, for its owner, for its app, within 10 minutes. Every other use gets the same refusal, and tests cover each case.
- Tapping the cart-ready notification opens the verified cart summary. "Open cart" launches the provider's app when its package is verified, and otherwise shows the steps.
- No link, log line or analytics event contains a task, quote or user ID.

```text
Approvals:  Product [ ]  Design [ ]  Engineering [ ]  Security [ ]  QA [ ]
Decision: proposed     Decision date:
Linked commits / tag:
```
