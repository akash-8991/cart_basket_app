# Change Request (post-freeze)

Use for any change after `frontend-freeze-v1.4` that is not a crash, security, accessibility, copy/translation, API-compatibility, telemetry, or provider-resilience fix that leaves the journey unchanged (audit addendum §4).

**Always needs a CR:** new screen · changed navigation · changed category · changed auth flow · changed comparison semantics · changed automation consent · changed data-collection purpose · changed notification or deep-link behaviour · any contract change (endpoint, schema, error code, task state, event, flag).

```text
CR ID:
Title:
Owner:                         Date raised:
Reason (user/business/defect):

Change description:

Impact analysis
  UX / screens / states:
  API contract (OpenAPI, fixtures, error codes):
  Compatibility & migration plan:
  Analytics events:
  Tests to add/update:
  Security / privacy (data classes, consent, threat model):
  Accessibility:
  Delivery dates:

Revised acceptance criteria:

Approvals:  Product [ ]  Design [ ]  Engineering [ ]  Security [ ]  QA [ ]
Decision: approved / rejected / deferred     Decision date:
Linked commits / tag:
```
