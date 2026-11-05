package com.solarlamp.aquariumlight

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import org.eclipse.paho.client.mqttv3.IMqttActionListener
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken
import org.eclipse.paho.client.mqttv3.IMqttToken
import org.eclipse.paho.client.mqttv3.MqttAsyncClient
import org.eclipse.paho.client.mqttv3.MqttCallbackExtended
import org.eclipse.paho.client.mqttv3.MqttConnectOptions
import org.eclipse.paho.client.mqttv3.MqttMessage
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence
import org.json.JSONException
import org.json.JSONObject
import java.util.UUID

/**
 * Keeps an MQTT connection to the broker while background alerts are on,
 * and posts a notification when the device's reported state changes.
 *
 * The device publishes its status and online flag as retained messages, so
 * each (re)connect immediately delivers the current state. Comparing it with
 * the last state seen (persisted across restarts) means changes that
 * happened while the phone was offline are still reported.
 */
class MonitorService : Service() {

    private val handler = Handler(Looper.getMainLooper())
    private val retryConnect = Runnable { connect() }

    private lateinit var alerts: Alerts
    private lateinit var settings: AppSettings
    private lateinit var lastSeen: SharedPreferences

    private var client: MqttAsyncClient? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        alerts = Alerts(this)
        settings = AppSettings(this)
        lastSeen = getSharedPreferences(STATE_PREFS, MODE_PRIVATE)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        enterForeground(getString(R.string.monitor_connecting))

        if (!settings.alertsEnabled || !settings.hasCredentials) {
            stopSelf()
            return START_NOT_STICKY
        }

        if (intent?.action == ACTION_RECONNECT) {
            teardown()
        }

        connect()
        return START_STICKY
    }

    override fun onDestroy() {
        teardown()
        super.onDestroy()
    }

    // -----------------------------------------------------------------
    // Connection
    // -----------------------------------------------------------------

    private fun connect() {
        handler.removeCallbacks(retryConnect)

        if (client?.isConnected == true) {
            return
        }

        teardown()

        val serverUri = "ssl://${settings.host}:${settings.tlsPort}"

        val mqtt = try {
            MqttAsyncClient(serverUri, clientId(), MemoryPersistence())
        } catch (e: Exception) {
            Log.e(TAG, "Bad broker address $serverUri", e)
            alerts.updateMonitor(getString(R.string.monitor_failed, e.message ?: "bad address"))
            return
        }

        mqtt.setCallback(object : MqttCallbackExtended {
            override fun connectComplete(reconnect: Boolean, serverURI: String?) {
                // Clean session: subscriptions have to be renewed every time.
                try {
                    mqtt.subscribe(arrayOf(TOPIC_ONLINE, TOPIC_STATUS), intArrayOf(1, 1))
                    alerts.updateMonitor(getString(R.string.monitor_connected))
                } catch (e: Exception) {
                    Log.e(TAG, "Subscribe failed", e)
                }
            }

            override fun connectionLost(cause: Throwable?) {
                Log.w(TAG, "Connection lost", cause)
                alerts.updateMonitor(getString(R.string.monitor_reconnecting))
            }

            override fun messageArrived(topic: String, message: MqttMessage) {
                handleMessage(topic, String(message.payload, Charsets.UTF_8))
            }

            override fun deliveryComplete(token: IMqttDeliveryToken?) = Unit
        })

        val options = MqttConnectOptions().apply {
            userName = settings.username
            password = settings.password.toCharArray()
            isCleanSession = true
            isAutomaticReconnect = true   // after the first successful connect
            keepAliveInterval = 60
            connectionTimeout = 20
            maxReconnectDelay = 60_000
        }

        client = mqtt

        try {
            mqtt.connect(options, null, object : IMqttActionListener {
                override fun onSuccess(token: IMqttToken?) = Unit

                override fun onFailure(token: IMqttToken?, e: Throwable?) {
                    // Automatic reconnect only covers drops after a successful
                    // connect, so retry the first connection ourselves.
                    Log.w(TAG, "Connect failed", e)
                    alerts.updateMonitor(getString(R.string.monitor_failed, e?.message ?: "unknown error"))
                    handler.postDelayed(retryConnect, RETRY_DELAY_MS)
                }
            })
        } catch (e: Exception) {
            Log.e(TAG, "Connect threw", e)
            handler.postDelayed(retryConnect, RETRY_DELAY_MS)
        }
    }

    private fun teardown() {
        handler.removeCallbacks(retryConnect)

        val mqtt = client ?: return
        client = null

        try {
            mqtt.setCallback(null)
            if (mqtt.isConnected) {
                mqtt.disconnectForcibly(0, 1000)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Disconnect failed", e)
        }

        try {
            mqtt.close()
        } catch (e: Exception) {
            Log.w(TAG, "Close failed", e)
        }
    }

    /** Stable per install, so the broker sees one consistent client. */
    private fun clientId(): String {
        val existing = lastSeen.getString(KEY_CLIENT_ID, null)
        if (existing != null) return existing

        val id = "aquarium-app-" + UUID.randomUUID().toString().take(8)
        lastSeen.edit().putString(KEY_CLIENT_ID, id).apply()
        return id
    }

    // -----------------------------------------------------------------
    // Messages
    // -----------------------------------------------------------------

    private fun handleMessage(topic: String, payload: String) {
        when (topic) {
            TOPIC_ONLINE -> {
                val online = payload.trim() == "1"
                val previous = remember(KEY_ONLINE, if (online) "1" else "0")

                if (previous != null && settings.notifyOnline) {
                    alerts.deviceOnline(online)
                }
            }

            TOPIC_STATUS -> {
                val status = try {
                    JSONObject(payload)
                } catch (e: JSONException) {
                    Log.w(TAG, "Ignoring malformed status: $payload")
                    return
                }

                val light = status.optString("light").takeIf { it == "ON" || it == "OFF" }
                val mode = status.optString("mode").takeIf { it.isNotEmpty() }

                if (mode != null && remember(KEY_MODE, mode) != null && settings.notifyMode) {
                    alerts.modeChanged(mode)
                }

                if (light != null && remember(KEY_LIGHT, light) != null && settings.notifyLight) {
                    alerts.lightChanged(light == "ON", mode)
                }
            }
        }
    }

    /**
     * Stores [value] under [key]. Returns the previous value if it differed,
     * or null if nothing changed (or this is the first value ever seen,
     * which is a baseline rather than a change).
     */
    private fun remember(key: String, value: String): String? {
        val previous = lastSeen.getString(key, null)

        if (previous == value) {
            return null
        }

        lastSeen.edit().putString(key, value).apply()
        return previous
    }

    // -----------------------------------------------------------------
    // Foreground
    // -----------------------------------------------------------------

    private fun enterForeground(text: String) {
        val notification = alerts.monitorNotification(text)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(Alerts.ID_MONITOR, notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(Alerts.ID_MONITOR, notification)
        }
    }

    companion object {
        private const val TAG = "MonitorService"

        const val TOPIC_STATUS = "aquarium/status"
        const val TOPIC_ONLINE = "aquarium/online"

        private const val ACTION_RECONNECT = "com.solarlamp.aquariumlight.RECONNECT"
        private const val RETRY_DELAY_MS = 30_000L

        private const val STATE_PREFS = "monitor_state"
        private const val KEY_CLIENT_ID = "client_id"
        private const val KEY_ONLINE = "online"
        private const val KEY_LIGHT = "light"
        private const val KEY_MODE = "mode"

        /**
         * Starts or stops the service to match the current settings.
         * Pass [reconnect] after broker details change.
         */
        fun sync(context: Context, reconnect: Boolean = false) {
            val settings = AppSettings(context)
            val intent = Intent(context, MonitorService::class.java)

            if (settings.alertsEnabled && settings.hasCredentials) {
                if (reconnect) intent.action = ACTION_RECONNECT
                ContextCompat.startForegroundService(context, intent)
            } else {
                context.stopService(intent)
            }
        }
    }
}
