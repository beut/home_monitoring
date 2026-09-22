---

description: "Task list for etap 1 (PV / APsystems) of the home monitoring dashboard"
---

# Tasks: Pulpit monitoringu domu — etap 1: fotowoltaika (APsystems)

**Input**: Design documents from `specs/001-home-energy-dashboard/`

**Prerequisites**: [plan.md](plan.md), [spec.md](spec.md), [research.md](research.md), [data-model.md](data-model.md), [contracts/apsystems-openapi.md](contracts/apsystems-openapi.md), [contracts/ui-screens.md](contracts/ui-screens.md), [quickstart.md](quickstart.md)

**Tests**: Included. The plan (research R9) and quickstart require JVM unit tests for the signer, DTO parsing, RefreshPolicy, SunCalculator, formatters and API error mapping. **No test may call the real APsystems API**, because every call spends the 1,000-per-month quota.

**Scope**: PV source only. Panasonic and tado appear only as disabled "Wkrótce" entries (US2).

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Can run in parallel (different files, no dependencies on incomplete tasks).
- **[Story]**: US1, US2 or US3 from spec.md.

## Path Conventions

- `APP` = `android/app/src/main/java/pl/home/monitoring`
- `TEST` = `android/app/src/test/java/pl/home/monitoring`
- `RES` = `android/app/src/main/res`

Full paths are written out in every task below.

---

## Phase 1: Setup (Shared Infrastructure)

**Purpose**: A buildable, empty Compose app in `android/`.

- [ ] T001 Create the Gradle project skeleton:
  - `android/settings.gradle.kts`: rootProject.name = "HomeMonitoring", include(":app"), with google() and mavenCentral() repositories.
  - `android/build.gradle.kts`: plugins `com.android.application` 8.5.2, `org.jetbrains.kotlin.android` 1.9.24 and `org.jetbrains.kotlin.plugin.serialization` 1.9.24, all `apply false`.
  - `android/gradle.properties`: `android.useAndroidX=true`, `org.gradle.jvmargs=-Xmx2g`, `kotlin.code.style=official`.
- [ ] T002 Generate the Gradle wrapper for 8.7 in `android/` by running `gradle wrapper --gradle-version 8.7` (produces `android/gradlew`, `android/gradlew.bat` and `android/gradle/wrapper/*`).
- [ ] T003 Create `android/app/build.gradle.kts`:
  - namespace and applicationId `pl.home.monitoring`; compileSdk 34, minSdk 26, targetSdk 34.
  - Java/Kotlin target 17; `buildFeatures { compose = true; buildConfig = true }`; `composeOptions.kotlinCompilerExtensionVersion = "1.5.14"`.
  - Dependencies:
    - Compose BOM `2024.06.00` (ui, ui-tooling-preview, material3, material-icons-extended), `activity-compose:1.9.0`, `lifecycle-viewmodel-compose:2.8.3`, `lifecycle-runtime-compose:2.8.3`.
    - `okhttp:4.12.0`, `kotlinx-serialization-json:1.6.3`, `kotlinx-coroutines-android:1.8.1`, `datastore-preferences:1.1.1`.
    - Test: `junit:4.13.2`, `kotlinx-coroutines-test:1.8.1`, `okhttp mockwebserver:4.12.0`.
  - `testOptions.unitTests.isReturnDefaultValues = true`.
- [ ] T004 [P] Debug-only credential prefill in `android/app/build.gradle.kts` (research R6):
  - Read `apsystems.appId`, `apsystems.appSecret` and `apsystems.sid` from `android/local.properties` if the file exists.
  - Expose them as `buildConfigField("String", "DEV_APS_APP_ID", …)` (and likewise for the other two) in the `debug` build type only.
  - In the `release` build type, set all three to `""`.
- [ ] T005 [P] Create `android/app/src/main/AndroidManifest.xml`:
  - The `INTERNET` permission.
  - `android:allowBackup="true"`, `android:dataExtractionRules="@xml/data_extraction_rules"` and `android:fullBackupContent="@xml/backup_rules"`.
  - A single `MainActivity` (exported, LAUNCHER) using theme `@style/Theme.HomeMonitoring`.
- [ ] T006 [P] Create the backup exclusions so encrypted credentials and the DataStore files are never backed up:
  - In `android/app/src/main/res/xml/data_extraction_rules.xml` and `android/app/src/main/res/xml/backup_rules.xml`, exclude domain `file` path `datastore/` and domain `sharedpref` for cloud backup and device transfer.
- [ ] T007 [P] Create the base resources:
  - `android/app/src/main/res/values/themes.xml`: `Theme.HomeMonitoring` with parent `android:Theme.Material.Light.NoActionBar`.
  - `android/app/src/main/res/values/strings.xml`: `app_name` = "Mój dom".
  - A launcher icon: an adaptive icon, `android/app/src/main/res/mipmap-anydpi-v26/ic_launcher.xml`, with a vector foreground (a sun) in `android/app/src/main/res/drawable/ic_launcher_foreground.xml`.
- [ ] T008 Create the Compose theme in `android/app/src/main/java/pl/home/monitoring/ui/theme/Theme.kt`: `HomeMonitoringTheme` with Material 3, dynamic color on API 31+, and light and dark schemes. Create a placeholder `android/app/src/main/java/pl/home/monitoring/MainActivity.kt` that shows `Text("Mój dom")`. Verify that `cd android && ./gradlew assembleDebug` succeeds.

**Checkpoint**: `./gradlew assembleDebug` builds an APK that shows "Mój dom".

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: The API client, storage, budget and refresh policy. Every story depends on these, and quota safety must exist before any code calls the API.

**⚠️ CRITICAL**: No user story work can begin until this phase is complete.

### Tests for Foundational (write first; they must fail before implementation)

- [ ] T009 [P] Signer test in `android/app/src/test/java/pl/home/monitoring/data/apsystems/ApsSignerTest.kt`:
  - With appId `testAppId`, appSecret `testSecret`, timestamp `1700000000000`, nonce `0123456789abcdef0123456789abcdef`, path `/user/api/v2/systems/summary` and method `GET`, `sign()` must return `/+AeRWeNIkLQanWAJAq3DiMz9H7s7uHYU+PeiBmNP9g=`.
  - Also assert that `lastPathSegment("/user/api/v2/systems/{sid}/devices/ecu/energy/ABC")` == `"ABC"`.
  - Assert that the query string is not part of the segment.
- [ ] T010 [P] DTO parsing tests in `android/app/src/test/java/pl/home/monitoring/data/apsystems/ApsDtosTest.kt`, using the literal JSON fixtures from `contracts/apsystems-openapi.md`:
  - The details response: `ecu[0]`, the timezone, and capacity `"9.96"` → 9.96.
  - A summary with `"today":null` and the string values.
  - The minutely response with the `today`, `time`, `power` and `energy` arrays: the last power value and the last time are extracted.
  - The envelope `{"code":1001,"data":{}}` and `{"code":1001}` without data.
  - Unknown extra fields are ignored.
  - A value that can't be parsed (for example `"abc"`) becomes `null`.
  - A negative power value is clamped to 0.
- [ ] T011 [P] API client error-mapping tests with MockWebServer in `android/app/src/test/java/pl/home/monitoring/data/apsystems/ApsApiClientTest.kt`:
  - The request carries all 5 `X-CA-*` headers, and the nonce matches `^[0-9a-f]{32}$`.
  - `code 0` → Success; `1001` → NoData; `2005` → AuthError; `7002` → Throttled.
  - HTTP 500 → ServiceError. A socket timeout or `IOException` → Offline. `code 9999` → ServiceError(9999).
  - The CallBudget counter is incremented once per HTTP attempt, **including failed attempts**.
- [ ] T012 [P] SunCalculator tests in `android/app/src/test/java/pl/home/monitoring/core/SunCalculatorTest.kt`. For lat 52.0, lon 19.0 and zone Europe/Warsaw, the results must match these reference values within ±5 min:
  - 2026-06-21: sunrise about 04:20, sunset about 21:00.
  - 2026-09-23: sunrise about 06:37, sunset about 18:48.
  - 2026-12-21: sunrise about 07:43, sunset about 15:28.
  - Before writing the assertions, verify these reference times against a NOAA calculator.
- [ ] T013 [P] RefreshPolicy tests in `android/app/src/test/java/pl/home/monitoring/core/RefreshPolicyTest.kt`, with a fixed clock and the data-model.md rules:
  - (a) At night: `shouldFetchMinutely` is false, and `nextScheduledAt` is the next sunrise.
  - (b) During the day with no snapshot: fetch.
  - (c) An automatic refresh with `fetchedAt` newer than the interval: no fetch.
  - (d) The interval never drops below 15 min, even with a large budget.
  - (e) A manual refresh when `now − fetchedAt < 10 min`: no fetch, with reason `TooSoon`.
  - (f) `used >= monthlyLimit`: no fetch, with reason `BudgetExhausted`.
  - (g) `throttledUntil` in the future: no fetch.
  - (h) Summary: fetched when `summaryFetchedAt` is null, or at least 3 h old during daylight, or on the first check after sunset. It is not fetched a second time after sunset.
  - (i) Simulate a full September, a full June and a full December with automatic refreshes every minute while "visible" from sunrise to sunset. Total calls must be ≤ 800. This is the quota guarantee. Also assert that the minutely data still refreshes on the **last** day of the month (the budget must not run out early), which proves the per-day summary reserve is large enough.
  - (k) `reservedSummaryCalls` for 21 June at lat 52 is 7 per day (`ceil(16.6 h / 3) + 1`), and for 21 December it is 4 per day.
  - (l) Throttle backoff: after consecutive throttled results, `throttledUntil` moves 15 → 30 → 60 min ahead, capped at 6 h, and resets after a success.
  - (j) The budget counter resets when the `monthKey` changes.
- [ ] T014 [P] Formatter tests in `android/app/src/test/java/pl/home/monitoring/core/FormattersTest.kt`:
  - `850` → `"850 W"`; `1234` → `"1,2 kW"`; `12.44` kWh → `"12,4 kWh"`; `null` kWh → `"—"`; `LocalTime 14:05` → `"14:05"`.
  - The Polish decimal comma is used in every case.

### Implementation for Foundational

- [ ] T015 [P] Domain types in `android/app/src/main/java/pl/home/monitoring/domain/Models.kt`:
  - `enum SourceId { PV, HEAT_PUMP, ROOMS }`.
  - `data class ApsCredentials(appId, appSecret, sid)`, with an `init` check that all three are trimmed and non-empty.
  - `data class ApsSystemInfo(ecuId: String, timezone: ZoneId, capacityKwp: Double?)`.
  - `@Serializable data class PvSnapshot(currentPowerW: Int?, powerSampleTime: String?, sampleDate: String, todayKwh: Double?, monthKwh: Double?, yearKwh: Double?, lifetimeKwh: Double?, fetchedAtEpochMs: Long, summaryFetchedAtEpochMs: Long?)`. Store times as ISO strings.
  - `sealed interface SectionState { Loading; data class Data(snapshot, freshness); data class NoDataYet(snapshot?); data class Error(reason: StaleReason); AuthError; NotConnected }`. `Error` is used only when a fetch fails **and** there is no cached snapshot yet, with reason Offline, Throttled or ServiceError.
  - `sealed interface Freshness { Fresh; data class Stale(reason) }`.
  - `sealed interface StaleReason { Offline; Throttled; data class ServiceError(code: Int?); Night(nextAt) }`.
- [ ] T016 [P] `ApsSigner` in `android/app/src/main/java/pl/home/monitoring/data/apsystems/ApsSigner.kt`:
  - `fun sign(appId, appSecret, timestamp: String, nonce: String, path: String, method: String): String` builds `"$ts/$nonce/$appId/${lastPathSegment(path)}/$method/HmacSHA256"` and applies `javax.crypto.Mac` HmacSHA256 followed by `java.util.Base64`. Use `java.util`, not `android.util`, so the JVM tests run.
  - `fun lastPathSegment(path)` strips the query string and trailing `/`.
  - `fun newNonce()` returns a UUID without dashes, in lowercase.
  - T009 must pass.
- [ ] T017 [P] DTOs in `android/app/src/main/java/pl/home/monitoring/data/apsystems/ApsDtos.kt`:
  - A `Json { ignoreUnknownKeys = true; isLenient = true; coerceInputValues = true }` instance.
  - The envelope `ApsResponse<T>(code: Int, data: T? = null)`.
  - `DetailsData(ecu: List<String> = emptyList(), timezone: String? = null, capacity: String? = null)`.
  - `SummaryData(today: String? = null, month: String? = null, year: String? = null, lifetime: String? = null)`.
  - `MinutelyData(today: String? = null, time: List<String> = emptyList(), power: List<Int?> = emptyList())`. Ignore `energy`.
  - A helper `String?.kwhOrNull()`.
  - The mappers `SummaryData.toTotals()` and `MinutelyData.lastSample(): Pair<LocalTime, Int>?`, with negative power clamped to 0.
  - T010 must pass.
- [ ] T018 `CallBudgetStore` in `android/app/src/main/java/pl/home/monitoring/data/store/CallBudgetStore.kt`, backed by DataStore Preferences (file `budget`):
  - Fields: `monthKey: YearMonth`, `used: Int`, `monthlyLimit: Int` ("Default 800. Allowed range 100–1000"; coerce values outside it), `throttledUntil: Instant?` and `throttleStreak: Int`, which counts consecutive throttled results for the backoff (15 → 30 → 60 min, capped at 6 h) and resets on success.
  - `suspend fun recordCall(now)`: resets `used` to 0 when the month differs, then increments it.
  - Also provide `setLimit(n)`, `setThrottledUntil(instant?)` and a `Flow<CallBudget>`.
  - Accept an injectable `DataStore<Preferences>` so it can be tested.
- [ ] T019 `ApsApiClient` in `android/app/src/main/java/pl/home/monitoring/data/apsystems/ApsApiClient.kt`:
  - Constructor: `(baseUrl: HttpUrl = "https://api.apsystemsema.com:9282", okHttp: OkHttpClient with 15 s timeouts, budget: CallBudgetStore, clock: Clock)`.
  - Methods: `suspend fun details(creds)`, `summary(creds)` and `minutely(creds, ecuId, date: LocalDate)`.
  - Each returns `ApiResult<T> = Success(T) | NoData | AuthError(code) | Throttled(code) | ServiceError(code?) | Offline`.
  - The code mapping follows contracts/apsystems-openapi.md: 0 → Success, 1001 → NoData, 2000–2999 → AuthError, 7001/7002/7003 → Throttled, anything else → ServiceError.
  - Call `budget.recordCall()` **before** each HTTP attempt. There are no internal retries.
  - Log response bodies only when `BuildConfig.DEBUG`, and never log the secret or the signature.
  - Run on `Dispatchers.IO`.
  - T011 must pass.
- [ ] T020 [P] `SunCalculator` in `android/app/src/main/java/pl/home/monitoring/core/SunCalculator.kt`:
  - An `object` with `fun sunTimes(date: LocalDate, lat: Double, lon: Double, zone: ZoneId): SunTimes(sunrise: ZonedDateTime, sunset: ZonedDateTime)`.
  - Use the NOAA general solar position algorithm with zenith 90.833°.
  - T012 must pass.
- [ ] T021 `RefreshPolicy` in `android/app/src/main/java/pl/home/monitoring/core/RefreshPolicy.kt`. It is a pure function, `decide(now: Instant, zone: ZoneId, lat, lon, budget: CallBudget, snapshot: PvSnapshot?, manual: Boolean): RefreshDecision(fetchMinutely: Boolean, fetchSummary: Boolean, skipReason: SkipReason?, nextScheduledAt: Instant)`, where `SkipReason ∈ Night | TooSoon | BudgetExhausted | Throttled`. It implements the data-model.md rules verbatim:
  - Daylight: "sunrise ≤ now ≤ sunset + 15 min".
  - Interval: "`max(15 min, remainingDaylightMinutesThisMonth / max(1, remainingBudget − reservedSummaryCalls))`", where `reservedSummaryCalls` = "Σ over the remaining days of this month (including today) of (ceil(daylightHours(day) / 3) + 1)". For today, count only the summary slots still ahead. Daylight hours come from `SunCalculator`.
  - A manual refresh requires "`now − fetchedAt ≥ 10 min`".
  - Summary: "at least 3 h old during daylight, or the first check after sunset".
  - T013 must pass.
- [ ] T022 [P] Formatters in `android/app/src/main/java/pl/home/monitoring/core/Formatters.kt`:
  - `formatPower(w: Int?)`: below 1000 → `"%d W"`, otherwise kW with 1 decimal place.
  - `formatKwh(Double?)`: 1 decimal place, or `"—"` when null.
  - `formatTime(LocalTime)`: `"HH:mm"`.
  - Use `Locale("pl", "PL")`.
  - T014 must pass.
- [ ] T023 [P] `CredentialCipher` in `android/app/src/main/java/pl/home/monitoring/data/security/CredentialCipher.kt`:
  - Use an AES-256-GCM key with alias `aps_credentials_key` in `AndroidKeyStore`, generated if absent (`KeyGenParameterSpec`, `PURPOSE_ENCRYPT or PURPOSE_DECRYPT`, `BLOCK_MODE_GCM`, `ENCRYPTION_PADDING_NONE`).
  - `encrypt(plain: String): String` returns Base64 of `iv(12 bytes) + ciphertext`; `decrypt(String): String` reverses it.
  - Extract an interface `StringCipher` so the stores can be tested with a fake.
- [ ] T024 `CredentialsStore` in `android/app/src/main/java/pl/home/monitoring/data/store/CredentialsStore.kt`, backed by DataStore (file `credentials`):
  - `suspend fun save(ApsCredentials)` stores the three encrypted strings (T023).
  - `val credentials: Flow<ApsCredentials?>` decrypts them. A decryption failure (for example the key was lost after a restore) → clear the stored values and emit null.
  - `suspend fun clear()`.
  - On first read, if all three `BuildConfig.DEV_APS_*` values are non-empty (debug builds only) and nothing is stored yet, **do not save**. Instead, expose them via `fun devPrefill(): ApsCredentials?` for the Konta form.
- [ ] T025 [P] `SystemInfoStore` and `LocationStore` in `android/app/src/main/java/pl/home/monitoring/data/store/SettingsStores.kt`, backed by DataStore (file `settings`):
  - SystemInfo: `ecuId`, `timezone` (falls back to `Europe/Warsaw` when invalid) and `capacityKwp`.
  - Location: "latitude Double (−90…90), default 52.0" and "longitude Double (−180…180), default 19.0", with values outside the range rejected.
  - Each store provides a Flow and `save`/`clear`.
- [ ] T026 [P] `SnapshotStore` in `android/app/src/main/java/pl/home/monitoring/data/store/SnapshotStore.kt`, backed by DataStore (file `snapshot`):
  - `Flow<PvSnapshot?>` (JSON via kotlinx.serialization, where corrupt JSON → null), `save(PvSnapshot)` and `clear()`.
- [ ] T027 `AppContainer` in `android/app/src/main/java/pl/home/monitoring/AppContainer.kt`: manual wiring of a single OkHttpClient, `Clock.systemDefaultZone()`, the four stores, `ApsApiClient` and `PvRepository` (added in US1). Create it lazily from a `HomeMonitoringApp : Application` in `android/app/src/main/java/pl/home/monitoring/HomeMonitoringApp.kt`, and register `android:name=".HomeMonitoringApp"` in `android/app/src/main/AndroidManifest.xml`.

**Checkpoint**: `./gradlew testDebugUnitTest` passes (T009–T014), and the API client is quota-accounted.

---

## Phase 3: User Story 1 — Jeden ekran z bieżącym stanem (Priority: P1) 🎯 MVP

**Goal**: The Pulpit screen shows the PV section: current power (with sample time), today, month, year, lifetime and "Aktualizacja: HH:MM". It auto-refreshes by RefreshPolicy while the screen is visible, and it supports pull-to-refresh.

**Independent Test**: Put the credentials in `android/local.properties`. In a debug build, a temporary hook (T033) saves the dev prefill so the dashboard works without the Konta screen. Install the app and check that the values match the EMA app (quickstart step 4), and that an immediate pull-to-refresh shows "Dane są aktualne…" (step 5).

### Tests for User Story 1

- [ ] T028 [P] [US1] Repository tests in `android/app/src/test/java/pl/home/monitoring/data/apsystems/PvRepositoryTest.kt`, using a fake `ApsApiClient`, in-memory stores and a fixed clock:
  - (a) During the day with no snapshot: the repository calls minutely and summary, and emits `Data(Fresh)` with the merged values.
  - (b) A second automatic refresh within the interval makes no call.
  - (c) Minutely → NoData during the day: emits `NoDataYet`, with `todayKwh` 0.
  - (d) At night with a snapshot: emits `Data(Stale(Night(nextSunrise)))` and makes no minutely call.
  - (e) A summary that isn't due keeps the previous month, year and lifetime values in the new snapshot.
  - (f) A snapshot whose `sampleDate` is yesterday shows today as null until a new sample arrives.

### Implementation for User Story 1

- [ ] T029 [US1] `PvRepository` in `android/app/src/main/java/pl/home/monitoring/data/apsystems/PvRepository.kt`:
  - `val state: StateFlow<SectionState>`, which starts from the cached snapshot (`Data(Fresh or Stale)`) or `Loading`.
  - `suspend fun refresh(manual: Boolean): RefreshOutcome`:
    - Load the credentials. None → `NotConnected`.
    - Load the system info. No `ecuId` → call `details` and save it.
    - Evaluate `RefreshPolicy.decide`.
    - Call minutely for today (the date in the system timezone) and/or summary, as decided.
    - Merge into a new `PvSnapshot` (keep the previous totals when summary is skipped), save it to `SnapshotStore`, and emit the state.
  - A `Mutex` ensures one refresh at a time.
  - `RefreshOutcome` = `Fetched | Skipped(reason, nextAt) | Failed`.
  - For now, error results map to `Data(Stale(ServiceError))` (refined in US3).
  - T028 must pass.
- [ ] T030 [US1] `DashboardViewModel` in `android/app/src/main/java/pl/home/monitoring/ui/dashboard/DashboardViewModel.kt`:
  - Exposes `pvState: StateFlow<SectionState>`, `isRefreshing: StateFlow<Boolean>` and `messages: SharedFlow<String>` (for snackbars).
  - `onPullToRefresh()` launches `refresh(manual = true)` for every connected source in parallel with `coroutineScope { launch {…} }` (only PV for now), then maps a `Skipped` outcome to a snackbar message:
    - TooSoon → "Dane są aktualne (sprzed N min)".
    - BudgetExhausted → "Wykorzystano miesięczny limit zapytań — kolejna aktualizacja HH:MM".
    - Night → "Noc — kolejna aktualizacja o HH:MM".
  - `startAutoRefresh()` and `stopAutoRefresh()` run a loop: `refresh(manual = false)`, then `delay` until `nextScheduledAt`, with a minimum of 60 s.
  - A `ViewModelProvider.Factory` gets the repository from `AppContainer`.
- [ ] T031 [P] [US1] `PvSection` composable in `android/app/src/main/java/pl/home/monitoring/ui/dashboard/PvSection.kt`. It is an `ElevatedCard` titled "Fotowoltaika" with a solar icon, and it renders these states per contracts/ui-screens.md:
  - `Loading`: a progress indicator.
  - `Data`:
    - A large "Moc teraz" value with the note "o HH:MM".
    - A "Dziś" row.
    - A three-column row: "Miesiąc", "Rok", "Łącznie".
    - A footer: "Aktualizacja: HH:MM".
  - `NoDataYet`: "0 W", "0 kWh" and the note "Brak odczytów z dzisiaj".
  - `NotConnected`: the text "Podłącz konto APsystems" and a button that calls `onOpenAccounts`.
  - Values are formatted with `Formatters`. Add `@Preview`s for the Data and Loading states.
- [ ] T032 [US1] `DashboardScreen` in `android/app/src/main/java/pl/home/monitoring/ui/dashboard/DashboardScreen.kt`:
  - A `Scaffold` with a `TopAppBar` titled "Mój dom" and a settings `IconButton` (→ `onOpenAccounts`), plus a `SnackbarHost` fed from `viewModel.messages`.
  - Pull-to-refresh wrapping a `LazyColumn` of source sections, with only `PvSection` for now. BOM 2024.06.00 ships material3 1.2.1, so use the experimental `rememberPullToRefreshState()` with `Modifier.nestedScroll(state.nestedScrollConnection)` and `PullToRefreshContainer`, all `@OptIn(ExperimentalMaterial3Api::class)`. Drive `state.isRefreshing` from `isRefreshing`, and call `onPullToRefresh` when `state.isRefreshing` becomes true.
  - Use `LifecycleResumeEffect` / `repeatOnLifecycle(RESUMED)` to call `startAutoRefresh` on resume and `stopAutoRefresh` on pause (FR-014: refresh only while visible).
- [ ] T033 [US1] Wire `MainActivity` in `android/app/src/main/java/pl/home/monitoring/MainActivity.kt`:
  - `setContent { HomeMonitoringTheme { AppRoot() } }`, where `AppRoot` holds `var screen by rememberSaveable` with values `Dashboard` and `Accounts`. For US1, always start on Dashboard.
  - **Temporary debug seeding** (removed in T039): in a debug build with no saved credentials, when `CredentialsStore.devPrefill()` is non-null, save it on startup so US1 can be tested without the Konta screen.
  - `enableEdgeToEdge()`.

**Checkpoint**: With the dev prefill, the dashboard shows live PV data, and the app makes no API calls within the interval or at night.

---

## Phase 4: User Story 2 — Konfiguracja kont (Priority: P1)

**Goal**: The Konta screen:
- Connect APsystems with validation.
- Disconnect.
- Show the call budget and set the limit.
- Set the installation location.
- List Panasonic and tado as "Wkrótce".
- Start routing: nothing connected → Konta.

**Independent Test**: Follow quickstart steps 1–3, 8 and 9. A wrong secret shows an error that names APsystems and saves nothing. The correct credentials show "Połączono ✓ · 9,96 kWp" and move to Pulpit. Odłącz wipes the data.

### Tests for User Story 2

- [ ] T034 [P] [US2] Tests in `android/app/src/test/java/pl/home/monitoring/ui/accounts/AccountsViewModelTest.kt`, with a fake API client and in-memory stores:
  - (a) Blank fields → the Połącz button is disabled or validation fails with no API call.
  - (b) `details` → AuthError: an error message containing "APsystems"; `CredentialsStore` stays empty (FR-009).
  - (c) `details` → Success: credentials and system info are saved, the state becomes Connected with capacity 9.96.
  - (d) `disconnect()` clears credentials, system info and the snapshot.
  - (e) `setLimit(1500)` is coerced to 1000, and `setLimit(50)` to 100.
  - (f) A latitude of 95 is rejected.

### Implementation for User Story 2

- [ ] T035 [US2] `AccountsViewModel` in `android/app/src/main/java/pl/home/monitoring/ui/accounts/AccountsViewModel.kt`:
  - The form state: `appId`, `appSecret`, `sid` and `secretVisible`, initialised from `CredentialsStore.devPrefill()` in debug builds.
  - `connectionState`: `NotConnected | Connecting | Connected(capacityKwp, maskedSid) | Error(message)`.
  - `connect()`: trim the fields, call `ApsApiClient.details` (1 call), and on Success save the credentials (T024) and the system info (T025), then clear the snapshot. Map the error messages to Polish:
    - AuthError → "APsystems: nieprawidłowe App ID, App Secret lub SID".
    - Offline → "APsystems: brak połączenia z internetem".
    - Throttled → "APsystems: przekroczono limit zapytań, spróbuj później".
    - ServiceError → "APsystems: usługa niedostępna (kod X)".
  - `disconnect()`.
  - `budget: StateFlow<CallBudget>` and `setLimit(Int)`.
  - `location` and `setLocation(lat, lon)`.
  - The masked SID shows only the last 4 characters.
  - T034 must pass.
- [ ] T036 [US2] `AccountsScreen` in `android/app/src/main/java/pl/home/monitoring/ui/accounts/AccountsScreen.kt`:
  - A `Scaffold` with the title "Konta" and a back arrow, shown only when the screen was opened from Pulpit.
  - The **APsystems card**, per contracts/ui-screens.md:
    - `OutlinedTextField`s for "App ID", "App Secret" (`PasswordVisualTransformation` with a visibility toggle) and "System ID (SID)".
    - The "Połącz" button, which shows a progress indicator while connecting.
    - The error text in `colorScheme.error`.
    - When connected: "Połączono ✓ · 9,96 kWp", the masked SID and an "Odłącz" button with a confirm dialog.
  - Disabled cards for "Panasonic (pompa ciepła)" and "tado (pokoje)", each labelled "Wkrótce".
  - A "Budżet zapytań" card with the text "Wykorzystano X z N w tym miesiącu" and a numeric field for N (100–1000).
  - A "Lokalizacja instalacji" card with latitude and longitude fields and a "Zapisz" button.
- [ ] T037 [US2] Start routing and navigation in `android/app/src/main/java/pl/home/monitoring/MainActivity.kt`:
  - Wait for the first `CredentialsStore.credentials` emission, showing a blank or splash screen until then.
  - No credentials → start on Accounts. Credentials present → start on Dashboard.
  - A successful connect → navigate to Dashboard and trigger `refresh(manual = false)`.
  - Disconnect → stay on Accounts.
  - The Dashboard settings icon → Accounts. System back from Accounts → Dashboard, if connected.
- [ ] T038 [US2] In `android/app/src/main/java/pl/home/monitoring/ui/dashboard/PvSection.kt`, wire the `NotConnected` state's "Podłącz konto" button to navigate to Accounts. This covers User Story 2, acceptance scenario 4.
- [ ] T039 [US2] Remove the temporary debug auto-save from T033 in `android/app/src/main/java/pl/home/monitoring/MainActivity.kt`. From now on the dev prefill only fills the form.

**Checkpoint**: A fresh install follows the full flow: Konta → connect → Pulpit. Odłącz works.

---

## Phase 5: User Story 3 — Odporność na awarie (Priority: P2)

**Goal**: A failed fetch keeps showing the last data, with a reason and time. Throttling backs off. An auth error prompts the user to fix the credentials. Offline launches show cached data in under 1 s.

**Independent Test**: Follow quickstart step 6 (airplane mode, then relaunch). Use a MockWebServer-driven unit test for throttling and auth errors. Change the secret on the device to a wrong value (through a debug-only menu or by reconnecting) and check that the section shows "Nieprawidłowe dane dostępowe APsystems" with a button.

### Tests for User Story 3

- [ ] T040 [P] [US3] Extend `android/app/src/test/java/pl/home/monitoring/data/apsystems/PvRepositoryTest.kt` with these cases:
  - (g) Offline with a snapshot → `Data(Stale(Offline))`, and the snapshot values are unchanged.
  - (h) Throttled: each of the codes 7001, 7002 and 7003 → `Data(Stale(Throttled))`. `budget.throttledUntil` is set to the next scheduled slot (at least 15 min ahead) and doubles on repeats (capped at 6 h). **No** code blocks the rest of the month. The next automatic refresh before `throttledUntil` makes **no** call.
  - (i) AuthError → the state is `AuthError`, the snapshot is kept, and no further calls are made until the credentials change.
  - (j) An error when there is no snapshot → `SectionState.Error(reason)`: Offline → `Error(Offline)`, and ServiceError(500) → `Error(ServiceError(500))`. After a later success the state becomes `Data(Fresh)`.
  - (k) Minutely returns a 2xxx code other than a signature error, once → the repository re-fetches `details` once to refresh `ecuId`, then retries minutely once. That is at most 2 extra calls.

### Implementation for User Story 3

- [ ] T041 [US3] Refine error handling in `android/app/src/main/java/pl/home/monitoring/data/apsystems/PvRepository.kt`:
  - With a cached snapshot, map `Offline` → `Stale(Offline)`, `Throttled` → `Stale(Throttled)` plus the backoff update in `budget`, and `ServiceError(code)` → `Stale(ServiceError(code))`. Without a snapshot, emit `SectionState.Error(reason)` instead.
  - `AuthError` → the `AuthError` state. Save an `authBlocked` flag in memory, and clear it when the `credentials` flow emits a new value.
  - Add the ECU-id recovery from T040(k).
  - The first emission on start comes from `SnapshotStore` before any network call (SC-002).
  - T040 must pass.
- [ ] T042 [P] [US3] Render the stale and error states in `android/app/src/main/java/pl/home/monitoring/ui/dashboard/PvSection.kt`, per contracts/ui-screens.md:
  - A muted banner under the values:
    - Offline → "Brak połączenia — dane z HH:MM".
    - Throttled → "Limit zapytań APsystems — dane z HH:MM".
    - ServiceError → "Usługa niedostępna — dane z HH:MM", with a "Spróbuj ponownie" `TextButton` that calls `onRetry` (a manual refresh).
    - Night → "Moc teraz: 0 W — noc" and "Następna aktualizacja o HH:MM".
  - The `Error(reason)` state (no cached data): dashes in place of the values, plus a message. Offline → "Brak połączenia z internetem", Throttled → "Limit zapytań APsystems — spróbuj później", ServiceError → "Usługa APsystems niedostępna (kod X)". Each has a "Spróbuj ponownie" button → `onRetry`.
  - The `AuthError` state: the text "Nieprawidłowe dane dostępowe APsystems" and a "Popraw w ustawieniach" button → `onOpenAccounts`.
  - Add a `@Preview` for each state.
- [ ] T043 [US3] Wire `onRetry` in `android/app/src/main/java/pl/home/monitoring/ui/dashboard/DashboardViewModel.kt` and `android/app/src/main/java/pl/home/monitoring/ui/dashboard/DashboardScreen.kt`. Make sure the other sections (future sources) are refreshed independently, so one source's failure or exception never cancels another's. Use `supervisorScope` in `onPullToRefresh` and in the auto-refresh loop (FR-012).

**Checkpoint**: All three stories work for PV. Quickstart steps 1–9 pass.

---

## Phase 6: Polish & Cross-Cutting Concerns

- [ ] T044 [P] Move every user-visible Polish string from the composables and view models into `android/app/src/main/res/values/strings.xml`, and reference it via `stringResource` / `context.getString` (FR-016).
- [ ] T045 [P] Harden logging:
  - Grep `android/app/src/main/java` for `Log.` and `println`, and make sure no appSecret, signature or full credentials can be logged.
  - Keep the OkHttp logging interceptor at level BODY in debug builds only, with the `X-CA-Signature` header redacted via `redactHeader`. Configure this in `android/app/src/main/java/pl/home/monitoring/AppContainer.kt`.
- [ ] T046 [P] Release build configuration in `android/app/build.gradle.kts`: `isMinifyEnabled = true` with `proguard-android-optimize.txt`, and add the kotlinx.serialization keep rules to `android/app/proguard-rules.pro`. Verify that `./gradlew assembleRelease` succeeds (an unsigned build is fine).
- [ ] T047 [P] Add `android/README.md` (in Polish) covering:
  - How to build.
  - The `local.properties` prefill keys.
  - The quota explanation: 800 of 1,000 calls per month by default, no calls at night.
  - How to install on a phone with `adb install`.
- [ ] T048 Run the full validation: `cd android && ./gradlew testDebugUnitTest assembleDebug`. Then run the manual smoke test from `specs/001-home-energy-dashboard/quickstart.md`, steps 1–9, on a device or emulator, and record the results (including the budget counter) at the end of `specs/001-home-energy-dashboard/quickstart.md`.

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)** has no dependencies.
- **Foundational (Phase 2)** depends on Setup, and it blocks all stories. Within Phase 2, tests T009–T014 come first, then:
  - T016 and T017 lead to T019. T019 also depends on T018.
  - T020 leads to T021.
  - T023 leads to T024.
  - T027 comes last.
- **US1 (Phase 3)** depends on Phase 2.
- **US2 (Phase 4)** depends on Phase 2. For routing, T037 and T038 build on T032 and T033 from US1, and T039 depends on T033.
- **US3 (Phase 5)** depends on US1 (it refines `PvRepository`, `PvSection` and `DashboardViewModel`).
- **Polish (Phase 6)** comes after the desired stories.

### User Story Dependencies

```text
Setup → Foundational ─┬─> US1 (MVP) ─┬─> US3
                      └─> US2 ───────┘   (US2 routing tasks T037–T039 touch MainActivity after T033)
```

### Parallel Opportunities

- Phase 1: T004–T007 run in parallel after T003.
- Phase 2: all tests T009–T014 in parallel. Then T015, T016, T017, T020, T022, T023, T025 and T026 in parallel. T018 → T019, T021, T024 and T027 are sequential.
- US1: T028 and T031 in parallel with T029.
- US2: T034 can be written while US1 is in progress. T035 and T036 depend only on Phase 2.
- US3: T040 and T042 in parallel.

---

## Parallel Example: Foundational

```bash
Task: "Signer test in android/app/src/test/java/pl/home/monitoring/data/apsystems/ApsSignerTest.kt"
Task: "DTO parsing tests in android/app/src/test/java/pl/home/monitoring/data/apsystems/ApsDtosTest.kt"
Task: "SunCalculator tests in android/app/src/test/java/pl/home/monitoring/core/SunCalculatorTest.kt"
Task: "RefreshPolicy tests in android/app/src/test/java/pl/home/monitoring/core/RefreshPolicyTest.kt"
Task: "Formatter tests in android/app/src/test/java/pl/home/monitoring/core/FormattersTest.kt"
```

## Parallel Example: User Story 1

```bash
Task: "Repository tests in android/app/src/test/java/pl/home/monitoring/data/apsystems/PvRepositoryTest.kt"
Task: "PvSection composable in android/app/src/main/java/pl/home/monitoring/ui/dashboard/PvSection.kt"
```

---

## Implementation Strategy

### MVP First (User Story 1)

1. Phase 1: Setup. Phase 2: Foundational. Stop when the unit tests are green; the quota logic is proven at that point.
2. Phase 3: US1, using the dev prefill.
3. **STOP and VALIDATE** with quickstart steps 4 and 5 on a device. This costs about 3 API calls.

### Incremental Delivery

1. The US1 dashboard is the first usable build.
2. US2 lets anyone connect an account without `local.properties`.
3. US3 adds resilience (offline, throttling, auth errors).
4. Later iterations add Panasonic (`data/panasonic`) and tado (`data/tado`) as new sections, with new specs or tasks.

---

## Notes

- Never commit `android/local.properties` or any real AppId, AppSecret or SID.
- Each device smoke test spends real quota. Check the "Budżet zapytań" counter.
- Commit after each phase checkpoint.
