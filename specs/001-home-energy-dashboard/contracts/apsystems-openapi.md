# Contract: APsystems OpenAPI (consumed)

The app is a **client** of this API. This contract fixes the subset the app relies on, which was verified live on 2026-09-23. Automated tests use fixtures of exactly these shapes and never hit the real API.

The error code meanings are taken from the community integration [emlynmac/apsystems-openapi](https://github.com/emlynmac/apsystems-openapi), because the official manual PDF could not be machine-read.

## Base and authentication

- Base URL: `https://api.apsystemsema.com:9282`
- Every request is `GET`, with the headers below:

| Header | Value |
|---|---|
| `X-CA-AppId` | AppId |
| `X-CA-Timestamp` | Current epoch **milliseconds**, decimal string |
| `X-CA-Nonce` | 32 lowercase hex characters (a UUID without dashes) |
| `X-CA-Signature-Method` | `HmacSHA256` |
| `X-CA-Signature` | `Base64(HMAC_SHA256(key = AppSecret, msg = stringToSign))` |

`stringToSign = "{timestamp}/{nonce}/{appId}/{lastPathSegment}/{METHOD}/HmacSHA256"`, where `lastPathSegment` is the final path segment **without** the query string (for example `summary`, `D20B…` for a SID, or the ECU id).

### Signer test vector (fake keys, generated with the reference Python implementation)

```text
appId     = testAppId
appSecret = testSecret
timestamp = 1700000000000
nonce     = 0123456789abcdef0123456789abcdef
path      = /user/api/v2/systems/summary      → lastPathSegment = summary
method    = GET
stringToSign = 1700000000000/0123456789abcdef0123456789abcdef/testAppId/summary/GET/HmacSHA256
signature    = /+AeRWeNIkLQanWAJAq3DiMz9H7s7uHYU+PeiBmNP9g=
```

## Envelope

All responses are HTTP 200 with a JSON body `{"code": <int>, "data": <any>}`.

| code | Meaning | App mapping |
|---|---|---|
| 0 | Success | Parse `data` |
| 1001 | No data (for example no samples yet today) | Not an error. Report `NoDataYet` (zero power, today = 0 or null) |
| 4000 | Wrong signature, AppId or AppSecret (or device clock drift) | `AuthError`. The section asks the user to check the credentials |
| 2000–2004, 2006–2999, 4001–4999 | Account problems or an invalid parameter (for example a wrong SID or ECU id) | `AuthError`. For the minutely call, first try ECU-id recovery (see data-model.md) |
| 2005 | Monthly access quota exhausted | `Throttled` |
| 5000 | No data yet, or a transient server error | `ServiceError(5000)` |
| 7001, 7002, 7003 | Server rate limit (a per-half-hour limit that lasts at most 30 min) | `Throttled`, with the same handling for all three. No retry loop in the app: it backs off to the next scheduled slot, doubling the pause on repeats (up to 6 h). See `CallBudget.throttledUntil` in data-model.md |
| any other | Server-side error | `ServiceError(code)` |

A non-200 HTTP status, a timeout or an IO failure maps to `Offline` or `ServiceError`. The body may contain the reason, and it is logged only in debug builds.

## Endpoints used

### 1. System details: called on account connect, or when the ECU id is unknown

`GET /user/api/v2/systems/details/{sid}`

```json
{"code":0,"data":{"sid":"…","type":1,"capacity":"9.96","timezone":"Europe/Warsaw",
 "ecu":["<eid>"],"create_date":"2020-07-31","light":1,"authorization_code":"…"}}
```

The app uses `ecu[0]` (the first ECU; more than one ECU is out of scope for v1), `timezone` and `capacity` (a string in kWp).

### 2. System summary: at most every 3 h plus once after sunset

`GET /user/api/v2/systems/summary/{sid}`

```json
{"code":0,"data":{"today":null,"month":"617.66","year":"6166.11","lifetime":"28320.78"}}
```

All values are kWh **strings**, and any of them can be `null`. `null` means 0 for `today`, and "unknown" for the others.

### 3. ECU minutely data for today: the main refresh call

`GET /user/api/v2/systems/{sid}/devices/ecu/energy/{eid}?energy_level=minutely&date_range=YYYY-MM-DD`

The date is today in the **system timezone**.

```json
{"code":0,"data":{"today":"20.17",
  "time":["06:05","06:15","06:25", "…", "18:45"],
  "power":[0,12,20, "…", 24],
  "energy":["0.00","0.00","0.00", "…", "0.00"]}}
```

- The `time`, `power` and `energy` arrays have the same length (for example 138 samples). Samples come about every 5–10 min.
- `power` is in W (int). The current power is the last element, and its time is the matching element of `time` (local system time).
- `today` is a kWh string.
- When there is no sample yet today, the response is `{"code":1001,...}`.

Endpoint 3 is the **only** call in a regular daytime refresh, so a refresh costs 1 call.
