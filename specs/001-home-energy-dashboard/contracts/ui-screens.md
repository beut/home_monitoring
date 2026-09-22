# Contract: UI screens and states (etap 1 — PV)

All UI text is in Polish (FR-016). The app has two screens: **Pulpit** and **Konta**.

## Start routing

| Condition | First screen |
|---|---|
| No source connected | Konta (User Story 2 / scenario 1) |
| At least one source connected | Pulpit |

## Pulpit (dashboard)

- A vertical list of source sections. In this iteration only **Fotowoltaika** is shown.
- A top bar with the title "Mój dom" and an icon that opens Konta.
- Pull-to-refresh triggers `refresh(manual = true)` for every connected source in parallel.

### Fotowoltaika section: required content by state

| State | Visible content |
|---|---|
| `Loading` (no cache) | Section title and a progress indicator |
| `Data(fresh)` | **Moc teraz**: `1,23 kW` (or `850 W` below 1 kW) with "o HH:MM" (sample time) · **Dziś**: `12,4 kWh` · Miesiąc / Rok / Łącznie in kWh (a dash when unknown) · "Aktualizacja: HH:MM" |
| `Data(stale, reason)` | The same values, plus a muted banner: `Offline` → "Brak połączenia — dane z HH:MM" · `Throttled` → "Limit zapytań APsystems — dane z HH:MM" · `ServiceError` → "Usługa niedostępna — dane z HH:MM" with a **Spróbuj ponownie** button |
| `Night` | Moc teraz "0 W — noc", Dziś = the day's final value, "Następna aktualizacja o HH:MM" (sunrise) |
| `NoDataYet` (code 1001 during the day) | Moc teraz "0 W", Dziś "0 kWh", with the note "Brak odczytów z dzisiaj" |
| `Error(reason)` (the fetch failed and there is no cached data yet) | The value placeholders show a dash. Offline → "Brak połączenia z internetem" · Throttled → "Limit zapytań APsystems — spróbuj później" · ServiceError → "Usługa APsystems niedostępna (kod X)". Each has a **Spróbuj ponownie** button |
| `AuthError` | "Nieprawidłowe dane dostępowe APsystems" with a **Popraw w ustawieniach** button |
| `NotConnected` | "Podłącz konto APsystems" with a button |

The number format uses the Polish locale: a decimal comma, one decimal place for kW and kWh.

Manual refresh throttled by the policy (the cache is less than 10 min old, or the budget is used up): the app shows a snackbar, for example "Dane są aktualne (sprzed 4 min)" or "Wykorzystano miesięczny limit zapytań — kolejna aktualizacja HH:MM".

## Konta (accounts)

For each source there is a card. In this iteration only APsystems is enabled. Panasonic and tado are listed as disabled with the note "Wkrótce".

APsystems card:

- Fields: **App ID**, **App Secret** (masked, with a show toggle) and **System ID (SID)**.
- The **Połącz** button validates by calling `details/{sid}` (1 call):
  - On success, the app saves the credentials, the ECU id and the timezone, and shows "Połączono ✓ · 9,96 kWp".
  - On failure, it saves nothing and shows an error that names APsystems (FR-009).
- When connected, the card shows the SID (masked except the last 4 characters), a status, and an **Odłącz** button. Odłącz wipes the credentials and the cache (User Story 2 / scenario 5).
- The **Budżet zapytań** section shows "Wykorzystano X z N w tym miesiącu" and a field for N (default 800).
- The **Lokalizacja instalacji** section has latitude and longitude fields, used only for sunrise and sunset. The default is 52.0, 19.0 (central Poland).
