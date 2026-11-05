package com.solarlamp.aquariumlight

import android.content.Context
import android.content.SharedPreferences
import androidx.preference.PreferenceManager

/** Typed access to the values edited on the Settings screen. */
class AppSettings(context: Context) {

    private val appContext = context.applicationContext

    private val prefs: SharedPreferences =
        PreferenceManager.getDefaultSharedPreferences(appContext)

    init {
        // Writes the XML defaults once, so the host and ports have values
        // before the user ever opens Settings.
        PreferenceManager.setDefaultValues(appContext, R.xml.preferences, false)
    }

    val host: String get() = text(KEY_HOST)
    val username: String get() = text(KEY_USERNAME)
    val password: String get() = prefs.getString(KEY_PASSWORD, "") ?: ""
    val tlsPort: Int get() = port(KEY_TLS_PORT, 8883)
    val wsPort: Int get() = port(KEY_WS_PORT, 8884)

    val alertsEnabled: Boolean get() = prefs.getBoolean(KEY_ALERTS, false)
    val notifyOnline: Boolean get() = prefs.getBoolean(KEY_NOTIFY_ONLINE, true)
    val notifyLight: Boolean get() = prefs.getBoolean(KEY_NOTIFY_LIGHT, true)
    val notifyMode: Boolean get() = prefs.getBoolean(KEY_NOTIFY_MODE, false)

    val hasCredentials: Boolean
        get() = host.isNotEmpty() && username.isNotEmpty() && password.isNotEmpty()

    /** Changes whenever anything that affects the broker connection changes. */
    val connectionFingerprint: String
        get() = listOf(host, tlsPort, wsPort, username, password).joinToString("\u0000")

    private fun text(key: String): String = prefs.getString(key, "")?.trim() ?: ""

    private fun port(key: String, fallback: Int): Int =
        text(key).toIntOrNull()?.takeIf { it in 1..65535 } ?: fallback

    companion object {
        const val KEY_HOST = "broker_host"
        const val KEY_USERNAME = "broker_username"
        const val KEY_PASSWORD = "broker_password"
        const val KEY_TLS_PORT = "broker_tls_port"
        const val KEY_WS_PORT = "broker_ws_port"
        const val KEY_ALERTS = "alerts_enabled"
        const val KEY_NOTIFY_ONLINE = "notify_online"
        const val KEY_NOTIFY_LIGHT = "notify_light"
        const val KEY_NOTIFY_MODE = "notify_mode"

        val BROKER_KEYS = setOf(KEY_HOST, KEY_USERNAME, KEY_PASSWORD, KEY_TLS_PORT, KEY_WS_PORT)
    }
}
