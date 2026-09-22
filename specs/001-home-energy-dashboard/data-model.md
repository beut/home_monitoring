# Data Model: etap 1 (PV / APsystems)

All data is stored on the device only. There is no server-side storage.

## ApsCredentials (encrypted, see research R6)

| Field | Type | Rules |
|---|---|---|
| appId | String | Required, trimmed, non-empty |
| appSecret | String | Required, trimmed, non-empty. Never logged or displayed except while the user is editing it |
| sid | String | Required, trimmed, non-empty |

Saved **only after** a successful `details` call (FR-009). Deleted on Odłącz.

## ApsSystemInfo (plain DataStore)

| Field | Type | Source |
|---|---|---|
| ecuId | String | `details.data.ecu[0]` |
| timezone | ZoneId | `details.data.timezone`. Falls back to `Europe/Warsaw` when invalid |
| capacityKwp | Double? | `details.data.capacity` (parsed from a string) |

Re-fetched when a `minutely` call fails with an error that suggests the ECU id is invalid (a 2xxx code other than a signature error). Otherwise it is fetched once.

## SourceConnection (derived)

`NotConnected` → `Connected` → (`AuthError` ↔ `Connected`) → `NotConnected`

| Transition | Trigger |
|---|---|
| NotConnected → Connected | Połącz, when `details` returns code 0 |
| Connected → AuthError | Any call returns a 2xxx code |
| AuthError → Connected | The user corrects the credentials and `details` returns 0 |
| any → NotConnected | Odłącz |

## PvSnapshot (cached JSON, the last successful data)

| Field | Type | Notes |
|---|---|---|
| currentPowerW | Int? | The last `power[]` value. `null` when code 1001 is returned |
| powerSampleTime | LocalTime? | The last `time[]` value (system timezone) |
| sampleDate | LocalDate | The date that `date_range` was requested for. A snapshot from a previous day shows Dziś = 0 until the first sample |
| todayKwh | Double? | From `minutely.today`, or `summary.today`. `null` means 0 |
| monthKwh, yearKwh, lifetimeKwh | Double? | From `summary`. They keep their previous value when `summary` is not fetched in this refresh |
| fetchedAt | Instant | When the minutely data was last fetched successfully |
| summaryFetchedAt | Instant? | When the summary was last fetched successfully |

Validation: power values below 0 are clamped to 0. A kWh string that can't be parsed becomes `null`, so it is shown as a dash and never crashes the app.

## SectionState (UI, in memory)

```text
Loading
Data(snapshot, freshness)   freshness ∈ Fresh | Stale(reason)
                            reason ∈ Offline | Throttled | ServiceError(code) | Night
NoDataYet(snapshot?)
Error(reason)               fetch failed and there is NO cached snapshot yet;
                            reason ∈ Offline | Throttled | ServiceError(code)
AuthError
NotConnected
```

When a snapshot exists, a failed fetch always gives `Data(snapshot, Stale(reason))`, never `Error`.

## CallBudget (plain DataStore)

| Field | Type | Rules |
|---|---|---|
| monthKey | YearMonth | When the current month ≠ monthKey, `used` is reset to 0 |
| used | Int | Incremented **before** every HTTP call to APsystems, including failed ones |
| monthlyLimit | Int | Default 800. Allowed range 100–1000 |
| throttledUntil | Instant? | Set on **any** of 7001/7002/7003 to the next scheduled slot (`RefreshDecision.nextScheduledAt`, at least 15 min ahead). The exact meaning of each code is unconfirmed, so no code blocks the rest of the month. If throttling repeats, the pause doubles each time (15 → 30 → 60 min, capped at 6 h), and a successful call resets it. The app stops for the rest of the month only when its own counter reaches `used >= monthlyLimit` |

## RefreshPolicy (pure function, no storage)

Inputs: now, the system timezone, the location (lat and lon), CallBudget, PvSnapshot, and whether the refresh is manual.

- `isDaylight(now)`: sunrise ≤ now ≤ sunset + 15 min, using the NOAA solar algorithm.
- `interval`: `max(15 min, remainingDaylightMinutesThisMonth / max(1, remainingBudget − reservedSummaryCalls))`, where `reservedSummaryCalls = Σ over the remaining days of this month (including today) of (ceil(daylightHours(day) / 3) + 1)`. Each day's daylight hours come from SunCalculator. For today, count only the summary slots still ahead. In central Poland that is about 4 per day in December and about 7 per day in June.
- `shouldFetchMinutely`:
  - It is daylight, **and** the budget is not used up, **and** the source is not throttled, **and** either:
    - it is an automatic refresh and `now − fetchedAt ≥ interval`, or
    - it is a manual refresh and `now − fetchedAt ≥ 10 min`.
- `shouldFetchSummary`:
  - The budget allows it, **and** either:
    - `summaryFetchedAt` is null, or
    - `now − summaryFetchedAt ≥ 3 h` during daylight, or
    - it is the first check after sunset and the summary has not been fetched since sunset.
- The policy's result also includes `nextScheduledAt`, which the UI uses for its "Następna aktualizacja" text.

## LocationSetting (plain DataStore)

| Field | Type | Default |
|---|---|---|
| latitude | Double (−90…90) | 52.0 |
| longitude | Double (−180…180) | 19.0 |
