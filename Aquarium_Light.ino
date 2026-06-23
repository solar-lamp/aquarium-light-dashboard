#include <ESP8266WiFi.h>
#include <WiFiManager.h>
#include <WiFiClientSecure.h>
#include <PubSubClient.h>
#include <Wire.h>
#include <RTClib.h>
#include <time.h>
#include <LittleFS.h>

#define RELAY_PIN D5
// =========================
// MQTT SETTINGS
// =========================

const char* mqtt_server = "8f4859d367254182889b312a863177b0.s1.eu.hivemq.cloud";

const int mqtt_port = 8883;

const char* mqtt_user = "aquarium_admin";
const char* mqtt_password = "sdey123A";

// =========================
// OBJECTS
// =========================

RTC_DS3231 rtc;

WiFiClientSecure espClient;
PubSubClient client(espClient);

// =========================
// SCHEDULE VARIABLES
// =========================

int slot1OnHour = 8;
int slot1OnMinute = 0;

int slot1OffHour = 14;
int slot1OffMinute = 0;

int slot2OnHour = 16;
int slot2OnMinute = 0;

int slot2OffHour = 22;
int slot2OffMinute = 0;

bool slot1Enabled = true;
bool slot2Enabled = true;
// =========================
// TIMERS
// =========================

unsigned long lastPublish = 0;
unsigned long lastNTPSync = 0;
bool previousLightState = false;
// =========================
// Operating Modes
// =========================
String operatingMode = "AUTO";

// =========================
// MQTT CALLBACK
// =========================

void callback(char* topic, byte* payload, unsigned int length)
{
    String message = "";

    for(unsigned int i = 0; i < length; i++)
    {
        message += (char)payload[i];
    }

    Serial.print("Topic: ");
    Serial.println(topic);

    Serial.print("Message: ");
    Serial.println(message);

    if(String(topic) == "aquarium/control")
    {
        if(message == "AUTO" ||
        message == "FORCE_ON" ||
        message == "FORCE_OFF")
        {
            operatingMode = message;
            Serial.print("Mode Changed To: ");
            Serial.println(operatingMode);
        }
        else
        {
            Serial.println("Invalid Mode Command");
        }
    }  

    if(String(topic) == "aquarium/schedule")
    {
        String values[6];
        int index = 0;
        int startPos = 0;
        while(index < 6)
        {
            int commaPos = message.indexOf(',', startPos);
            if(commaPos == -1)
            {
                values[index++] =
                    message.substring(startPos);

                break;
            }

            values[index++] = message.substring(startPos, commaPos);
            startPos =
                commaPos + 1;
        }

        slot1Enabled = values[0].toInt();
        String slot1On = values[1];
        String slot1Off = values[2];
        slot2Enabled = values[3].toInt();
        String slot2On = values[4];
        String slot2Off = values[5];
        slot1OnHour = slot1On.substring(0,2).toInt();
        slot1OnMinute = slot1On.substring(3,5).toInt();
        slot1OffHour = slot1Off.substring(0,2).toInt();
        slot1OffMinute = slot1Off.substring(3,5).toInt();
        slot2OnHour = slot2On.substring(0,2).toInt();
        slot2OnMinute = slot2On.substring(3,5).toInt();
        slot2OffHour = slot2Off.substring(0,2).toInt();
        slot2OffMinute = slot2Off.substring(3,5).toInt();

        Serial.println();
        Serial.println("Schedule Updated");
        saveSchedule();
        Serial.printf( "Slot1 Enabled = %d\n", slot1Enabled);
        Serial.printf("Slot2 Enabled = %d\n", slot2Enabled);
        Serial.printf( "Slot1: %02d:%02d -> %02d:%02d\n", slot1OnHour, slot1OnMinute, slot1OffHour, slot1OffMinute);
        Serial.printf( "Slot2: %02d:%02d -> %02d:%02d\n", slot2OnHour, slot2OnMinute, slot2OffHour, slot2OffMinute);
    }
}

// =========================
// MQTT RECONNECT
// =========================

void reconnectMQTT()
{
    while(!client.connected())
    {
    Serial.println("Connecting to MQTT...");

        String clientId = "Aquarium-" + String(ESP.getChipId());

        if(client.connect( clientId.c_str(), mqtt_user, mqtt_password))
        {
            Serial.println("MQTT Connected");
            client.subscribe("aquarium/control");
            client.subscribe("aquarium/schedule");
        }
        else
        {
            Serial.print("Failed, rc=");
            Serial.println(client.state());
            delay(5000);
        }
    }

}

// =========================
// RTC SYNC
// =========================

void syncRTCFromInternet()
{
    Serial.println();
    Serial.println("Syncing RTC from NTP...");

    time_t now = time(nullptr);

    int retries = 0;

    while(now < 100000 &&
        retries < 20)
    {
        delay(500);

        Serial.print(".");

        now = time(nullptr);

        retries++;
    }

    if(now > 100000)
    {
        struct tm* timeinfo = localtime(&now);

        rtc.adjust(
            DateTime(
                timeinfo->tm_year + 1900,
                timeinfo->tm_mon + 1,
                timeinfo->tm_mday,
                timeinfo->tm_hour,
                timeinfo->tm_min,
                timeinfo->tm_sec));

        Serial.println();
        Serial.println(
            "RTC Updated Successfully");
    }
    else
    {
        Serial.println();
        Serial.println(
            "NTP Sync Failed");
    }

}
// =========================
// Save Schedule Function
// =========================

void saveSchedule()
{
    File file =
        LittleFS.open( "/schedule.txt", "w");

    if(!file)
    {
        Serial.println( "Save Failed");
        return;
    }

    file.printf(
        "%d,%d,%02d,%02d,%02d,%02d,%02d,%02d,%02d,%02d",

        slot1Enabled,
        slot2Enabled,

        slot1OnHour,
        slot1OnMinute,

        slot1OffHour,
        slot1OffMinute,

        slot2OnHour,
        slot2OnMinute,

        slot2OffHour,
        slot2OffMinute);

    file.close();

    Serial.println(
        "Schedule Saved");
}

// =========================
// Load Schedule Function
// =========================

void loadSchedule()
{
    if(!LittleFS.exists("/schedule.txt"))
    {
        Serial.println("No Saved Schedule");

        return;
    }

    File file = LittleFS.open( "/schedule.txt", "r");

    if(!file)
    {
        Serial.println("Load Failed");

        return;
    }

    String data = file.readString();

    file.close();
    int values[10];
    int index = 0;
    char buffer[100];
    data.toCharArray(buffer,sizeof(buffer));

    char* token = strtok(buffer, ",");

    while(token != NULL &&
          index < 10)
    {
        values[index++] = atoi(token);

        token = strtok(NULL, ",");
    }

    if(index == 10)
    {
        slot1Enabled = values[0];
        slot2Enabled = values[1];

        slot1OnHour = values[2];
        slot1OnMinute = values[3];

        slot1OffHour = values[4];
        slot1OffMinute = values[5];

        slot2OnHour = values[6];
        slot2OnMinute = values[7];

        slot2OffHour = values[8];
        slot2OffMinute = values[9];

        Serial.println( "Schedule Loaded");
        Serial.printf( "Loaded: S1=%d S2=%d\n",slot1Enabled,slot2Enabled);
    }
}

// =========================
// SETUP
// =========================

void setup()
{
    Serial.begin(115200);
    
    pinMode(RELAY_PIN, OUTPUT);
    digitalWrite(RELAY_PIN, LOW);
    
    // RTC

    Wire.begin(D2, D1);

    if(!rtc.begin())
    {
        Serial.println("RTC Not Found");

        while(1);
    }

    //LittleFS

    if(!LittleFS.begin())
    {
        Serial.println("LittleFS Failed");

        while(1);
    }

    Serial.println("LittleFS Ready");

    // Load Schedule
    loadSchedule();

    // WiFiManager

    WiFiManager wm;

    bool result = wm.autoConnect("AquariumLight_Setup");

    if(!result)
    {
        Serial.println("WiFi Failed");

        ESP.restart();
    }

    Serial.println("WiFi Connected");

    // MQTT

    espClient.setInsecure();

    client.setServer(mqtt_server,mqtt_port);

    client.setCallback(callback);

    // NTP

    configTime(19800,0,"pool.ntp.org","time.nist.gov","time.google.com");

    syncRTCFromInternet();

    
}

// =========================
// LOOP
// =========================

void loop()
{


    // MQTT

    if(!client.connected())
    {
        reconnectMQTT();
    }

    client.loop();

    // RTC RESYNC
    // every 10 minutes

    if(millis() - lastNTPSync > 600000)
    {
        lastNTPSync = millis();
        syncRTCFromInternet();
    }

    // CURRENT TIME

    DateTime rtcNow = rtc.now();

    int currentMinutes = rtcNow.hour() * 60 + rtcNow.minute();
    int slot1On = slot1OnHour * 60 + slot1OnMinute;
    int slot1Off = slot1OffHour * 60 + slot1OffMinute;
    int slot2On = slot2OnHour * 60 + slot2OnMinute;
    int slot2Off = slot2OffHour * 60 + slot2OffMinute;
    bool inSlot1 = false;

    if(slot1Enabled)
    {
        if(slot1On < slot1Off)
        {
            inSlot1 = currentMinutes >= slot1On && currentMinutes < slot1Off;
        }
        else
        {
            inSlot1 = currentMinutes >= slot1On || currentMinutes < slot1Off;
        }
    }

    // SLOT 2 ENABLE CHECK

    bool inSlot2 = false;

    if(slot2Enabled)
    {
        if(slot2On < slot2Off)
        {
            inSlot2 = currentMinutes >= slot2On && currentMinutes < slot2Off;
        }
        else
        {
            inSlot2 = currentMinutes >= slot2On || currentMinutes < slot2Off;
        }
    }

    bool lightState = false;
    if(operatingMode == "AUTO")
    {
        lightState =
            inSlot1 || inSlot2;
    }
    else if(operatingMode == "FORCE_ON")
    {
        lightState = true;
    }
    else if(operatingMode == "FORCE_OFF")
    {
        lightState = false;
    }

    // SERIAL MONITOR

    digitalWrite(RELAY_PIN, lightState);
    if(lightState != previousLightState)
    {
        previousLightState = lightState;
        digitalWrite(RELAY_PIN, lightState);
        Serial.printf(
            "%02d:%02d:%02d | %s\n",
            rtcNow.hour(),
            rtcNow.minute(),
            rtcNow.second(),
            lightState ?
            "LIGHT ON" :
            "LIGHT OFF");
    }

    // STATUS PUBLISH

    if(millis() - lastPublish > 10000)
    {
        lastPublish = millis();

        char timeBuffer[20];

        sprintf( 
            timeBuffer,
            "%02d:%02d:%02d",
            rtcNow.hour(),
            rtcNow.minute(),
            rtcNow.second());

        String currentTime = String(timeBuffer);
        char slot1Buffer[20];
        sprintf(
            slot1Buffer,
            "%02d:%02d-%02d:%02d",
            slot1OnHour,
            slot1OnMinute,
            slot1OffHour,
            slot1OffMinute);
        char slot2Buffer[20];
        sprintf(
            slot2Buffer,
            "%02d:%02d-%02d:%02d",
            slot2OnHour,
            slot2OnMinute,
            slot2OffHour,
            slot2OffMinute);
            
        String slot1 = String(slot1Buffer);
        String slot2 = String(slot2Buffer);

        String payload =
        "{\"online\":true,"
        "\"mode\":\"" + operatingMode + "\","
        "\"light\":\"" +
        String(lightState ? "ON" : "OFF") + "\","
        "\"time\":\"" + currentTime + "\","
        "\"slot1\":\"" + slot1 + "\","
        "\"slot2\":\"" + slot2 + "\"}";
        client.publish(
            "aquarium/status",
            payload.c_str());

        Serial.println(
            "Status Published");
    }

    delay(1000);

}
