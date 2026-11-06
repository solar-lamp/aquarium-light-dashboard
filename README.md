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
| `firmware/Aquarium_Light/secrets.example.h` | Optional template to pre-fill the broker login for a USB build |
| `android/` | Native Android app (its own UI, background alerts) |

## Dashboard

Open `index.html` in a browser, or host the folder anywhere static (GitHub
Pages works). The broker settings are in `config.js`.

Everything in `config.js` is public to anyone who can load the page. Use a
broker user whose permissions only cover `aquarium/#`, not an admin account.

## Android app

A native Android app (Kotlin, Jetpack Compose) with its own black and
blue-glass interface. It controls the light and can notify you when something
changes, from any network. It isn't on the Play Store: you install the APK
directly.

Screens: Home, Light, Feeding, Care (maintenance and water tests),
Fish & plants, History and Settings. Everything except live light control is
shared through Firebase and stays in sync across the family's phones.

**Family access:** everyone signs in with Google. The owner
(`souradeep241@gmail.com`) is the admin. Anyone else sees "Waiting for
approval" until the admin approves them in *Settings → Members*. Approved
members receive the broker login automatically. Only the admin can change the
light schedule, reset the controller's WiFi, manage members and delete history.

**Firebase:** the project config is `android/app/google-services.json`, and
the database security rules are in `firebase/firestore.rules`. Paste the rules
into *Firestore Database → Rules* and publish whenever they change. Google
sign-in only works for APKs signed with the release key below, whose SHA-1 is
registered in the Firebase project settings.

**Get the APK:** every push to `main` that touches `android/` builds it with GitHub Actions and publishes it as the
[`app-latest` release](../../releases/tag/app-latest). On your phone, download
`aquarium-light.apk`, open it and allow installing from your browser when asked.
Builds from other branches are attached to their Actions run as an artifact.

**First launch:** sign in with Google. The admin then enters the broker
username and password once in Settings; family members get them automatically
after approval.

**Notifications** (Settings → Notifications):

- *Background alerts* keeps a connection to the broker open while the app is
  closed. Android requires a small permanent notification for that.
- Choose which alerts you want: device offline / back online, light on / off,
  mode changes.
- *Run in background reliably* lets the app skip Android's battery
  optimisation. Without it, alerts can be delayed for a long time while the
  phone is idle.
- *Sound and style* opens Android's settings for each alert type.

Changes that happen while your phone has no connection are reported when it
reconnects.

**How it's built:** the app is independent of the website; changing one
doesn't affect the other. It talks to the controller over the same MQTT topics.
To build locally, open `android/` in Android Studio, or run
`./gradlew assembleRelease` in `android/`.

**Signing key (do this once):** without one, every CI build gets a new
throwaway key, and Android refuses to install an update over an app signed with
a different key. Create a key and add it as repository secrets
(*Settings → Secrets and variables → Actions*):

```sh
keytool -genkeypair -v -keystore release.jks -alias aquarium \
  -keyalg RSA -keysize 2048 -validity 10000
base64 -w0 release.jks   # paste the output into SIGNING_KEYSTORE_BASE64
```

| Secret | Value |
| --- | --- |
| `SIGNING_KEYSTORE_BASE64` | base64 of `release.jks` |
| `SIGNING_STORE_PASSWORD` | the keystore password |
| `SIGNING_KEY_ALIAS` | `aquarium` |
| `SIGNING_KEY_PASSWORD` | the key password |

Keep `release.jks` and its passwords somewhere safe. If you lose them, you'll
have to uninstall the app before you can install a newer build.

## Firmware

**Board:** ESP8266 (e.g. NodeMCU / Wemos D1 mini), ESP8266 Arduino core 3.x

**Libraries:** WiFiManager (tzapu), PubSubClient, RTClib (Adafruit)

**Wiring:**

| Part | Pin |
| --- | --- |
| Relay input | D5 |
| DS3231 SDA | D2 |
| DS3231 SCL | D1 |

**First install (USB, once):**

1. Download `firmware.bin` from the
   [`firmware-latest` release](../../releases/tag/firmware-latest).
2. On a computer with Chrome or Edge, connect the board over USB, open
   [ESP Web Flasher](https://espressif.github.io/esptool-js/), connect, and
   flash `firmware.bin` at address `0x0`.
   (Or build from source in the Arduino IDE: board *NodeMCU 1.0 (ESP-12E
   Module)*, flash size *4MB (FS:2MB OTA:~1019KB)*.)
3. The device opens the `AquariumLight_Setup` hotspot. Join it from a phone,
   choose your WiFi network, and enter the broker username and password.
   They're kept in the device's flash.

**Updates after that:** in the app, *Light → Controller → Firmware → Update*.
The device downloads the latest release from GitHub and restarts. Every push
to `main` that touches `firmware/` builds and publishes a new release; the
binary never contains broker credentials.

**Tests:** `firmware/test/run.sh` compiles the unmodified firmware against a
simulated board and runs scenarios such as:

- router off before and during a slot
- power cuts with and without internet
- a flat RTC cell
- slots across midnight
- forced modes surviving restarts
- ISP and broker outages
- clock fixes
- the crash guard and OTA updates

It checks the relay second by second. CI runs it before every firmware release.

**Self-recovery:** if WiFi is up but the broker can't be reached, the
controller backs off its reconnect attempts (5 s up to 60 s), so a failing
attempt never delays the light by more than a moment. It restarts its WiFi
after 10 minutes and restarts itself after 60, keeping the schedule, mode and
on-time. Each failure reason is logged, and the status reports WiFi signal
strength (`rssi`) and free memory (`heap`).

**Crash guard:** if the firmware crashes and restarts 3 times in a row, the
board starts in a minimal *safe mode*. It only connects to WiFi and the broker,
reports `"safeMode": true`, and accepts On/Off, *Update* and *Restart*. The
light schedule is paused. The app shows a warning and sends an alert. Install
a fixed firmware with *Update*, or use *Restart normally* to try the normal
firmware again. So a bad update can always be fixed remotely, as long as the
board can still reach WiFi.

If you're not in IST, change `UTC_OFFSET_SECONDS` at the top of the sketch.

**Behaviour worth knowing:**

- The schedule and mode are saved to flash and survive reboots and power cuts.
- With no RTC, or an RTC that lost power, the device uses NTP time. If it has
  no trustworthy time at all, Auto mode keeps the light **off**.
- A slot whose on and off times match is treated as empty. A slot whose off
  time is earlier than its on time runs across midnight (e.g. 22:00 → 02:00).

## MQTT protocol

| Topic | Direction | Payload |
| --- | --- | --- |
| `aquarium/control` | app/dashboard → device | `AUTO`, `FORCE_ON`, `FORCE_OFF`, `STATUS`, `SYNC_TIME`, `SET_TIME:YYYY-MM-DDTHH:MM:SS`, `UPDATE_FIRMWARE`, `RESTART`, `RESET_WIFI`, `ACK_EVENTS:<seq>` |
| `aquarium/schedule` | dashboard → device | `e1,HH:MM,HH:MM,e2,HH:MM,HH:MM` (`e` = `1` enabled, `0` disabled) |
| `aquarium/status` | device → dashboard | JSON, retained, every 10 s and on every change |
| `aquarium/events` | device → app | Retained log of what the controller did (light switches, WiFi lost/back, restarts, clock set), including while offline: `{"log":"<id>","events":[{"seq":1,"t":<local epoch>,"text":"…"}]}`. The first phone to see it saves it to History and sends `ACK_EVENTS:<seq>`, then the device drops those entries. |
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
  "slot2Enabled": false,
  "date": "2026-11-06",
  "onToday": 18240,
  "timeSource": "rtc",
  "rtcFound": true,
  "rtcLostPower": false,
  "rtcTemp": 27.5,
  "ntpAgo": 412,
  "uptime": 86400,
  "reset": "Power On",
  "fw": "1.3",
  "update": ""
}
```
