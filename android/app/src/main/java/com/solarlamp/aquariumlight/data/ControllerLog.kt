package com.solarlamp.aquariumlight.data

import android.util.Log
import org.json.JSONException
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * The controller keeps a log of what it did (light switched, WiFi lost, restarts,
 * clock set), including while offline, and publishes the entries not yet saved
 * on aquarium/events. Whichever phone sees them first writes them to the shared
 * history and acknowledges, and the controller then drops them.
 */
object ControllerLog {

    private const val TAG = "ControllerLog"
    const val TOPIC = "aquarium/events"

    data class Entry(val seq: Long, val at: Instant, val clockKnown: Boolean, val text: String)

    /** Parses the retained log message. */
    fun parse(json: String): Pair<String, List<Entry>>? = try {
        val o = JSONObject(json)
        val log = o.optString("log")
        val list = o.optJSONArray("events")
        val entries = (0 until (list?.length() ?: 0)).mapNotNull { i ->
            val e = list!!.optJSONObject(i) ?: return@mapNotNull null
            val t = e.optLong("t", 0L)
            Entry(
                seq = e.optLong("seq"),
                // The controller counts local time as if it were UTC (same zone as the phones).
                at = if (t > 0) LocalDateTime.ofEpochSecond(t, 0, ZoneOffset.UTC).atZone(ZoneId.systemDefault()).toInstant()
                     else Instant.now(),
                clockKnown = t > 0,
                text = e.optString("text"),
            )
        }
        log to entries
    } catch (e: JSONException) {
        null
    }

    /**
     * Saves new entries to the shared history, then calls [ack] with the
     * highest sequence number saved, so the controller can drop them.
     */
    fun handle(json: String, ack: (Long) -> Unit) {
        val (log, entries) = parse(json) ?: return
        if (entries.isEmpty() || log.isEmpty()) return
        if (Cloud.me == null) return   // not signed in / approved: leave them for another phone

        Cloud.saveControllerEvents(log, entries)
            .addOnSuccessListener { ack(entries.maxOf { it.seq }) }
            .addOnFailureListener { Log.w(TAG, "Couldn't save controller log; it stays on the device", it) }
    }
}
