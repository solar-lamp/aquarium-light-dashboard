/*
 * AquaHome light controller — ESP8266 firmware
 *
 * Switches a relay on a two-slot daily schedule. Time comes from a DS3231
 * RTC (kept accurate via NTP whenever the internet is reachable), so the
 * schedule keeps running with no WiFi or broker at all. Everything network-
 * related is non-blocking: losing WiFi or MQTT never stalls the light logic.
 *
 * MQTT topics
 *   aquarium/control   <- AUTO | FORCE_ON | FORCE_OFF          set the mode
 *                         STATUS                               publish status now
 *                         SYNC_TIME                            re-sync the clock from the internet
 *                         SET_TIME:YYYY-MM-DDTHH:MM:SS         set the clock (local time)
 *                         UPDATE_FIRMWARE                      install the latest release over the air
 *                         RESTART                              restart (also leaves safe mode)
 *                         RESET_WIFI                           forget WiFi, reopen the setup hotspot
 *   aquarium/schedule  <- "e1,HH:MM,HH:MM,e2,HH:MM,HH:MM"  (e = 1 enabled / 0 disabled)
 *   aquarium/status    -> JSON status, retained, every STATUS_INTERVAL_MS and on change
 *   aquarium/online    -> "1" / "0", retained; "0" is the broker-side last will
 *
 * Crash guard: after CRASH_LIMIT crash restarts in a row the board starts in
 * safe mode, which only connects, reports {"safeMode":true}, and accepts
 * FORCE_ON / FORCE_OFF / AUTO (relay off) / STATUS / UPDATE_FIRMWARE / RESTART,
 * so a bad update can always be fixed over the air.
 *
 * Broker login: entered on the setup hotspot (AquariumLight_Setup) and kept
 * in flash, so over-the-air updates never need it. secrets.h can pre-fill it
 * for a USB flash.
 */

#include <ESP8266WiFi.h>
#include <ESP8266httpUpdate.h>
#include <WiFiManager.h>
#include <WiFiClientSecure.h>
#include <PubSubClient.h>
#include <Wire.h>
#include <RTClib.h>
#include <LittleFS.h>
#include <coredecls.h>   // settimeofday_cb
#include <time.h>

#include "secrets.h"
#include "version.h"     // FW_VERSION, written by CI

// =========================================================
// CONFIGURATION
// =========================================================

const uint8_t RELAY_PIN   = D5;
const uint8_t I2C_SDA_PIN = D2;
const uint8_t I2C_SCL_PIN = D1;

const long UTC_OFFSET_SECONDS = 19800;   // IST, UTC+5:30

const char* const SETUP_AP_NAME          = "AquariumLight_Setup";
const uint16_t    SETUP_PORTAL_TIMEOUT_S = 180;   // then carry on offline
const uint16_t    SETUP_PORTAL_LONG_S    = 900;   // when the broker login is missing

const char* const DEFAULT_MQTT_HOST = "8f4859d367254182889b312a863177b0.s1.eu.hivemq.cloud";

const char* const FIRMWARE_URL =
    "https://github.com/solar-lamp/aquarium-light-dashboard/releases/download/firmware-latest/firmware.bin";

const unsigned long CONTROL_INTERVAL_MS    = 1000;
const unsigned long STATUS_INTERVAL_MS     = 10000;
const unsigned long MQTT_RETRY_INTERVAL_MS = 5000;
const unsigned long WIFI_RETRY_INTERVAL_MS = 30000;
const unsigned long ONTIME_SAVE_MS         = 300000;   // persist on-time every 5 min

// Crash guard
const uint32_t      CRASH_LIMIT          = 3;        // crash restarts in a row before safe mode
const unsigned long CRASH_STABLE_MS      = 60000;    // running this long clears the count
const uint32_t      GUARD_MAGIC          = 0xA9A0C0DE;
const uint32_t      GUARD_RTC_OFFSET     = 64;       // RTC user memory block; first 128 bytes belong to OTA

const char* const TOPIC_CONTROL  = "aquarium/control";
const char* const TOPIC_SCHEDULE = "aquarium/schedule";
const char* const TOPIC_STATUS   = "aquarium/status";
const char* const TOPIC_ONLINE   = "aquarium/online";

const char* const SCHEDULE_FILE = "/schedule.txt";
const char* const MODE_FILE     = "/mode.txt";
const char* const ONTIME_FILE   = "/ontime.txt";
const char* const MQTT_FILE     = "/mqtt.txt";

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
bool rtcAvailable     = false;   // chip answered on I2C
bool rtcTimeValid     = false;   // chip holds a trustworthy time
bool rtcLostPowerBoot = false;   // the RTC had lost its time at power-up (flat coin cell?)
bool fsAvailable      = false;

// Broker login, from flash (or secrets.h on first boot)
char     mqttHost[96] = "";
char     mqttUser[64] = "";
char     mqttPass[96] = "";
uint16_t mqttPort     = 8883;

WiFiManager      wifiManager;
WiFiClientSecure tlsClient;
PubSubClient     mqtt(tlsClient);

WiFiManagerParameter paramHost("mqtt_host", "Broker host", DEFAULT_MQTT_HOST, 95);
WiFiManagerParameter paramUser("mqtt_user", "Broker username", "", 63);
WiFiManagerParameter paramPass("mqtt_pass", "Broker password", "", 95, "type='password'");

bool lightOn                = false;
bool statusDirty            = true;    // publish at the next opportunity
bool wifiResetRequested     = false;
bool firmwareUpdateRequested = false;
String updateMessage        = "";

volatile bool ntpTimeReceived = false;   // set from the SNTP callback
unsigned long lastNtpSyncMs   = 0;       // 0 = never

// Light on-time today
long          onTimeDay   = 0;    // yyyymmdd the counter belongs to (0 = unknown)
unsigned long onMsToday   = 0;
unsigned long lastTickMs  = 0;
unsigned long lastOnSave  = 0;
bool          onTimeDirty = false;

// Crash guard (stored in RTC user memory: survives restarts, not power loss)
struct CrashGuard
{
    uint32_t magic;
    uint32_t crashes;
};

bool     safeMode      = false;
uint32_t crashCount    = 0;
bool     guardCleared  = false;

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

// Local date and time: the RTC when it's trustworthy, otherwise the
// NTP-backed system clock. Returns false when neither is usable.
bool readLocalDateTime(int& year, int& month, int& day, int& hour, int& minute, int& second)
{
    if(rtcAvailable && rtcTimeValid)
    {
        DateTime now = rtc.now();
        year = now.year(); month = now.month(); day = now.day();
        hour = now.hour(); minute = now.minute(); second = now.second();
        return true;
    }

    if(systemTimeValid())
    {
        time_t t = time(nullptr);
        struct tm local;
        localtime_r(&t, &local);
        year = local.tm_year + 1900; month = local.tm_mon + 1; day = local.tm_mday;
        hour = local.tm_hour; minute = local.tm_min; second = local.tm_sec;
        return true;
    }

    return false;
}

bool readLocalTime(int& hour, int& minute, int& second)
{
    int y, mo, d;
    return readLocalDateTime(y, mo, d, hour, minute, second);
}

const char* timeSource()
{
    if(rtcAvailable && rtcTimeValid) return "rtc";
    if(systemTimeValid()) return "ntp";
    return "none";
}

void syncRtcFromSystemTime()
{
    lastNtpSyncMs = millis();
    statusDirty = true;

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

    Serial.printf("RTC synced from NTP: %04d-%02d-%02d %02d:%02d:%02d\n",
        local.tm_year + 1900, local.tm_mon + 1, local.tm_mday,
        local.tm_hour, local.tm_min, local.tm_sec);
}

void startNtp()
{
    configTime(UTC_OFFSET_SECONDS, 0, "pool.ntp.org", "time.nist.gov", "time.google.com");
}

// Defined further down; declared here for the safe-mode code.
void maintainMqtt();
void runFirmwareUpdate();
void onMqttMessage(char* topic, byte* payload, unsigned int length);

// =========================================================
// CRASH GUARD
// =========================================================

void writeCrashCount(uint32_t count)
{
    CrashGuard guard = { GUARD_MAGIC, count };
    ESP.rtcUserMemoryWrite(GUARD_RTC_OFFSET, (uint32_t*)&guard, sizeof(guard));
}

// Called first thing at boot. Returns true if the board should start in safe mode.
bool checkCrashGuard()
{
    CrashGuard guard;
    ESP.rtcUserMemoryRead(GUARD_RTC_OFFSET, (uint32_t*)&guard, sizeof(guard));
    uint32_t previous = guard.magic == GUARD_MAGIC ? guard.crashes : 0;

    uint32_t reason = ESP.getResetInfoPtr()->reason;
    bool crashed = reason == REASON_WDT_RST ||
                   reason == REASON_EXCEPTION_RST ||
                   reason == REASON_SOFT_WDT_RST;

    crashCount = crashed ? previous + 1 : 0;
    writeCrashCount(crashCount);

    Serial.printf("Reset reason %u, crash restarts in a row: %u\n", reason, crashCount);
    return crashCount >= CRASH_LIMIT;
}

// =========================================================
// PERSISTENCE (LittleFS)
// =========================================================

// File format (unchanged from earlier firmware, so old saves still load):
//   e1,e2,h1on,m1on,h1off,m1off,h2on,m2on,h2off,m2off
void saveSchedule()
{
    if(!fsAvailable) return;

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
    if(!file) return;

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
    if(!fsAvailable) return;

    File file = LittleFS.open(MODE_FILE, "w");
    if(!file) return;

    file.print(modeName(mode));
    file.close();
}

void loadMode()
{
    if(!fsAvailable || !LittleFS.exists(MODE_FILE)) return;

    File file = LittleFS.open(MODE_FILE, "r");
    if(!file) return;

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

void saveOnTime()
{
    onTimeDirty = false;
    lastOnSave = millis();
    if(!fsAvailable) return;

    File file = LittleFS.open(ONTIME_FILE, "w");
    if(!file) return;

    file.printf("%ld,%lu", onTimeDay, onMsToday);
    file.close();
}

void loadOnTime()
{
    if(!fsAvailable || !LittleFS.exists(ONTIME_FILE)) return;

    File file = LittleFS.open(ONTIME_FILE, "r");
    if(!file) return;

    String data = file.readString();
    file.close();

    long day;
    unsigned long ms;
    if(sscanf(data.c_str(), "%ld,%lu", &day, &ms) == 2 && ms <= 86400000UL)
    {
        onTimeDay = day;
        onMsToday = ms;
    }
}

// Broker login: one value per line (host, port, user, password).
void saveMqttConfig()
{
    if(!fsAvailable) return;

    File file = LittleFS.open(MQTT_FILE, "w");
    if(!file) return;

    file.printf("%s\n%u\n%s\n%s\n", mqttHost, mqttPort, mqttUser, mqttPass);
    file.close();
    Serial.println(F("Broker login saved"));
}

void loadMqttConfig()
{
    if(fsAvailable && LittleFS.exists(MQTT_FILE))
    {
        File file = LittleFS.open(MQTT_FILE, "r");
        if(file)
        {
            String host = file.readStringUntil('\n'); host.trim();
            String port = file.readStringUntil('\n'); port.trim();
            String user = file.readStringUntil('\n'); user.trim();
            String pass = file.readStringUntil('\n'); pass.trim();
            file.close();

            if(host.length() && user.length() && pass.length())
            {
                strlcpy(mqttHost, host.c_str(), sizeof(mqttHost));
                strlcpy(mqttUser, user.c_str(), sizeof(mqttUser));
                strlcpy(mqttPass, pass.c_str(), sizeof(mqttPass));
                mqttPort = port.toInt() > 0 ? port.toInt() : 8883;
                return;
            }
        }
    }

    // First boot after a USB flash: take the login from secrets.h, if any.
    if(strlen(MQTT_HOST) > 0 && strlen(MQTT_USER) > 0 && strlen(MQTT_PASSWORD) > 0)
    {
        strlcpy(mqttHost, MQTT_HOST, sizeof(mqttHost));
        strlcpy(mqttUser, MQTT_USER, sizeof(mqttUser));
        strlcpy(mqttPass, MQTT_PASSWORD, sizeof(mqttPass));
        mqttPort = MQTT_PORT;
        saveMqttConfig();
    }
}

bool mqttConfigured()
{
    return mqttHost[0] && mqttUser[0] && mqttPass[0];
}

// Called when someone saves the setup hotspot form.
void onPortalParamsSaved()
{
    const char* host = paramHost.getValue();
    const char* user = paramUser.getValue();
    const char* pass = paramPass.getValue();

    if(strlen(user) == 0 || strlen(pass) == 0)
    {
        return;   // left blank: keep the current login
    }

    strlcpy(mqttHost, strlen(host) ? host : DEFAULT_MQTT_HOST, sizeof(mqttHost));
    strlcpy(mqttUser, user, sizeof(mqttUser));
    strlcpy(mqttPass, pass, sizeof(mqttPass));
    mqttPort = 8883;
    saveMqttConfig();

    mqtt.disconnect();
    mqtt.setServer(mqttHost, mqttPort);
    lastMqttAttempt = 0;
}

// =========================================================
// LIGHT CONTROL
// =========================================================

bool slotActive(const Slot& slot, int nowMinutes)
{
    if(!slot.enabled) return false;

    int on  = slot.onHour * 60 + slot.onMinute;
    int off = slot.offHour * 60 + slot.offMinute;

    if(on == off) return false;   // zero-length window

    if(on < off) return nowMinutes >= on && nowMinutes < off;

    // Window crosses midnight, e.g. 22:00 -> 02:00
    return nowMinutes >= on || nowMinutes < off;
}

void trackOnTime(int year, int month, int day, bool haveDate)
{
    unsigned long nowMs = millis();

    if(lastTickMs != 0 && lightOn)
    {
        onMsToday += nowMs - lastTickMs;
        onTimeDirty = true;
    }
    lastTickMs = nowMs;

    if(haveDate)
    {
        long today = year * 10000L + month * 100L + day;
        if(today != onTimeDay)
        {
            // New day (or first known date): start counting afresh.
            if(onTimeDay != 0) onMsToday = 0;
            onTimeDay = today;
            saveOnTime();
        }
    }

    if(onTimeDirty && nowMs - lastOnSave >= ONTIME_SAVE_MS)
    {
        saveOnTime();
    }
}

void runControl()
{
    int y, mo, d, h, m, s;
    bool haveTime = readLocalDateTime(y, mo, d, h, m, s);

    // Count the time since the last pass at the state it was in.
    trackOnTime(y, mo, d, haveTime);

    bool shouldBeOn = false;

    if(mode == MODE_FORCE_ON)
    {
        shouldBeOn = true;
    }
    else if(mode == MODE_AUTO && haveTime)
    {
        // With no trustworthy clock, stay off rather than guess.
        int nowMinutes = h * 60 + m;
        shouldBeOn = slotActive(slots[0], nowMinutes) || slotActive(slots[1], nowMinutes);
    }

    // Written every pass so a glitched pin state can't persist.
    digitalWrite(RELAY_PIN, shouldBeOn ? HIGH : LOW);

    if(shouldBeOn != lightOn)
    {
        lightOn = shouldBeOn;
        statusDirty = true;
        saveOnTime();
        Serial.printf("Light %s (mode %s)\n", lightOn ? "ON" : "OFF", modeName(mode));
    }
}

// =========================================================
// MQTT
// =========================================================

bool handleSetTime(const char* value)
{
    int y, mo, d, h, mi, s;
    if(sscanf(value, "%d-%d-%dT%d:%d:%d", &y, &mo, &d, &h, &mi, &s) != 6 ||
       y < 2024 || y > 2099 || mo < 1 || mo > 12 || d < 1 || d > 31 || !isValidTime(h, mi) || s < 0 || s > 59)
    {
        Serial.println(F("Invalid SET_TIME, ignored"));
        return false;
    }

    if(rtcAvailable)
    {
        rtc.adjust(DateTime(y, mo, d, h, mi, s));
        rtcTimeValid = true;
    }
    Serial.printf("Clock set to %04d-%02d-%02d %02d:%02d:%02d\n", y, mo, d, h, mi, s);
    return true;
}

void handleControl(const char* message)
{
    if(strcmp(message, "STATUS") == 0)
    {
        statusDirty = true;
        return;
    }

    if(strcmp(message, "RESET_WIFI") == 0)
    {
        wifiResetRequested = true;   // acted on from loop()
        return;
    }

    if(strcmp(message, "UPDATE_FIRMWARE") == 0)
    {
        firmwareUpdateRequested = true;   // acted on from loop()
        return;
    }

    if(strcmp(message, "RESTART") == 0)
    {
        Serial.println(F("Restart requested"));
        if(mqtt.connected())
        {
            mqtt.publish(TOPIC_ONLINE, "0", true);
            mqtt.disconnect();
        }
        saveOnTime();
        writeCrashCount(0);
        delay(300);
        ESP.restart();
    }

    if(strcmp(message, "SYNC_TIME") == 0)
    {
        Serial.println(F("Clock re-sync requested"));
        startNtp();
        statusDirty = true;
        return;
    }

    if(strncmp(message, "SET_TIME:", 9) == 0)
    {
        if(handleSetTime(message + 9))
        {
            statusDirty = true;
            runControl();
        }
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
    statusDirty = true;
    runControl();
}

// =========================================================
// SAFE MODE
// =========================================================

void publishSafeStatus()
{
    String update = updateMessage;
    update.replace("\"", "'");

    char payload[384];
    snprintf(payload, sizeof(payload),
        "{\"online\":true,"
        "\"safeMode\":true,"
        "\"crashes\":%u,"
        "\"mode\":\"%s\","
        "\"light\":\"%s\","
        "\"uptime\":%lu,"
        "\"reset\":\"%s\","
        "\"fw\":\"%s\","
        "\"update\":\"%s\"}",
        crashCount,
        lightOn ? "FORCE_ON" : "FORCE_OFF",
        lightOn ? "ON" : "OFF",
        millis() / 1000UL,
        ESP.getResetReason().c_str(),
        FW_VERSION,
        update.c_str());

    mqtt.publish(TOPIC_STATUS, payload, true);
}

void handleSafeControl(const char* message)
{
    if(strcmp(message, "FORCE_ON") == 0)
    {
        lightOn = true;
    }
    else if(strcmp(message, "FORCE_OFF") == 0 || strcmp(message, "AUTO") == 0)
    {
        lightOn = false;   // no schedule in safe mode
    }
    else if(strcmp(message, "UPDATE_FIRMWARE") == 0)
    {
        firmwareUpdateRequested = true;
    }
    else if(strcmp(message, "RESTART") == 0)
    {
        Serial.println(F("Leaving safe mode"));
        mqtt.publish(TOPIC_ONLINE, "0", true);
        mqtt.disconnect();
        writeCrashCount(0);
        delay(300);
        ESP.restart();
    }
    else if(strcmp(message, "STATUS") != 0)
    {
        return;
    }

    digitalWrite(RELAY_PIN, lightOn ? HIGH : LOW);
    statusDirty = true;
}

void setupSafeMode()
{
    Serial.println(F("SAFE MODE: repeated crashes, starting minimal firmware"));

    fsAvailable = LittleFS.begin();
    loadMqttConfig();

    WiFi.mode(WIFI_STA);
    WiFi.begin();   // saved network

    tlsClient.setInsecure();
    mqtt.setServer(mqttHost, mqttPort);
    mqtt.setCallback(onMqttMessage);
    mqtt.setBufferSize(1024);
}

void loopSafeMode()
{
    unsigned long now = millis();

    if(WiFi.status() != WL_CONNECTED && now - lastWifiAttempt >= WIFI_RETRY_INTERVAL_MS)
    {
        lastWifiAttempt = now;
        WiFi.begin();
    }

    maintainMqtt();

    if(mqtt.connected() && (statusDirty || now - lastStatusPublish >= STATUS_INTERVAL_MS))
    {
        publishSafeStatus();
        lastStatusPublish = now;
        statusDirty = false;
    }

    if(firmwareUpdateRequested)
    {
        runFirmwareUpdate();
    }
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
        if(safeMode) handleSafeControl(message);
        else handleControl(message);
    }
    else if(strcmp(topic, TOPIC_SCHEDULE) == 0 && !safeMode)
    {
        handleSchedule(message);
    }
}

void publishStatus()
{
    char clockText[16] = "--:--:--";
    char dateText[16]  = "";
    int y, mo, d, h, m, s;

    if(readLocalDateTime(y, mo, d, h, m, s))
    {
        snprintf(clockText, sizeof(clockText), "%02d:%02d:%02d", h, m, s);
        snprintf(dateText, sizeof(dateText), "%04d-%02d-%02d", y, mo, d);
    }

    char rtcTemp[12] = "null";
    if(rtcAvailable)
    {
        dtostrf(rtc.getTemperature(), 0, 2, rtcTemp);
    }

    long ntpAgo = lastNtpSyncMs == 0 ? -1 : (long)((millis() - lastNtpSyncMs) / 1000UL);

    // Escape-free: the update message only ever holds library error text.
    String update = updateMessage;
    update.replace("\"", "'");

    char payload[768];

    snprintf(payload, sizeof(payload),
        "{\"online\":true,"
        "\"mode\":\"%s\","
        "\"light\":\"%s\","
        "\"time\":\"%s\","
        "\"date\":\"%s\","
        "\"slot1\":\"%02d:%02d-%02d:%02d\","
        "\"slot2\":\"%02d:%02d-%02d:%02d\","
        "\"slot1Enabled\":%s,"
        "\"slot2Enabled\":%s,"
        "\"onToday\":%lu,"
        "\"timeSource\":\"%s\","
        "\"rtcFound\":%s,"
        "\"rtcLostPower\":%s,"
        "\"rtcTemp\":%s,"
        "\"ntpAgo\":%ld,"
        "\"uptime\":%lu,"
        "\"reset\":\"%s\","
        "\"fw\":\"%s\","
        "\"update\":\"%s\"}",
        modeName(mode),
        lightOn ? "ON" : "OFF",
        clockText,
        dateText,
        slots[0].onHour, slots[0].onMinute, slots[0].offHour, slots[0].offMinute,
        slots[1].onHour, slots[1].onMinute, slots[1].offHour, slots[1].offMinute,
        slots[0].enabled ? "true" : "false",
        slots[1].enabled ? "true" : "false",
        onMsToday / 1000UL,
        timeSource(),
        rtcAvailable ? "true" : "false",
        rtcLostPowerBoot ? "true" : "false",
        rtcTemp,
        ntpAgo,
        millis() / 1000UL,
        ESP.getResetReason().c_str(),
        FW_VERSION,
        update.c_str());

    // Retained, so a freshly opened app sees the state immediately.
    mqtt.publish(TOPIC_STATUS, payload, true);
}

void maintainMqtt()
{
    if(WiFi.status() != WL_CONNECTED || !mqttConfigured())
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
    if(mqtt.connect(clientId.c_str(), mqttUser, mqttPass, TOPIC_ONLINE, 1, true, "0"))
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
// FIRMWARE UPDATE (over the air)
// =========================================================

void runFirmwareUpdate()
{
    firmwareUpdateRequested = false;

    if(WiFi.status() != WL_CONNECTED)
    {
        updateMessage = "No WiFi";
        statusDirty = true;
        return;
    }

    Serial.println(F("Firmware update: downloading"));

    if(mqtt.connected())
    {
        updateMessage = "Updating";
        if(safeMode) publishSafeStatus(); else publishStatus();
        mqtt.loop();
        mqtt.disconnect();
    }

    // Free the broker connection's TLS memory before opening another.
    tlsClient.stop();
    saveOnTime();

    WiFiClientSecure client;
    client.setInsecure();

    ESPhttpUpdate.setFollowRedirects(HTTPC_FORCE_FOLLOW_REDIRECTS);
    ESPhttpUpdate.rebootOnUpdate(true);

    // Restarts into the new firmware on success.
    t_httpUpdate_return result = ESPhttpUpdate.update(client, FIRMWARE_URL, FW_VERSION);

    switch(result)
    {
        case HTTP_UPDATE_FAILED:
            updateMessage = "Failed: " + ESPhttpUpdate.getLastErrorString();
            break;
        case HTTP_UPDATE_NO_UPDATES:
            updateMessage = "Already up to date";
            break;
        default:
            updateMessage = "";
            break;
    }

    Serial.println(updateMessage);
    lastMqttAttempt = 0;   // reconnect straight away
    statusDirty = true;
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

    saveOnTime();
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
    Serial.printf("AquaHome firmware %s\n", FW_VERSION);

    pinMode(RELAY_PIN, OUTPUT);
    digitalWrite(RELAY_PIN, LOW);

    if(checkCrashGuard())
    {
        safeMode = true;
        setupSafeMode();
        return;
    }

    // RTC: optional. Without it we fall back to NTP time.
    Wire.begin(I2C_SDA_PIN, I2C_SCL_PIN);
    rtcAvailable = rtc.begin();

    if(!rtcAvailable)
    {
        Serial.println(F("RTC not found, using NTP time only"));
    }
    else
    {
        rtcLostPowerBoot = rtc.lostPower();
        rtcTimeValid = !rtcLostPowerBoot;

        if(rtcLostPowerBoot)
        {
            Serial.println(F("RTC lost power (coin cell flat or missing?), waiting for NTP"));
        }
    }

    // Storage: optional. Without it settings just don't survive a reboot.
    fsAvailable = LittleFS.begin();

    if(fsAvailable)
    {
        loadSchedule();
        loadMode();
        loadOnTime();
    }
    else
    {
        Serial.println(F("LittleFS mount failed, settings won't persist"));
    }

    loadMqttConfig();

    // Get the light right before touching the network.
    runControl();

    // NTP: the SDK resyncs periodically on its own; each sync updates the RTC.
    settimeofday_cb([](bool fromSntp) {
        if(fromSntp)
        {
            ntpTimeReceived = true;
        }
    });
    startNtp();

    // WiFi: non-blocking, so the schedule keeps running while the setup
    // portal is open, and after it times out with no network at all.
    WiFi.mode(WIFI_STA);
    wifiManager.setConfigPortalBlocking(false);
    wifiManager.addParameter(&paramHost);
    wifiManager.addParameter(&paramUser);
    wifiManager.addParameter(&paramPass);
    wifiManager.setSaveParamsCallback(onPortalParamsSaved);

    if(!mqttConfigured())
    {
        // No broker login yet: open the hotspot so someone can enter it.
        Serial.println(F("No broker login, opening setup portal"));
        wifiManager.setConfigPortalTimeout(SETUP_PORTAL_LONG_S);
        wifiManager.startConfigPortal(SETUP_AP_NAME);
    }
    else
    {
        wifiManager.setConfigPortalTimeout(SETUP_PORTAL_TIMEOUT_S);
        if(wifiManager.autoConnect(SETUP_AP_NAME))
        {
            Serial.println(F("WiFi connected"));
        }
        else
        {
            Serial.println(F("WiFi not connected, setup portal open"));
        }
    }

    // MQTT. TLS is encrypted but the broker certificate isn't verified.
    tlsClient.setInsecure();
    mqtt.setServer(mqttHost, mqttPort);
    mqtt.setCallback(onMqttMessage);
    mqtt.setBufferSize(1024);
}

void loop()
{
    if(safeMode)
    {
        loopSafeMode();
        return;
    }

    // Survived long enough: this firmware is stable, clear the crash count.
    if(!guardCleared && crashCount > 0 && millis() > CRASH_STABLE_MS)
    {
        guardCleared = true;
        crashCount = 0;
        writeCrashCount(0);
    }

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

    if(firmwareUpdateRequested)
    {
        runFirmwareUpdate();
    }

    if(wifiResetRequested)
    {
        resetWifiAndRestart();
    }
}
