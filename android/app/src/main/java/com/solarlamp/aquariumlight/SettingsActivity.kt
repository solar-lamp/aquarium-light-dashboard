package com.solarlamp.aquariumlight

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.text.InputType
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.preference.EditTextPreference
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat

class SettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.settingsContainer, SettingsFragment())
                .commit()
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}

class SettingsFragment : PreferenceFragmentCompat(),
    SharedPreferences.OnSharedPreferenceChangeListener {

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (!granted) {
                toast(R.string.notifications_blocked)
            }
            MonitorService.sync(requireContext())
        }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        AppSettings(requireContext())   // make sure defaults are written first
        setPreferencesFromResource(R.xml.preferences, rootKey)

        findPreference<EditTextPreference>(AppSettings.KEY_PASSWORD)?.apply {
            setOnBindEditTextListener {
                it.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            }
            summaryProvider = Preference.SummaryProvider<EditTextPreference> { pref ->
                getString(if (pref.text.isNullOrEmpty()) R.string.pref_password_unset
                          else R.string.pref_password_set)
            }
        }

        for (key in listOf(AppSettings.KEY_TLS_PORT, AppSettings.KEY_WS_PORT)) {
            findPreference<EditTextPreference>(key)?.setOnBindEditTextListener {
                it.inputType = InputType.TYPE_CLASS_NUMBER
            }
        }

        // Usernames are case-sensitive: stop the keyboard capitalising or "correcting" them.
        findPreference<EditTextPreference>(AppSettings.KEY_USERNAME)?.setOnBindEditTextListener {
            it.inputType = InputType.TYPE_CLASS_TEXT or
                InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD or
                InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        }

        findPreference<EditTextPreference>(AppSettings.KEY_HOST)?.setOnBindEditTextListener {
            it.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        }

        findPreference<Preference>("battery")?.setOnPreferenceClickListener {
            requestBatteryExemption()
            true
        }

        findPreference<Preference>("notification_settings")?.setOnPreferenceClickListener {
            startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, requireContext().packageName))
            true
        }

        findPreference<Preference>("version")?.summary = BuildConfigInfo.versionName(requireContext())

        if (!AppSettings(requireContext()).hasCredentials && savedInstanceState == null) {
            toast(R.string.setup_needed)
        }
    }

    override fun onResume() {
        super.onResume()
        preferenceManager.sharedPreferences?.registerOnSharedPreferenceChangeListener(this)
        updateBatterySummary()
    }

    override fun onPause() {
        preferenceManager.sharedPreferences?.unregisterOnSharedPreferenceChangeListener(this)
        super.onPause()
    }

    override fun onSharedPreferenceChanged(prefs: SharedPreferences?, key: String?) {
        val changed = key ?: return

        when (changed) {
            AppSettings.KEY_ALERTS -> {
                if (AppSettings(requireContext()).alertsEnabled && needsNotificationPermission()) {
                    notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                } else {
                    MonitorService.sync(requireContext())
                }
            }
            in AppSettings.BROKER_KEYS -> MonitorService.sync(requireContext(), reconnect = true)
        }
    }

    private fun needsNotificationPermission(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED

    private fun isBatteryExempt(): Boolean {
        val power = requireContext().getSystemService(PowerManager::class.java)
        return power.isIgnoringBatteryOptimizations(requireContext().packageName)
    }

    private fun updateBatterySummary() {
        findPreference<Preference>("battery")?.summary = getString(
            if (isBatteryExempt()) R.string.pref_battery_ok else R.string.pref_battery_needed)
    }

    @SuppressLint("BatteryLife")   // sideloaded app; this is the point of the setting
    private fun requestBatteryExemption() {
        if (isBatteryExempt()) {
            return
        }

        startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
            Uri.parse("package:${requireContext().packageName}")))
    }

    private fun toast(message: Int) {
        Toast.makeText(requireContext(), message, Toast.LENGTH_LONG).show()
    }
}

private object BuildConfigInfo {
    fun versionName(context: android.content.Context): String =
        try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "?"
        } catch (e: PackageManager.NameNotFoundException) {
            "?"
        }
}
