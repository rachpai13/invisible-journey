# 03 — Tech Stack

> **v1 as built (simplified for a class project, zero cost):**
> - **DI:** a hand-written `AppContainer` instead of Hilt (fewer build plugins to break).
> - **Storage:** one JSON file per journey (`files/journeys/<id>.journey.json`, the export format) instead of Room. SharedPreferences for settings.
> - **Networking:** OkHttp + kotlinx.serialization (no Retrofit).
> - **Maps:** **osmdroid** + OpenStreetMap tiles (free, no key).
> - **Live sharing (Firebase):** deferred. v1 shares journeys as exported `.journey.json` files that the Customer/Demo mode replays.
> - **Pinned versions:** AGP 9.4.0 (built-in Kotlin, no `kotlin-android` plugin) · Kotlin 2.4.20 · Gradle 9.6.0 · Compose BOM 2025.09.00 · compileSdk/targetSdk 36 · minSdk 26 · Java 17 bytecode. Builds on Android Studio's bundled JDK 25.
>
> The tables below are the full target stack. Hilt, Room, Firebase etc. can be added later.

Guiding principles: **native Android** (we need deep sensor, foreground service and BLE access), **modern and boring** (well-documented libraries a student team can debug), and **free tiers only**.

## Platform

| Item | Choice | Why |
|---|---|---|
| OS | Android | Project requirement; best sensor access |
| `minSdk` | **26** (Android 8.0) | Covers nearly all riders' budget phones; foreground-service and notification-channel APIs available |
| `targetSdk` / `compileSdk` | **Latest stable (35+)** | Required by Play; enforces foreground-service types and BLE permissions |
| Language | **Kotlin** (2.x) | Standard for Android; coroutines for sensor streams |
| Build | **Gradle Kotlin DSL** + **version catalog** (`libs.versions.toml`) | One place for dependency versions |
| IDE | Android Studio (latest stable) | — |

## App libraries

| Concern | Library | Notes |
|---|---|---|
| UI | **Jetpack Compose** + **Material 3** | Declarative UI. The meter dial is drawn with Compose `Canvas` |
| Navigation | Navigation Compose (type-safe routes) | Rider / Customer / Demo graphs |
| Architecture | **MVVM** + unidirectional data flow (`StateFlow` UI state) | ViewModels expose `UiState`; see [04_ARCHITECTURE.md](04_ARCHITECTURE.md) |
| DI | **Hilt** | Swap real ↔ fake sensor sources for testing and demo mode |
| Async | **Kotlin Coroutines + Flow** | Every sensor is exposed as a `Flow<Sample>` |
| Local DB | **Room** | Journeys, samples, events; works offline |
| Preferences | **DataStore (Preferences)** | Settings, paired ESP32 address, mount type |
| Background sync | **WorkManager** | Upload finished journeys when the network returns |
| Foreground tracking | `LifecycleService` + foreground service type `location` (+ `connectedDevice`) | Keeps recording with the screen off |
| Location | **Google Play Services Location** (`FusedLocationProviderClient`, `ActivityRecognitionClient`, `GeofencingClient`) | Best accuracy/battery trade-off |
| Sensors | Android `SensorManager` (platform) | Wrapped in `callbackFlow` |
| BLE (ESP32) | **Nordic Android BLE Library** (`no.nordicsemi.android:ble-ktx`) | Handles GATT queueing, reconnects and MTU; much less painful than raw `BluetoothGatt`. Alternative: **Kable** (Kotlin-multiplatform, coroutine-first) |
| Wi-Fi (ESP32 fallback) | **OkHttp WebSocket** or **HiveMQ MQTT client** | Only if the ESP32 has to be on Wi-Fi; see [05_ESP32_INTEGRATION.md](05_ESP32_INTEGRATION.md) |
| Networking | **Retrofit** + **OkHttp** + **kotlinx.serialization** | Weather / AQI / traffic APIs |
| Maps | **MapLibre Android** + OpenStreetMap tiles (e.g. MapTiler free tier), **or** Google Maps Compose | MapLibre = free, styleable (good for the "exposure" aesthetic). Google Maps = easiest. Pick one in Phase 1 |
| Charts | **Vico** (Compose charts) | Speed, AQI and roughness timelines |
| Animation | Compose animation APIs; optional **Lottie** | Meter ticking, condition chips |
| QR (share code) | **ZXing** (generate) + **CameraX + ML Kit Barcode** (scan) | Customer joins a journey |
| Logging | **Timber** | — |
| Crash reporting | Firebase Crashlytics (optional) | — |

## Backend (live sharing rider → customer)

| Concern | Choice | Why |
|---|---|---|
| Realtime sync | **Firebase Firestore** (or Realtime Database) | No server to run; realtime listeners for the customer live view; generous free tier |
| Auth | **Firebase Anonymous Auth** | No accounts needed; still gives security-rule identities |
| Rules | Firestore security rules | Only the rider writes their journey; anyone with the share code reads the *public* projection |
| Storage (optional) | Firebase Storage | Raw IMU CSVs for debugging only |

> If no backend is allowed, the fallback is **offline-only**: the rider exports a `.journey.json` and the customer/demo view imports it. Demo mode works this way anyway.

## External APIs

| Data | API | Key needed? | Free tier |
|---|---|---|---|
| Weather | **Open-Meteo Forecast** | No | Yes (non-commercial) |
| Air quality | **Open-Meteo Air Quality** | No | Yes |
| Traffic flow | **TomTom Traffic Flow** (`flowSegmentData`) | Yes | Yes (daily request quota) |
| Traffic (alt) | Google Routes API | Yes + billing account | Monthly credit |
| Signals / road tags | OpenStreetMap **Overpass API** | No | Fair use |
| Map tiles | MapTiler / Stadia / OSM | Yes for most | Yes |

API keys go in `local.properties` → `BuildConfig` via the Secrets Gradle Plugin. **Never commit keys.**

## ESP32 side (if used)

| Item | Choice |
|---|---|
| Board | ESP32-WROOM-32 DevKit (or **ESP32-S3 / ESP32-C3** for lower power and native USB) |
| Framework | **Arduino core for ESP32** via **PlatformIO** (VS Code). Move to ESP-IDF only if needed |
| BLE stack | **NimBLE-Arduino** (lighter and more stable than the default Bluedroid) |
| Possible sensors | MPU6050 / BMI270 (IMU), BME280 (temp, humidity, pressure), PMS5003 / SDS011 (PM2.5), GPS NEO-6M (not usually needed), SSD1306 OLED / e-ink (physical meter display) |
| Power | 18650 Li-ion + TP4056 charger, or USB power bank |

## Testing & quality

| Layer | Tool |
|---|---|
| Unit tests | JUnit 5 / JUnit 4 + **Turbine** (Flow testing) + **MockK** |
| Algorithms (pothole, stop detection) | Pure-Kotlin unit tests fed with **recorded CSV fixtures** |
| UI | Compose UI tests, Paparazzi or Roborazzi screenshots (optional) |
| Lint / format | ktlint or Spotless + Android Lint |
| Sensor simulation | `FakeSensorSource` + recorded journey replay (also powers Demo mode) |
| Location mocking | Android Studio emulator route playback / "mock location app" developer option |

## Version control & CI

- Git + GitHub. Branches `main` / feature branches.
- GitHub Actions: `./gradlew lint test assembleDebug` on every PR (optional but cheap).
