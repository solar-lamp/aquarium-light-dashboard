// Scenario tests for the controller firmware, run on a PC.
//
// The unmodified firmware (firmware/Aquarium_Light/Aquarium_Light.ino) is
// built as a shared library against the stand-ins in mock/ and loaded fresh on
// every simulated boot. This program owns the simulated world: real time, the
// router, the internet, the broker, the RTC chip and its coin cell, the flash
// filesystem and the relay pin. Each scenario drives that world and checks what
// the relay did, second by second.
//
// Build and run: firmware/test/run.sh

#include <dlfcn.h>

#include <algorithm>
#include <cstdarg>
#include <cstdio>
#include <cstring>
#include <ctime>
#include <string>

#include "mock/sim_world.h"

// =========================================================
// World state
// =========================================================

namespace sim {
int64_t  worldMs = 0;
uint64_t bootMs = 0;
bool routerOn = true, internetOn = true, brokerOn = true, wifiSaved = true, staStarted = false;
bool portalActive = false;
int64_t portalEndsMs = 0;
int zombieTcpMs = 0, blockingWriteMs = 0, failedConnectMs = 1000;
int64_t linkLostAtMs = 0;
bool rtcPresent = true, rtcBatteryOk = true, rtcOsf = false;
int64_t rtcOffsetMs = 0;
bool sysTimeValid = false;
int64_t sysOffsetMs = 0;
bool ntpRequested = false;
int64_t ntpDueMs = 0, ntpLastMs = 0;
std::function<void(bool)> timeSetCallback;
int relayState = 0;
std::vector<Switch> relayLog;
std::map<std::string, std::string> files;
bool fsOk = true;
uint32_t rtcMem[128];
int resetReason = 0;
bool mqttConnected = false;
std::string mqttUser = "aquarium_admin", mqttPass = "secret";
std::map<std::string, std::string> retained;
std::vector<std::pair<std::string, std::string>> inbound;
int publishCount = 0;
bool otaAvailable = false;
bool verbose = false;

bool wifiUp() { return routerOn && wifiSaved && staStarted && !portalActive; }
bool mqttReachable() { return wifiUp() && internetOn; }

static std::string clockText(int64_t ms) {
    time_t t = (time_t)(ms / 1000);
    struct tm v; gmtime_r(&t, &v);
    char b[32]; strftime(b, sizeof b, "%m-%d %H:%M:%S", &v);
    return b;
}

void log(const char* fmt, ...) {
    if (!verbose) return;
    char buf[600];
    va_list ap; va_start(ap, fmt); vsnprintf(buf, sizeof buf, fmt, ap); va_end(ap);
    printf("      %s | %s\n", clockText(worldMs).c_str(), buf);
}

void advance(int64_t ms) {
    worldMs += ms;

    // SNTP: answers a few seconds after it's asked, whenever the internet is
    // reachable, and re-syncs hourly.
    if (ntpRequested && timeSetCallback) {
        bool online = mqttReachable();
        bool due = (!sysTimeValid && worldMs >= ntpDueMs) || (sysTimeValid && worldMs - ntpLastMs >= 3600000);
        if (due && online) {
            sysTimeValid = true;
            sysOffsetMs = 0;
            ntpLastMs = worldMs;
            timeSetCallback(true);
        } else if (due && !sysTimeValid) {
            ntpDueMs = worldMs + 15000;
        }
    }
}
}  // namespace sim

using sim::worldMs;

// =========================================================
// Device: loads the firmware library, boots, runs the loop
// =========================================================

static const char* g_libPath = nullptr;

struct Device {
    void* lib = nullptr;
    void (*setupFn)() = nullptr;
    void (*loopFn)() = nullptr;
    int boots = 0;

    void unload() {
        if (!lib) return;
        sim::timeSetCallback = nullptr;
        dlclose(lib);
        if (dlopen(g_libPath, RTLD_NOW | RTLD_NOLOAD)) {
            fprintf(stderr, "firmware library did not unload; globals would leak across reboots\n");
            exit(2);
        }
        lib = nullptr;
    }

    void boot(int reason) {
        for (;;) {
            unload();
            sim::resetReason = reason;
            sim::bootMs = (uint64_t)worldMs;
            sim::mqttConnected = false;
            sim::staStarted = false;
            sim::portalActive = false;
            sim::sysTimeValid = false;
            sim::ntpRequested = false;
            boots++;

            lib = dlopen(g_libPath, RTLD_NOW | RTLD_LOCAL);
            if (!lib) { fprintf(stderr, "dlopen: %s\n", dlerror()); exit(2); }
            setupFn = (void (*)())dlsym(lib, "fw_setup");
            loopFn = (void (*)())dlsym(lib, "fw_loop");
            try {
                setupFn();
                return;
            } catch (const sim::Reboot& r) {
                reason = r.reason;
            }
        }
    }

    // Runs the main loop for `ms` of world time. Each pass of loop() takes `stepMs`.
    void run(int64_t ms, int stepMs = 50) {
        int64_t end = worldMs + ms;
        while (worldMs < end) {
            try {
                loopFn();
            } catch (const sim::Reboot& r) {
                boot(r.reason);
                continue;
            }
            sim::advance(stepMs);
        }
    }

    void powerCycle(int64_t offMs) {
        unload();
        sim::mqttConnected = false;
        memset(sim::rtcMem, 0, sizeof sim::rtcMem);   // RTC user memory needs power
        if (!sim::rtcBatteryOk) {
            // No coin cell: the RTC stops and restarts from 2000-01-01 with OSF set.
            sim::rtcOsf = true;
            sim::rtcOffsetMs = 946684800000LL - (worldMs + offMs);
        }
        worldMs += offMs;
        boot(REASON_DEFAULT_RST_VALUE);
    }

    void crash(int reason) { unload(); boot(reason); }

    void* symbol(const char* name) { return dlsym(lib, name); }

    static constexpr int REASON_DEFAULT_RST_VALUE = 0;
};

static Device dev;

static constexpr int REASON_EXCEPTION_RST_VALUE = 2;
static constexpr int REASON_SOFT_WDT_RST_VALUE = 3;

// =========================================================
// Helpers
// =========================================================

static const char* TOPIC_CONTROL = "aquarium/control";
static const char* TOPIC_SCHEDULE = "aquarium/schedule";

// Local time on the test day (Fri 6 Nov 2026), as ms.
static int64_t at(int h, int m, int s = 0, int dayOffset = 0) {
    struct tm t = {};
    t.tm_year = 2026 - 1900; t.tm_mon = 10; t.tm_mday = 6 + dayOffset;
    t.tm_hour = h; t.tm_min = m; t.tm_sec = s;
    return (int64_t)timegm(&t) * 1000;
}

static void runUntil(int64_t t, int stepMs = 50) { if (t > worldMs) dev.run(t - worldMs, stepMs); }

static int relayAt(int64_t t) {
    int state = 0;
    for (auto& s : sim::relayLog) { if (s.atMs <= t) state = s.state; else break; }
    return state;
}

// When the relay last changed to `state` at or after `from` (or -1).
static int64_t switchedTo(int state, int64_t from) {
    for (auto& s : sim::relayLog) if (s.atMs >= from && s.state == state) return s.atMs;
    return -1;
}

static void setRouter(bool on) {
    if (!on && sim::mqttReachable()) sim::linkLostAtMs = worldMs;
    sim::routerOn = on;
}
static void setInternet(bool on) {
    if (!on && sim::mqttReachable()) sim::linkLostAtMs = worldMs;
    sim::internetOn = on;
}
static void send(const char* topic, const char* msg) { sim::inbound.push_back({ topic, msg }); }

static std::string status() { return sim::retained["aquarium/status"]; }
static std::string field(const std::string& json, const char* key) {
    std::string k = std::string("\"") + key + "\":";
    size_t p = json.find(k);
    if (p == std::string::npos) return "";
    p += k.size();
    if (json[p] == '"') { size_t e = json.find('"', p + 1); return json.substr(p + 1, e - p - 1); }
    size_t e = json.find_first_of(",}", p);
    return json.substr(p, e - p);
}

// Fresh world: board flashed and set up, router and internet on, good RTC
// coin cell, RTC on the right time, default schedule.
static void freshWorld(int64_t startMs) {
    worldMs = startMs;
    sim::routerOn = sim::internetOn = sim::brokerOn = sim::wifiSaved = true;
    sim::zombieTcpMs = 0; sim::blockingWriteMs = 0; sim::failedConnectMs = 1000;
    sim::rtcPresent = true; sim::rtcBatteryOk = true; sim::rtcOsf = false; sim::rtcOffsetMs = 0;
    sim::relayState = 0; sim::relayLog.clear();
    sim::files.clear(); sim::fsOk = true;
    sim::files["/mqtt.txt"] = "broker.test\n8883\naquarium_admin\nsecret\n";
    memset(sim::rtcMem, 0, sizeof sim::rtcMem);
    sim::retained.clear(); sim::inbound.clear(); sim::publishCount = 0;
    sim::otaAvailable = false;
    dev.boots = 0;
    dev.powerCycle(0);
}

// =========================================================
// Assertions
// =========================================================

static int g_failures = 0, g_checks = 0;
static std::string g_scenario;

static void check(bool ok, const char* what, ...) {
    g_checks++;
    char buf[400];
    va_list ap; va_start(ap, what); vsnprintf(buf, sizeof buf, what, ap); va_end(ap);
    printf("    %s %s\n", ok ? "PASS" : "FAIL", buf);
    if (!ok) g_failures++;
}

static void expectRelay(int64_t t, int expected, const char* label) {
    check(relayAt(t) == expected, "%s: light %s at %s", label, expected ? "ON" : "OFF",
          sim::clockText(t).substr(6).c_str());
}

// Light switched to `state` within `toleranceS` seconds after `t`.
static void expectSwitch(int state, int64_t t, int toleranceS, const char* label) {
    int64_t s = switchedTo(state, t);
    bool ok = s >= 0 && s - t <= toleranceS * 1000;
    check(ok, "%s: switched %s %s (expected within %ds of %s)", label, state ? "ON" : "OFF",
          s < 0 ? "never" : (std::to_string((s - t) / 1000) + "s after").c_str(), toleranceS,
          sim::clockText(t).substr(6).c_str());
}

static void scenario(const char* name) {
    g_scenario = name;
    printf("\n== %s\n", name);
}

// =========================================================
// Scenarios
// =========================================================

static void normalOnline() {
    scenario("1. Online, default schedule 08:00-14:00 and 16:00-22:00");
    freshWorld(at(7, 58));
    runUntil(at(22, 5), 200);
    expectRelay(at(7, 59), 0, "before slot 1");
    expectSwitch(1, at(8, 0), 2, "slot 1 start");
    expectRelay(at(13, 59, 50), 1, "end of slot 1");
    expectSwitch(0, at(14, 0), 2, "slot 1 end");
    expectSwitch(1, at(16, 0), 2, "slot 2 start");
    expectSwitch(0, at(22, 0), 2, "slot 2 end");
    check(field(status(), "mode") == "AUTO", "status reports mode AUTO");
    long on = atol(field(status(), "onToday").c_str());
    check(on >= 12 * 3600 - 10 && on <= 12 * 3600 + 10, "on-time today = %lds (expected 12h = 43200s)", on);
}

static void routerOffDuringSlot(int zombieMs, int blockMs, const char* name) {
    scenario(name);
    freshWorld(at(11, 0));
    sim::files["/schedule.txt"] = "0,1,00,00,00,00,19,00,22,30";   // your real schedule
    dev.powerCycle(0);
    sim::zombieTcpMs = zombieMs;
    sim::blockingWriteMs = blockMs;
    runUntil(at(11, 13));
    send(TOPIC_SCHEDULE, "1,11:15,11:17,1,19:00,22:30");
    runUntil(at(11, 13, 30));
    check(field(status(), "slot1") == "11:15-11:17", "schedule saved on device (%s)", field(status(), "slot1").c_str());
    setRouter(false);                      // 11:13:30 router switched off completely
    runUntil(at(11, 20));
    expectRelay(at(11, 14, 59), 0, "before test slot");
    expectSwitch(1, at(11, 15), 3, "test slot start, no WiFi");
    expectSwitch(0, at(11, 17), 3, "test slot end, no WiFi");
    setRouter(true);                       // 11:20 router back
    runUntil(at(11, 22));
    check(sim::mqttConnected, "reconnected to broker after router came back");
    long on = atol(field(status(), "onToday").c_str());
    check(on >= 115 && on <= 125, "on-time today = %lds (expected ~120s)", on);
}

static void bootWithoutRouter() {
    scenario("4. Power cut while router is off; boots with no WiFi (good coin cell)");
    freshWorld(at(11, 0));
    runUntil(at(11, 5));
    send(TOPIC_SCHEDULE, "1,11:15,11:25,1,19:00,22:30");
    runUntil(at(11, 6));
    setRouter(false);
    dev.powerCycle(60000);                 // 11:06 -> 11:07, boots with no network
    runUntil(at(11, 30));
    expectSwitch(1, at(11, 15), 3, "slot start after offline boot");
    expectSwitch(0, at(11, 25), 3, "slot end after offline boot");
    check(sim::portalActive == false, "setup hotspot closed again after its timeout");
    setRouter(true);
    runUntil(at(11, 33));
    check(sim::mqttConnected, "back online within 3 minutes of the router returning");
}

static void flatCellNoInternet() {
    scenario("5. Flat coin cell + power cut + no internet: no trustworthy time");
    freshWorld(at(11, 0));
    sim::rtcBatteryOk = false;
    send(TOPIC_SCHEDULE, "1,11:15,11:25,0,19:00,22:30");
    runUntil(at(11, 2));
    setRouter(false);
    dev.powerCycle(60000);
    runUntil(at(11, 20));
    expectRelay(at(11, 18), 0, "stays off without a valid clock (by design)");
    setRouter(true);
    runUntil(at(11, 27));
    expectRelay(at(11, 23, 59), 1, "turns on once the internet gives it the time");
    expectSwitch(0, at(11, 25), 3, "and follows the schedule after that");
    check(field(status(), "rtcLostPower") == "true", "reports the coin cell problem");
}

static void midnight() {
    scenario("6. Slot across midnight 22:00-02:00, offline the whole night");
    freshWorld(at(21, 50));
    send(TOPIC_SCHEDULE, "1,22:00,02:00,0,08:00,09:00");
    runUntil(at(21, 51));
    setRouter(false);
    runUntil(at(2, 10, 0, 1), 200);
    expectSwitch(1, at(22, 0), 3, "on at 22:00");
    expectRelay(at(23, 59, 59), 1, "on before midnight");
    expectRelay(at(0, 0, 30, 1), 1, "still on after midnight");
    expectSwitch(0, at(2, 0, 0, 1), 3, "off at 02:00");
    setRouter(true);
    runUntil(at(2, 12, 0, 1));
    long on = atol(field(status(), "onToday").c_str());
    check(on >= 7190 && on <= 7210, "on-time counter restarted at midnight: %lds today (expected 2h)", on);
}

static void forcedModes() {
    scenario("7. Force on / Force off survive a power cut; Auto returns to the schedule");
    freshWorld(at(12, 0));
    runUntil(at(14, 30));
    send(TOPIC_CONTROL, "FORCE_ON");
    runUntil(at(14, 31));
    check(relayAt(worldMs) == 1, "Force on outside the schedule turns the light on");
    setRouter(false);
    dev.powerCycle(30000);
    runUntil(at(14, 40));
    check(relayAt(worldMs) == 1, "still forced on after a power cut with no WiFi");
    setRouter(true);
    runUntil(at(14, 43));
    send(TOPIC_CONTROL, "AUTO");
    runUntil(at(14, 44));
    check(relayAt(worldMs) == 0, "Auto at 14:44 follows the schedule (off)");
    send(TOPIC_CONTROL, "FORCE_OFF");
    runUntil(at(16, 5));
    check(relayAt(worldMs) == 0, "Force off keeps it off through slot 2 (16:05)");
}

static void schedulePersistence() {
    scenario("8. Schedule survives a power cut; invalid schedule messages are ignored");
    freshWorld(at(9, 0));
    send(TOPIC_SCHEDULE, "1,09:30,10:00,0,00:00,00:00");
    runUntil(at(9, 1));
    send(TOPIC_SCHEDULE, "1,25:00,10:00,1,10:00,11:00");     // invalid hour
    send(TOPIC_SCHEDULE, "garbage");
    runUntil(at(9, 2));
    check(field(status(), "slot1") == "09:30-10:00", "invalid messages ignored (slot1 %s)", field(status(), "slot1").c_str());
    setRouter(false);
    dev.powerCycle(120000);
    runUntil(at(10, 5));
    expectSwitch(1, at(9, 30), 3, "saved schedule used after reboot");
    expectSwitch(0, at(10, 0), 3, "saved schedule end");
}

static void internetDownWifiUp() {
    scenario("9. Router on but internet down (ISP outage): broker attempts block 5s each");
    freshWorld(at(11, 0));
    sim::failedConnectMs = 5000;
    sim::zombieTcpMs = 60000;
    sim::blockingWriteMs = 5000;
    send(TOPIC_SCHEDULE, "1,11:15,11:17,1,19:00,22:30");
    runUntil(at(11, 5));
    setInternet(false);
    runUntil(at(11, 20));
    expectSwitch(1, at(11, 15), 8, "slot start despite blocking reconnects");
    expectSwitch(0, at(11, 17), 8, "slot end despite blocking reconnects");
}

static void brokerDown() {
    scenario("10. WiFi and internet fine, broker refusing connections");
    freshWorld(at(11, 0));
    send(TOPIC_SCHEDULE, "1,11:15,11:17,1,19:00,22:30");
    runUntil(at(11, 5));
    sim::brokerOn = false;
    sim::mqttConnected = false;
    runUntil(at(11, 20));
    expectSwitch(1, at(11, 15), 3, "slot start with broker down");
    expectSwitch(0, at(11, 17), 3, "slot end with broker down");
}

static void clockCommands() {
    scenario("11. Wrong RTC time fixed by Set from phone / Sync from internet");
    freshWorld(at(11, 0));
    sim::rtcOffsetMs = -3LL * 3600 * 1000;      // RTC 3h behind
    sim::internetOn = false;                    // so NTP can't fix it first
    dev.powerCycle(1000);
    runUntil(at(11, 2));
    send(TOPIC_SCHEDULE, "1,11:10,11:20,0,00:00,00:00");
    sim::internetOn = true;
    sim::ntpRequested = false;                  // pretend NTP hasn't answered yet
    runUntil(at(11, 3));
    std::string t = field(status(), "time");
    check(t.rfind("08:", 0) == 0, "device shows the wrong time first (%s)", t.c_str());
    send(TOPIC_CONTROL, "SET_TIME:2026-11-06T11:03:00");   // the phone's clock
    runUntil(at(11, 4));
    t = field(status(), "time");
    check(t.rfind("11:0", 0) == 0, "Set from phone corrected it (%s)", t.c_str());
    runUntil(at(11, 11));
    expectSwitch(1, at(11, 10), 3, "schedule follows the corrected time");
    sim::rtcOffsetMs += 120000;                 // drift 2 minutes
    send(TOPIC_CONTROL, "SYNC_TIME");
    runUntil(at(11, 15));
    long long drift = (long long)(sim::rtcOffsetMs / 1000);
    check(drift > -3 && drift < 3, "Sync from internet removed the drift (%llds left)", drift);
    send(TOPIC_CONTROL, "SET_TIME:nonsense");
    runUntil(at(11, 16));
    check(field(status(), "time").rfind("11:1", 0) == 0, "invalid SET_TIME ignored");
}

static void statusOnRequest() {
    scenario("12. STATUS request is answered immediately (fast app loading)");
    freshWorld(at(11, 0));
    runUntil(at(11, 1));
    int before = sim::publishCount;
    send(TOPIC_CONTROL, "STATUS");
    dev.run(300);
    check(sim::publishCount > before, "status published within 0.3s of the request");
    check(!field(status(), "fw").empty() && field(status(), "timeSource") == "rtc", "status carries firmware version and time source");
}

static void crashGuard() {
    scenario("13. Crash guard: 3 crashes in a row -> safe mode; restart -> normal");
    freshWorld(at(12, 0));
    runUntil(at(12, 1));
    dev.crash(REASON_EXCEPTION_RST_VALUE);
    runUntil(at(12, 1, 10));
    dev.crash(REASON_EXCEPTION_RST_VALUE);
    runUntil(at(12, 1, 20));
    check(field(status(), "safeMode").empty(), "2 crashes: still normal firmware");
    dev.crash(REASON_EXCEPTION_RST_VALUE);
    runUntil(at(12, 2));
    check(field(status(), "safeMode") == "true", "3rd crash in a row: safe mode (crashes=%s)", field(status(), "crashes").c_str());
    send(TOPIC_CONTROL, "FORCE_ON");
    runUntil(at(12, 2, 10));
    check(relayAt(worldMs) == 1, "On works in safe mode");
    send(TOPIC_CONTROL, "RESTART");
    runUntil(at(12, 3));
    check(field(status(), "safeMode").empty() && field(status(), "timeSource") == "rtc", "Restart normally: back to the full firmware");

    // Two crashes, then a minute of stable running clears the count.
    dev.crash(REASON_EXCEPTION_RST_VALUE);
    runUntil(at(12, 3, 10));
    dev.crash(REASON_SOFT_WDT_RST_VALUE);
    runUntil(at(12, 5));
    dev.crash(REASON_EXCEPTION_RST_VALUE);
    runUntil(at(12, 6));
    check(field(status(), "safeMode").empty(), "a crash after 60s of stable running starts the count again");
}

static void noCredentialsFresh() {
    scenario("14. Freshly flashed: no broker login, no WiFi saved -> hotspot; schedule still runs");
    freshWorld(at(7, 50));
    sim::files.erase("/mqtt.txt");
    sim::wifiSaved = false;
    dev.powerCycle(0);
    check(sim::portalActive, "setup hotspot opens");
    runUntil(at(8, 5));
    expectSwitch(1, at(8, 0), 3, "default schedule runs from the RTC while the hotspot is open");
}

static void longOutage() {
    scenario("15. Router off for 2 full days: schedule keeps running every day");
    freshWorld(at(6, 0));
    send(TOPIC_SCHEDULE, "1,08:00,14:00,1,19:00,22:30");
    runUntil(at(6, 1));
    setRouter(false);
    sim::zombieTcpMs = 30000;
    sim::blockingWriteMs = 5000;
    runUntil(at(23, 0, 0, 1), 500);
    for (int d = 0; d <= 1; d++) {
        char label[32];
        snprintf(label, sizeof label, "day %d 08:00", d + 1); expectSwitch(1, at(8, 0, 0, d), 3, label);
        snprintf(label, sizeof label, "day %d 14:00", d + 1); expectSwitch(0, at(14, 0, 0, d), 3, label);
        snprintf(label, sizeof label, "day %d 19:00", d + 1); expectSwitch(1, at(19, 0, 0, d), 3, label);
        snprintf(label, sizeof label, "day %d 22:30", d + 1); expectSwitch(0, at(22, 30, 0, d), 3, label);
    }
}

static void otaUpdate() {
    scenario("16. Over-the-air update keeps schedule, mode and on-time");
    freshWorld(at(15, 0));
    send(TOPIC_SCHEDULE, "1,15:00,16:00,0,00:00,00:00");
    runUntil(at(15, 30));
    sim::otaAvailable = true;
    int bootsBefore = dev.boots;
    send(TOPIC_CONTROL, "UPDATE_FIRMWARE");
    runUntil(at(15, 32));
    check(dev.boots == bootsBefore + 1, "device restarted into the update");
    check(relayAt(worldMs) == 1, "light back on straight after the update (inside the slot)");
    long on = atol(field(status(), "onToday").c_str());
    check(on >= 31 * 60 - 60 && on <= 32 * 60 + 5, "on-time kept across the update: %lds", on);
    check(field(status(), "slot1") == "15:00-16:00", "schedule kept");
}

int main(int argc, char** argv) {
    setenv("TZ", "UTC", 1);
    tzset();
    g_libPath = argc > 1 ? argv[1] : "./firmware.so";
    sim::verbose = getenv("VERBOSE") != nullptr;

    normalOnline();
    routerOffDuringSlot(0, 0, "2. Router switched off completely before a 2-minute slot (your test)");
    routerOffDuringSlot(45000, 5000, "3. Same, but the dead connection lingers 45s and every send blocks 5s");
    bootWithoutRouter();
    flatCellNoInternet();
    midnight();
    forcedModes();
    schedulePersistence();
    internetDownWifiUp();
    brokerDown();
    clockCommands();
    statusOnRequest();
    crashGuard();
    noCredentialsFresh();
    longOutage();
    otaUpdate();

    printf("\n%d checks, %d failed\n", g_checks, g_failures);
    dev.unload();
    return g_failures == 0 ? 0 : 1;
}
