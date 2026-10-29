# 05 — ESP32 Integration

> **Status:** the ESP32's exact job is **not decided yet**. This doc defines a connection, protocol and app-side design that works **whatever the ESP32 ends up doing**, so the hardware can be added late without rewriting the app.
>
> **Built so far: the IJ Meter module** (`firmware/IJ_Meter/IJ_Meter.ino`): role **C** plus a gas sensor. A 4-digit 7-segment display shows the ETA counting down while the rider moves (green LED) and the current wait counting up while they are stopped (red LED blinking); an MQ-2 streams `GAS` packets that the app turns into exhaust/smoke exposure. It uses `DISPLAY_STATE` (0x13) and `GAS` (0x06) below; see [IJ Meter module](#ij-meter-module-built).

## What the ESP32 could do (candidates)

| Role | Hardware | Why it helps the concept | Direction |
|---|---|---|---|
| **A. Bike-mounted road sensor** | MPU6050 / BMI270 IMU | Much cleaner pothole/roughness data than a phone in a pocket | ESP32 → phone |
| **B. Street-level exposure sensor** | BME280 (temp/humidity/pressure) + PMS5003 / SDS011 (PM2.5) | *Real* heat and pollution the rider breathes, not a city-grid estimate | ESP32 → phone |
| **C. Physical journey meter** | OLED / e-ink / 7-segment / LED ring | A literal auto-rickshaw-style meter on the bike or at an exhibit, showing the effort ticking up | phone → ESP32 |
| **D. Exhibit / installation controller** | LEDs, servos, vibration motor, speaker | A tangible exhibit: potholes as thumps, rain as light, waiting as a slow pulse | phone → ESP32 |
| **E. Phase buttons** | 2–3 big physical buttons | Rider marks "picked up / arrived / delivered" without touching the phone | ESP32 → phone |

The protocol below supports **all of these at once**. The ESP32 announces what it can do on connect.

---

## Transport choice

| Option | Pros | Cons | Verdict |
|---|---|---|---|
| **BLE (GATT)** | No Wi-Fi or internet needed on the road; low power; phone keeps mobile data; direct pairing | ~10–100 kB/s; Android BLE quirks | ✅ **Primary** |
| Wi-Fi: ESP32 as SoftAP + WebSocket | Higher bandwidth; easy debugging | Phone joins an AP with no internet (use `WifiNetworkSpecifier` so mobile data stays up); more power | Fallback (raw data / exhibit) |
| Wi-Fi: both on the phone's hotspot + mDNS + WebSocket/MQTT | ESP32 can also reach the internet | Rider must enable hotspot; battery drain | Fallback |
| Bluetooth Classic (SPP) | Simple serial | Not supported on ESP32-C3/S3; more power; awkward on modern Android | ❌ |

**Decision:** BLE GATT with the ESP32 as **peripheral (server)** and the phone as **central (client)**. A Wi-Fi WebSocket path reuses the **same packet format** (see end), so switching transport doesn't change parsing.

---

## BLE GATT protocol (v1)

### Service & characteristics

Base UUID (custom, 128-bit): `a3c1xxxx-7f1e-4b6a-9c2d-5e9f3b0d1a00`

| Name | UUID | Properties | Direction | Purpose |
|---|---|---|---|---|
| **Journey Service** | `a3c10001-7f1e-4b6a-9c2d-5e9f3b0d1a00` | — | — | Advertised service UUID (used as the scan filter) |
| `DeviceInfo` | `a3c10002-…` | Read | ESP32 → phone | JSON: firmware version, protocol version, capabilities |
| `Data` | `a3c10003-…` | Notify | ESP32 → phone | Binary sensor packets (below) |
| `Command` | `a3c10004-…` | Write (with response) | phone → ESP32 | Start/stop, set rate, time sync, meter display updates |
| `Status` | `a3c10005-…` | Read, Notify | ESP32 → phone | Battery %, error flags, sensor health |

Advertising name: `IJ-Module-XXXX` (last 4 hex of MAC).

### `DeviceInfo` (read, UTF-8 JSON, ≤ 180 bytes)

```json
{ "fw": "0.1.0", "proto": 1, "caps": ["imu", "env", "pm25", "display", "buttons"], "imuHz": 100 }
```

The app uses `caps` to decide which `SensorSource`s to create and which meter metrics get the `ESP32` source tag.

### Connection setup (phone side)

1. Scan with a filter on the Journey Service UUID.
2. Connect, then request **MTU 247**, then request connection priority `HIGH` while recording (`BALANCED` while idle).
3. Read `DeviceInfo`.
4. Write `TIME_SYNC` with the phone's epoch ms.
5. Enable notifications on `Data` and `Status`.
6. Write `START_STREAM`.
7. On disconnect: auto-reconnect with backoff (Nordic library `retry` / `useAutoConnect`). Recording continues on phone sensors and the ESP32 source shows `CONNECTING`.

### Packet format (`Data` notifications)

All multi-byte fields are **little-endian**. Every packet starts with an 8-byte header:

| Offset | Size | Field | Notes |
|---|---|---|---|
| 0 | 1 | `type` | see table below |
| 1 | 1 | `ver` | protocol version (= 1) |
| 2 | 2 | `seq` | uint16, increments per packet (detects drops) |
| 4 | 4 | `t_ms` | uint32 ms since `TIME_SYNC` (add to the phone's sync epoch) |

| `type` | Name | Payload | Rate |
|---|---|---|---|
| `0x01` | `IMU_SUMMARY` | `int16 rms_z_mg`, `int16 peak_z_mg`, `int16 peak_dz_mg`, `uint8 bucket` (0 smooth…3 very rough), `uint8 window_ms/10` | 1–5 Hz |
| `0x02` | `POTHOLE_EVENT` | `int16 peak_z_mg`, `uint8 severity` (1–3), `uint8 reserved` | on event |
| `0x03` | `ENV` | `int16 temp_c×100`, `uint16 rh×100`, `uint32 pressure_pa` | 0.2–1 Hz |
| `0x04` | `AIR` | `uint16 pm1_0`, `uint16 pm2_5`, `uint16 pm10` (µg/m³) | 0.2–1 Hz |
| `0x05` | `BUTTON` | `uint8 button_id`, `uint8 action` (1 press, 2 long) | on event |
| `0x06` | `GAS` | `uint16 adc_raw`, `uint16 millivolts`, `uint16 rs_r0×1000` (MQ-2 Rs/R0), `uint8 flags` (bit0 heater warm, bit1 R0 calibrated) | 0.5 Hz |
| `0x10` | `IMU_RAW` | `uint8 n`, then `n × (int16 ax, ay, az)` in mg | only in calibration mode |
| `0x7F` | `HEARTBEAT` | — | 0.2 Hz |

**Design rule:** the ESP32 does pothole detection **on-device** at 100–200 Hz and sends **events + summaries**. Raw IMU streaming is only for calibration and threshold tuning.

### `Command` writes (phone → ESP32)

Format: `uint8 opcode` + payload.

| Opcode | Name | Payload |
|---|---|---|
| `0x01` | `START_STREAM` | — |
| `0x02` | `STOP_STREAM` | — |
| `0x03` | `TIME_SYNC` | `uint64 epoch_ms` |
| `0x04` | `SET_RATE` | `uint8 packet_type`, `uint16 hz×10` |
| `0x05` | `SET_MODE` | `uint8 mode` (0 normal, 1 calibration/raw) |
| `0x10` | `METER_UPDATE` | `uint8 phase`, `uint32 distance_m`, `uint16 moving_s`, `uint16 waiting_s`, `uint8 potholes`, `int16 temp_c×10`, `uint16 aqi` (for role C/D) |
| `0x11` | `DISPLAY_TEXT` | UTF-8 string ≤ 64 bytes |
| `0x12` | `ALERT` | `uint8 kind` (pothole, rain, heat…) for vibration/LED feedback |
| `0x13` | `DISPLAY_STATE` | `uint8 mode` (0 idle, 1 moving, 2 waiting, 3 finished), `int32 eta_left_s` (negative = late), `uint32 wait_s` (this wait), `uint32 elapsed_s`, `uint8 rate` (journey s per real s; >1 in demo rides). Sent 1 Hz; the module ticks on by itself in between |

### `Status` (notify, 4 bytes)

`uint8 battery_pct`, `uint8 flags` (bit0 imu_ok, bit1 env_ok, bit2 pm_ok, bit3 low_batt, bit4 sensor_warmup), `uint16 dropped_samples`.

---

## App-side design

```
data/sensors/esp32/
├── Esp32Uuids.kt            # mirrors the table above
├── Esp32BleManager.kt       # Nordic BleManager subclass: connect, MTU, notifications, writes
├── Esp32PacketParser.kt     # ByteArray → Esp32Packet (pure Kotlin, unit-tested)
├── Esp32Repository.kt       # scan/pair/reconnect, exposes connection state + packets Flow
└── Esp32Sources.kt          # Esp32ImuSource, Esp32EnvSource, Esp32AirSource : SensorSource
```

- `Esp32Repository.packets: SharedFlow<Esp32Packet>`. Each `Esp32*Source` filters it by type and maps it to the shared domain `Sample` types.
- **Source priority:** when the ESP32 provides `imu`, the `PotholeDetector` takes its events over the phone's. When it provides `env`/`pm25`, the exposure metrics prefer them over the APIs. The source tag (`ESP32`) shows up in the customer UI ("measured on the street").
- **Pairing UX:** Rider mode → "Connect module" → scan list → tap → the paired MAC is stored in DataStore → auto-connect on journey start.
- **Meter push (role C/D):** the recorder sends `METER_UPDATE` once per second while connected.

Sketch (Nordic BLE library, `ble-ktx`):

```kotlin
class Esp32BleManager(context: Context) : BleManager(context) {
    private var data: BluetoothGattCharacteristic? = null
    private var command: BluetoothGattCharacteristic? = null
    val packets = MutableSharedFlow<ByteArray>(extraBufferCapacity = 64)

    override fun isRequiredServiceSupported(gatt: BluetoothGatt): Boolean {
        val s = gatt.getService(Esp32Uuids.SERVICE) ?: return false
        data = s.getCharacteristic(Esp32Uuids.DATA)
        command = s.getCharacteristic(Esp32Uuids.COMMAND)
        return data != null && command != null
    }

    override fun initialize() {
        requestMtu(247).enqueue()
        setNotificationCallback(data).with { _, d -> d.value?.let { packets.tryEmit(it) } }
        enableNotifications(data).enqueue()
    }

    suspend fun send(cmd: ByteArray) = writeCharacteristic(
        command, cmd, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
    ).suspend()

    override fun onServicesInvalidated() { data = null; command = null }
}
```

---

## Firmware skeleton (ESP32, Arduino + NimBLE-Arduino 2.x)

```cpp
#include <NimBLEDevice.h>
#include "protocol.h"   // UUIDs, packet structs, opcodes (mirror of this doc)

static NimBLECharacteristic* dataChr;
static NimBLECharacteristic* statusChr;
static volatile bool streaming = false;
static uint16_t seq = 0;
static uint32_t syncMillis = 0;

class CommandCallbacks : public NimBLECharacteristicCallbacks {
  void onWrite(NimBLECharacteristic* c, NimBLEConnInfo& info) override {
    std::string v = c->getValue();
    if (v.empty()) return;
    switch ((uint8_t)v[0]) {
      case OP_START_STREAM: streaming = true;  break;
      case OP_STOP_STREAM:  streaming = false; break;
      case OP_TIME_SYNC:    syncMillis = millis(); break;
      case OP_METER_UPDATE: /* parse + draw on OLED */ break;
    }
  }
};

void setup() {
  NimBLEDevice::init("IJ-Module");
  NimBLEDevice::setMTU(247);
  auto* server  = NimBLEDevice::createServer();
  auto* service = server->createService(UUID_SERVICE);

  service->createCharacteristic(UUID_DEVICE_INFO, NIMBLE_PROPERTY::READ)
         ->setValue(R"({"fw":"0.1.0","proto":1,"caps":["imu","env"],"imuHz":100})");
  dataChr   = service->createCharacteristic(UUID_DATA, NIMBLE_PROPERTY::NOTIFY);
  statusChr = service->createCharacteristic(UUID_STATUS, NIMBLE_PROPERTY::READ | NIMBLE_PROPERTY::NOTIFY);
  service->createCharacteristic(UUID_COMMAND, NIMBLE_PROPERTY::WRITE)
         ->setCallbacks(new CommandCallbacks());

  service->start();
  auto* adv = NimBLEDevice::getAdvertising();
  adv->addServiceUUID(UUID_SERVICE);
  adv->start();
  // init sensors here (Wire.begin(), mpu.begin(), bme.begin() …)
}

void loop() {
  // 1. sample IMU at 100–200 Hz → run pothole detector → on event: send POTHOLE_EVENT
  // 2. every 500 ms: send IMU_SUMMARY
  // 3. every 2 s: send ENV / AIR
  // Helper: fill header {type, ver=1, seq++, t_ms = millis() - syncMillis}, then
  //   dataChr->setValue(buf, len); dataChr->notify();
}
```

`platformio.ini`:

```ini
[env:esp32dev]
platform = espressif32
board = esp32dev
framework = arduino
monitor_speed = 115200
lib_deps =
  h2zero/NimBLE-Arduino@^2.1.0
  adafruit/Adafruit MPU6050
  adafruit/Adafruit BME280 Library
```

---

## IJ Meter module (built)

| Part | ESP32 pin |
|---|---|
| 5461AS segments a b c d e f g dp (330 Ω each) | 13 14 27 26 25 33 32 23 |
| 5461AS digit commons D1–D4 | 18 19 21 22 |
| Red LED (330 Ω) / green LED (330 Ω) | 4 / 5 |
| MQ-2 A0 (D0 unused) | 34 |
| BOOT button: hold 2 s to calibrate MQ-2 R0 in clean air | 0 |

**Display:** `m.ss` with the dot after digit 2 (` 7.42`); late `-1.15`, 10+ min late ` -12` (minutes); 100+ min `1h40`; no phone `----`. A finished journey stays on the display after the phone disconnects.

**When is the rider "waiting"?** `MeterState.waitingNow`: on the road, below walking pace for 5 s (`JourneyEngine.SHOW_WAIT_AFTER_MS`); at the store and at the door, the whole phase. The wait counts from the moment they slowed down. A road stop only goes into the waiting totals once it lasts 20 s (`StopDetector`), so a 10 s pause shows red on the module but stays in moving time.

**MQ-2 → pollution** (`domain/processor/GasExposure.kt`, `Mq2`): Rs/R0 → the datasheet smoke curve `ppm = 1200·(Rs/R0)^-1.9` → minus clean air (Rs/R0 = 9.83, ≈ 15.6 ppm) = **ppm above clean air**, shown live on the air chip and integrated as **ppm·min** (`MeterState.gasPpmMinutes`, `JourneySummary.gasPpmMinutes`), the same way AQI-minutes are counted. The MQ-2 can't tell gases apart and has no official scale, so it is labelled an estimate everywhere; readings before warm-up or calibration are dropped.

**App side:** `data/sensors/esp32/Esp32Protocol.kt` (packets, unit-tested), `Esp32Link.kt` (plain Android `BluetoothGatt`, no library: scans for the service UUID, connects to the first module, reconnects on drop). `JourneyRecorder` starts it with each journey; the live screen's top bar shows its status. No pairing screen yet: it takes the first module it finds.

---

## Wi-Fi fallback (same packets)

If BLE bandwidth isn't enough (raw IMU at 200 Hz for research) or the ESP32 drives an exhibit:

- ESP32 runs a **WebSocket server** at `ws://ij-module.local:81/` (mDNS).
- **Binary frames = exactly the same packets** as `Data` notifications. **Text frames = the `DeviceInfo` JSON.** Commands are sent as binary frames with the same opcodes.
- App: an `Esp32WebSocketTransport` implements the same `Esp32Transport` interface as BLE, so the parser and sources stay unchanged.
- Joining the ESP32 SoftAP: use `WifiNetworkSpecifier` + `ConnectivityManager.requestNetwork()` and bind the socket to that network so mobile data stays the default for Firebase and APIs.

```kotlin
interface Esp32Transport {
    val state: StateFlow<ConnectionState>
    val packets: Flow<ByteArray>
    suspend fun send(command: ByteArray)
}
```

---

## Testing without hardware

- `FakeEsp32Transport` emits packets from a recorded `.bin`/hex file, so the whole app can be tested before the board arrives.
- Use **nRF Connect** (Android app) to test the GATT server on the ESP32 by hand: read `DeviceInfo`, subscribe to `Data`, write commands.
- Unit-test `Esp32PacketParser` with hand-built byte arrays for every packet type, including truncated and unknown-type packets.

## Open questions (decide once the ESP32's role is known)

- [ ] Which role(s): A / B / C / D / E?
- [ ] Mounted on bike, worn by rider, or at the exhibit only?
- [ ] Power source and expected runtime (one shift ≈ 8–10 h)?
- [ ] Do we need raw IMU (research) or only events (product)?
- [ ] One module per rider, or a single demo unit?
