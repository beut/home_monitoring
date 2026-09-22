# Implementation Plan: Pulpit monitoringu domu — etap 1: fotowoltaika (APsystems)

**Branch**: `001-home-energy-dashboard` | **Date**: 2026-09-23 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `specs/001-home-energy-dashboard/spec.md`, plus user direction: *"na razie tylko dane z fotowoltaiki"* (for now, PV data only). The reference script and credentials are in `C:\Develop\apsystems`.

## Summary

This iteration builds a native Android app with a dashboard (Pulpit) and an accounts screen (Konta). The dashboard shows live PV data from the APsystems EMA OpenAPI: current power, energy today, and month/year/lifetime totals. The app connects to the cloud directly and signs each request with HMAC-SHA256, porting the user's Python reference script. Credentials are encrypted with the Android Keystore.

Research found that APsystems allows only **1,000 API calls per month**. Because of that, refreshes follow a call budget and daylight hours instead of a fixed 5-minute interval, and the last snapshot is cached so the app opens instantly and still works offline. The dashboard is built as a list of independent source sections, so Panasonic (heat pump) and tado (rooms) can be added in later iterations without touching the PV code.

**Scope of this iteration**: User Story 1, User Story 2 and User Story 3 for the **PV source only**. Panasonic and tado are deferred: they appear only as disabled "Wkrótce" entries on the Konta screen.

### Requirement scope for etap 1

| Status in etap 1 | Requirements |
|---|---|
| **Delivered** (for the PV source) | FR-001 (PV section only), FR-002, FR-003, FR-006, FR-007, FR-008 and FR-009 (APsystems account), FR-010, FR-011, FR-012, FR-014, FR-016; SC-001, SC-002, SC-003, SC-005 |
| **Met by design** (no dedicated task) | FR-017 (the app calls only read endpoints), FR-018 (no charts or history) |
| **Not applicable to APsystems** | FR-013 and SC-006: APsystems uses fixed AppId/Secret keys and signs every request, so there is no session to renew. An auth error means the keys are wrong, and the user fixes them on the Konta screen |
| **Deferred to a later etap** | FR-004 (heat pump) and FR-005 (rooms), until those sources are added. FR-015 (choosing between several systems or devices): v1 uses the first ECU (`ecu[0]`), and the user's system has exactly one. SC-004 (connecting all three accounts in under 5 min) |

## Technical Context

**Language/Version**: Kotlin 1.9.24 (JVM target 17)

**Primary Dependencies**:
- AGP 8.5.2
- Jetpack Compose, BOM 2024.06.00, Material 3
- lifecycle-viewmodel-compose / lifecycle-runtime-compose 2.8.3
- activity-compose 1.9.0
- OkHttp 4.12
- kotlinx.serialization-json 1.6.x
- kotlinx.coroutines 1.8
- AndroidX DataStore Preferences 1.1

**Storage**: DataStore (Preferences), used for the cached snapshot, system info, budget and location, plus the Android Keystore (AES-GCM) for encrypting credentials.

**Testing**: JUnit 4, kotlinx-coroutines-test, OkHttp MockWebServer. These are JVM unit tests only. Manual device smoke tests are listed in [quickstart.md](quickstart.md).

**Target Platform**: Android 8.0+ (minSdk 26), with compileSdk and targetSdk 34.

**Project Type**: Mobile app (a single Android module in `android/`).

**Performance Goals**:
- Cached content is visible in under 1 s after launch (SC-002).
- A fresh fetch completes in under 5 s on a normal connection (SC-001). A refresh costs 1 call, or 2 when the summary is due.

**Constraints**:
- **At most 800 APsystems calls per month by default**, shared with the 1,000-call AppId quota (research R3).
- No calls at night.
- The app refreshes only while it is visible.
- Credentials never leave the device, except that the HMAC signature is sent to `api.apsystemsema.com`.
- UI text is in Polish.

**Scale/Scope**: One user, one PV system (1 ECU, 6 inverters), two screens, three API endpoints.

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

The constitution in `.specify/memory/constitution.md` is still the **unfilled template**, so it defines no principles or gates. Until `/speckit-constitution` is run, the plan applies these default gates:

| Gate | Status |
|---|---|
| Simplicity: no framework without a need (no DI framework, no Room, no Retrofit, no navigation library) | ✅ PASS |
| Testable core: the signer, parsing, RefreshPolicy and error mapping are pure and unit-tested without the network | ✅ PASS |
| Secrets are never committed (credentials are entered at runtime; debug prefill comes from the gitignored `local.properties`) | ✅ PASS |
| Spec alignment: deviations are recorded in the spec | ✅ PASS. FR-006 and FR-014 were amended for the API quota (see the spec) |

Post-design re-check (after Phase 1): ✅ still passes. No violations, so Complexity Tracking is empty.

## Project Structure

### Documentation (this feature)

```text
specs/001-home-energy-dashboard/
├── plan.md                        # This file
├── research.md                    # Phase 0: API, quota, stack, security decisions
├── data-model.md                  # Phase 1: entities, states, RefreshPolicy
├── quickstart.md                  # Phase 1: build, test, device smoke test
├── contracts/
│   ├── apsystems-openapi.md       # Consumed API subset, signing, test vector, error mapping
│   └── ui-screens.md              # Screen states and Polish copy
└── tasks.md                       # Phase 2 (/speckit-tasks — not created here)
```

### Source Code (repository root)

```text
android/
├── settings.gradle.kts
├── build.gradle.kts
├── gradle.properties
├── gradle/wrapper/…
└── app/
    ├── build.gradle.kts           # debug BuildConfig prefill from local.properties
    └── src/
        ├── main/
        │   ├── AndroidManifest.xml          # INTERNET; backup rules exclude credentials
        │   ├── res/ (strings.xml — pl, xml/data_extraction_rules.xml, …)
        │   └── java/pl/home/monitoring/
        │       ├── MainActivity.kt
        │       ├── AppContainer.kt           # manual wiring
        │       ├── core/
        │       │   ├── SunCalculator.kt      # NOAA sunrise/sunset
        │       │   ├── RefreshPolicy.kt      # budget + daylight + intervals (pure)
        │       │   └── Formatters.kt         # pl-PL W/kW/kWh, HH:MM
        │       ├── data/
        │       │   ├── security/CredentialCipher.kt     # Keystore AES-GCM
        │       │   ├── store/                            # DataStore: credentials, system info,
        │       │   │                                     #   snapshot, budget, location
        │       │   └── apsystems/
        │       │       ├── ApsSigner.kt
        │       │       ├── ApsApiClient.kt   # OkHttp, envelope + error mapping
        │       │       ├── ApsDtos.kt        # kotlinx.serialization
        │       │       └── PvRepository.kt   # policy + budget + cache → Flow<SectionState>
        │       ├── domain/                   # PvSnapshot, SectionState, SourceId, errors
        │       └── ui/
        │           ├── dashboard/ (DashboardScreen, DashboardViewModel, PvSection)
        │           ├── accounts/  (AccountsScreen, AccountsViewModel)
        │           └── theme/
        └── test/java/pl/home/monitoring/
            ├── data/apsystems/ (ApsSignerTest, ApsDtosTest, ApsApiClientTest[MockWebServer], PvRepositoryTest)
            └── core/ (RefreshPolicyTest, SunCalculatorTest, FormattersTest)
```

**Structure Decision**: A single Gradle module, `android/app`, which matches the existing `.gitignore`. Code is layered by package (`core` / `data` / `domain` / `ui`). Future sources get sibling packages, `data/panasonic` and `data/tado`, plus a section composable each.

## Complexity Tracking

No violations to justify.
