package com.solarlamp.aquariumlight

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
import android.os.Build
import android.net.ConnectivityManager
import android.net.Network
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import com.solarlamp.aquariumlight.data.Access
import com.solarlamp.aquariumlight.data.Cloud
import com.solarlamp.aquariumlight.data.ControllerLog
import com.solarlamp.aquariumlight.data.MemberStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import org.eclipse.paho.client.mqttv3.IMqttActionListener
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken
import org.eclipse.paho.client.mqttv3.IMqttToken
import org.eclipse.paho.client.mqttv3.MqttAsyncClient
import org.eclipse.paho.client.mqttv3.MqttCallbackExtended
import org.eclipse.paho.client.mqttv3.MqttConnectOptions
import org.eclipse.paho.client.mqttv3.MqttException
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

    // Only tell the user about a dropped connection if it stays down a while;
    // a few seconds' blip (phone switching networks) isn't worth a notification.
    private val showReconnecting = Runnable { alerts.updateMonitor(getString(R.string.monitor_reconnecting)) }
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    private lateinit var alerts: Alerts
    private lateinit var settings: AppSettings
    private lateinit var lastSeen: SharedPreferences

    private var client: MqttAsyncClient? = null

    // Shared-data listeners (feeding, join requests) while the service runs.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var cloudJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        alerts = Alerts(this)
        settings = AppSettings(this)
        lastSeen = getSharedPreferences(STATE_PREFS, MODE_PRIVATE)
        watchCloud()
        watchNetwork()
    }

    /** Reconnect as soon as the phone's network comes back. */
    private fun watchNetwork() {
        val cm = getSystemService(ConnectivityManager::class.java) ?: return
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                handler.post {
                    if (client?.isConnected != true && settings.alertsEnabled && settings.hasCredentials) {
                        teardown()
                        connect()
                    }
                }
            }
        }
        runCatching { cm.registerDefaultNetworkCallback(callback) }.onSuccess { networkCallback = callback }
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
        networkCallback?.let { cb -> runCatching { getSystemService(ConnectivityManager::class.java)?.unregisterNetworkCallback(cb) } }
        handler.removeCallbacks(showReconnecting)
        scope.cancel()
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
                    mqtt.subscribe(arrayOf(TOPIC_ONLINE, TOPIC_STATUS, ControllerLog.TOPIC), intArrayOf(1, 1, 1))
                    handler.removeCallbacks(showReconnecting)
                    alerts.updateMonitor(getString(R.string.monitor_connected))
                } catch (e: Exception) {
                    Log.e(TAG, "Subscribe failed", e)
                }
            }

            override fun connectionLost(cause: Throwable?) {
                Log.w(TAG, "Connection lost", cause)
                handler.removeCallbacks(showReconnecting)
                handler.postDelayed(showReconnecting, RECONNECT_NOTICE_DELAY_MS)
            }

            override fun messageArrived(topic: String, message: MqttMessage) {
                // An exception escaping here would make the MQTT library drop the connection.
                try {
                    handleMessage(topic, String(message.payload, Charsets.UTF_8))
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to handle message on $topic", e)
                }
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
            maxReconnectDelay = 15_000
        }

        client = mqtt

        try {
            mqtt.connect(options, null, object : IMqttActionListener {
                override fun onSuccess(token: IMqttToken?) = Unit

                override fun onFailure(token: IMqttToken?, e: Throwable?) {
                    // Automatic reconnect only covers drops after a successful
                    // connect, so retry the first connection ourselves.
                    Log.w(TAG, "Connect failed", e)

                    if (isAuthFailure(e)) {
                        // Retrying fast won't help until the details change in
                        // Settings, which restarts the connection anyway.
                        alerts.updateMonitor(getString(R.string.monitor_auth_failed))
                        handler.postDelayed(retryConnect, AUTH_RETRY_DELAY_MS)
                    } else {
                        alerts.updateMonitor(getString(R.string.monitor_failed, e?.message ?: "unknown error"))
                        handler.postDelayed(retryConnect, RETRY_DELAY_MS)
                    }
                }
            })
        } catch (e: Exception) {
            Log.e(TAG, "Connect threw", e)
            handler.postDelayed(retryConnect, RETRY_DELAY_MS)
        }
    }

    private fun isAuthFailure(e: Throwable?): Boolean {
        val code = (e as? MqttException)?.reasonCode ?: return false
        return code == MqttException.REASON_CODE_FAILED_AUTHENTICATION.toInt() ||
            code == MqttException.REASON_CODE_NOT_AUTHORIZED.toInt()
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
    // Shared data
    // -----------------------------------------------------------------

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun watchCloud() {
        cloudJob?.cancel()
        cloudJob = scope.launch {
            Cloud.access.collectLatest { access ->
                if (access !is Access.Approved) return@collectLatest

                // Someone fed recently: clear this phone's feeding reminder.
                launch {
                    Cloud.feedingsFlow(limit = 1).collect { latest ->
                        val last = latest.firstOrNull() ?: return@collect
                        val recent = last.at.isAfter(java.time.Instant.now().minusSeconds(Reminders.FED_WINDOW_HOURS * 3600))
                        if (recent) alerts.cancelFeedingReminder()
                    }
                }

                // Admin: new join requests.
                if (access.member.isAdmin) {
                    launch {
                        Cloud.membersFlow().collect { list ->
                            val notified = lastSeen.getStringSet(KEY_NOTIFIED_REQUESTS, emptySet())!!.toMutableSet()
                            for (m in list.filter { it.status == MemberStatus.PENDING && it.uid !in notified }) {
                                alerts.memberRequest(m.displayName, m.email)
                                notified += m.uid
                            }
                            lastSeen.edit().putStringSet(KEY_NOTIFIED_REQUESTS, notified).apply()
                        }
                    }
                }
            }
        }
    }

    // -----------------------------------------------------------------
    // Messages
    // -----------------------------------------------------------------

    private fun handleMessage(topic: String, payload: String) {
        when (topic) {
            TOPIC_ONLINE -> {
                val online = payload.trim() == "1"
                val previous = remember(KEY_ONLINE, if (online) "1" else "0")

                if (previous != null) {
                    Cloud.logDeviceEvent(if (online) "online" else "offline",
                        if (online) "Controller came back online" else "Controller went offline")
                    if (settings.notifyOnline) alerts.deviceOnline(online)
                }
            }

            ControllerLog.TOPIC -> ControllerLog.handle(payload) { seq ->
                runCatching { client?.publish(TOPIC_CONTROL, "ACK_EVENTS:$seq".toByteArray(), 1, false) }
            }

            TOPIC_STATUS -> {
                val status = try {
                    JSONObject(payload)
                } catch (e: JSONException) {
                    Log.w(TAG, "Ignoring malformed status: $payload")
                    return
                }

                val safe = status.optBoolean("safeMode", false)
                if (remember(KEY_SAFE, if (safe) "1" else "0") != null && safe) {
                    Cloud.logDeviceEvent("safe-mode", "Controller started in safe mode after repeated crashes")
                    alerts.safeMode(if (status.has("crashes")) status.optInt("crashes") else null)
                }

                val light = status.optString("light").takeIf { it == "ON" || it == "OFF" }
                val mode = status.optString("mode").takeIf { it.isNotEmpty() }

                if (mode != null && remember(KEY_MODE, mode) != null && settings.notifyMode) {
                    alerts.modeChanged(mode)
                }

                // History gets light switches from the controller's own log (exact
                // times, even while offline); here we only notify.
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
        const val TOPIC_CONTROL = "aquarium/control"

        private const val ACTION_RECONNECT = "com.solarlamp.aquariumlight.RECONNECT"
        private const val RETRY_DELAY_MS = 10_000L
        private const val RECONNECT_NOTICE_DELAY_MS = 20_000L
        private const val AUTH_RETRY_DELAY_MS = 5 * 60_000L

        private const val STATE_PREFS = "monitor_state"
        private const val KEY_CLIENT_ID = "client_id"
        private const val KEY_ONLINE = "online"
        private const val KEY_LIGHT = "light"
        private const val KEY_MODE = "mode"
        private const val KEY_NOTIFIED_REQUESTS = "notified_requests"
        private const val KEY_SAFE = "safe_mode"

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
