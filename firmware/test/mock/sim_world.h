// State of the simulated world that outlives a firmware reboot. Shared by the
// test executable (which defines it) and the firmware library (which uses it).
#pragma once

#include <cstdint>
#include <functional>
#include <map>
#include <string>
#include <vector>

// =========================================================
// Simulated world (defined in sim_world.cpp, in the test executable)
// =========================================================

namespace sim {

struct Reboot { int reason; };   // thrown by ESP.restart() and friends

// Time
extern int64_t  worldMs;          // real-world local time, ms since 1970 (as if UTC)
extern uint64_t bootMs;           // worldMs at the last boot

// Network
extern bool routerOn;             // WiFi network present
extern bool internetOn;           // router has internet
extern bool brokerOn;             // MQTT broker accepts connections
extern bool wifiSaved;            // the board has saved WiFi credentials
extern bool staStarted;           // station has been started (begin/autoConnect)
extern bool portalActive;
extern int64_t portalEndsMs;
extern int  zombieTcpMs;          // after the link dies, MQTT still looks connected this long
extern int  blockingWriteMs;      // ... and every write blocks this long (TLS on a dead link)
extern int  failedConnectMs;      // time an MQTT connect attempt blocks when it can't reach the broker
extern int64_t linkLostAtMs;      // worldMs when the MQTT link last became unreachable

// RTC chip (DS3231): runs from main power or the coin cell
extern bool    rtcPresent;
extern bool    rtcBatteryOk;
extern bool    rtcOsf;            // oscillator-stopped flag
extern int64_t rtcOffsetMs;       // rtc time = worldMs + rtcOffsetMs

// System clock (set by NTP)
extern bool    sysTimeValid;
extern int64_t sysOffsetMs;
extern bool    ntpRequested;
extern int64_t ntpDueMs;
extern int64_t ntpLastMs;
extern std::function<void(bool)> timeSetCallback;

// Relay
extern int relayState;
struct Switch { int64_t atMs; int state; };
extern std::vector<Switch> relayLog;

// Flash filesystem
extern std::map<std::string, std::string> files;
extern bool fsOk;

// RTC user memory (survives resets, not power loss)
extern uint32_t rtcMem[128];
extern int resetReason;

// MQTT
extern bool mqttConnected;
extern std::string mqttUser, mqttPass;
extern std::map<std::string, std::string> retained;   // topic -> last retained payload
extern std::vector<std::pair<std::string, std::string>> inbound;
extern int publishCount;

// OTA
extern bool otaAvailable;

extern bool verbose;

void advance(int64_t ms);   // move time forward, run background "SDK" work
bool wifiUp();
bool mqttReachable();
void log(const char* fmt, ...);

}  // namespace sim
