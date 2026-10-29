/*
  IJ Meter module  --  The Invisible Journey, ESP32 side
  ESP32 DevKit V1 + 5461AS-1 4-digit 7-segment (common cathode) + red/green LEDs + MQ-2

  The phone app connects over BLE (protocol: docs/05_ESP32_INTEGRATION.md) and once a second
  tells the module what the meter is doing. The module shows it:

    GREEN on, red off       rider is moving   -> display counts the ETA down   " 7.42"  (7 min 42 s left)
                                                 late: counts up with a minus   "-1.15"  (1 min 15 s over)
                                                 10+ min late: minutes only     " -12"
    RED blinking, green off rider is waiting  -> display counts this wait up    " 0.37"  (37 s at a signal)
    GREEN on (steady)       delivered         -> display holds the total time   "14.05"
    both off, "----"        no phone / journey not running

  A dot after the second digit separates minutes from seconds. 100 min or more shows as "1h40".
  Between the phone's updates the module keeps ticking on its own, so the seconds never stall.

  At the same time it reads the MQ-2 and sends Rs/R0 to the phone every 2 s; the app turns that
  into "exhaust and smoke above clean air" and how much of it the rider rode through.

  Wiring  (same pins as 5461AS_ESP32.ino)
    Segments a b c d e f g dp -> 330R each -> GPIO 13 14 27 26 25 33 32 23   (HIGH = segment on)
    Digit commons D1 D2 D3 D4 -> GPIO 18 19 21 22                              (LOW  = digit on)
    Red LED   -> 330R -> GPIO 4 ;  Green LED -> 330R -> GPIO 5 ;  LED cathodes -> GND
    MQ-2  VCC -> 3V3 (or 5V, see VDIV) ; GND -> GND ; A0 -> GPIO 34 ; D0 not used
          (GPIO 27, where MQ2_Browser put D0, is segment c here.)

  MQ-2 calibration (R0 = clean-air baseline, kept in flash)
    - Saved R0 from an earlier calibration is used if there is one.
    - Otherwise R0 is taken automatically when the 3-minute heater warm-up ends,
      which assumes the module was switched on in clean air.
    - To calibrate: put it in clean outdoor air, wait for warm-up, hold BOOT (GPIO 0) for 2 s.
      The display shows "CAL " for a moment.

  Without the phone: send 'd' on the Serial Monitor (115200) to run a 40 s demo of every state.

  Arduino IDE: board "ESP32 Dev Module"; library "NimBLE-Arduino" by h2zero, version 2.x.
*/

#include <NimBLEDevice.h>
#include <Preferences.h>
#include <esp_timer.h>
#include <math.h>

// Arduino puts auto-generated function prototypes above the struct definition below.
struct Meter;

// ---------------------------------------------------------------- pins
const uint8_t SEG[8] = {13, 14, 27, 26, 25, 33, 32, 23};  // a b c d e f g dp
const uint8_t DIG[4] = {18, 19, 21, 22};                  // D1..D4
const uint8_t LED_RED   = 4;
const uint8_t LED_GREEN = 5;
const uint8_t MQ2_AO    = 34;   // ADC1, input only: fine while the radio runs
const uint8_t BTN_BOOT  = 0;    // on-board BOOT button, LOW when pressed

// ---------------------------------------------------------------- MQ-2
const float RL_KOHM   = 10.0;   // load resistor on the module (usually 10k)
const float VCC       = 3.3;    // 5.0 if the module runs on 5V
const float VDIV      = 1.0;    // 3.0 with the 10k/20k divider on A0 (needed at 5V)
const float CLEAN_AIR = 9.83;   // datasheet Rs/R0 in clean air
const uint32_t WARMUP_MS = 3UL * 60UL * 1000UL;

// ---------------------------------------------------------------- protocol (mirror of the doc)
#define UUID_SERVICE     "a3c10001-7f1e-4b6a-9c2d-5e9f3b0d1a00"
#define UUID_DEVICE_INFO "a3c10002-7f1e-4b6a-9c2d-5e9f3b0d1a00"
#define UUID_DATA        "a3c10003-7f1e-4b6a-9c2d-5e9f3b0d1a00"
#define UUID_COMMAND     "a3c10004-7f1e-4b6a-9c2d-5e9f3b0d1a00"
#define UUID_STATUS      "a3c10005-7f1e-4b6a-9c2d-5e9f3b0d1a00"

const uint8_t PROTO_VER      = 1;
const uint8_t TYPE_GAS       = 0x06;
const uint8_t TYPE_HEARTBEAT = 0x7F;
const uint8_t OP_START       = 0x01;
const uint8_t OP_STOP        = 0x02;
const uint8_t OP_TIME_SYNC   = 0x03;
const uint8_t OP_DISPLAY     = 0x13;

enum Mode : uint8_t { MODE_IDLE = 0, MODE_MOVING = 1, MODE_WAITING = 2, MODE_FINISHED = 3 };

// ---------------------------------------------------------------- font
// bit0=a ... bit6=g, bit7=dp
const uint8_t FONT[10] = {
  0b00111111, 0b00000110, 0b01011011, 0b01001111, 0b01100110,
  0b01101101, 0b01111101, 0b00000111, 0b01111111, 0b01101111,
};
const uint8_t G_BLANK = 0;
const uint8_t G_MINUS = 0b01000000;
const uint8_t G_DOT   = 0b10000000;
const uint8_t G_H     = 0b01110100;  // h
const uint8_t G_C     = 0b00111001;  // C
const uint8_t G_A     = 0b01110111;  // A
const uint8_t G_L     = 0b00111000;  // L

// ---------------------------------------------------------------- display (multiplexed by a timer)
volatile uint8_t frame[4] = {G_MINUS, G_MINUS, G_MINUS, G_MINUS};

// Runs every 2 ms from esp_timer, so BLE work in loop() can never make the digits flicker.
void refreshDigit(void*) {
  static uint8_t d = 0;
  for (uint8_t i = 0; i < 4; i++) digitalWrite(DIG[i], HIGH);   // blank first: no ghosting
  uint8_t mask = frame[d];
  for (uint8_t i = 0; i < 8; i++) digitalWrite(SEG[i], (mask >> i) & 1);
  digitalWrite(DIG[d], LOW);
  d = (d + 1) & 3;
}

void show(uint8_t a, uint8_t b, uint8_t c, uint8_t d) {
  frame[0] = a; frame[1] = b; frame[2] = c; frame[3] = d;
}

void showDashes() { show(G_MINUS, G_MINUS, G_MINUS, G_MINUS); }

// m.ss with a dot after digit 2; leading zero blanked. 100 min and up -> "1h40".
void showClock(uint32_t s) {
  if (s < 6000) {
    uint32_t m = s / 60, ss = s % 60;
    show(m >= 10 ? FONT[m / 10] : G_BLANK, FONT[m % 10] | G_DOT, FONT[ss / 10], FONT[ss % 10]);
  } else {
    uint32_t h = s / 3600, m = (s / 60) % 60;
    if (h > 9) { h = 9; m = 59; }
    show(FONT[h], G_H, FONT[m / 10], FONT[m % 10]);
  }
}

// Late by s seconds: "-1.15" under 10 min, then " -12" (whole minutes, up to 999).
void showLate(uint32_t s) {
  if (s < 600) {
    show(G_MINUS, FONT[s / 60] | G_DOT, FONT[(s % 60) / 10], FONT[s % 10]);
    return;
  }
  uint32_t m = s / 60;
  if (m > 999) m = 999;
  if (m < 100) show(G_BLANK, G_MINUS, FONT[m / 10], FONT[m % 10]);
  else         show(G_MINUS, FONT[m / 100], FONT[(m / 10) % 10], FONT[m % 10]);
}

// ---------------------------------------------------------------- meter state from the phone
struct Meter {
  uint8_t  mode = MODE_IDLE;
  int32_t  etaLeftS = 0;
  uint32_t waitS = 0;
  uint32_t elapsedS = 0;
  uint8_t  rate = 1;       // journey seconds per real second (demo rides run faster)
  uint32_t rxMs = 0;       // millis() when it arrived
};

portMUX_TYPE meterLock = portMUX_INITIALIZER_UNLOCKED;
Meter meter;                       // guarded by meterLock (written from the BLE task)
volatile bool connected = false;
volatile bool streaming = false;
volatile uint32_t syncMillis = 0;

const uint32_t STALE_MS = 10000;   // no update for this long -> dashes

// ---------------------------------------------------------------- MQ-2 state
Preferences prefs;
float    R0 = 0;                   // kOhm, 0 = not calibrated yet
float    gRs = 0;
float    gRatio = 0;
uint16_t gRaw = 0;
uint16_t gMv = 0;
bool     gWarm = false;

void readGas() {
  uint32_t acc = 0, mvAcc = 0;
  for (uint8_t i = 0; i < 16; i++) acc += analogRead(MQ2_AO);
  for (uint8_t i = 0; i < 4; i++) mvAcc += analogReadMilliVolts(MQ2_AO);
  gRaw = acc / 16;
  gMv  = mvAcc / 4;

  float vout = (gMv / 1000.0f) * VDIV;
  if (vout < 0.02f)             gRs = 9999.0f;
  else if (vout >= VCC - 0.01f) gRs = 0.01f;
  else                          gRs = RL_KOHM * (VCC - vout) / vout;

  if (!gWarm && millis() >= WARMUP_MS) {
    gWarm = true;
    if (R0 <= 0.1f) {
      R0 = gRs / CLEAN_AIR;
      Serial.printf("R0 set automatically to %.2f kOhm (assumes clean air at power-on)\n", R0);
    }
  }
  gRatio = (R0 > 0.1f) ? gRs / R0 : 0;
}

void calibrate() {
  if (gRs <= 0.1f || gRs >= 9000.0f) { Serial.println("calibrate: reading unusable"); return; }
  R0 = gRs / CLEAN_AIR;
  prefs.putFloat("r0", R0);
  Serial.printf("R0 calibrated to %.2f kOhm and saved\n", R0);
  show(G_C, G_A, G_L, G_BLANK);
  delay(1200);
}

// ---------------------------------------------------------------- BLE
NimBLECharacteristic* dataChr   = nullptr;
NimBLECharacteristic* statusChr = nullptr;
uint16_t seq = 0;

static int32_t  rdI32(const uint8_t* p) { return (int32_t)(p[0] | p[1] << 8 | p[2] << 16 | (uint32_t)p[3] << 24); }
static uint32_t rdU32(const uint8_t* p) { return (uint32_t)rdI32(p); }

class CommandCallbacks : public NimBLECharacteristicCallbacks {
  void onWrite(NimBLECharacteristic* c, NimBLEConnInfo& info) override {
    NimBLEAttValue v = c->getValue();
    const uint8_t* p = v.data();
    size_t n = v.length();
    if (n == 0) return;
    switch (p[0]) {
      case OP_START:     streaming = true;  break;
      case OP_STOP:      streaming = false; break;
      case OP_TIME_SYNC: syncMillis = millis(); break;
      case OP_DISPLAY:
        if (n >= 15) {
          portENTER_CRITICAL(&meterLock);
          meter.mode     = p[1];
          meter.etaLeftS = rdI32(p + 2);
          meter.waitS    = rdU32(p + 6);
          meter.elapsedS = rdU32(p + 10);
          meter.rate     = p[14] ? p[14] : 1;
          meter.rxMs     = millis();
          portEXIT_CRITICAL(&meterLock);
        }
        break;
    }
  }
};

class ServerCallbacks : public NimBLEServerCallbacks {
  void onConnect(NimBLEServer* s, NimBLEConnInfo& info) override {
    connected = true;
    Serial.println("BLE: phone connected");
  }
  void onDisconnect(NimBLEServer* s, NimBLEConnInfo& info, int reason) override {
    connected = false;
    streaming = false;
    // A finished journey stays on the display; anything else goes back to dashes.
    portENTER_CRITICAL(&meterLock);
    if (meter.mode != MODE_FINISHED) meter.mode = MODE_IDLE;
    portEXIT_CRITICAL(&meterLock);
    Serial.printf("BLE: disconnected (reason %d), advertising again\n", reason);
    NimBLEDevice::startAdvertising();
  }
};

// header {type, ver, seq, t_ms} then payload, little-endian
size_t header(uint8_t* b, uint8_t type) {
  uint32_t t = millis() - syncMillis;
  b[0] = type; b[1] = PROTO_VER;
  b[2] = seq & 0xFF; b[3] = seq >> 8; seq++;
  b[4] = t; b[5] = t >> 8; b[6] = t >> 16; b[7] = t >> 24;
  return 8;
}

void sendGas() {
  uint8_t b[15];
  size_t n = header(b, TYPE_GAS);
  uint32_t r = (uint32_t)lroundf(gRatio * 1000.0f);
  if (r > 65535) r = 65535;
  uint8_t flags = (gWarm ? 1 : 0) | (R0 > 0.1f ? 2 : 0);
  b[n++] = gRaw & 0xFF; b[n++] = gRaw >> 8;
  b[n++] = gMv & 0xFF;  b[n++] = gMv >> 8;
  b[n++] = r & 0xFF;    b[n++] = r >> 8;
  b[n++] = flags;
  dataChr->setValue(b, n);
  dataChr->notify();
}

void sendHeartbeat() {
  uint8_t b[8];
  size_t n = header(b, TYPE_HEARTBEAT);
  dataChr->setValue(b, n);
  dataChr->notify();
}

void sendStatus() {
  // battery unknown (USB / power bank), bit4 = sensor warming up
  uint8_t b[4] = {0xFF, (uint8_t)(gWarm ? 0 : 0x10), 0, 0};
  statusChr->setValue(b, 4);
  statusChr->notify();
}

void setupBle() {
  uint64_t mac = ESP.getEfuseMac();
  char name[20];
  snprintf(name, sizeof(name), "IJ-Module-%02X%02X", (uint8_t)(mac >> 32), (uint8_t)(mac >> 40));

  NimBLEDevice::init(name);
  NimBLEServer* server = NimBLEDevice::createServer();
  server->setCallbacks(new ServerCallbacks());
  NimBLEService* service = server->createService(UUID_SERVICE);

  service->createCharacteristic(UUID_DEVICE_INFO, NIMBLE_PROPERTY::READ)
         ->setValue("{\"fw\":\"0.2.0\",\"proto\":1,\"caps\":[\"display\",\"gas\"]}");
  dataChr   = service->createCharacteristic(UUID_DATA, NIMBLE_PROPERTY::NOTIFY);
  statusChr = service->createCharacteristic(UUID_STATUS, NIMBLE_PROPERTY::READ | NIMBLE_PROPERTY::NOTIFY);
  service->createCharacteristic(UUID_COMMAND, NIMBLE_PROPERTY::WRITE)
         ->setCallbacks(new CommandCallbacks());
  service->start();

  // Flags + 128-bit service UUID fill most of the 31-byte advert, so the name goes in the scan response.
  NimBLEAdvertisementData adv;
  adv.setFlags(BLE_HS_ADV_F_DISC_GEN | BLE_HS_ADV_F_BREDR_UNSUP);
  adv.addServiceUUID(NimBLEUUID(UUID_SERVICE));
  NimBLEAdvertisementData scan;
  scan.setName(name);
  NimBLEAdvertising* a = NimBLEDevice::getAdvertising();
  a->setAdvertisementData(adv);
  a->setScanResponseData(scan);
  a->start();
  Serial.printf("BLE: advertising as %s\n", name);
}

// ---------------------------------------------------------------- demo (no phone)
uint32_t demoStart = 0;   // 0 = off

// 0-15 s moving (ETA 10:00 running 50x fast, so it goes late after 12 s), 15-30 s waiting, 30-40 s delivered.
bool demoMeter(Meter& m) {
  if (!demoStart) return false;
  uint32_t t = (millis() - demoStart) / 1000;
  if (t >= 40) { demoStart = 0; Serial.println("demo: done"); return false; }
  m.rate = 1; m.rxMs = millis();
  if (t < 15)      { m.mode = MODE_MOVING;   m.etaLeftS = 600 - (int32_t)t * 50; }
  else if (t < 30) { m.mode = MODE_WAITING;  m.waitS = (t - 15) * 7; }
  else             { m.mode = MODE_FINISHED; m.elapsedS = 845; }
  return true;
}

// ---------------------------------------------------------------- main
void drawMeter() {
  Meter m;
  portENTER_CRITICAL(&meterLock);
  m = meter;
  portEXIT_CRITICAL(&meterLock);

  bool demo = demoMeter(m);
  uint32_t now = millis();
  bool fresh = demo || (connected && now - m.rxMs <= STALE_MS) || m.mode == MODE_FINISHED;
  uint8_t mode = fresh ? m.mode : MODE_IDLE;
  // Tick on between updates: journey seconds since the last one arrived.
  int32_t extra = demo ? 0 : (int32_t)((now - m.rxMs) / 1000) * m.rate;
  bool blinkOn = (now / 500) % 2 == 0;

  switch (mode) {
    case MODE_MOVING: {
      int32_t left = m.etaLeftS - extra;
      if (left >= 0) showClock(left); else showLate(-left);
      digitalWrite(LED_GREEN, HIGH);
      digitalWrite(LED_RED, LOW);
      break;
    }
    case MODE_WAITING:
      showClock(m.waitS + extra);
      digitalWrite(LED_GREEN, LOW);
      digitalWrite(LED_RED, blinkOn);
      break;
    case MODE_FINISHED:
      showClock(m.elapsedS);
      digitalWrite(LED_GREEN, HIGH);
      digitalWrite(LED_RED, LOW);
      break;
    default:
      showDashes();
      digitalWrite(LED_GREEN, LOW);
      digitalWrite(LED_RED, LOW);
  }
}

void setup() {
  Serial.begin(115200);
  delay(200);
  Serial.println();
  Serial.println("IJ Meter module 0.2.0");

  for (uint8_t i = 0; i < 8; i++) { pinMode(SEG[i], OUTPUT); digitalWrite(SEG[i], LOW); }
  for (uint8_t i = 0; i < 4; i++) { pinMode(DIG[i], OUTPUT); digitalWrite(DIG[i], HIGH); }
  pinMode(LED_RED, OUTPUT);
  pinMode(LED_GREEN, OUTPUT);
  pinMode(BTN_BOOT, INPUT_PULLUP);

  analogReadResolution(12);
  analogSetPinAttenuation(MQ2_AO, ADC_11db);   // full scale ~3.1 V
  pinMode(MQ2_AO, INPUT);

  prefs.begin("mq2", false);
  R0 = prefs.getFloat("r0", 0);
  Serial.printf("MQ-2: R0 %s (%.2f kOhm), heater warm-up 3 min\n", R0 > 0.1f ? "from flash" : "not set", R0);

  const esp_timer_create_args_t args = { .callback = &refreshDigit, .arg = nullptr, .dispatch_method = ESP_TIMER_TASK, .name = "digits" };
  esp_timer_handle_t timer;
  esp_timer_create(&args, &timer);
  esp_timer_start_periodic(timer, 2000);   // 2 ms per digit -> each digit 125 times a second

  setupBle();
}

void loop() {
  uint32_t now = millis();

  static uint32_t tDraw = 0;
  if (now - tDraw >= 50) { tDraw = now; drawMeter(); }

  static uint32_t tGas = 0;
  if (now - tGas >= 2000) {
    tGas = now;
    readGas();
    if (connected && streaming) sendGas();
    Serial.printf("MQ-2 raw=%4u %4umV Rs=%7.2fk R0=%6.2fk Rs/R0=%5.2f %s | %s\n",
                  gRaw, gMv, gRs, R0, gRatio, gWarm ? "warm" : "heating",
                  connected ? (streaming ? "streaming" : "connected") : "no phone");
  }

  static uint32_t tBeat = 0;
  if (now - tBeat >= 5000) {
    tBeat = now;
    if (connected && streaming) sendHeartbeat();
    if (connected) sendStatus();
  }

  // BOOT held 2 s -> calibrate R0 in clean air (once per press)
  static uint32_t pressedAt = 0;
  static bool calibratedThisPress = false;
  if (digitalRead(BTN_BOOT) == LOW) {
    if (!pressedAt) pressedAt = now;
    else if (!calibratedThisPress && now - pressedAt >= 2000) { calibrate(); calibratedThisPress = true; }
  } else {
    pressedAt = 0;
    calibratedThisPress = false;
  }

  if (Serial.available()) {
    int c = Serial.read();
    if (c == 'd') { demoStart = millis(); Serial.println("demo: moving -> waiting -> finished"); }
  }
}
