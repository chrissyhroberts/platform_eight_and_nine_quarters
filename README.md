# Platform 8 9/4

Platform 8 9/4 is an Android live UK rail awareness dashboard built around Darwin Staff Live Departure Boards. It automatically watches an open route, preserves raw operational state, and makes platform suppression, live timings, formations, loading and delay reasons easy to inspect.

The project is independent and is not endorsed by National Rail or the Rail Delivery Group.

## Highlights

- Automatic foreground polling at a configurable 15, 30 or 60 second interval.
- Stable service rows keyed primarily by Darwin RID.
- Separate local-fetch and railway `generatedAt` freshness indicators.
- Explicit `LIVE`, `STALE` and `OFFLINE` states while retaining the last useful board.
- Staff LDBSVWS as the primary source, with optional Public LDBWS comparison.
- Raw hidden and suppressed Staff platforms remain visible, including the last observed platform.
- Numeric delay, cancellation and diversion codes resolved through Darwin's reason-code reference data.
- Scheduled, forecast and actual route timings, formation and coach-loading details.
- Passenger calling points only; operational timing and pass locations are excluded from the visible stop list.
- `London (Any)` aggregation for the King's Cross and St Pancras cluster.
- Configurable home-screen departure-board widget showing the next three matching trains.
- Optional foreground-service monitoring with platform and disruption notifications.
- Durable SQLite observation and transition history.
- Offline GB station directory with optional Rail Data Marketplace reference-data refresh.

## Requirements

- Android 9 or newer (API 28+).
- Android Studio with JDK 17 or newer.
- Android SDK platform 36.
- A Rail Data Marketplace consumer key subscribed to the Staff Live Departure Board product.
- A separate Rail Data Marketplace Reference Data consumer key to translate Darwin reason codes into text.
- Optionally, access to Public Live Departure Boards.

## Getting started

1. Clone the repository and open its root folder in Android Studio.
2. Let Android Studio install the requested SDK components and complete Gradle sync.
3. Run the `app` configuration on an emulator or Android device.
4. Open the app's settings and enter the Staff RDM consumer key.
5. Enter the Reference Data consumer key and copy the `GetReasonCodeList` endpoint from that product's specification. This is required for delay and cancellation descriptions.
6. Optionally enter the Public comparator key.
7. Choose an origin and destination, then open the live route.

RDM endpoints authenticate with the consumer key in the `x-apikey` header. The consumer secret is not used by these endpoints.

No credentials are committed to this repository. The app encrypts entered keys with AES-GCM backed by Android Keystore and excludes them from Android backup and device transfer.

## Data sources

The configured defaults are:

- Staff board: `LDBSVWS/api/20220120/GetDepBoardWithDetails/{crs}/{time}`
- Reference Data reasons: `LDBSVWS/api/ref/20211101/GetReasonCodeList`
- Public comparator: `LDBWS/api/20220120/GetDepartureBoard/{crs}`

Staff delay/cancellation objects contain numeric codes. The reason catalogue belongs to the separate RDM Reference Data product and uses that product's consumer key. Platform 8 9/4 downloads the catalogue, caches it for 24 hours in the running app, and combines each code with its delay or cancellation description and optional TIPLOC context. If only a Reference Data `GetStationList` endpoint is saved, the app derives the matching `GetReasonCodeList` URL automatically.

## Live watching and widgets

Opening a route starts live foreground polling without a separate refresh action. Pull-to-refresh remains available as an escape hatch. A watched route can continue through an Android `dataSync` foreground service, subject to Android's background execution limits.

The home-screen widget uses the same repository and observation store as the app. It does not create a second polling loop. Its switch starts or pauses a short monitoring lease for the selected route.

## London (Any)

As an origin, `London (Any)` watches the King's Cross/St Pancras cluster (`KGX`, `STP`, `SPX`). As a destination, it matches direct services whose destination or passenger calling points include the configured central London set.

This is a live-board intelligence tool, not a multi-leg journey planner or ticket-validity engine. Journeys requiring a change are outside the current scope.

## Build and test

The Gradle wrapper is included:

```bash
./gradlew testDebugUnitTest lintDebug assembleDebug
```

Instrumentation tests require an emulator or test device:

```bash
./gradlew connectedDebugAndroidTest
```

Do not run instrumentation tests against a personal production installation; they deliberately write synthetic app settings and test observations.

## Project structure

- `app/src/main/java/uk/ac/rawrail/data` — Darwin clients, repository, persistence and route logic.
- `app/src/main/java/uk/ac/rawrail/ui` — application state and route presentation model.
- `app/src/main/java/uk/ac/rawrail/watch` — continued monitoring and notification summaries.
- `app/src/main/java/uk/ac/rawrail/widget` — home-screen widget and configuration UI.
- `app/src/main/res` — Compose theme, widget layout and launcher artwork.
- `app/src/test` — parser and route-behaviour regression tests.

## Privacy and security

- API keys remain encrypted on the device.
- Redirects are disabled for authenticated railway requests.
- Only official HTTPS railway hosts are accepted.
- The app contains no HTTP body logger, crash uploader or credential export function.
- Successful raw Staff and Public responses are retained in the app-private observation database until application data is cleared.

## Release

The current release is **v0.20.2**. See [the release notes](docs/releases/v0.20.2.md) for the corrected Reference Data reason-code integration.

## Licence

Platform 8 9/4 is available under the [MIT License](LICENSE). The bundled station directory has its own attribution notice in [`GB_STATIONS_NOTICE.txt`](app/src/main/assets/GB_STATIONS_NOTICE.txt).
