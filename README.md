# Aquarium Light Dashboard

An ESP8266 aquarium light controller with a web dashboard.

The device switches a relay on a two-slot daily schedule. It keeps time with a
DS3231 RTC, so the schedule keeps running with no internet. When it is online,
it syncs the clock over NTP and reports to an MQTT broker (HiveMQ Cloud). The
static web page in this repo talks to the same broker to show status, change
the mode and edit the schedule.

```
┌────────────┐   MQTT/TLS   ┌──────────────┐   MQTT/WSS   ┌────────────────┐
│  ESP8266   │ ───────────▶ │    HiveMQ    │ ◀─────────── │ Web dashboard  │
│ relay, RTC │ ◀─────────── │    broker    │ ───────────▶ │  (index.html)  │
└────────────┘              └──────────────┘              └────────────────┘
```

## Repository layout

| Path | What it is |
| --- | --- |
| `index.html`, `style.css`, `script.js` | The dashboard (static, no build step) |
| `config.js` | Broker connection settings for the dashboard |
| `firmware/Aquarium_Light/` | Arduino sketch for the ESP8266 |
| `firmware/Aquarium_Light/secrets.example.h` | Template for the firmware's broker credentials |

## Dashboard

Open `index.html` in a browser, or host the folder anywhere static (GitHub
Pages works). The broker settings are in `config.js`.

Everything in `config.js` is public to anyone who can load the page. Use a
broker user whose permissions only cover `aquarium/#`, not an admin account.

## Firmware

**Board:** ESP8266 (e.g. NodeMCU / Wemos D1 mini), ESP8266 Arduino core 3.x

**Libraries:** WiFiManager (tzapu), PubSubClient, RTClib (Adafruit)

**Wiring:**

| Part | Pin |
| --- | --- |
| Relay input | D5 |
| DS3231 SDA | D2 |
| DS3231 SCL | D1 |

**Setup:**

1. Copy `firmware/Aquarium_Light/secrets.example.h` to `secrets.h` in the same
   folder and fill in your broker details. `secrets.h` is git-ignored.
2. If you're not in IST, change `UTC_OFFSET_SECONDS` at the top of the sketch.
3. Open `firmware/Aquarium_Light/Aquarium_Light.ino` in the Arduino IDE and upload.
4. On first boot the device opens a `AquariumLight_Setup` hotspot. Join it to
   enter your WiFi details. If nobody configures it within 3 minutes, the
   device carries on offline and keeps following the schedule.

**Behaviour worth knowing:**

- The schedule and mode are saved to flash and survive reboots and power cuts.
- With no RTC, or an RTC that lost power, the device uses NTP time. If it has
  no trustworthy time at all, Auto mode keeps the light **off**.
- A slot whose on and off times match is treated as empty. A slot whose off
  time is earlier than its on time runs across midnight (e.g. 22:00 → 02:00).

## MQTT protocol

| Topic | Direction | Payload |
| --- | --- | --- |
| `aquarium/control` | dashboard → device | `AUTO`, `FORCE_ON`, `FORCE_OFF` or `RESET_WIFI` |
| `aquarium/schedule` | dashboard → device | `e1,HH:MM,HH:MM,e2,HH:MM,HH:MM` (`e` = `1` enabled, `0` disabled) |
| `aquarium/status` | device → dashboard | JSON, retained, every 10 s and on every change |
| `aquarium/online` | device → dashboard | `1` / `0`, retained. The broker sends `0` as the last will if the device drops off |

Example status:

```json
{
  "online": true,
  "mode": "AUTO",
  "light": "ON",
  "time": "09:12:01",
  "slot1": "08:00-14:00",
  "slot2": "16:00-22:00",
  "slot1Enabled": true,
  "slot2Enabled": false
}
```
