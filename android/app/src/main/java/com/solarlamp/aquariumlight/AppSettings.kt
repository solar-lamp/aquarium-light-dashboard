package com.solarlamp.aquariumlight

import android.content.Context
import android.content.SharedPreferences

/**
 * Values edited on the Settings screen.
 *
 * Uses the same file and keys as earlier versions of the app, so saved
 * broker details survive the update.
 */
class AppSettings(context: Context) {

    private val prefs: SharedPreferences = context.applicationContext
        .getSharedPreferences(context.packageName + "_preferences", Context.MODE_PRIVATE)

    var name: String
        get() = text(KEY_NAME)
        set(value) = put(KEY_NAME, value.trim())

    var host: String
        get() = text(KEY_HOST).ifEmpty { DEFAULT_HOST }
        set(value) = put(KEY_HOST, value.trim())

    var username: String
        get() = text(KEY_USERNAME)
        set(value) = put(KEY_USERNAME, value.trim())

    var password: String
        get() = prefs.getString(KEY_PASSWORD, "") ?: ""
        set(value) = put(KEY_PASSWORD, value)

    var tlsPort: Int
        get() = text(KEY_TLS_PORT).toIntOrNull()?.takeIf { it in 1..65535 } ?: DEFAULT_TLS_PORT
        set(value) = put(KEY_TLS_PORT, value.toString())

    var alertsEnabled: Boolean
        get() = prefs.getBoolean(KEY_ALERTS, false)
        set(value) = prefs.edit().putBoolean(KEY_ALERTS, value).apply()

    var notifyOnline: Boolean
        get() = prefs.getBoolean(KEY_NOTIFY_ONLINE, true)
        set(value) = prefs.edit().putBoolean(KEY_NOTIFY_ONLINE, value).apply()

    var notifyLight: Boolean
        get() = prefs.getBoolean(KEY_NOTIFY_LIGHT, true)
        set(value) = prefs.edit().putBoolean(KEY_NOTIFY_LIGHT, value).apply()

    var notifyMode: Boolean
        get() = prefs.getBoolean(KEY_NOTIFY_MODE, false)
        set(value) = prefs.edit().putBoolean(KEY_NOTIFY_MODE, value).apply()

    val hasCredentials: Boolean
        get() = host.isNotEmpty() && username.isNotEmpty() && password.isNotEmpty()

    private fun text(key: String): String = prefs.getString(key, "")?.trim() ?: ""

    private fun put(key: String, value: String) = prefs.edit().putString(key, value).apply()

    companion object {
        const val DEFAULT_HOST = "8f4859d367254182889b312a863177b0.s1.eu.hivemq.cloud"
        const val DEFAULT_TLS_PORT = 8883

        private const val KEY_NAME = "user_name"
        private const val KEY_HOST = "broker_host"
        private const val KEY_USERNAME = "broker_username"
        private const val KEY_PASSWORD = "broker_password"
        private const val KEY_TLS_PORT = "broker_tls_port"
        private const val KEY_ALERTS = "alerts_enabled"
        private const val KEY_NOTIFY_ONLINE = "notify_online"
        private const val KEY_NOTIFY_LIGHT = "notify_light"
        private const val KEY_NOTIFY_MODE = "notify_mode"
    }
}
