# 07 — Roadmap

> **Progress (v0.1):** Phase 0 done. Phase 1 done (JSON files instead of Room). Phase 2 done for factors 1–8 (barometer and steps are out of scope). Phase 3 done (meter dial, OSM map, breakdown, story, replay with samples). **Not yet verified on a real device or build.** Next: build in Android Studio, do a real test ride, tune pothole thresholds, then Phase 4/5.

## Phase 0 — Setup
- [ ] Android Studio project (`com.invisiblejourney`), Compose + Hilt + Room + version catalog
- [ ] Git repo, `.gitignore`, `local.properties` for API keys
- [ ] Decide on maps: MapLibre vs Google Maps
- [ ] Decide on backend: Firebase vs offline-only

## Phase 1 — Core recording (Rider mode)
- [ ] Permission onboarding flow
- [ ] `JourneyTrackingService` (foreground, location)
- [ ] GPS source → distance, speed, trail
- [ ] Manual phase buttons
- [ ] Stop detection + waiting time
- [ ] Room persistence and journey summary
- [ ] Export `.journey.json`

**Milestone:** record a real 15-minute ride and see correct distance and waiting time.

## Phase 2 — Exposure factors
- [ ] IMU source + pothole detector + roughness (with a CSV debug recorder for tuning)
- [ ] Open-Meteo weather + AQI
- [ ] Traffic congestion (TomTom) with an "estimated" fallback
- [ ] Barometer floors + step counter at destination
- [ ] `SensorCapabilities` and graceful hiding of missing metrics

**Milestone:** ride over a known bad road; potholes appear in the right places.

## Phase 3 — Customer visualisation
- [ ] Meter dial component (the signature element)
- [ ] Annotated map trail
- [ ] Effort timeline (phases + weather/AQI bands)
- [ ] Journey story card + share
- [ ] Demo mode replay (1× / 5× / 20×)

## Phase 4 — Live sharing
- [ ] Firebase anonymous auth + Firestore projection
- [ ] Share code + QR, join flow
- [ ] Location coarsening + privacy rules

## Phase 5 — ESP32 (once the role is decided)
- [ ] Fake transport + packet parser + unit tests (can be done **before** the hardware arrives)
- [ ] Firmware: GATT server + heartbeat, verified with nRF Connect
- [ ] Add the sensors or display for the chosen role
- [ ] Pairing UX + auto-reconnect
- [ ] Source priority (ESP32 over phone or API)

## Phase 6 — Polish & presentation
- [ ] Record 3–5 real journeys in different conditions (rain, peak traffic, bad road, tall building)
- [ ] Exhibit / demo build
- [ ] Battery test (target < 8%/h)

## Open questions

| # | Question | Affects |
|---|---|---|
| Q1 | What exactly does the ESP32 do? | [05_ESP32_INTEGRATION.md](05_ESP32_INTEGRATION.md), hardware budget |
| Q2 | Will real riders use it, or team members simulating rides? | Ethics, consent forms, data realism |
| Q3 | Is live sharing needed, or are recorded journeys + demo enough for the brief? | Whether we need Firebase at all |
| Q4 | Where does the "promised ETA" come from? (manual entry / simulated algorithm) | Comparison visual |
| Q5 | Exhibit format: phone, tablet, physical installation? | Demo mode, ESP32 role C/D |
| Q6 | Is the mic / noise level worth the privacy cost? | Permissions, consent |
