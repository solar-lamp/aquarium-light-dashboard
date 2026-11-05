package com.solarlamp.aquariumlight.data

import org.json.JSONException
import org.json.JSONObject

enum class Mode(val wire: String, val label: String) {
    AUTO("AUTO", "Auto"),
    FORCE_ON("FORCE_ON", "Forced on"),
    FORCE_OFF("FORCE_OFF", "Forced off");

    companion object {
        fun fromWire(value: String?): Mode? = entries.firstOrNull { it.wire == value }
    }
}

/** A clock time of day, in minutes since midnight. */
@JvmInline
value class TimeOfDay(val minutes: Int) {
    val hour get() = minutes / 60
    val minute get() = minutes % 60

    override fun toString(): String = "%02d:%02d".format(hour, minute)

    companion object {
        fun of(hour: Int, minute: Int) = TimeOfDay(hour * 60 + minute)

        /** Parses "8:00", "08:00" or "08:00:15". */
        fun parse(text: String?): TimeOfDay? {
            val parts = text?.trim()?.split(":") ?: return null
            if (parts.size < 2) return null
            val h = parts[0].toIntOrNull() ?: return null
            val m = parts[1].toIntOrNull() ?: return null
            return if (h in 0..23 && m in 0..59) of(h, m) else null
        }
    }
}

data class Slot(
    val enabled: Boolean,
    val on: TimeOfDay,
    val off: TimeOfDay,
) {
    fun isActiveAt(t: TimeOfDay): Boolean {
        if (!enabled || on == off) return false
        return if (on.minutes < off.minutes) t.minutes in on.minutes until off.minutes
        else t.minutes >= on.minutes || t.minutes < off.minutes   // crosses midnight
    }
}

/** What the controller last reported on aquarium/status. */
data class DeviceStatus(
    val mode: Mode?,
    val lightOn: Boolean,
    val clock: String?,          // "HH:MM:SS" device time
    val slots: List<Slot>,
    /** False for firmware that doesn't report slot enabled flags. */
    val reportsEnabledFlags: Boolean,
) {
    val time: TimeOfDay? get() = TimeOfDay.parse(clock)

    companion object {
        fun parse(json: String): DeviceStatus? = try {
            val o = JSONObject(json)
            val hasFlags = o.has("slot1Enabled") && o.has("slot2Enabled")

            fun slot(n: Int): Slot? {
                val parts = o.optString("slot$n").split("-")
                if (parts.size != 2) return null
                val on = TimeOfDay.parse(parts[0]) ?: return null
                val off = TimeOfDay.parse(parts[1]) ?: return null
                return Slot(if (hasFlags) o.optBoolean("slot${n}Enabled", true) else true, on, off)
            }

            DeviceStatus(
                mode = Mode.fromWire(o.optString("mode")),
                lightOn = o.optString("light") == "ON",
                clock = o.optString("time").takeIf { it.isNotEmpty() },
                slots = listOfNotNull(slot(1), slot(2)),
                reportsEnabledFlags = hasFlags,
            )
        } catch (e: JSONException) {
            null
        }
    }
}

/** The next scheduled switch in Auto mode, if any. */
data class NextChange(val turnsOn: Boolean, val at: TimeOfDay, val inMinutes: Int)

fun DeviceStatus.nextChange(): NextChange? {
    val now = time ?: return null
    val active = slots.filter { it.enabled && it.on != it.off }
    if (active.isEmpty()) return null

    val lightOnAt = { t: TimeOfDay -> active.any { it.isActiveAt(t) } }

    // Walk forward minute by minute (at most a day) to the next state change.
    // Simple, and handles overlapping or midnight-crossing slots correctly.
    val startState = lightOnAt(now)
    for (step in 1..1440) {
        val t = TimeOfDay((now.minutes + step) % 1440)
        val state = lightOnAt(t)
        if (state != startState) return NextChange(state, t, step)
    }
    return null
}

/** Wire format for aquarium/schedule: e1,HH:MM,HH:MM,e2,HH:MM,HH:MM */
fun scheduleMessage(slots: List<Slot>): String =
    slots.joinToString(",") { "${if (it.enabled) 1 else 0},${it.on},${it.off}" }
