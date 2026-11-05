/*
 * Aquarium Light Controller — ESP8266 firmware
 *
 * Switches a relay on a two-slot daily schedule. Time comes from a DS3231
 * RTC (kept accurate via NTP whenever the internet is reachable), so the
 * schedule keeps running with no WiFi or broker at all. Everything network-
 * related is non-blocking: losing WiFi or MQTT never stalls the light logic.
 *
 * MQTT topics
 *   aquarium/control   <- AUTO | FORCE_ON | FORCE_OFF | RESET_WIFI
 *   aquarium/schedule  <- "e1,HH:MM,HH:MM,e2,HH:MM,HH:MM"  (e = 1 enabled / 0 disabled)
 *   aquarium/status    -> JSON status, retained, every STATUS_INTERVAL_MS and on change
 *   aquarium/online    -> "1" / "0", retained; "0" is the broker-side last will
 *
 * Credentials live in secrets.h (copy secrets.example.h).
 */

#include <ESP8266WiFi.h>
#include <WiFiManager.h>
#include <WiFiClientSecure.h>
#include <PubSubClient.h>
#include <Wire.h>
#include <RTClib.h>
#include <LittleFS.h>
#include <coredecls.h>   // settimeofday_cb
#include <time.h>

#include "secrets.h"

// =========================================================
// CONFIGURATION
// =========================================================

const uint8_t RELAY_PIN   = D5;
const uint8_t I2C_SDA_PIN = D2;
const uint8_t I2C_SCL_PIN = D1;

const long UTC_OFFSET_SECONDS = 19800;   // IST, UTC+5:30

const char* const SETUP_AP_NAME          = "AquariumLight_Setup";
const uint16_t    SETUP_PORTAL_TIMEOUT_S = 180;   // then carry on offline

const unsigned long CONTROL_INTERVAL_MS    = 1000;
const unsigned long STATUS_INTERVAL_MS     = 10000;
const unsigned long MQTT_RETRY_INTERVAL_MS = 5000;
const unsigned long WIFI_RETRY_INTERVAL_MS = 30000;

const char* const TOPIC_CONTROL  = "aquarium/control";
const char* const TOPIC_SCHEDULE = "aquarium/schedule";
const char* const TOPIC_STATUS   = "aquarium/status";
const char* const TOPIC_ONLINE   = "aquarium/online";

const char* const SCHEDULE_FILE = "/schedule.txt";
const char* const MODE_FILE     = "/mode.txt";

// Anything earlier than this means the clock was never set.
const time_t MIN_VALID_EPOCH = 1700000000;   // Nov 2023

// =========================================================
// STATE
// =========================================================

struct Slot
{
    bool    enabled;
    uint8_t onHour;
    uint8_t onMinute;
    uint8_t offHour;
    uint8_t offMinute;
};

Slot slots[2] = {
    { true,  8, 0, 14, 0 },
    { true, 16, 0, 22, 0 },
};

enum Mode : uint8_t { MODE_AUTO, MODE_FORCE_ON, MODE_FORCE_OFF };

Mode mode = MODE_AUTO;

RTC_DS3231 rtc;
bool rtcAvailable = false;   // chip answered on I2C
bool rtcTimeValid = false;   // chip holds a trustworthy time
bool fsAvailable  = false;

WiFiManager      wifiManager;
WiFiClientSecure tlsClient;
PubSubClient     mqtt(tlsClient);

bool lightOn            = false;
bool statusDirty        = true;    // publish at the next opportunity
bool wifiResetRequested = false;

volatile bool ntpTimeReceived = false;   // set from the SNTP callback

unsigned long lastControlRun    = 0;
unsigned long lastStatusPublish = 0;
unsigned long lastMqttAttempt   = 0;
unsigned long lastWifiAttempt   = 0;

// =========================================================
// HELPERS
// =========================================================

const char* modeName(Mode m)
{
    switch(m)
    {
        case MODE_FORCE_ON:  return "FORCE_ON";
        case MODE_FORCE_OFF: return "FORCE_OFF";
        default:             return "AUTO";
    }
}

bool parseMode(const char* text, Mode& out)
{
    if(strcmp(text, "AUTO") == 0)      { out = MODE_AUTO;      return true; }
    if(strcmp(text, "FORCE_ON") == 0)  { out = MODE_FORCE_ON;  return true; }
    if(strcmp(text, "FORCE_OFF") == 0) { out = MODE_FORCE_OFF; return true; }
    return false;
}

bool isValidTime(int hour, int minute)
{
    return hour >= 0 && hour < 24 && minute >= 0 && minute < 60;
}

bool isValidFlag(int value)
{
    return value == 0 || value == 1;
}

Slot makeSlot(int enabled, int onH, int onM, int offH, int offM)
{
    Slot s;
    s.enabled   = enabled == 1;
    s.onHour    = onH;
    s.onMinute  = onM;
    s.offHour   = offH;
    s.offMinute = offM;
    return s;
}

bool systemTimeValid()
{
    return time(nullptr) > MIN_VALID_EPOCH;
}

// Local wall-clock time: the RTC when it's trustworthy, otherwise the
// NTP-backed system clock. Returns false when neither is usable.
bool readLocalTime(int& hour, int& minute, int& second)
{
    if(rtcAvailable && rtcTimeValid)
    {
        DateTime now = rtc.now();
        hour   = now.hour();
        minute = now.minute();
        second = now.second();
        return true;
    }

    if(systemTimeValid())
    {
        time_t t = time(nullptr);
        struct tm local;
        localtime_r(&t, &local);
        hour   = local.tm_hour;
        minute = local.tm_min;
        second = local.tm_sec;
        return true;
    }

    return false;
}

void syncRtcFromSystemTime()
{
    if(!rtcAvailable || !systemTimeValid())
    {
        return;
    }

    time_t t = time(nullptr);
    struct tm local;
    localtime_r(&t, &local);

    rtc.adjust(DateTime(
        local.tm_year + 1900,
        local.tm_mon + 1,
        local.tm_mday,
        local.tm_hour,
        local.tm_min,
        local.tm_sec));

    rtcTimeValid = true;

    Serial.printf("RTC synced from NTP: %02d:%02d:%02d\n",
        local.tm_hour, local.tm_min, local.tm_sec);
}

// =========================================================
// PERSISTENCE (LittleFS)
// =========================================================

// File format (unchanged from earlier firmware, so old saves still load):
//   e1,e2,h1on,m1on,h1off,m1off,h2on,m2on,h2off,m2off
void saveSchedule()
{
    if(!fsAvailable)
    {
        return;
    }

    File file = LittleFS.open(SCHEDULE_FILE, "w");

    if(!file)
    {
        Serial.println(F("Schedule save failed"));
        return;
    }

    file.printf("%d,%d,%02d,%02d,%02d,%02d,%02d,%02d,%02d,%02d",
        slots[0].enabled, slots[1].enabled,
        slots[0].onHour, slots[0].onMinute, slots[0].offHour, slots[0].offMinute,
        slots[1].onHour, slots[1].onMinute, slots[1].offHour, slots[1].offMinute);

    file.close();
    Serial.println(F("Schedule saved"));
}

void loadSchedule()
{
    if(!fsAvailable || !LittleFS.exists(SCHEDULE_FILE))
    {
        Serial.println(F("No saved schedule, using defaults"));
        return;
    }

    File file = LittleFS.open(SCHEDULE_FILE, "r");

    if(!file)
    {
        Serial.println(F("Schedule load failed"));
        return;
    }

    String data = file.readString();
    file.close();

    int e1, e2, h1, m1, h2, m2, h3, m3, h4, m4;

    int parsed = sscanf(data.c_str(), "%d,%d,%d,%d,%d,%d,%d,%d,%d,%d",
        &e1, &e2, &h1, &m1, &h2, &m2, &h3, &m3, &h4, &m4);

    if(parsed != 10 ||
       !isValidFlag(e1) || !isValidFlag(e2) ||
       !isValidTime(h1, m1) || !isValidTime(h2, m2) ||
       !isValidTime(h3, m3) || !isValidTime(h4, m4))
    {
        Serial.println(F("Saved schedule is corrupt, using defaults"));
        return;
    }

    slots[0] = makeSlot(e1, h1, m1, h2, m2);
    slots[1] = makeSlot(e2, h3, m3, h4, m4);

    Serial.println(F("Schedule loaded"));
}

void saveMode()
{
    if(!fsAvailable)
    {
        return;
    }

    File file = LittleFS.open(MODE_FILE, "w");

    if(!file)
    {
        Serial.println(F("Mode save failed"));
        return;
    }

    file.print(modeName(mode));
    file.close();
}

void loadMode()
{
    if(!fsAvailable || !LittleFS.exists(MODE_FILE))
    {
        return;
    }

    File file = LittleFS.open(MODE_FILE, "r");

    if(!file)
    {
        return;
    }

    String data = file.readString();
    file.close();
    data.trim();

    Mode loaded;

    if(parseMode(data.c_str(), loaded))
    {
        mode = loaded;
        Serial.printf("Mode loaded: %s\n", modeName(mode));
    }
}

// =========================================================
// LIGHT CONTROL
// =========================================================

bool slotActive(const Slot& slot, int nowMinutes)
{
    if(!slot.enabled)
    {
        return false;
    }

    int on  = slot.onHour * 60 + slot.onMinute;
    int off = slot.offHour * 60 + slot.offMinute;

    if(on == off)
    {
        return false;   // zero-length window
    }

    if(on < off)
    {
        return nowMinutes >= on && nowMinutes < off;
    }

    // Window crosses midnight, e.g. 22:00 -> 02:00
    return nowMinutes >= on || nowMinutes < off;
}

void runControl()
{
    bool shouldBeOn = false;

    if(mode == MODE_FORCE_ON)
    {
        shouldBeOn = true;
    }
    else if(mode == MODE_AUTO)
    {
        int h, m, s;

        // With no trustworthy clock, stay off rather than guess.
        if(readLocalTime(h, m, s))
        {
            int nowMinutes = h * 60 + m;
            shouldBeOn = slotActive(slots[0], nowMinutes) ||
                         slotActive(slots[1], nowMinutes);
        }
    }

    // Written every pass so a glitched pin state can't persist.
    digitalWrite(RELAY_PIN, shouldBeOn ? HIGH : LOW);

    if(shouldBeOn != lightOn)
    {
        lightOn = shouldBeOn;
        statusDirty = true;
        Serial.printf("Light %s (mode %s)\n", lightOn ? "ON" : "OFF", modeName(mode));
    }
}

// =========================================================
// MQTT
// =========================================================

void handleControl(const char* message)
{
    if(strcmp(message, "RESET_WIFI") == 0)
    {
        wifiResetRequested = true;   // acted on from loop()
        return;
    }

    Mode requested;

    if(!parseMode(message, requested))
    {
        Serial.println(F("Unknown control command, ignored"));
        return;
    }

    if(requested != mode)
    {
        mode = requested;
        saveMode();
        Serial.printf("Mode changed to %s\n", modeName(mode));
    }

    statusDirty = true;
    runControl();
}

void handleSchedule(const char* message)
{
    int e1, h1, m1, h2, m2, e2, h3, m3, h4, m4;
    char trailing;

    // The trailing %c catches junk after the last field.
    int parsed = sscanf(message, "%d,%d:%d,%d:%d,%d,%d:%d,%d:%d %c",
        &e1, &h1, &m1, &h2, &m2, &e2, &h3, &m3, &h4, &m4, &trailing);

    if(parsed != 10 ||
       !isValidFlag(e1) || !isValidFlag(e2) ||
       !isValidTime(h1, m1) || !isValidTime(h2, m2) ||
       !isValidTime(h3, m3) || !isValidTime(h4, m4))
    {
        Serial.println(F("Invalid schedule message, ignored"));
        return;
    }

    slots[0] = makeSlot(e1, h1, m1, h2, m2);
    slots[1] = makeSlot(e2, h3, m3, h4, m4);

    saveSchedule();

    Serial.printf("Schedule: S1 %s %02d:%02d-%02d:%02d | S2 %s %02d:%02d-%02d:%02d\n",
        e1 ? "on " : "off", h1, m1, h2, m2,
        e2 ? "on " : "off", h3, m3, h4, m4);

    statusDirty = true;
    runControl();
}

void onMqttMessage(char* topic, byte* payload, unsigned int length)
{
    char message[128];

    if(length >= sizeof(message))
    {
        Serial.println(F("MQTT message too long, ignored"));
        return;
    }

    memcpy(message, payload, length);
    message[length] = '\0';

    Serial.printf("MQTT [%s] %s\n", topic, message);

    if(strcmp(topic, TOPIC_CONTROL) == 0)
    {
        handleControl(message);
    }
    else if(strcmp(topic, TOPIC_SCHEDULE) == 0)
    {
        handleSchedule(message);
    }
}

void publishStatus()
{
    char clockText[16] = "--:--:--";
    int h, m, s;

    if(readLocalTime(h, m, s))
    {
        snprintf(clockText, sizeof(clockText), "%02d:%02d:%02d", h, m, s);
    }

    char payload[320];

    snprintf(payload, sizeof(payload),
        "{\"online\":true,"
        "\"mode\":\"%s\","
        "\"light\":\"%s\","
        "\"time\":\"%s\","
        "\"slot1\":\"%02d:%02d-%02d:%02d\","
        "\"slot2\":\"%02d:%02d-%02d:%02d\","
        "\"slot1Enabled\":%s,"
        "\"slot2Enabled\":%s}",
        modeName(mode),
        lightOn ? "ON" : "OFF",
        clockText,
        slots[0].onHour, slots[0].onMinute, slots[0].offHour, slots[0].offMinute,
        slots[1].onHour, slots[1].onMinute, slots[1].offHour, slots[1].offMinute,
        slots[0].enabled ? "true" : "false",
        slots[1].enabled ? "true" : "false");

    // Retained, so a freshly opened dashboard sees the state immediately.
    mqtt.publish(TOPIC_STATUS, payload, true);
}

void maintainMqtt()
{
    if(WiFi.status() != WL_CONNECTED)
    {
        return;
    }

    if(mqtt.connected())
    {
        mqtt.loop();
        return;
    }

    unsigned long now = millis();

    if(lastMqttAttempt != 0 && now - lastMqttAttempt < MQTT_RETRY_INTERVAL_MS)
    {
        return;
    }

    lastMqttAttempt = now;

    String clientId = "Aquarium-" + String(ESP.getChipId());

    Serial.println(F("Connecting to MQTT..."));

    // Last will: the broker marks us offline if we drop off without a goodbye.
    if(mqtt.connect(clientId.c_str(), MQTT_USER, MQTT_PASSWORD,
                    TOPIC_ONLINE, 1, true, "0"))
    {
        Serial.println(F("MQTT connected"));
        mqtt.publish(TOPIC_ONLINE, "1", true);
        mqtt.subscribe(TOPIC_CONTROL);
        mqtt.subscribe(TOPIC_SCHEDULE);
        statusDirty = true;
    }
    else
    {
        Serial.printf("MQTT connect failed, rc=%d\n", mqtt.state());
    }
}

// =========================================================
// WIFI
// =========================================================

void maintainWifi()
{
    // Serves the setup portal while it's open (non-blocking mode).
    wifiManager.process();

    if(WiFi.status() == WL_CONNECTED || wifiManager.getConfigPortalActive())
    {
        return;
    }

    // Nudge the station to retry with its saved credentials.
    unsigned long now = millis();

    if(now - lastWifiAttempt >= WIFI_RETRY_INTERVAL_MS)
    {
        lastWifiAttempt = now;
        WiFi.begin();
    }
}

void resetWifiAndRestart()
{
    Serial.println(F("WiFi reset requested, restarting into setup portal"));

    if(mqtt.connected())
    {
        mqtt.publish(TOPIC_ONLINE, "0", true);
        mqtt.disconnect();
    }

    wifiManager.resetSettings();
    delay(500);
    ESP.restart();
}

// =========================================================
// SETUP / LOOP
// =========================================================

void setup()
{
    Serial.begin(115200);
    Serial.println();

    pinMode(RELAY_PIN, OUTPUT);
    digitalWrite(RELAY_PIN, LOW);

    // RTC: optional. Without it we fall back to NTP time.
    Wire.begin(I2C_SDA_PIN, I2C_SCL_PIN);
    rtcAvailable = rtc.begin();

    if(!rtcAvailable)
    {
        Serial.println(F("RTC not found, using NTP time only"));
    }
    else
    {
        rtcTimeValid = !rtc.lostPower();

        if(!rtcTimeValid)
        {
            Serial.println(F("RTC lost power, waiting for NTP"));
        }
    }

    // Storage: optional. Without it settings just don't survive a reboot.
    fsAvailable = LittleFS.begin();

    if(fsAvailable)
    {
        loadSchedule();
        loadMode();
    }
    else
    {
        Serial.println(F("LittleFS mount failed, settings won't persist"));
    }

    // Get the light right before touching the network.
    runControl();

    // NTP: the SDK resyncs periodically on its own; each sync updates the RTC.
    settimeofday_cb([](bool fromSntp) {
        if(fromSntp)
        {
            ntpTimeReceived = true;
        }
    });
    configTime(UTC_OFFSET_SECONDS, 0, "pool.ntp.org", "time.nist.gov", "time.google.com");

    // WiFi: non-blocking, so the schedule keeps running while the setup
    // portal is open, and after it times out with no network at all.
    WiFi.mode(WIFI_STA);
    wifiManager.setConfigPortalBlocking(false);
    wifiManager.setConfigPortalTimeout(SETUP_PORTAL_TIMEOUT_S);

    if(wifiManager.autoConnect(SETUP_AP_NAME))
    {
        Serial.println(F("WiFi connected"));
    }
    else
    {
        Serial.println(F("WiFi not connected, setup portal open"));
    }

    // MQTT. TLS is encrypted but the broker certificate isn't verified.
    tlsClient.setInsecure();
    mqtt.setServer(MQTT_HOST, MQTT_PORT);
    mqtt.setCallback(onMqttMessage);
    mqtt.setBufferSize(512);
}

void loop()
{
    maintainWifi();
    maintainMqtt();

    if(ntpTimeReceived)
    {
        ntpTimeReceived = false;
        syncRtcFromSystemTime();
    }

    unsigned long now = millis();

    if(now - lastControlRun >= CONTROL_INTERVAL_MS)
    {
        lastControlRun = now;
        runControl();
    }

    if(mqtt.connected() &&
       (statusDirty || now - lastStatusPublish >= STATUS_INTERVAL_MS))
    {
        publishStatus();
        lastStatusPublish = now;
        statusDirty = false;
    }

    if(wifiResetRequested)
    {
        resetWifiAndRestart();
    }
}
