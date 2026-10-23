# 01 — Concept

## The problem

Quick-commerce platforms use ETA prediction algorithms to promise a time and then show progress as a countdown. That interface hides what produces the number:

- the rider waiting at the dark store while the order is packed
- traffic, signals, wrong-way shortcuts, flyovers
- rain, heat, pollution
- bad roads and potholes
- gated societies, lifts that don't work, stairs
- an unclear address and a customer who doesn't pick up the call

The customer sees "8 min". The rider lives everything else.

## The intervention

A **companion app** that turns the rider's journey into a readable, emotional and honest visualisation for the customer.

**Metaphor: the auto-rickshaw meter.** A meter ticks up with distance and waiting time, and everyone understands that waiting costs something. Our meter ticks up with *effort and exposure*:

| Meter reading | Example |
|---|---|
| Distance | 3.4 km |
| Moving time / Waiting time | 9 min moving · 6 min waiting |
| Stops | 7 stops (4 at signals) |
| Congestion | 2.1× slower than empty roads |
| Road quality | 12 potholes / rough patches |
| Weather exposure | 34 °C, feels like 41 °C |
| Air exposure | AQI 182 for 15 min |
| Vertical effort | 4 floors climbed |

## Mode of engagement: Exposure

"Exposure" has two meanings here, and the design uses both:

1. **Revealing** what is hidden: making the invisible work visible.
2. **Being exposed** to heat, rain, pollution, traffic and road risk, which the rider physically experiences.

The visualisation should make the customer *feel* the second through the first.

## Users and roles

| Role | Device | What they do |
|---|---|---|
| **Rider** | Android phone (+ optional ESP32 module) | Starts and ends a journey, marks phases (picked up, arrived). The app records sensor data in the background. |
| **Customer** | Android phone | Opens a shared journey link or code and watches the live meter, then gets a summary card after delivery. |
| **Demo / Exhibit** | Android tablet or phone | Replays recorded journeys for presentations (no live rider needed). |

All three roles are **modes of one app** (see [04_ARCHITECTURE.md](04_ARCHITECTURE.md)).

## Journey phases

A journey is split into phases because each one hides a different kind of labour:

```
ASSIGNED ─► AT_STORE (waiting for pack) ─► PICKED_UP ─► EN_ROUTE ─► ARRIVED (building/gate) ─► AT_DOOR ─► DELIVERED
```

- Phase changes are **manual buttons** for the rider at first (large, glove-friendly).
- Later they can be **auto-detected** (geofence around store and destination, plus Activity Recognition walking vs. vehicle).

## Core flows

### Rider
1. Open app → Rider mode → **Start journey** (creates a journey ID and a share code).
2. A foreground service starts. A persistent notification shows the meter.
3. Tap phase buttons as they happen (or let auto-detection do it).
4. **Deliver** → the journey closes, the summary is computed and the data is synced.

### Customer
1. Enter the share code or scan a QR → Customer mode.
2. **Live view:** map trail, ticking meter, current "conditions" chips (🌧 rain, 🚦 heavy traffic, 🕳 rough road).
3. **After delivery:** a "Journey story" card, a scrollable timeline of what happened, which can be shared.

### Demo
1. Pick a recorded journey → replay at 1×, 5× or 20× speed with the same customer UI.

## Visualisation ideas (customer side)

- **Meter dial / odometer** that ticks like an auto meter (the signature element).
- **Annotated map trail:** colour by speed, icons at potholes, stops and waiting points.
- **Effort timeline:** a horizontal strip split into phases, with weather and AQI bands under it.
- **"Your 10 minutes were…"** summary: one sentence per hidden factor.
- **Comparison:** "ETA shown: 10 min · Actual: 16 min · Of which 6 min was waiting at the store."

## Ethics and privacy (important)

Exposure for the customer must **not** turn into surveillance of the worker.

- **Rider consent first.** Recording happens only when the rider starts it, and they can pause.
- **Share effort, not identity.** No rider name, photo or phone number is shown by default.
- **Coarse live location.** Customer live view snaps or blurs location (for example ~100 m) and hides the trail near the rider's start point or home.
- **No raw audio, ever.** If the mic is used, only a dB level is computed on-device.
- **Rider owns the data.** They can delete journeys. Retention is short by default.
- **No scoring of riders.** The app never ranks or rates rider performance; it only shows conditions.

## Out of scope (for now)

- Integration with real Blinkit, Zepto or Swiggy APIs (none are public). We simulate the order and ETA.
- Payments, ratings, tipping.
- iOS.
