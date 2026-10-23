# CLAUDE.md

Project: **The Invisible Journey**, an Android companion app that shows customers the hidden effort of instant-delivery riders (concept: auto-rickshaw-style "journey meter"; mode of engagement: Exposure). Class project.

## Hard constraints
- **Zero cost.** No paid APIs, no billing accounts (no Google Maps SDK / Google Routes). Free: Open-Meteo, Overpass, osmdroid/OSM tiles, TomTom free key (optional). Only physical sensors may cost money.
- **Scope = factors 1–8** of `docs/02_SENSORS.md` (location, speed, distance, waiting, potholes/roughness, congestion, weather, air quality). Factors 9–14 are out of scope.

## Read first
- `README.md`: overview, how to run
- `docs/02_SENSORS.md`: what each metric is and how it's computed
- `docs/04_ARCHITECTURE.md`: layers and data flow
- `docs/05_ESP32_INTEGRATION.md`: BLE protocol (source of truth for UUIDs, packets, opcodes)

## Frontend work (UI changes)
Read `docs/08_FRONTEND_GUIDE.md` first. In short: all UI is in `ui/` (Compose + Material 3); colours, sizes and text styles are in `ui/Theme.kt` (`Palette`, `Dimens`, `Num`, `Format`), reusable widgets in `ui/components/`, routes in `AppNav()` in `MainActivity.kt`. Screens only read `ActiveJourney.state` and `JourneyStore`; never call `JourneyEngine` from UI. Use `FitText` in fixed-size tiles, keep touch targets >= `Dimens.Touch`, show `—` for missing data. A UI request that needs a new number is also a `domain/` change with a test. Verify with `gradlew assembleDebug` (and `gradlew test` if `domain/` changed); the sample journeys and the rider demo ride exercise every screen without sensors.

## Code map (`app/src/main/java/com/invisiblejourney/`)
- `domain/`: **pure Kotlin, no Android imports.** `JourneyEngine` (all meter logic), `processor/` (distance, stops, potholes, roughness, congestion, AQI, summary), `SampleJourneys` (synthetic demo data run through the real engine), `model/`.
- `data/`: `sensors/PhoneSensors.kt` (GPS, IMU as Flows), `remote/RemoteApis.kt` (Open-Meteo, Overpass, TomTom), `JourneyStore` (one JSON file per journey = export format).
- `service/`: `JourneyTrackingService` (foreground, type location) owns `JourneyRecorder`, which feeds the engine on a single thread and publishes `ActiveJourney.state`.
- `ui/`: Compose screens (mode select, rider home, live meter, library, replay, story) and components (`MeterDial`, `JourneyMap`, chips, breakdown).
- `AppContainer`: hand-written DI (no Hilt). No Room: journeys are JSON files.

## Conventions
- Kotlin, Jetpack Compose, Material 3, Coroutines/Flow.
- New metric logic goes in `domain/` with a unit test in `app/src/test`. Engine calls must stay single-threaded.
- Every metric carries a `SourceTag` (`PHONE` / `ESP32` / `API` / `ESTIMATED`). Show "estimated" honestly; never fake zeros.
- Privacy: no raw audio, no rider identity, rider starts/stops recording.
- API keys live in `local.properties` only (`TOMTOM_API_KEY`).
