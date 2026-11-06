// Optional: copy this file to `secrets.h` (same folder) to pre-fill the broker
// login for a USB flash. `secrets.h` is git-ignored.
//
// You can also leave the values empty: the device then opens the
// AquariumLight_Setup hotspot, where you enter the WiFi network and the broker
// username and password. The login is kept in the device's flash, so
// over-the-air updates keep it.

#pragma once

const char* const MQTT_HOST     = "8f4859d367254182889b312a863177b0.s1.eu.hivemq.cloud";
const uint16_t    MQTT_PORT     = 8883;   // MQTT over TLS
const char* const MQTT_USER     = "";
const char* const MQTT_PASSWORD = "";
