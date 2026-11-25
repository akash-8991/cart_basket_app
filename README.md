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

### Device tests (emulators)

`android/app/src/androidTest` holds the instrumented suite: the full register → compare → approve → handoff journey through the real app, Android Keystore session storage, rotation (typed input and the emergency Stop control), and an automated accessibility audit (Google Accessibility Test Framework) of the key screens in light and dark mode. The Compose acceptance tests in `src/sharedTest` run both under Robolectric and on devices.

```bash
tools/device-matrix.sh          # API 26, 30, 34 and 36, one emulator at a time
tools/device-matrix.sh 34 36    # selected API levels
```

Needs the `system-images;android-NN;google_apis;arm64-v8a` images. For each level the script creates a Pixel 6 AVD, boots it headless, runs the suite, re-runs the accessibility audit at 2× font size, and collects JUnit XML and screenshots in `android/build/device-matrix/apiNN/`. Keep the Mac awake while it runs (`caffeinate -dims tools/device-matrix.sh`). Against a single running emulator or phone: `cd android && ./gradlew connectedDebugAndroidTest`.

## Source documents

- `smart-shopping-chat-prd-and-claude-master-prompt-v1.4.md`: PRD and master prompt
- `smart-shopping-chat-app-detailed-audit-v1.2.md`: application audit
