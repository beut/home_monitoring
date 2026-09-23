# Research: Pulpit monitoringu domu — etap 1 (fotowoltaika APsystems)

**Date**: 2026-09-23 | **Plan**: [plan.md](plan.md)

Scope of this iteration (user input to `/speckit-plan`): **only PV data from APsystems**. Panasonic and tado are deferred to later iterations; the design leaves a slot for them (see R8).

Sources: the user's reference script `C:\Develop\apsystems\apsystems_yearly_modules.py` (signing and endpoints), 5 live probe calls made on 2026-09-23 against the user's system (response shapes below), the APsystems OpenAPI End-User Manual, and community integrations.

---

## R1. Data source for PV: the APsystems EMA OpenAPI

- **Decision**: Use the official APsystems OpenAPI (`https://api.apsystemsema.com:9282`) with AppId, AppSecret and SID, the same way as the reference script.
- **Rationale**: The user already has working OpenAPI credentials. It is the official, documented API, and the reference script and live probes confirm it works for this system (SID capacity 9.96 kWp, 1 ECU, 6 inverters of types QS1/DS3/DS3D).
- **Alternatives considered**: The EMA web login scraping or the reverse-engineered EasyPower app API. Both are unofficial and fragile, and they need the user's EMA password. The local ECU API only works on the home LAN, while the app must work anywhere.

## R2. Which endpoints give "current power" and "energy today"

Live-verified response shapes:

| Need | Endpoint | Observed |
|---|---|---|
| ECU id, timezone, capacity | `GET /user/api/v2/systems/details/{sid}` | `data.ecu: ["<eid>"]`, `data.timezone: "Europe/Warsaw"`, `data.capacity: "9.96"` (strings) |
| Month / year / lifetime totals | `GET /user/api/v2/systems/summary/{sid}` | `data: {today, month, year, lifetime}` — kWh as **strings**; `today` is **`null`** before first production of the day |
| Current power + today | `GET /user/api/v2/systems/{sid}/devices/ecu/energy/{eid}?energy_level=minutely&date_range=YYYY-MM-DD` | `data: {today:"20.17", time:["06:05",…], power:[0,12,…], energy:["0.00",…]}` — ~5-min resolution, power in W as numbers; **code 1001 ("no data") at night before the first sample of the day** |

- **Decision**: "Current power" = the last element of `power[]` from the ECU minutely call for today, with its `time[]` timestamp shown to the user. "Energy today" = `data.today` from that same call. Month/year/lifetime come from `summary`.
- **Rationale**: One call returns both headline numbers. The ECU uploads to the cloud about every 5 minutes, so the timestamp tells the user how fresh the value is.
- **Alternatives considered**: Summary only, which has no current power. Summing per-inverter data would cost one call per inverter per refresh, which is unaffordable (R3).

## R3. API call quota: 1,000 calls per month (this drives the refresh design)

- **Finding**: The APsystems OpenAPI allows end users **1,000 calls per month** per AppId. The Home Assistant OpenAPI integration tunes its polling to stay under this limit and allows fixed intervals of only 30 min to 2 h. The monthly quota error is code 2005, and 7001–7003 are short-term rate limits.
- **Impact on the spec**: A refresh every 5 minutes (FR-006 as first written) would use about 290 calls per day and exhaust the monthly quota in 3–4 days. The quota is also **shared** with the user's own scripts that use the same AppId.
- **Decision**: A budget-driven refresh policy:
  1. **App budget**: 800 calls per month by default (configurable), leaving about 200 for the user's scripts. The app counts its own calls locally per calendar month.
  2. **Daylight only**: the app makes no PV calls between sunset and sunrise. Sun times are computed on the device (NOAA algorithm) from the system timezone and a configurable location, defaulting to central Poland. At night the app shows the cached values, labelled as night.
  3. **Adaptive interval**: `interval = remaining daylight minutes this month / (remaining budget − reserved summary calls)`, never less than 15 min. The reserve holds `ceil(daylight hours / 3) + 1` summary calls for each remaining day, which is about 4 in December and about 7 in June (see data-model.md). That comes to about 30 min in September, about 40 min in June and about 20 min in December.
  4. **Summary** (month/year/lifetime) is fetched at most every 3 h and once after sunset. `details` is fetched only when an account is connected, or when the ECU id is unknown or invalid.
  5. **Refresh on open or manually**: the app fetches only when the cached value is older than the current interval. A manual refresh fetches only when the cached value is more than 10 min old and the budget is not used up. Otherwise it tells the user why no new data was fetched.
  6. If the API returns 2005, 7001, 7002 or 7003, the app marks the source as throttled and keeps showing cached data. Code 2005 means the monthly quota is exhausted and 7001–7003 are per-half-hour rate limits (per a community integration; the manual PDF couldn't be read). All four get the same handling: back off until the next slot, doubling the pause on repeats (up to 6 h). The app stops for the month only when its own counter reaches the limit.
- **Alternatives considered**: A fixed 30-min interval, which is simpler but wastes calls at night and still breaks the budget in summer. A background worker, which is out of scope: the spec says refresh happens only while the app is visible.

## R4. Request signing

- **Decision**: Port `_sign()` from the reference script exactly: `stringToSign = ts + "/" + nonce + "/" + appId + "/" + lastPathSegment + "/" + METHOD + "/HmacSHA256"`, then HMAC-SHA256 with AppSecret, then Base64. Headers are `X-CA-AppId`, `X-CA-Timestamp` (epoch ms), `X-CA-Nonce` (32 hex characters), `X-CA-Signature-Method`, `X-CA-Signature`.
- **Verification**: A test vector computed with the Python implementation, using fake keys, is recorded in [contracts/apsystems-openapi.md](contracts/apsystems-openapi.md). The Kotlin unit test must reproduce it.

## R5. Platform and stack

- **Decision**: Native Android app in Kotlin with Jetpack Compose (Material 3), in the `android/` directory. This matches the repository's `.gitignore` and the installed toolchain.
- **Versions**: These are pinned to what is already in the local Gradle cache and SDK, so the build works offline-first: AGP 8.5.2, Gradle 8.7, Kotlin 1.9.24, Compose BOM 2024.06.00 (compiler extension 1.5.14), compileSdk/targetSdk 34, minSdk 26, JDK 17.
- **Libraries** (kept to a minimum):
  - Networking: OkHttp 4.12.
  - JSON: kotlinx.serialization 1.6.x, because the fields are quirky (numbers sent as strings, nulls, arrays of mixed types).
  - Async: kotlinx.coroutines 1.8.
  - Persistence: AndroidX DataStore (Preferences).
  - Lifecycle and ViewModel: lifecycle 2.8.3.
  - No DI framework: a single manual `AppContainer`.
  - No navigation library: there are two screens and a simple state switch.
- **Alternatives considered**: Flutter or React Native. Neither toolchain is installed here, and the user's `.gitignore` targets native Android. Hilt, Retrofit and Room would be overkill for 3 endpoints and a single cached snapshot.

## R6. Secure credential storage (FR-010)

- **Decision**: Encrypt AppSecret (and, for simplicity, AppId and SID too) with an AES-256-GCM key held in the **Android Keystore** (non-exportable). Store the ciphertext and IV in DataStore. Exclude credentials from Android backup (`dataExtractionRules` / `fullBackupContent`).
- **Rationale**: Credentials never leave the device except inside signed requests to `api.apsystemsema.com`. Only the HMAC signature is sent, never the AppSecret itself.
- **Alternatives considered**: `androidx.security:security-crypto` (EncryptedSharedPreferences), which is deprecated upstream. Tink would add a dependency just to wrap about 30 lines of Keystore code.
- **Dev convenience**: debug builds can prefill the account form from `android/local.properties` (`apsystems.appId`, `apsystems.appSecret`, `apsystems.sid`). That file is already gitignored. Release builds never read it.

## R7. Offline and stale data (FR-011, User Story 3)

- **Decision**: After each successful fetch, save the last `PvSnapshot` as JSON in DataStore. On app start, show the cached snapshot immediately (SC-002). Every section shows "Aktualizacja: HH:MM" and, when data is stale, a reason: offline, throttled, night, or error.
- **Rationale**: This makes it cheap to meet the "<1 s to first content" goal, and it is required anyway because of the quota.

## R8. Extensibility for Panasonic and tado (later iterations)

- **Decision**: The dashboard renders a list of `SourceSection`s. Each source (`PV`, and later `HEAT_PUMP` and `ROOMS`) has its own repository, state flow and refresh policy, and they are refreshed in parallel (FR-002, FR-012). In this iteration only `PV` is implemented. The other sections are **not shown**, so there are no dead "coming soon" cards.
- **Rationale**: Adding a source later means adding one repository and one card composable, with no changes to the PV code.

## R9. Testing approach

- **Decision**: JVM unit tests for the pure logic, and a documented manual smoke test on a device or emulator (quickstart).
  - Unit tests cover the signer (test vector), DTO parsing (fixtures copied from the live shapes in R2, including `today: null` and code 1001), the refresh policy (budget, daylight, min interval), the sun calculator (known sunrise/sunset for Warsaw on fixed dates, ±5 min), and repository error mapping using OkHttp MockWebServer.
- **Rationale**: Every call against the live API spends quota, so automated tests must never hit the real API.
