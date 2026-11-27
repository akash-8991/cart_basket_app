# CR-004 — User-entered delivery addresses

```text
CR ID:        CR-004
Title:        Let users enter and choose their own delivery addresses (no automatic location)
Owner:        Akash (project owner) — drafted by Claude      Date raised: 2026-11-27
Reason:       Decision D1 (27 Nov 2026): the app must not work out where users are; where an address
              matters, the user enters it. Today there is nowhere to enter one: comparisons assume
              "Delivery to your saved Home label" without the user ever choosing it.
```

## Change description

Follows the existing rules for addresses (`data-classification.md`: *sensitive location data, kept on the device, sent to the server only for an active task, never in logs, analytics or notifications*; audit v1.2: minimise, encrypt, treat as untrusted text; contract: ride labels only, coordinates stay on the device).

1. **New screen `S-SET-08` `settings/addresses`** — list, add, edit, delete. Fields: *label* (Home / Work / a name the user types), *flat / house*, *street / area*, *landmark* (optional), *city*, *PIN code* (6 digits). No phone number, no map pin, no GPS — the user types everything. One address can be the default.
2. **Stored on the device only, encrypted** (Keystore-backed, like the session token), per signed-in user. Removed on sign-out of a non-remembered device, on "Delete account", and with a *Delete all saved addresses* button on the screen. Not part of the server export (it never reaches the server); the Privacy screen says so.
3. **Choosing where to deliver** — the cart card in grocery/food/shopping chat (`S-CHAT-01`) gains a *Deliver to: Home ▾* row with the saved labels and *Add an address*. Comparing needs an address chosen; with none saved, *Compare full cart* first opens the add-address form.
4. **Only the label reaches the server.** `QuoteRequest` gains optional `delivery_label` (e.g. "Home"), used for the comparison's assumptions text ("Delivery to Home"). The street address never leaves the phone; in Phase 4 the on-device automation uses it to pick the matching saved address inside the partner app and to check the cart's delivery address before hand-off.
5. **Claude never sees an address.** Labels aren't sent to the gateway either (the gateway input is unchanged: redacted message, category, installed apps). A full address typed into chat stays chat text, redacted for phone/email/card numbers only — the address form is the place for addresses, and chat suggests it ("Add it in Settings → Addresses").

### OpenAPI patch

```yaml openapi-patch
components:
  schemas:
    QuoteRequest:
      properties:
        delivery_label:
          type: [string, 'null']
          maxLength: 40
          description: >
            Label of the user's saved delivery address (e.g. "Home"), entered on the device. The address itself
            never leaves the device (D-025, CR-004). Used only for this comparison's assumptions text.
```

## Impact analysis

| Area | Impact |
|---|---|
| UX / screens / states | New `S-SET-08` (empty, list, form with field errors, delete confirm); new row on the cart card; Settings hub gains *Addresses*; Privacy screen notes that addresses stay on the device. Navigation map gains `settings/addresses`. Design needed for the form and the picker. |
| API contract | One optional, nullable field on `QuoteRequest`. Contract version → 1.3.0. |
| Compatibility & migration | The backend must accept `delivery_label` before an app that sends it ships (the server rejects unknown fields today). Older apps don't send it; comparisons then keep the current generic assumption. No server data to migrate. |
| Analytics events | None new; `screen_view` for `settings/addresses`. No address, label or PIN code in any event (schema already forbids free text). |
| Tests to add/update | App: form validation (PIN code 6 digits, required fields, label length), encrypted storage round-trip, deletion paths (button, sign-out, delete account), picker on the cart card, compare blocked until an address is chosen, address text never in a request body (only the label). Backend: `delivery_label` accepted, length-limited, echoed only in assumptions, never logged or stored beyond the quote. Accessibility audit of the new screen at 1× and 2× font. |
| Security / privacy | Addresses stay within `data-classification.md` (device, encrypted, task-scoped server use). Label is low-sensitivity but treated as user text: length-limited, untrusted, not sent to Claude. Threat model: address strings are untrusted input for Phase 4 adapters (A-05 list already includes addresses). |
| Accessibility | Form fields with labels and errors announced; picker reachable by TalkBack; works at 2× font. |
| Delivery | Design ~1–2 days; app ~3 days; backend < 1 day; QA ~1 day. Proposed: before Phase 4, since Phase 4 automation needs the address to choose the right one in the partner app. |

## Revised acceptance criteria

- A user can add, edit, delete and choose a default address; nothing about it is sent to the server except the chosen label with a comparison.
- *Compare full cart* asks for an address when none is saved, and the comparison's assumptions show the chosen label.
- Deleting the account, signing out of a non-remembered device or *Delete all saved addresses* removes every saved address from the phone.
- No request body, log line or analytics event contains an address (test inspects all three).

```text
Approvals:  Product [ ]  Design [ ]  Engineering [ ]  Security [ ]  QA [ ]
Decision: approved / rejected / deferred     Decision date:
Linked commits / tag:
```

## Open question for the Phase 4 spike

Whether the comparison engine needs a coarse location (e.g. PIN code) to tell which apps deliver to the user. If it does, that is a separate change: it would send location data to the server, which D1 and `data-classification.md` currently exclude.
