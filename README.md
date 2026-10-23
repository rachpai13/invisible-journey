# The Invisible Journey

> A digital "journey meter" for instant-delivery workers, built as an Android app.
> **Mode of engagement: Exposure.**

Instant delivery apps (Blinkit, Zepto, Swiggy Instamart) shrink a delivery down to a countdown timer. This app shows the customer what that countdown leaves out: distance, waiting, traffic, heat, rain, potholes, air quality and stairs. It works like an auto-rickshaw meter, but for effort instead of fare.

**Core question shift**
- From: *"Why isn't my order here in 10 minutes?"*
- To: *"What does it take to deliver my order in 10 minutes?"*

---

## Documentation map

| Doc | What's in it |
|---|---|
| [docs/01_CONCEPT.md](docs/01_CONCEPT.md) | Concept, user roles, core flows, the "meter" metaphor, ethics and privacy |
| [docs/02_SENSORS.md](docs/02_SENSORS.md) | Every sensor and data source: what it measures, how it's sampled and processed, what it shows the customer |
| [docs/03_TECH_STACK.md](docs/03_TECH_STACK.md) | Languages, libraries, APIs, tooling and the reason for each choice |
| [docs/04_ARCHITECTURE.md](docs/04_ARCHITECTURE.md) | App modules, layers, services, data flow, package layout |
| [docs/05_ESP32_INTEGRATION.md](docs/05_ESP32_INTEGRATION.md) | How the app talks to an ESP32 wirelessly (BLE first, Wi-Fi fallback), the protocol, and the firmware skeleton |
| [docs/06_DATA_MODEL.md](docs/06_DATA_MODEL.md) | Journey and sample entities, derived metrics, sync payloads |
| [docs/07_ROADMAP.md](docs/07_ROADMAP.md) | Build phases, demo plan, open questions |
| [docs/08_FRONTEND_GUIDE.md](docs/08_FRONTEND_GUIDE.md) | **Start here to change the UI**: screen map, design system, data sources, recipes, rules |

## TL;DR of the system

```
 ┌──────────────── Rider phone ────────────────┐          ┌────── Customer phone ──────┐
 │ GPS · IMU · Barometer · Activity · (Mic)    │          │                            │
 │        │                                    │          │  Live journey meter        │
 │        ▼                                    │  Cloud   │  Map + "effort" timeline   │
 │  Foreground "Journey Service" ──► Room DB ──┼─► sync ──┼► Post-delivery story card  │
 │        ▲                                    │(Firebase)│                            │
 │        │ BLE                                │          └────────────────────────────┘
 │   ESP32 module (optional, role TBD)         │
 └─────────────────────────────────────────────┘
        ▲
        │ Weather / AQI / Traffic APIs
```

- **One Android app, two modes:** *Rider mode* (records the journey) and *Customer mode* (watches it as a visualisation).
- **Kotlin + Jetpack Compose**, Material 3, Coroutines/Flow. Hand-written DI (no Hilt); journeys are stored as JSON files (no Room).
- **ESP32 is optional and pluggable.** The app treats it as one more "sensor source" behind an interface, so the hardware's job can be decided later.

## Status

**v0.1 app scaffolded** (factors 1–8, zero-cost stack). What's built:

- **Rider mode:** start a journey, then a foreground service records GPS and accelerometer data with the screen off. Big phase buttons (store → picked up → building → delivered). Live meter, plus weather/AQI from Open-Meteo and traffic-signal lookup from Overpass.
- **Customer mode:** a library of journeys (two built-in **samples** plus your own or imported ones). Replay at 1–60× with the meter dial, map trail (OpenStreetMap) and condition chips, then the post-delivery **journey story**.
- **Sharing:** export a journey as `.journey.json` and open it on another phone (Firebase live sharing is a later phase).
- **Unit tests** for the processors and the journey engine.

Not built yet: live sharing, ESP32, pothole-threshold calibration on real rides. See [docs/07_ROADMAP.md](docs/07_ROADMAP.md).

## Pothole research rides

Every **real** ride (not simulated) logs the raw accelerometer at 100 Hz to `files/research/<journeyId>/` on the phone. Each tap of **POTHOLE!** (or a press of either volume key, which also vibrates) saves a 10-second clip: 7 s before and 3 s after. Every automatic detection saves the same kind of clip, so the two can be compared.

After a ride, with the phone plugged in:

```
python tools/pothole_lab.py pull
python tools/pothole_lab.py analyse
```

`analyse` prints which marked potholes the detector caught or missed, its false alarms, and a threshold sweep, and writes SVG charts of every clip to `research_data/<id>/charts/`.

## Changing the UI (with or without Claude Code)

Clone the repo, open it in Android Studio (or run `claude` in the folder). `CLAUDE.md` is read automatically and points to [docs/08_FRONTEND_GUIDE.md](docs/08_FRONTEND_GUIDE.md), which explains where each screen lives, the colour/size system, and where the data comes from. Every screen can be worked on with the built-in sample journeys and the rider demo ride, with no sensors needed.

## How to run (free)

1. Install **Android Studio** (free). It bundles the JDK and the Android SDK.
2. **File → Open** this folder. Let Gradle sync (it downloads Gradle 9.6.0 and the dependencies the first time).
3. *(Optional)* For real traffic data, get a free key at developer.tomtom.com (no card) and put `TOMTOM_API_KEY=...` in `local.properties`. Without it, congestion shows as "estimated".
4. Run on a real phone (USB debugging) for actual sensor data, or on an emulator for the samples (use the emulator's *Extended controls → Location → Routes* to fake a ride).
5. Tests: right-click `app/src/test` → **Run Tests**, or `gradlew test` (uses Android Studio's JDK: set `JAVA_HOME` to `C:\Program Files\Android\Android Studio\jbr`).
