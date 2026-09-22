# Quickstart / validation: etap 1 (PV)

## Prerequisites (already present on this machine)

- JDK 17 (`~/tools/jdk-17…`), Gradle 8.7, and the Android SDK (`~/Android/Sdk`) with platform 34 and build-tools 34.0.0.
- An Android phone with USB debugging enabled, or an emulator (`adb devices` must list it).
- APsystems OpenAPI credentials. They are kept outside the repo, in `C:\Develop\apsystems\apsystems-credentials.md`.

## Build and unit tests

```bash
cd android
gradle wrapper --gradle-version 8.7   # first time only
./gradlew testDebugUnitTest           # must pass; never calls the real API
./gradlew assembleDebug
```

The unit tests must cover the signer test vector ([contracts/apsystems-openapi.md](contracts/apsystems-openapi.md)), DTO parsing of the live shapes (including `today:null` and code 1001), the RefreshPolicy ([data-model.md](data-model.md)) and error mapping.

## Optional: prefill credentials for debug builds

Add the following to `android/local.properties`, which is gitignored and must **never** be committed:

```properties
apsystems.appId=…
apsystems.appSecret=…
apsystems.sid=…
```

## Manual smoke test on a device (costs about 3–5 API calls)

```bash
./gradlew installDebug && adb shell am start -n pl.home.monitoring/.MainActivity
```

| # | Step | Expected result |
|---|---|---|
| 1 | First launch | The Konta screen opens, with APsystems enabled and Panasonic/tado marked "Wkrótce" |
| 2 | Enter a wrong App Secret, then tap Połącz | An error that names APsystems. Nothing is saved |
| 3 | Enter the correct credentials, then tap Połącz | "Połączono ✓ · 9,96 kWp". The app goes to Pulpit |
| 4 | Pulpit during the day (on the first open after connecting, measure with a stopwatch from tapping the app icon) | Fresh values appear in under 5 s (SC-001). Moc teraz and Dziś match the APsystems EMA app (within the ~5-min cloud delay). Month/Year/Łącznie are filled in. "Aktualizacja" shows the current time |
| 5 | Pull to refresh immediately | Snackbar "Dane są aktualne…". The budget counter does **not** increase |
| 6 | Turn on airplane mode, then kill and relaunch the app | Cached values appear in under 1 s, with the note "Brak połączenia — dane z HH:MM" |
| 7 | Open the app after sunset | The "noc" label. No new calls are made once the post-sunset summary has been fetched |
| 8 | Konta → Odłącz | The app returns to the Konta screen. Relaunching doesn't show old data |
| 9 | Konta → Budżet zapytań | "Wykorzystano X z 800" matches the number of calls made during this test |

## Done criteria for this iteration

- Every requirement marked **Delivered** in the "Requirement scope for etap 1" table in [plan.md](plan.md) is met. Deferred and not-applicable requirements are listed in that same table.
- SC-002, SC-003 and SC-005 hold for PV (SC-005 is checked in step 6).
- Using the app normally stays under the monthly budget. This follows from RefreshPolicy and is checked by its unit tests.
