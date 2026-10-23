# 04 — Architecture

## High-level

```
                         ┌──────────────────────────── Android App ───────────────────────────┐
                         │                                                                    │
 Phone sensors ──┐       │  data/sensors/          domain/                    ui/              │
 GPS, IMU, baro, │       │  ┌───────────────┐      ┌──────────────────┐       ┌─────────────┐  │
 activity, steps ├──────►│  │ SensorSource  │─────►│ JourneyRecorder  │──────►│ Rider mode  │  │
                 │       │  │ (Flow<Sample>)│      │  + Processors    │       └─────────────┘  │
 ESP32 (BLE) ────┤       │  └───────────────┘      │  (pothole, stop, │                        │
                 │       │  ┌───────────────┐      │   congestion…)   │       ┌─────────────┐  │
 Weather/AQI/ ───┘       │  │ RemoteSources │─────►│                  │──┐    │Customer mode│  │
 Traffic APIs            │  └───────────────┘      └────────┬─────────┘  │    └──────▲──────┘  │
                         │                                  ▼            │           │         │
                         │                          ┌──────────────┐     │    ┌──────┴──────┐  │
                         │                          │  Room DB     │─────┴───►│ Demo mode   │  │
                         │                          └──────┬───────┘          │ (replay)    │  │
                         │                                 │ WorkManager      └─────────────┘  │
                         └─────────────────────────────────┼──────────────────────────────────┘
                                                           ▼
                                               Firebase Firestore (public journey projection)
                                                           │ realtime listener
                                                           ▼
                                                 Customer device (Customer mode)
```

## Layers

| Layer | Responsibility | Depends on |
|---|---|---|
| **ui** | Compose screens, ViewModels, `UiState` | domain |
| **domain** | Pure Kotlin: models, processors (algorithms), use-cases, repository interfaces | nothing Android-specific (easy to unit test) |
| **data** | Sensor sources, BLE, Room, Retrofit, Firebase, repository implementations | domain |
| **service** | `JourneyTrackingService` (foreground service), owns the recorder lifetime | domain, data |

## The key abstraction: `SensorSource`

Every data source, whether phone, ESP32 or API, implements the same shape. This is what makes the ESP32 pluggable.

```kotlin
interface SensorSource<T : Sample> {
    val id: SourceId                    // e.g. PHONE_GPS, PHONE_IMU, ESP32_IMU, ESP32_ENV, API_WEATHER
    val availability: StateFlow<Availability>   // AVAILABLE / UNAVAILABLE / CONNECTING / PERMISSION_DENIED
    fun samples(): Flow<T>              // cold flow; collecting starts the sensor, cancelling stops it
}

sealed interface Sample { val timestampNanos: Long; val source: SourceId }
data class LocationSample(...) : Sample
data class ImuSample(...) : Sample
data class PressureSample(...) : Sample
data class EnvSample(val tempC: Float?, val humidity: Float?, val pm25: Float?, ...) : Sample
data class WeatherSample(...) : Sample
// …
```

- Phone sensors: `callbackFlow { sensorManager.registerListener(...); awaitClose { unregister } }`
- ESP32: a BLE notification characteristic mapped to `Flow<ImuSample | EnvSample>`
- APIs: `flow { while (true) { emit(api.fetch()); delay(10.minutes) } }`
- Fakes: replay a recorded CSV/JSON at N× speed, which powers **Demo mode** and tests

Hilt binds a `Set<SensorSource<*>>` so the recorder doesn't care where data comes from.

## Processors (domain)

Pure functions or classes that turn raw sample streams into meter metrics. Each is unit-tested with recorded fixtures.

| Processor | Input | Output |
|---|---|---|
| `DistanceProcessor` | `LocationSample` | cumulative km, filtered trail |
| `SpeedProcessor` | `LocationSample` | smoothed speed |
| `StopDetector` | speed + activity + geofences | `StopEvent(type, start, end)` |
| `PotholeDetector` | `ImuSample` (+ rotation, speed) | `PotholeEvent(lat, lng, severity)` |
| `RoughnessProcessor` | `ImuSample` | 5 s roughness buckets |
| `CongestionProcessor` | segment speed + traffic API | congestion index per segment |
| `FloorCounter` | `PressureSample` + phase | floors climbed |
| `ExposureProcessor` | weather + AQI + time | heat-minutes, AQI-minutes, rain-minutes |
| `PhaseDetector` (later) | geofences + activity | auto phase transitions |

The `JourneyRecorder` combines processor outputs into a `MeterState` (a `StateFlow`) and persists samples, events and aggregates to Room.

## Foreground service

`JourneyTrackingService : LifecycleService`
- Started by "Start journey"; `foregroundServiceType="location|connectedDevice"` (plus `microphone` only if noise is enabled).
- Holds the `JourneyRecorder` in `lifecycleScope`.
- Ongoing notification shows the mini meter (distance · waiting · time) and phase buttons as notification actions.
- Stopped by "Delivered" or "Cancel". Then it enqueues a `SyncJourneyWorker`.

## Sync model

- **Rider → cloud:** while live, push the **public projection** (see [06_DATA_MODEL.md](06_DATA_MODEL.md)) to Firestore at most every **5 s**, with a coarsened location. Full detail is uploaded once at the end.
- **Cloud → customer:** a Firestore snapshot listener on `journeys/{shareCode}`.
- **Offline:** Room is the source of truth; WorkManager retries with backoff.

## App modes & navigation

```
Launcher ─► ModeSelect
            ├── Rider:    Onboarding/Permissions → Home → Pair ESP32 (optional) → LiveJourney → Summary
            ├── Customer: JoinJourney (code/QR) → LiveMeter → JourneyStory
            └── Demo:     JourneyLibrary → Replay (reuses Customer screens)
```

## Package layout (single module to start)

```
app/src/main/java/com/invisiblejourney/
├── App.kt                         # @HiltAndroidApp
├── MainActivity.kt
├── di/                            # Hilt modules (SensorModule, NetworkModule, DbModule, BleModule)
├── domain/
│   ├── model/                     # Journey, Phase, MeterState, events
│   ├── processor/                 # PotholeDetector, StopDetector, …
│   ├── repository/                # interfaces
│   └── usecase/
├── data/
│   ├── sensors/phone/             # GpsSource, ImuSource, BarometerSource, ActivitySource, StepSource
│   ├── sensors/esp32/             # Esp32BleManager, Esp32Source, packet parser
│   ├── sensors/fake/              # ReplaySource
│   ├── remote/                    # OpenMeteoApi, TomTomApi, OverpassApi
│   ├── local/                     # Room DB, DAOs, entities
│   ├── sync/                      # Firestore, SyncJourneyWorker
│   └── repository/                # implementations
├── service/
│   └── JourneyTrackingService.kt
└── ui/
    ├── theme/
    ├── components/                # MeterDial, ConditionChip, EffortTimeline, PhaseButton
    ├── rider/
    ├── customer/
    └── demo/
```

Split into Gradle modules (`:core:domain`, `:core:data`, `:feature:rider`, …) **only if** the app grows. One module is fine for a prototype.

## Firmware repo layout (if ESP32 is used)

```
firmware/esp32-journey-module/
├── platformio.ini
├── include/protocol.h             # shared UUIDs + packet structs (mirror of docs/05)
└── src/
    ├── main.cpp
    ├── ble_service.cpp
    └── sensors/                   # imu.cpp, env.cpp, pm.cpp
```

## Key decisions log

| # | Decision | Reason |
|---|---|---|
| D1 | One app, three modes | One codebase; demo reuses customer UI |
| D2 | `SensorSource` abstraction | ESP32's role is undecided, so plug it in later without touching processors |
| D3 | Room as source of truth | Riders lose network constantly |
| D4 | Store events + aggregates, not raw IMU | Storage, battery and privacy |
| D5 | BLE first for ESP32 | Works with no Wi-Fi/internet on the road; low power |
| D6 | Firebase for live sharing | No server to maintain |
