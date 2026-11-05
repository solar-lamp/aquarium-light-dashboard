package com.solarlamp.aquariumlight

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.solarlamp.aquariumlight.data.Cloud
import com.solarlamp.aquariumlight.data.TimeOfDay
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

/**
 * Local alarms for shared schedules. Every phone schedules its own alarms from
 * the shared feeding times; when one fires it checks the shared log first, so
 * nobody is reminded to feed fish that someone else already fed.
 */
object Reminders {

    private const val TAG = "Reminders"
    private const val MAX_FEEDING_SLOTS = 12
    private const val FEED_CODE_BASE = 2000
    private const val CARE_CODE = 3000

    /** How early a feeding still counts for a reminder slot. */
    const val FED_WINDOW_HOURS = 2L

    /** Daily maintenance check time. */
    private val CARE_CHECK_TIME: LocalTime = LocalTime.of(9, 30)

    const val ACTION_FEED = "com.solarlamp.aquariumlight.REMIND_FEED"
    const val ACTION_CARE = "com.solarlamp.aquariumlight.REMIND_CARE"
    const val EXTRA_TIME = "time"

    fun updateFeedingTimes(context: Context, times: List<TimeOfDay>) {
        AppSettings(context).cachedFeedingTimes = times.joinToString(",")
        rescheduleAll(context)
    }

    /** Re-arms everything from the cached times and current preferences. */
    fun rescheduleAll(context: Context) {
        val settings = AppSettings(context)
        val alarms = context.getSystemService(AlarmManager::class.java)

        for (i in 0 until MAX_FEEDING_SLOTS) {
            alarms.cancel(feedIntent(context, i, null))
        }

        if (settings.feedingReminders) {
            settings.cachedFeedingTimes.split(",")
                .mapNotNull { TimeOfDay.parse(it) }
                .take(MAX_FEEDING_SLOTS)
                .forEachIndexed { i, time ->
                    alarms.setAndAllowWhileIdle(
                        AlarmManager.RTC_WAKEUP,
                        nextOccurrence(LocalTime.of(time.hour, time.minute)),
                        feedIntent(context, i, time),
                    )
                }
        }

        alarms.cancel(careIntent(context))
        if (settings.careReminders) {
            alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, nextOccurrence(CARE_CHECK_TIME), careIntent(context))
        }
    }

    private fun nextOccurrence(time: LocalTime): Long {
        val now = LocalDateTime.now()
        var at = now.toLocalDate().atTime(time)
        if (!at.isAfter(now)) at = at.plusDays(1)
        return at.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
    }

    private fun feedIntent(context: Context, slot: Int, time: TimeOfDay?): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            FEED_CODE_BASE + slot,
            Intent(context, ReminderReceiver::class.java).setAction(ACTION_FEED)
                .apply { if (time != null) putExtra(EXTRA_TIME, time.toString()) },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    private fun careIntent(context: Context): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            CARE_CODE,
            Intent(context, ReminderReceiver::class.java).setAction(ACTION_CARE),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    internal suspend fun handleFeeding(context: Context, timeText: String?) {
        val time = TimeOfDay.parse(timeText) ?: return
        val slotStart = LocalDate.now().atTime(time.hour, time.minute)
            .minusHours(FED_WINDOW_HOURS)
            .atZone(ZoneId.systemDefault()).toInstant()

        // If the check can't complete (offline), remind anyway: better safe.
        val alreadyFed = withTimeoutOrNull(8_000) { runCatching { Cloud.fedSince(slotStart) }.getOrDefault(false) } ?: false
        if (!alreadyFed) Alerts(context).feedingReminder(time.toString())
    }

    internal suspend fun handleCare(context: Context) {
        val tasks = withTimeoutOrNull(8_000) { runCatching { Cloud.tasksOnce() }.getOrNull() } ?: return
        val due = tasks.filter { it.lastDoneAt != null && it.isDue }
        if (due.isNotEmpty()) Alerts(context).careDue(due.map { it.name })
    }
}

class ReminderReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        val appContext = context.applicationContext

        CoroutineScope(Dispatchers.IO).launch {
            try {
                when (intent.action) {
                    Reminders.ACTION_FEED -> Reminders.handleFeeding(appContext, intent.getStringExtra(Reminders.EXTRA_TIME))
                    Reminders.ACTION_CARE -> Reminders.handleCare(appContext)
                }
            } catch (e: Exception) {
                Log.e("ReminderReceiver", "Reminder failed", e)
            } finally {
                // Alarms are one-shot; arm the next ones.
                Reminders.rescheduleAll(appContext)
                pending.finish()
            }
        }
    }
}
