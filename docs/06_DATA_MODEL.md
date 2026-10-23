# 06 — Data Model

Three tiers of data, from most private to most public:

1. **Raw samples** — on the rider's phone only, short-lived, optional (debug)
2. **Journey record** — on the rider's phone (Room), synced in full after delivery
3. **Public projection** — what the customer sees (Firestore), coarsened, no identity

## Domain models (Kotlin)

```kotlin
enum class Phase { ASSIGNED, AT_STORE, PICKED_UP, EN_ROUTE, ARRIVED, AT_DOOR, DELIVERED, CANCELLED }
enum class SourceTag { PHONE, ESP32, API, ESTIMATED }
enum class MountType { POCKET, HANDLEBAR, BAG, UNKNOWN }
enum class StopType { STORE_WAIT, SIGNAL_WAIT, TRAFFIC_STOP, GATE_OR_DOOR_WAIT, UNKNOWN }
enum class Roughness { SMOOTH, FAIR, ROUGH, VERY_ROUGH }

data class Journey(
    val id: String,                 // UUID
    val shareCode: String,          // 6-char, e.g. "K7Q2MX"
    val startedAt: Instant,
    val endedAt: Instant?,
    val promisedEtaMin: Int?,       // the "10 minutes" (entered or simulated)
    val mountType: MountType,
    val currentPhase: Phase,
    val storeLocation: LatLng?,
    val destination: LatLng?,       // stored coarsened
)

data class PhaseChange(val journeyId: String, val phase: Phase, val at: Instant, val auto: Boolean)

data class TrackPoint(
    val journeyId: String, val at: Instant,
    val lat: Double, val lng: Double, val accuracyM: Float,
    val speedKmh: Float, val altitudeM: Float?,
)

data class StopEvent(val journeyId: String, val type: StopType, val start: Instant, val end: Instant, val lat: Double, val lng: Double)
data class PotholeEvent(val journeyId: String, val at: Instant, val lat: Double, val lng: Double, val severity: Int, val source: SourceTag)
data class RoughnessWindow(val journeyId: String, val start: Instant, val rmsZ: Float, val bucket: Roughness, val source: SourceTag)
data class CongestionSegment(val journeyId: String, val start: Instant, val end: Instant, val actualKmh: Float, val freeFlowKmh: Float?, val index: Float, val source: SourceTag)
data class ConditionReading(                       // weather + air + env, from API or ESP32
    val journeyId: String, val at: Instant, val source: SourceTag,
    val tempC: Float?, val feelsLikeC: Float?, val humidityPct: Float?,
    val precipitationMm: Float?, val weatherCode: Int?, val windKmh: Float?,
    val pm25: Float?, val pm10: Float?, val aqi: Int?, val isDay: Boolean?,
)
```

## Live meter state (what the UI binds to)

```kotlin
data class MeterState(
    val phase: Phase,
    val elapsed: Duration,
    val movingTime: Duration,
    val waitingTime: Duration,
    val waitingByType: Map<StopType, Duration>,
    val distanceKm: Float,
    val currentSpeedKmh: Float,
    val avgMovingSpeedKmh: Float,
    val stopCount: Int,
    val potholeCount: Int,
    val roughnessNow: Roughness?,
    val congestionIndex: Float?,          // 1.0 = free flow
    val tempC: Float?, val feelsLikeC: Float?, val raining: Boolean,
    val aqiNow: Int?, val aqiMinutes: Float,
    val heatMinutes: Float,               // minutes with feels-like ≥ 35 °C
    val floorsClimbed: Int?, val stepsAtDestination: Int?,
    val sources: Map<String, SourceTag>,  // per-metric provenance
)
```

## Journey summary (computed at DELIVERED)

```kotlin
data class JourneySummary(
    val journeyId: String,
    val promisedEtaMin: Int?, val actualMin: Float,
    val distanceKm: Float,
    val movingMin: Float, val waitingMin: Float,
    val waitingByType: Map<StopType, Float>,
    val potholes: Int, val roughShare: Float,      // % of distance on ROUGH or worse
    val avgCongestion: Float?,
    val maxFeelsLikeC: Float?, val rainMinutes: Float,
    val aqiMinutes: Float, val avgAqi: Int?,
    val floorsClimbed: Int?, val steps: Int?,
    val storyLines: List<String>,                  // "6 of your 16 minutes were spent waiting at the store."
)
```

## Room schema

| Table | PK | Notes |
|---|---|---|
| `journeys` | `id` | + `share_code` unique index |
| `phase_changes` | auto | FK `journey_id` |
| `track_points` | auto | FK, index `(journey_id, at)` |
| `stop_events` | auto | FK |
| `pothole_events` | auto | FK |
| `roughness_windows` | auto | FK |
| `congestion_segments` | auto | FK |
| `condition_readings` | auto | FK |
| `journey_summaries` | `journey_id` | 1:1 |
| `raw_imu_chunks` | auto | **debug only**, blob of samples, auto-deleted after 24 h |

Retention: journeys auto-delete after **30 days** (configurable). The rider can delete any journey instantly.

## Firestore (public projection)

```
journeys/{shareCode}
  ├── phase: "EN_ROUTE"
  ├── updatedAt: Timestamp
  ├── promisedEtaMin: 10
  ├── startedAt: Timestamp
  ├── meter: { distanceKm, movingMin, waitingMin, waitingByType{…}, stopCount,
  │            potholeCount, roughnessNow, congestionIndex, tempC, feelsLikeC,
  │            raining, aqiNow, aqiMinutes, floorsClimbed, sources{…} }
  ├── liveLocation: { lat, lng }          // snapped to ~100 m grid, omitted near start point
  ├── trail: [ {lat,lng,speedKmh,t} … ]   // simplified, coarsened, appended in chunks
  ├── events: [ {kind:"pothole"|"stop"|"rain"|"phase", lat?, lng?, t, detail} … ]
  └── summary: { … JourneySummary … }     // written once at DELIVERED
```

- **No** rider name, phone, photo or exact start/home location.
- Writes are throttled to every 5 s. Trail and events are appended in batches.
- Security rules: only the creating anonymous UID can write. Reads are allowed for anyone who knows `shareCode`, and the document expires (TTL policy) after 7 days.

## Export format (offline / demo)

`<journeyId>.journey.json` holds the full journey record (all tables above except raw IMU), version-tagged:

```json
{ "format": "invisible-journey", "version": 1, "journey": { … }, "phases": [ … ],
  "track": [ … ], "stops": [ … ], "potholes": [ … ], "roughness": [ … ],
  "congestion": [ … ], "conditions": [ … ], "summary": { … } }
```

Demo mode replays these files through `ReplaySource`, so the customer UI behaves exactly as it does live.
