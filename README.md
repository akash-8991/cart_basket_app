# Smart Shopping Chat Assistant

Android-first conversational shopping assistant with user-approved automation, full-cart price comparison and order-history insights. Built contract-first; see [`docs/DEVELOPMENT_PLAN.md`](docs/DEVELOPMENT_PLAN.md).

**Current phase:** 1 — mocked frontend. There is no backend yet, and no partner app or Claude call is made.

## Layout

| Path | What |
|---|---|
| `docs/` | Plan, decision log, Phase 0 design artefacts, freeze evidence |
| `contracts/openapi/openapi.yaml` | API contract (source of truth) |
| `contracts/schemas/` | Analytics, notification, deep-link and Claude-output JSON Schemas |
| `contracts/fixtures/` | Generated mock responses (used by the app and tests) |
| `contracts/error-codes.yaml`, `feature-flags.yaml` | Frozen catalogues |
| `tools/contract-check/` | Fixture generator, Ajv validation, Spectral lint, Prism mock |
| `android/` | Jetpack Compose app |

## Contract checks

```bash
cd tools/contract-check && npm install && npm run check
```

`npm run mock` starts a Prism mock server on port 4010.

## Android

Requires JDK 21 and the Android SDK (platform 36). With Homebrew paths:

```bash
cd android && JAVA_HOME=/opt/homebrew/opt/openjdk@21 ANDROID_HOME=/opt/homebrew/share/android-commandlinetools ./gradlew testDebugUnitTest assembleDebug
```

Debug builds include a **Mock scenario** switcher (Settings → Developer) to show every screen state from the fixtures.

## Source documents

- `smart-shopping-chat-prd-and-claude-master-prompt-v1.4.md`: PRD and master prompt
- `smart-shopping-chat-app-detailed-audit-v1.2.md`: application audit
