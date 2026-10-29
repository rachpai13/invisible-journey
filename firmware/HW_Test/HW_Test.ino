/*
  HW test  --  lights everything on the IJ Meter board, no phone or sensor needed

  Same pins as firmware/IJ_Meter. On power-up the whole display and both LEDs are on:

      8.8.8.8.   all four digits with every segment and every dot, red LED + green LED

  If a segment, a dot or a digit is dark (or a wrong one is lit), that wire, resistor or pin is the
  fault. Open the Serial Monitor at 115200 and send a letter to change the test:

    a   all on (the default)
    s   walk the segments one at a time on all digits: a b c d e f g dp, 1 s each
    d   walk the digits one at a time, all segments lit: D1 D2 D3 D4, 1 s each
    l   LEDs alternate: red, green, both, off, 1 s each (display stays all on)

  Wiring
    Segments a b c d e f g dp -> 330R each -> GPIO 13 14 27 26 25 33 32 23   (HIGH = segment on)
    Digit commons D1..D4      -> GPIO 18 19 21 22                              (LOW  = digit on)
    Red LED -> 330R -> GPIO 4 ; Green LED -> 330R -> GPIO 5 ; LED cathodes -> GND

  Arduino IDE: board "ESP32 Dev Module". No extra libraries.
*/

#include <esp_timer.h>

const uint8_t SEG[8] = {13, 14, 27, 26, 25, 33, 32, 23};  // a b c d e f g dp
const uint8_t DIG[4] = {18, 19, 21, 22};                  // D1..D4
const uint8_t LED_RED   = 4;
const uint8_t LED_GREEN = 5;

const char* SEG_NAME[8] = {"a", "b", "c", "d", "e", "f", "g", "dp"};

volatile uint8_t frame[4] = {0xFF, 0xFF, 0xFF, 0xFF};   // segment mask per digit, bit0 = a ... bit7 = dp

// Runs every 2 ms: one digit at a time, so each digit is lit 125 times a second.
void refreshDigit(void*) {
  static uint8_t d = 0;
  for (uint8_t i = 0; i < 4; i++) digitalWrite(DIG[i], HIGH);   // blank first: no ghosting
  uint8_t mask = frame[d];
  for (uint8_t i = 0; i < 8; i++) digitalWrite(SEG[i], (mask >> i) & 1);
  digitalWrite(DIG[d], LOW);
  d = (d + 1) & 3;
}

const char ALL = 'a', SEGMENTS = 's', DIGITS = 'd', LEDS = 'l';
char test = ALL;
uint32_t testStart = 0;

void setAll(uint8_t mask) { for (uint8_t i = 0; i < 4; i++) frame[i] = mask; }

void setTest(char t) {
  test = t;
  testStart = millis();
  switch (t) {
    case ALL:    Serial.println("all on: 8.8.8.8. + both LEDs"); break;
    case SEGMENTS: Serial.println("segment walk: a b c d e f g dp on every digit"); break;
    case DIGITS:   Serial.println("digit walk: D1 D2 D3 D4, all segments lit"); break;
    case LEDS:     Serial.println("LED walk: red, green, both, off (display all on)"); break;
  }
}

void setup() {
  Serial.begin(115200);
  delay(200);
  Serial.println();
  Serial.println("IJ Meter HW test. Send a=all on, s=segments, d=digits, l=LEDs");

  for (uint8_t i = 0; i < 8; i++) { pinMode(SEG[i], OUTPUT); digitalWrite(SEG[i], LOW); }
  for (uint8_t i = 0; i < 4; i++) { pinMode(DIG[i], OUTPUT); digitalWrite(DIG[i], HIGH); }
  pinMode(LED_RED, OUTPUT);
  pinMode(LED_GREEN, OUTPUT);

  const esp_timer_create_args_t args = { .callback = &refreshDigit, .arg = nullptr, .dispatch_method = ESP_TIMER_TASK, .name = "digits" };
  esp_timer_handle_t timer;
  esp_timer_create(&args, &timer);
  esp_timer_start_periodic(timer, 2000);

  setTest(ALL);
}

void loop() {
  if (Serial.available()) {
    char c = Serial.read();
    if (c == ALL || c == SEGMENTS || c == DIGITS || c == LEDS) setTest(c);
  }

  uint32_t step = (millis() - testStart) / 1000;   // changes once a second
  static uint32_t lastStep = UINT32_MAX;
  static char lastTest = ALL;
  bool changed = step != lastStep || test != lastTest;
  lastStep = step;
  lastTest = test;

  switch (test) {
    case ALL:
      setAll(0xFF);
      digitalWrite(LED_RED, HIGH);
      digitalWrite(LED_GREEN, HIGH);
      break;

    case SEGMENTS: {
      uint8_t s = step % 8;
      setAll(1 << s);
      digitalWrite(LED_RED, HIGH);
      digitalWrite(LED_GREEN, HIGH);
      if (changed) Serial.printf("segment %s\n", SEG_NAME[s]);
      break;
    }

    case DIGITS: {
      uint8_t d = step % 4;
      for (uint8_t i = 0; i < 4; i++) frame[i] = (i == d) ? 0xFF : 0x00;
      digitalWrite(LED_RED, HIGH);
      digitalWrite(LED_GREEN, HIGH);
      if (changed) Serial.printf("digit D%u\n", d + 1);
      break;
    }

    case LEDS: {
      setAll(0xFF);
      uint8_t s = step % 4;
      digitalWrite(LED_RED,   s == 0 || s == 2);
      digitalWrite(LED_GREEN, s == 1 || s == 2);
      if (changed) {
        static const char* NAME[4] = {"red", "green", "both", "off"};
        Serial.printf("LEDs: %s\n", NAME[s]);
      }
      break;
    }
  }
  delay(20);
}
