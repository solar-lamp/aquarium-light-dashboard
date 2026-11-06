// Host-side simulation of the ESP8266 environment the firmware runs in.
//
// The firmware (Aquarium_Light.ino) is compiled unchanged against these
// stand-ins for the Arduino core and libraries. All state that must survive a
// simulated reboot (flash files, the RTC chip, RTC user memory, the outside
// world) lives in the test executable, in namespace `sim`; the firmware itself
// is built as a shared library that is unloaded and reloaded on every reboot,
// so its globals start fresh exactly like on the real board.
#pragma once

#include <cstdarg>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <ctime>
#include <functional>
#include <map>
#include <string>
#include <vector>

#include "sim_world.h"


// =========================================================
// Arduino core basics
// =========================================================

typedef uint8_t byte;
typedef bool boolean;

#define HIGH 1
#define LOW 0
#define OUTPUT 1
#define INPUT 0

static const uint8_t D1 = 5;
static const uint8_t D2 = 4;
static const uint8_t D5 = 14;

#define F(x) (x)

inline unsigned long millis() { return (unsigned long)(sim::worldMs - (int64_t)sim::bootMs); }
inline void delay(unsigned long ms) { sim::advance(ms); }
inline void yield() {}
inline void pinMode(uint8_t, uint8_t) {}
inline void digitalWrite(uint8_t pin, uint8_t value) {
    if (pin != D5) return;
    int v = value ? 1 : 0;
    if (v != sim::relayState) {
        sim::relayState = v;
        sim::relayLog.push_back({ sim::worldMs, v });
    }
}

inline char* dtostrf(double value, signed char width, unsigned char prec, char* buf) {
    sprintf(buf, "%*.*f", width, prec, value);
    return buf;
}

// Minimal Arduino String
class String {
public:
    String() {}
    String(const char* s) : s_(s ? s : "") {}
    String(const std::string& s) : s_(s) {}
    explicit String(unsigned long v) : s_(std::to_string(v)) {}
    explicit String(unsigned int v) : s_(std::to_string(v)) {}
    explicit String(int v) : s_(std::to_string(v)) {}
    const char* c_str() const { return s_.c_str(); }
    unsigned int length() const { return (unsigned int)s_.size(); }
    long toInt() const { return atol(s_.c_str()); }
    bool startsWith(const char* p) const { return s_.rfind(p, 0) == 0; }
    void trim() {
        size_t a = s_.find_first_not_of(" \t\r\n");
        size_t b = s_.find_last_not_of(" \t\r\n");
        s_ = a == std::string::npos ? "" : s_.substr(a, b - a + 1);
    }
    void replace(const char* from, const char* to) {
        std::string f(from), t(to);
        for (size_t p = 0; (p = s_.find(f, p)) != std::string::npos; p += t.size()) s_.replace(p, f.size(), t);
    }
    String& operator+=(const String& o) { s_ += o.s_; return *this; }
    friend String operator+(const String& a, const String& b) { return String(a.s_ + b.s_); }
    friend String operator+(const char* a, const String& b) { return String(std::string(a) + b.s_); }
    friend String operator+(const String& a, const char* b) { return String(a.s_ + b); }
    bool operator==(const char* o) const { return s_ == o; }
    const std::string& str() const { return s_; }
private:
    std::string s_;
};

struct SerialPort {
    void begin(unsigned long) {}
    void println() { if (sim::verbose) printf("\n"); }
    void println(const char* s) { sim::log("%s", s); }
    void println(const String& s) { sim::log("%s", s.c_str()); }
    void print(const char* s) { if (sim::verbose) printf("%s", s); }
    void printf(const char* fmt, ...) {
        if (!sim::verbose) return;
        char buf[512];
        va_list ap; va_start(ap, fmt); vsnprintf(buf, sizeof(buf), fmt, ap); va_end(ap);
        size_t n = strlen(buf);
        if (n && buf[n - 1] == '\n') buf[n - 1] = 0;
        sim::log("%s", buf);
    }
};
static SerialPort Serial;

struct TwoWire { void begin(int, int) {} };
static TwoWire Wire;

// =========================================================
// ESP object
// =========================================================

enum rst_reason {
    REASON_DEFAULT_RST = 0, REASON_WDT_RST = 1, REASON_EXCEPTION_RST = 2, REASON_SOFT_WDT_RST = 3,
    REASON_SOFT_RESTART = 4, REASON_DEEP_SLEEP_AWAKE = 5, REASON_EXT_SYS_RST = 6,
};
struct rst_info { uint32_t reason; };

struct EspClass {
    rst_info info;
    uint32_t getChipId() { return 0x91a3c5; }
    [[noreturn]] void restart() { throw sim::Reboot{ REASON_SOFT_RESTART }; }
    rst_info* getResetInfoPtr() { info.reason = (uint32_t)sim::resetReason; return &info; }
    String getResetReason() {
        switch (sim::resetReason) {
            case REASON_DEFAULT_RST: return "Power On";
            case REASON_WDT_RST: return "Hardware Watchdog";
            case REASON_EXCEPTION_RST: return "Exception";
            case REASON_SOFT_WDT_RST: return "Software Watchdog";
            case REASON_SOFT_RESTART: return "Software/System restart";
            case REASON_EXT_SYS_RST: return "External System";
        }
        return "Unknown";
    }
    bool rtcUserMemoryRead(uint32_t offset, uint32_t* data, size_t size) {
        memcpy(data, &sim::rtcMem[offset], size);
        return true;
    }
    bool rtcUserMemoryWrite(uint32_t offset, uint32_t* data, size_t size) {
        memcpy(&sim::rtcMem[offset], data, size);
        return true;
    }
};
static EspClass ESP;

// =========================================================
// Time: time(), configTime(), settimeofday_cb()
// =========================================================

inline time_t sim_time(time_t* out) {
    time_t t = sim::sysTimeValid ? (time_t)((sim::worldMs + sim::sysOffsetMs) / 1000)
                                 : (time_t)(millis() / 1000);   // unset clock: seconds since boot
    if (out) *out = t;
    return t;
}
#define time(x) sim_time(x)

inline void configTime(long, int, const char*, const char* = nullptr, const char* = nullptr) {
    sim::ntpRequested = true;
    sim::ntpDueMs = sim::worldMs + 3000;   // first answer a few seconds after asking, if online
}
inline void settimeofday_cb(std::function<void(bool)> cb) { sim::timeSetCallback = cb; }

// =========================================================
// RTClib
// =========================================================

class DateTime {
public:
    DateTime(int y, int mo, int d, int h, int mi, int s) {
        struct tm t = {};
        t.tm_year = y - 1900; t.tm_mon = mo - 1; t.tm_mday = d;
        t.tm_hour = h; t.tm_min = mi; t.tm_sec = s;
        epoch_ = timegm(&t);
    }
    explicit DateTime(time_t epoch) : epoch_(epoch) {}
    int year() const { return tmv().tm_year + 1900; }
    int month() const { return tmv().tm_mon + 1; }
    int day() const { return tmv().tm_mday; }
    int hour() const { return tmv().tm_hour; }
    int minute() const { return tmv().tm_min; }
    int second() const { return tmv().tm_sec; }
    time_t unixtime() const { return epoch_; }
private:
    struct tm tmv() const { struct tm t; gmtime_r(&epoch_, &t); return t; }
    time_t epoch_;
};

class RTC_DS3231 {
public:
    bool begin() { return sim::rtcPresent; }
    bool lostPower() { return sim::rtcOsf; }
    DateTime now() { return DateTime((time_t)((sim::worldMs + sim::rtcOffsetMs) / 1000)); }
    void adjust(const DateTime& dt) {
        sim::rtcOffsetMs = (int64_t)dt.unixtime() * 1000 - sim::worldMs;
        sim::rtcOsf = false;
    }
    float getTemperature() { return 27.25f; }
};

// =========================================================
// LittleFS
// =========================================================

class File {
public:
    File() {}
    File(const std::string& path, bool write) : path_(path), write_(write), open_(true) {
        if (!write) data_ = sim::files[path];
    }
    explicit operator bool() const { return open_; }
    size_t print(const char* s) { data_ += s; return strlen(s); }
    size_t printf(const char* fmt, ...) {
        char buf[512];
        va_list ap; va_start(ap, fmt); int n = vsnprintf(buf, sizeof(buf), fmt, ap); va_end(ap);
        data_ += buf;
        return (size_t)n;
    }
    String readString() { String r(data_.substr(pos_)); pos_ = data_.size(); return r; }
    String readStringUntil(char c) {
        size_t e = data_.find(c, pos_);
        std::string r = data_.substr(pos_, e == std::string::npos ? std::string::npos : e - pos_);
        pos_ = e == std::string::npos ? data_.size() : e + 1;
        return String(r);
    }
    void close() {
        if (open_ && write_) sim::files[path_] = data_;
        open_ = false;
    }
private:
    std::string path_, data_;
    size_t pos_ = 0;
    bool write_ = false, open_ = false;
};

struct LittleFSClass {
    bool begin() { return sim::fsOk; }
    bool exists(const char* p) { return sim::fsOk && sim::files.count(p) > 0; }
    File open(const char* p, const char* mode) {
        if (!sim::fsOk) return File();
        bool write = mode[0] == 'w';
        if (!write && !sim::files.count(p)) return File();
        return File(p, write);
    }
};
static LittleFSClass LittleFS;

// =========================================================
// WiFi and WiFiManager
// =========================================================

#define WIFI_STA 1
#define WL_CONNECTED 3
#define WL_DISCONNECTED 6

struct WiFiClass {
    void mode(int) {}
    void begin() { sim::staStarted = true; }
    int status() { return sim::wifiUp() ? WL_CONNECTED : WL_DISCONNECTED; }
};
static WiFiClass WiFi;

class WiFiManagerParameter {
public:
    WiFiManagerParameter(const char* id, const char* label, const char* def, int len, const char* custom = "")
        : value_(def) { (void)id; (void)label; (void)len; (void)custom; }
    const char* getValue() const { return value_.c_str(); }
    void set(const char* v) { value_ = v; }
private:
    std::string value_;
};

class WiFiManager {
public:
    void setConfigPortalBlocking(bool) {}
    void setConfigPortalTimeout(unsigned long s) { timeoutS_ = s; }
    void addParameter(WiFiManagerParameter* p) { params_.push_back(p); }
    void setSaveParamsCallback(std::function<void()> cb) { saveCb_ = cb; }
    bool autoConnect(const char*) {
        sim::staStarted = true;
        if (sim::wifiSaved) {
            // Tries the saved network first; with no network it gives up after a scan.
            if (sim::routerOn) { sim::advance(2500); return true; }
            sim::advance(6000);
        }
        openPortal();
        return false;
    }
    bool startConfigPortal(const char*) { openPortal(); return false; }
    void process() {
        if (sim::portalActive && sim::worldMs >= sim::portalEndsMs) {
            sim::portalActive = false;
            sim::log("[sim] setup portal timed out");
        }
    }
    bool getConfigPortalActive() { return sim::portalActive; }
    void resetSettings() { sim::wifiSaved = false; }

    // Test hook: someone fills in the hotspot form.
    void submit(const char* host, const char* user, const char* pass) {
        params_.at(0)->set(host); params_.at(1)->set(user); params_.at(2)->set(pass);
        sim::wifiSaved = true;
        sim::portalActive = false;
        if (saveCb_) saveCb_();
    }
private:
    void openPortal() {
        sim::portalActive = true;
        sim::portalEndsMs = sim::worldMs + (int64_t)timeoutS_ * 1000;
        sim::log("[sim] setup portal open for %lus", timeoutS_);
    }
    unsigned long timeoutS_ = 0;
    std::vector<WiFiManagerParameter*> params_;
    std::function<void()> saveCb_;
};

// =========================================================
// TLS client, MQTT (PubSubClient), OTA
// =========================================================

class WiFiClientSecure {
public:
    void setInsecure() {}
    void stop() {}
};

class PubSubClient {
public:
    typedef void (*Callback)(char*, uint8_t*, unsigned int);
    explicit PubSubClient(WiFiClientSecure&) {}
    void setServer(const char*, uint16_t) {}
    void setCallback(Callback cb) { cb_ = cb; }
    void setBufferSize(uint16_t n) { bufferSize_ = n; }
    bool connected() {
        if (!sim::mqttConnected) return false;
        if (sim::mqttReachable()) return true;
        // Dead link: TCP doesn't notice straight away.
        if (sim::worldMs - sim::linkLostAtMs < sim::zombieTcpMs) return true;
        sim::mqttConnected = false;
        return false;
    }
    bool connect(const char*, const char* user, const char* pass, const char*, int, bool, const char* will) {
        if (!sim::mqttReachable() || !sim::brokerOn) {
            sim::advance(sim::failedConnectMs);
            state_ = -2;
            return false;
        }
        sim::advance(800);   // TLS handshake
        if (sim::mqttUser != user || sim::mqttPass != pass) { state_ = 5; return false; }
        will_ = will;
        sim::mqttConnected = true;
        state_ = 0;
        return true;
    }
    bool publish(const char* topic, const char* payload, bool retain = false) {
        if (!connected()) return false;
        if (!sim::mqttReachable()) { sim::advance(sim::blockingWriteMs); return false; }
        if (strlen(payload) + strlen(topic) + 8 > bufferSize_) {
            sim::log("[sim] PUBLISH TOO LARGE (%zu bytes, buffer %u)", strlen(payload), bufferSize_);
            return false;
        }
        if (retain) sim::retained[topic] = payload;
        sim::publishCount++;
        return true;
    }
    bool subscribe(const char*) { return connected(); }
    bool loop() {
        if (!connected()) return false;
        if (!sim::mqttReachable()) { sim::advance(sim::blockingWriteMs); return false; }
        auto pending = sim::inbound;
        sim::inbound.clear();
        for (auto& m : pending) {
            std::vector<uint8_t> buf(m.second.begin(), m.second.end());
            cb_(const_cast<char*>(m.first.c_str()), buf.data(), (unsigned int)buf.size());
        }
        return true;
    }
    void disconnect() { sim::mqttConnected = false; }
    int state() { return state_; }
private:
    Callback cb_ = nullptr;
    uint16_t bufferSize_ = 256;
    int state_ = -1;
    std::string will_;
};

#define HTTPC_FORCE_FOLLOW_REDIRECTS 2
enum t_httpUpdate_return { HTTP_UPDATE_FAILED, HTTP_UPDATE_NO_UPDATES, HTTP_UPDATE_OK };

struct ESP8266HTTPUpdate {
    void setFollowRedirects(int) {}
    void rebootOnUpdate(bool r) { reboot_ = r; }
    t_httpUpdate_return update(WiFiClientSecure&, const char*, const char*) {
        sim::advance(20000);
        if (!sim::wifiUp() || !sim::internetOn) { err_ = "connection failed"; return HTTP_UPDATE_FAILED; }
        if (!sim::otaAvailable) return HTTP_UPDATE_NO_UPDATES;
        if (reboot_) throw sim::Reboot{ REASON_SOFT_RESTART };
        return HTTP_UPDATE_OK;
    }
    String getLastErrorString() { return String(err_); }
    bool reboot_ = true;
    std::string err_;
};
static ESP8266HTTPUpdate ESPhttpUpdate;
