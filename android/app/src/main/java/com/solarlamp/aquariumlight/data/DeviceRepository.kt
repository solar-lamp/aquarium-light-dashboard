package com.solarlamp.aquariumlight.data

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.solarlamp.aquariumlight.AppSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withTimeoutOrNull
import org.eclipse.paho.client.mqttv3.IMqttActionListener
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken
import org.eclipse.paho.client.mqttv3.IMqttToken
import org.eclipse.paho.client.mqttv3.MqttAsyncClient
import org.eclipse.paho.client.mqttv3.MqttCallbackExtended
import org.eclipse.paho.client.mqttv3.MqttConnectOptions
import org.eclipse.paho.client.mqttv3.MqttException
import org.eclipse.paho.client.mqttv3.MqttMessage
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence
import java.util.UUID

enum class Link { IDLE, CONNECTING, CONNECTED, FAILED }

data class DeviceState(
    val link: Link = Link.IDLE,
    val linkError: String? = null,
    /** From aquarium/online; null until heard. */
    val onlineFlag: Boolean? = null,
    val status: DeviceStatus? = null,
    val statusAt: Long = 0L,
) {
    /** The device reports every 10 s; three missed reports means it's gone. */
    fun deviceOnline(now: Long): Boolean =
        link == Link.CONNECTED && onlineFlag != false && status != null && now - statusAt < STALE_MS

    /** Known to be offline (not merely still connecting or waiting). */
    fun deviceOffline(now: Long): Boolean =
        link == Link.CONNECTED && (onlineFlag == false || (statusAt > 0 && now - statusAt >= STALE_MS))

    /** Showing the last state saved on this phone, not yet refreshed. */
    val isCached: Boolean get() = status != null && statusAt == 0L

    companion object {
        const val STALE_MS = 30_000L
    }
}

/**
 * The app's live connection to the controller while the app is on screen.
 * (Background alerts use their own connection in MonitorService.)
 */
object DeviceRepository {

    private const val TAG = "DeviceRepository"
    private const val TOPIC_STATUS = "aquarium/status"
    private const val TOPIC_ONLINE = "aquarium/online"
    private const val TOPIC_CONTROL = "aquarium/control"
    private const val TOPIC_SCHEDULE = "aquarium/schedule"
    private const val RETRY_MS = 10_000L
    private const val CONFIRM_TIMEOUT_MS = 15_000L
    private const val KEY_LAST_STATUS = "last_status"

    private val _state = MutableStateFlow(DeviceState())
    val state: StateFlow<DeviceState> = _state

    private val handler = Handler(Looper.getMainLooper())
    private val retry = Runnable { connect() }

    private lateinit var appContext: Context
    private var client: MqttAsyncClient? = null
    private var started = false

    fun start(context: Context) {
        appContext = context.applicationContext
        started = true

        // Show the last known state instantly while connecting.
        if (_state.value.status == null) {
            cache().getString(KEY_LAST_STATUS, null)?.let { DeviceStatus.parse(it) }?.let { cached ->
                _state.update { it.copy(status = cached, statusAt = 0L) }
            }
        }
        connect()
    }

    fun stop() {
        started = false
        teardown()
        _state.update { it.copy(link = Link.IDLE) }
    }

    /** Call after broker details change. */
    fun restart() {
        if (!started) return
        teardown()
        connect()
    }

    // -----------------------------------------------------------------
    // Commands. Each returns false if it couldn't be sent at all; the
    // confirm* helpers then wait for the device to report the change.
    // -----------------------------------------------------------------

    fun setMode(mode: Mode): Boolean = publish(TOPIC_CONTROL, mode.wire)

    fun saveSchedule(slots: List<Slot>): Boolean = publish(TOPIC_SCHEDULE, scheduleMessage(slots))

    fun resetWifi(): Boolean = publish(TOPIC_CONTROL, "RESET_WIFI")

    /** Ask the controller to re-sync its clock from the internet. */
    fun syncTime(): Boolean = publish(TOPIC_CONTROL, "SYNC_TIME")

    /** Set the controller's clock to this phone's local time. */
    fun setTimeFromPhone(): Boolean {
        val now = java.time.LocalDateTime.now()
            .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss"))
        return publish(TOPIC_CONTROL, "SET_TIME:$now")
    }

    /** Ask the controller to install the latest firmware release. */
    fun updateFirmware(): Boolean = publish(TOPIC_CONTROL, "UPDATE_FIRMWARE")

    /** Restart the controller (also takes it out of safe mode). */
    fun restartController(): Boolean = publish(TOPIC_CONTROL, "RESTART")

    /** Ask for a status report right now instead of waiting for the next one. */
    fun requestStatus(): Boolean = publish(TOPIC_CONTROL, "STATUS")

    suspend fun confirm(matches: (DeviceStatus) -> Boolean): Boolean =
        withTimeoutOrNull(CONFIRM_TIMEOUT_MS) {
            // Skip whatever is current; wait for a fresh report that matches.
            val since = _state.value.statusAt
            state.first { it.statusAt > since && it.status != null && matches(it.status) }
        } != null

    // -----------------------------------------------------------------
    // Connection
    // -----------------------------------------------------------------

    private fun connect() {
        handler.removeCallbacks(retry)
        if (!started || client?.isConnected == true) return

        val settings = AppSettings(appContext)

        if (!settings.hasCredentials) {
            _state.update { it.copy(link = Link.FAILED, linkError = "Add your broker login in Settings") }
            return
        }

        teardown()
        _state.update { it.copy(link = Link.CONNECTING, linkError = null) }

        val mqtt = try {
            MqttAsyncClient("ssl://${settings.host}:${settings.tlsPort}", clientId(), MemoryPersistence())
        } catch (e: Exception) {
            _state.update { it.copy(link = Link.FAILED, linkError = "Invalid broker address") }
            return
        }

        mqtt.setCallback(object : MqttCallbackExtended {
            override fun connectComplete(reconnect: Boolean, serverURI: String?) {
                try {
                    mqtt.subscribe(arrayOf(TOPIC_STATUS, TOPIC_ONLINE, ControllerLog.TOPIC), intArrayOf(1, 1, 1))
                } catch (e: Exception) {
                    Log.e(TAG, "Subscribe failed", e)
                }
                _state.update { it.copy(link = Link.CONNECTED, linkError = null) }
                // Don't wait up to 10 s for the next scheduled report.
                requestStatus()
            }

            override fun connectionLost(cause: Throwable?) {
                _state.update { it.copy(link = Link.CONNECTING, linkError = "Reconnecting…") }
            }

            override fun messageArrived(topic: String, message: MqttMessage) {
                val text = String(message.payload, Charsets.UTF_8)
                when (topic) {
                    TOPIC_ONLINE -> _state.update { it.copy(onlineFlag = text.trim() == "1") }
                    ControllerLog.TOPIC -> ControllerLog.handle(text) { seq -> publish(TOPIC_CONTROL, "ACK_EVENTS:$seq") }
                    TOPIC_STATUS -> DeviceStatus.parse(text)?.let { status ->
                        _state.update { it.copy(status = status, statusAt = System.currentTimeMillis()) }
                        cache().edit().putString(KEY_LAST_STATUS, text).apply()
                    }
                }
            }

            override fun deliveryComplete(token: IMqttDeliveryToken?) = Unit
        })

        val options = MqttConnectOptions().apply {
            userName = settings.username
            password = settings.password.toCharArray()
            isCleanSession = true
            isAutomaticReconnect = true
            keepAliveInterval = 30
            connectionTimeout = 15
            maxReconnectDelay = 30_000
        }

        client = mqtt

        try {
            mqtt.connect(options, null, object : IMqttActionListener {
                override fun onSuccess(token: IMqttToken?) = Unit

                override fun onFailure(token: IMqttToken?, e: Throwable?) {
                    Log.w(TAG, "Connect failed", e)
                    _state.update { it.copy(link = Link.FAILED, linkError = describe(e)) }
                    handler.postDelayed(retry, RETRY_MS)
                }
            })
        } catch (e: Exception) {
            _state.update { it.copy(link = Link.FAILED, linkError = describe(e)) }
            handler.postDelayed(retry, RETRY_MS)
        }
    }

    private fun teardown() {
        handler.removeCallbacks(retry)
        val mqtt = client ?: return
        client = null

        try {
            mqtt.setCallback(null)
            if (mqtt.isConnected) mqtt.disconnectForcibly(0, 1000)
        } catch (e: Exception) {
            Log.w(TAG, "Disconnect failed", e)
        }
        try {
            mqtt.close()
        } catch (e: Exception) {
            Log.w(TAG, "Close failed", e)
        }
    }

    private fun publish(topic: String, payload: String): Boolean {
        val mqtt = client ?: return false
        if (!mqtt.isConnected) return false
        return try {
            mqtt.publish(topic, payload.toByteArray(Charsets.UTF_8), 0, false)
            true
        } catch (e: Exception) {
            Log.w(TAG, "Publish failed", e)
            false
        }
    }

    private fun describe(e: Throwable?): String {
        val code = (e as? MqttException)?.reasonCode
        return when (code) {
            MqttException.REASON_CODE_FAILED_AUTHENTICATION.toInt(),
            MqttException.REASON_CODE_NOT_AUTHORIZED.toInt() ->
                "Broker rejected the login. Check it in Settings."
            else -> "Can't reach the broker. Retrying…"
        }
    }

    private fun cache() = appContext.getSharedPreferences("device_repo", Context.MODE_PRIVATE)

    private fun clientId(): String {
        val prefs = cache()
        return prefs.getString("client_id", null) ?: ("aquarium-ui-" + UUID.randomUUID().toString().take(8))
            .also { prefs.edit().putString("client_id", it).apply() }
    }
}
