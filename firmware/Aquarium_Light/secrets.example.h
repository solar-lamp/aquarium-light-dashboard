// Copy this file to `secrets.h` (same folder) and fill in your broker details.
// `secrets.h` is git-ignored so real credentials never get committed.

#pragma once

const char* const MQTT_HOST     = "your-cluster-id.s1.eu.hivemq.cloud";
const uint16_t    MQTT_PORT     = 8883;   // MQTT over TLS
const char* const MQTT_USER     = "your-device-user";
const char* const MQTT_PASSWORD = "your-device-password";
