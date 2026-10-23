# 02 — Sensors & Data Sources

> **Scope for v1 (decided):** only factors **1–8** are built. Factors 9–14 (barometer floors, steps, heading, noise, light, battery) are **out of scope** and kept below for reference only.
>
> **Zero-cost rule:** no paid services and no billing accounts. So: no Google Maps SDK, no Google Routes. Maps use **osmdroid + OpenStreetMap tiles**. Weather and AQI use **Open-Meteo**. Signal lookup uses **Overpass**. Traffic uses **TomTom's free key (optional)**, falling back to an "estimated" free-flow speed when no key is set. The only money that may be spent is on physical sensors.
>
> **v1 simplification for factor 4:** stops are classified by **journey phase** (`AT_STORE` → store wait, `ARRIVED` → gate/door wait) plus an Overpass traffic-signal lookup. The Play Services Activity Recognition API is not used in v1.

Every reading on the journey meter comes from one of three places:

1. **On-phone sensors** (the rider's Android phone)
2. **External APIs** (weather, air quality, traffic, maps)
3. **External hardware** (optional ESP32 module, see [05_ESP32_INTEGRATION.md](05_ESP32_INTEGRATION.md))

For a full breakdown of which is which, see [Where each source comes from](#where-each-source-comes-from-phone-vs-free-api-vs-physical-sensor).

All sources feed one pipeline: `SensorSource → JourneyRecorder → Room DB → Sync`. See [04_ARCHITECTURE.md](04_ARCHITECTURE.md).

---

## Summary table

| # | Meter factor | Primary source | Android API | Sample rate | Required? |
|---|---|---|---|---|---|
| 1 | Location / route trail | GNSS (GPS) | `FusedLocationProviderClient` | 1 Hz (en route), 0.2 Hz (stationary) | ✅ Core |
| 2 | Speed | GNSS Doppler speed | `Location.getSpeed()` | 1 Hz | ✅ Core |
| 3 | Distance travelled | Derived from GPS fixes | — | — | ✅ Core |
| 4 | Waiting / stopped time | Speed + Activity Recognition | `ActivityRecognitionClient` | Event-based | ✅ Core |
| 5 | Potholes / road roughness | Accelerometer (+ gyro) | `SensorManager` `TYPE_ACCELEROMETER`, `TYPE_GYROSCOPE`, `TYPE_ROTATION_VECTOR` | 50–100 Hz | ✅ Core |
| 6 | Traffic congestion | Speed vs free-flow speed | Traffic API (TomTom / Google Routes) | Per road segment, ~every 60 s | ✅ Core |
| 7 | Weather | Weather API | Open-Meteo (HTTP) | Every 10 min + at phase changes | ✅ Core |
| 8 | Air quality (AQI / PM2.5) | AQ API (or ESP32 PM sensor) | Open-Meteo Air Quality | Every 10 min | ⭐ Recommended |
| 9 | Elevation / floors climbed | Barometer | `TYPE_PRESSURE` | 1–5 Hz | ⭐ Recommended (not all phones have one) |
| 10 | Walking at destination (steps) | Step counter | `TYPE_STEP_COUNTER` / `TYPE_STEP_DETECTOR` | Event-based | ⭐ Recommended |
| 11 | Heading / turns | Rotation vector | `TYPE_ROTATION_VECTOR` | 10 Hz | Optional |
| 12 | Street noise level | Microphone (dB only) | `AudioRecord` | 1 reading / 2 s | Optional, privacy-sensitive |
| 13 | Light / night riding | Ambient light + sunset time | `TYPE_LIGHT` + API | 1 Hz | Optional |
| 14 | Phone heat / battery drain | Battery stats | `BatteryManager` | Every 60 s | Optional |

---

## Where each source comes from: phone vs free API vs physical sensor

Legend: 📱 **Built into the phone** (free, no extra hardware) · 🌐 **Over-the-air API** (needs internet) · 🔧 **Physical sensor** (extra hardware, i.e. the ESP32 module)

### 📱 Built into the phone

| Sensor / service | Used for | On every phone? | Notes |
|---|---|---|---|
| GPS / GNSS (`FusedLocationProviderClient`) | Location, speed, distance, trail | ✅ Yes | Needs sky view. The Fused provider also uses Wi-Fi and cell for faster fixes |
| Accelerometer | Potholes, road roughness | ✅ Yes | Noisy in a pocket; see 🔧 upgrade below |
| Gyroscope | Reorientation for pothole detection, turns | ⚠️ Most (some very cheap phones lack it) | Without it, rotation vector falls back to accel + magnetometer (less accurate) |
| Magnetometer (compass) | Heading | ✅ Nearly all | — |
| Rotation vector (software, fused) | Earth-frame reorientation, heading | ✅ Yes (virtual sensor) | Built from accel + gyro + magnetometer |
| Barometer (`TYPE_PRESSURE`) | Floors climbed, flyover climbs | ❌ **Mid/high-end only** | Many budget phones riders use **don't** have one; see 🔧 |
| Step counter / detector | Steps from gate to door | ✅ Most | Hardware or software implementation |
| Activity Recognition (Play Services) | Still vs vehicle vs walking (waiting time) | ✅ Yes (with Google Play Services) | Free; runs on-device from phone sensors |
| Geofencing (Play Services) | Store/destination detection | ✅ Yes | Free; on-device |
| Microphone | Street noise level (dB only) | ✅ Yes | Optional, privacy-sensitive |
| Ambient light sensor | Night riding (weak signal) | ✅ Nearly all | Unreliable in a pocket, so prefer the API's `is_day` |
| Battery manager | Battery drain, phone temperature | ✅ Yes | Battery temperature is **not** air temperature |

> Phones have **no** usable air-temperature, humidity or PM2.5 sensor. `TYPE_AMBIENT_TEMPERATURE` exists in the API, but almost no phone ships with it.

### 🌐 Over-the-air APIs

| API | Used for | Cost | Key? |
|---|---|---|---|
| **Open-Meteo Forecast** | Temperature, feels-like, rain, wind, humidity, UV, `is_day`, sunset | **Free** (non-commercial) | No |
| **Open-Meteo Air Quality** | PM2.5, PM10, AQI | **Free** | No |
| **OpenStreetMap Overpass** | Traffic-signal locations (stop classification), road tags | **Free** (fair use) | No |
| **TomTom Traffic Flow** | Free-flow vs current speed (congestion) | **Free tier** (daily quota) | Yes (free signup) |
| Google Routes API | Traffic-aware duration (congestion alternative) | ⚠️ Paid with monthly credit; **needs a billing account** | Yes |
| Map tiles (MapTiler / Stadia / OSM) | Map display | **Free tier** | Usually yes |
| Firebase Firestore + Anonymous Auth | Live sharing rider → customer | **Free tier** (Spark plan) | Project config |

> Truly free with no key: Open-Meteo (weather + AQ) and Overpass. Free with signup: TomTom, MapTiler and Firebase. Avoid Google Routes unless you're fine adding a card for billing.

### 🔧 Physical sensors (ESP32 module, not in phones)

| Hardware | Used for | Why it's needed | Approx. cost (India) |
|---|---|---|---|
| **PMS5003 / SDS011** (PM sensor) | Real street-level PM2.5 / PM10 | **No phone has one.** The API only gives a city-grid estimate | ₹1,200–2,500 |
| **BME280** (temp / humidity / pressure) | Real air temperature + humidity the rider feels; barometer fallback | **No phone has an air-temperature sensor.** The API gives the area average, not the asphalt heat | ₹300–600 |
| **BMP280** (pressure only) | Floors climbed on phones without a barometer | Fills the budget-phone gap | ₹100–200 |
| **MPU6050 / BMI270** (bike-mounted IMU) | Cleaner pothole/roughness data | Phone has an IMU, but body/pocket damping makes it noisy. This is an *upgrade*, not a requirement | ₹150–400 |
| OLED / e-ink / LED display | Physical auto-rickshaw-style meter | Output, not a sensor | ₹200–800 |
| Push buttons | Phase marking without touching the phone | Input, not a sensor | ₹20–100 |
| ESP32 board + battery (18650 + TP4056) | Hosts all of the above, BLE to the phone | — | ₹400–800 |

### Per meter factor at a glance

| # | Meter factor | 📱 Phone | 🌐 API | 🔧 Physical |
|---|---|:-:|:-:|:-:|
| 1–3 | Location, speed, distance | ✅ **primary** | — | — |
| 4 | Waiting / stopped time | ✅ **primary** | Overpass (signal context) | Buttons (optional) |
| 5 | Potholes / roughness | ✅ primary | — | IMU *(upgrade)* |
| 6 | Traffic congestion | ✅ actual speed | ✅ **free-flow speed** (TomTom) | — |
| 7 | Weather (temp, rain) | — | ✅ **primary** (Open-Meteo) | BME280 *(real street temp)* |
| 8 | Air quality | — | ✅ primary (Open-Meteo AQ) | PMS5003 *(real street PM2.5)* |
| 9 | Floors climbed | ⚠️ only if phone has a barometer | — | BMP280/BME280 *(fallback)* |
| 10 | Steps at destination | ✅ **primary** | — | — |
| 11 | Heading / turns | ✅ **primary** | — | — |
| 12 | Street noise | ✅ **primary** (mic) | — | — |
| 13 | Night riding | weak (light sensor) | ✅ **primary** (`is_day`) | — |
| 14 | Battery drain | ✅ **primary** | — | — |

**Bottom line:** the whole app works with **phone + free APIs only**. The physical sensors are only needed for what phones can't measure: **real street air quality, real air temperature/humidity, and barometric floors on budget phones**. Bike-mounted IMU is an accuracy upgrade.

---

## 1–3. Location, Speed, Distance (GPS)

**What it shows the customer:** the route trail, the current speed, the total kilometres, and detours the ETA didn't predict.

**How:**
- Use the `FusedLocationProviderClient` with `Priority.PRIORITY_HIGH_ACCURACY`.
- Interval: **1000 ms** while moving and **5000 ms** while stationary (switch based on Activity Recognition).
- Must run inside a **Foreground Service** with `foregroundServiceType="location"` so recording survives the screen turning off.

**Processing:**
- Drop fixes with `accuracy > 25 m`.
- Distance = sum of `Location.distanceTo()` between consecutive accepted fixes, **ignoring jumps** where implied speed is over 80 km/h (GPS jitter).
- Speed: use `location.speed` when `hasSpeed()`; otherwise derive it from distance/time. Smooth with a 3–5 sample moving average.
- Simplify the trail for upload with Douglas-Peucker (ε ≈ 5 m) to keep payloads small.

**Permissions:** `ACCESS_FINE_LOCATION`, `ACCESS_COARSE_LOCATION`, `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_LOCATION`, `POST_NOTIFICATIONS`.
(`ACCESS_BACKGROUND_LOCATION` is **not** needed as long as recording always happens in a foreground service the user started.)

---

## 4. Waiting / Stopped time

**What it shows:** "6 min waiting at the store", "4 min stuck at signals", "3 min at the society gate".

**How:**
- A **stop** is when smoothed speed stays under 3 km/h for at least 20 s.
- Activity Recognition (`STILL`, `IN_VEHICLE`, `ON_BICYCLE`, `ON_FOOT`, `WALKING`) confirms the state and helps tell *rider waiting* apart from *rider walking to the door*.
- Each stop is classified by **context**:
  - inside the store geofence → `STORE_WAIT`
  - near a known signal or junction (OSM `highway=traffic_signals` lookup) → `SIGNAL_WAIT`
  - inside the destination geofence → `GATE_OR_DOOR_WAIT`
  - otherwise → `TRAFFIC_STOP`

**Permission:** `ACTIVITY_RECOGNITION` (runtime permission on Android 10+).

---

## 5. Potholes & Road Roughness (IMU)

> **v2 (tuned on a real car ride, 22 Oct 2026).** The single-threshold detector below fired 49 times in 17 min. It's now an event detector (`BumpDetector`):
> 1. **One event per bump.** It stays open until the signal has been quiet for 0.8 s.
> 2. **Must stand out from the road.** Peak ≥ floor **and** ≥ prominence × the background RMS of the road just before it.
> 3. **Knock rejection.** Spikes under 25 ms wide are the phone being tapped or rattling, not the road.
>
> Car: floor 0.45 g, prominence 5. Result: 20 bumps (was 49), 4 of the 6 marked events caught; one of the two misses was just the tap itself. Two-wheeler values (floor 0.8–1.2 g, prominence 6) are still guesses until a marked bike ride.
>
> **Speed breaker vs pothole (`BumpClassifier`), from the labelled car ride:** a speed breaker is wide and smooth, so the vehicle **heaves slowly** (mean frequency 2–3 Hz, 80–93% of energy under 4 Hz). A pothole edge gives a **sharp hit** (4–12 Hz). Rule: mean frequency ≤ 3.5 Hz **or** ≥ 80% of energy under 4 Hz, and slow (≤ 30 km/h car, ≤ 25 bike) → speed breaker. On that ride every detected hump was right (7/7) and all 6 marked potholes were right. Braking before a bump is **not** used: drivers brake for visible potholes too.
>
> No gyroscope on the Galaxy M13, so "vertical" comes from projecting onto a slowly tracked gravity vector (`VerticalAccel`).

**What it shows:** pothole markers on the map, a "road roughness" bar (smooth → brutal), and "your rider hit 12 potholes".

**Sensors:** `TYPE_ACCELEROMETER` (raw), `TYPE_LINEAR_ACCELERATION` (gravity removed), `TYPE_GYROSCOPE`, `TYPE_ROTATION_VECTOR`.

**Problem:** the phone can be in a pocket, in a handlebar mount or in a bag, and its orientation changes.

**Pipeline:**
1. Sample accelerometer + rotation vector at **50–100 Hz** (`SENSOR_DELAY_GAME` or explicit µs). For more than 200 Hz you need `HIGH_SAMPLING_RATE_SENSORS`, but that isn't required.
2. **Reorient to the earth frame:** rotate the acceleration vector using the rotation-vector quaternion so `z` is always vertical.
3. **High-pass filter** the vertical acceleration (remove gravity and slow tilt).
4. **Pothole event detection** (Z-THRESH + Z-DIFF style):
   - `|a_z| > 2.5 g` peak **or** `Δa_z > 2.0 g` within 50 ms
   - only when speed > 8 km/h (ignore phone handling while stopped)
   - debounce 1 s
   - tag the event with the nearest GPS fix
5. **Roughness index** per 5 s window: RMS of vertical acceleration, bucketed into `SMOOTH / FAIR / ROUGH / VERY_ROUGH`.
6. Thresholds are **calibrated per mount type** (pocket / mount / bag). The rider picks a mount at journey start, and later it can be auto-detected.

> ⚠️ Phone IMU data is noisy because the rider's body damps it. **A bike-mounted ESP32 + MPU6050/BMI270 gives much cleaner pothole data**. This is one likely job for the ESP32 (see [05_ESP32_INTEGRATION.md](05_ESP32_INTEGRATION.md)).

**Storage:** don't store raw 100 Hz data by default. Store **events + 5 s window aggregates**. A debug toggle stores raw data to CSV for tuning thresholds.

---

## 6. Traffic Congestion

**What it shows:** "Roads were 2.1× slower than normal", with the trail coloured green / amber / red.

**How (two parts):**
- **Measured:** the rider's actual moving speed per segment (from GPS).
- **Reference:** free-flow speed for that road, from either:
  - **TomTom Traffic Flow API**, `flowSegmentData` (returns `currentSpeed` and `freeFlowSpeed`). Has a free tier. **← recommended**
  - **Google Routes API**, `duration` (traffic-aware) vs `staticDuration`.
- **Congestion index** = `freeFlowSpeed / actualSpeed` (clamped 1.0–5.0).
- Call the API at most once per ~60 s or per ~500 m, and cache by segment to save quota.

**Fallback without an API:** compare against a fixed city free-flow assumption (for example 30 km/h urban) and label it "estimated".

---

## 7. Weather

**What it shows:** temperature, "feels like", rain/drizzle, wind, and a heat or rain warning chip.

**Source:** **Open-Meteo Forecast API** (free, no API key). Request `temperature_2m`, `apparent_temperature`, `precipitation`, `rain`, `weather_code`, `wind_speed_10m`, `relative_humidity_2m`, `uv_index`, `is_day`.

**When:** at journey start, at every phase change, and every 10 min. Each reading is attached to the journey timeline.

---

## 8. Air Quality

**What it shows:** "Your rider breathed AQI 182 air for 15 minutes" plus an exposure band in the timeline.

**Source:** **Open-Meteo Air Quality API** (`pm2_5`, `pm10`, `us_aqi` / `european_aqi`). For Indian AQI, compute it from PM2.5 and PM10 using CPCB breakpoints.

**Upgrade:** an ESP32 with a **PMS5003 / SDS011** gives *real street-level* PM2.5, which is much more honest than a city-grid model value.

**Exposure metric:** `Σ (AQI × minutes)` → "AQI-minutes".

---

## 9. Elevation / Floors Climbed (Barometer)

**What it shows:** "Climbed 4 floors (lift not used)" and flyover climbs.

**How:**
- `TYPE_PRESSURE` at 1–5 Hz, low-pass filtered.
- Relative altitude via `SensorManager.getAltitude(p0, p)`. Use the pressure at the start of the `ARRIVED` phase as `p0`.
- ~**0.36 hPa ≈ 3 m ≈ 1 floor**. Count floors only during `ARRIVED → AT_DOOR` while Activity = walking/on-foot.
- Many budget phones **have no barometer**. Check with `sensorManager.getDefaultSensor(TYPE_PRESSURE) != null`, and hide the metric if it's missing. (An ESP32 with a BMP280/BME280 can fill the gap.)

---

## 10. Steps at Destination

**What it shows:** "Walked 420 steps from the gate to your door."

**How:** `TYPE_STEP_COUNTER` (cumulative since boot). Take a snapshot at `ARRIVED` and at `DELIVERED`; the difference is the step count. Needs `ACTIVITY_RECOGNITION`.

---

## 11. Heading / Turns (optional)

From `TYPE_ROTATION_VECTOR`. Used to count sharp turns and U-turns ("3 U-turns: confusing address"), and to orient the reorientation in §5.

---

## 12. Street Noise (optional, privacy-sensitive)

**What it shows:** a noise band (quiet → honking chaos).

**How:** `AudioRecord` with a short buffer. Compute RMS → dBFS → approximate dB SPL **on-device**. **Never store or transmit audio.** Store a single number every 2 s.

**Permission:** `RECORD_AUDIO`, plus foreground service type `microphone`. Off by default and clearly explained to the rider.

---

## 13. Light / Night riding (optional)

`TYPE_LIGHT` is unreliable in a pocket, so prefer `is_day` from Open-Meteo plus sunset time. Shows "Riding after dark" as a condition.

---

## 14. Battery / Phone heat (optional)

`BatteryManager` gives battery % drain over the journey and `EXTRA_TEMPERATURE`. It's a small but real cost to the rider (their own phone and their own data).

---

## Sensor availability & graceful degradation

On startup, a `SensorCapabilities` check reports what the device has. The UI **hides metrics it can't measure** instead of showing zeros. Each metric carries a `source` tag (`PHONE`, `API`, `ESP32`, `ESTIMATED`) so the customer view can be honest about what was measured and what was estimated.

## Battery budget (rider phone)

| Component | Impact | Mitigation |
|---|---|---|
| GPS 1 Hz | High | Drop to 0.2 Hz when `STILL` |
| IMU 50–100 Hz | Medium | Batch with `maxReportLatencyUs` (sensor FIFO batching), process in chunks |
| Network APIs | Low | Cache, call every 10 min |
| BLE to ESP32 | Low | Notify at 1–10 Hz, not raw streaming |
| Mic | Medium | Off by default |

Target: **under 8% battery per hour** of active recording.

## Full permission list

```xml
<uses-permission android:name="android.permission.ACCESS_FINE_LOCATION" />
<uses-permission android:name="android.permission.ACCESS_COARSE_LOCATION" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_LOCATION" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_CONNECTED_DEVICE" />
<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
<uses-permission android:name="android.permission.ACTIVITY_RECOGNITION" />
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
<uses-permission android:name="android.permission.WAKE_LOCK" />

<!-- ESP32 over BLE -->
<uses-permission android:name="android.permission.BLUETOOTH_SCAN"
    android:usesPermissionFlags="neverForLocation" />
<uses-permission android:name="android.permission.BLUETOOTH_CONNECT" />

<!-- Optional -->
<uses-permission android:name="android.permission.RECORD_AUDIO" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_MICROPHONE" />

<uses-feature android:name="android.hardware.location.gps" android:required="true" />
<uses-feature android:name="android.hardware.sensor.accelerometer" android:required="true" />
<uses-feature android:name="android.hardware.sensor.barometer" android:required="false" />
<uses-feature android:name="android.hardware.bluetooth_le" android:required="false" />
```
