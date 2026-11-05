package com.solarlamp.aquariumlight.data

import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentSnapshot
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

// =========================================================
// Members
// =========================================================

enum class MemberStatus { PENDING, APPROVED, DECLINED, REMOVED }

data class Member(
    val uid: String,
    val email: String,
    val name: String,
    val status: MemberStatus,
    val isAdmin: Boolean,
    val requestedAt: Instant?,
    val lastSeen: Instant?,
) {
    val displayName: String get() = name.ifBlank { email.substringBefore("@") }

    companion object {
        fun from(doc: DocumentSnapshot): Member? {
            if (!doc.exists()) return null
            return Member(
                uid = doc.id,
                email = doc.getString("email").orEmpty(),
                name = doc.getString("name").orEmpty(),
                status = runCatching { MemberStatus.valueOf(doc.getString("status").orEmpty().uppercase()) }
                    .getOrDefault(MemberStatus.PENDING),
                isAdmin = doc.getString("role") == "admin",
                requestedAt = doc.getTimestamp("requestedAt")?.toInstant(),
                lastSeen = doc.getTimestamp("lastSeen")?.toInstant(),
            )
        }
    }
}

/** Where the signed-in person stands. */
sealed interface Access {
    data object Loading : Access
    data object SignedOut : Access
    data class Waiting(val email: String) : Access
    data class Declined(val email: String) : Access
    data class Approved(val member: Member) : Access
}

// =========================================================
// Feeding
// =========================================================

data class Feeding(
    val id: String,
    val at: Instant,
    val byName: String,
    val note: String,
)

// =========================================================
// Care: maintenance tasks and water tests
// =========================================================

enum class TaskKind(val label: String) {
    WATER_CHANGE("Water change"),
    GLASS("Glass clean"),
    FULL_CLEAN("Full tank clean"),
    FILTER("Filter"),
    OTHER("Other"),
}

data class CareTask(
    val id: String,
    val name: String,
    val kind: TaskKind,
    val intervalDays: Int,
    val lastDoneAt: Instant?,
    val lastDoneBy: String?,
    val order: Long,
) {
    val dueOn: LocalDate?
        get() = lastDoneAt?.let { it.atZone(ZoneId.systemDefault()).toLocalDate().plusDays(intervalDays.toLong()) }

    /** Days until due; negative when overdue; null if never done. */
    fun daysUntilDue(today: LocalDate = LocalDate.now()): Long? =
        dueOn?.let { ChronoUnit.DAYS.between(today, it) }

    val isDue: Boolean get() = (daysUntilDue() ?: 0L) <= 0L
}

data class WaterTest(
    val id: String,
    val at: Instant,
    val byName: String,
    val ph: Double?,
    val ammonia: Double?,
    val nitrite: Double?,
    val nitrate: Double?,
    val temperature: Double?,
    val note: String,
)

/** The values a water test records, with sensible healthy ranges for a tropical tank. */
enum class WaterParam(val label: String, val unit: String, val key: String, val healthy: ClosedFloatingPointRange<Double>) {
    PH("pH", "", "ph", 6.5..7.8),
    AMMONIA("Ammonia", "ppm", "ammonia", 0.0..0.25),
    NITRITE("Nitrite", "ppm", "nitrite", 0.0..0.25),
    NITRATE("Nitrate", "ppm", "nitrate", 0.0..40.0),
    TEMPERATURE("Temperature", "°C", "temperature", 24.0..28.0);

    fun valueOf(test: WaterTest): Double? = when (this) {
        PH -> test.ph
        AMMONIA -> test.ammonia
        NITRITE -> test.nitrite
        NITRATE -> test.nitrate
        TEMPERATURE -> test.temperature
    }
}

// =========================================================
// Fish & plants
// =========================================================

enum class LifeType(val label: String, val plural: String) {
    FISH("Fish", "Fish"),
    PLANT("Plant", "Plants"),
}

data class Inhabitant(
    val id: String,
    val type: LifeType,
    val name: String,
    val count: Int,
    val addedAt: Instant?,
    val note: String,
)

// =========================================================
// History
// =========================================================

enum class HistoryType(val label: String) {
    LIGHT("Light"),
    FEEDING("Feeding"),
    CARE("Care"),
    WATER("Water"),
    TANK("Fish & plants"),
    MEMBERS("Members"),
    DEVICE("Device"),
}

data class HistoryEntry(
    val id: String,
    val at: Instant,
    val type: HistoryType,
    val text: String,
    val byName: String?,
)

// =========================================================
// Parsing helpers
// =========================================================

internal fun DocumentSnapshot.instant(field: String): Instant? = getTimestamp(field)?.toInstant()

internal fun Instant.toTimestamp(): Timestamp = Timestamp(epochSecond, nano)

internal fun DocumentSnapshot.number(field: String): Double? = (get(field) as? Number)?.toDouble()

internal fun feedingFrom(doc: DocumentSnapshot) = Feeding(
    id = doc.id,
    at = doc.instant("at") ?: Instant.EPOCH,
    byName = doc.getString("byName").orEmpty(),
    note = doc.getString("note").orEmpty(),
)

internal fun taskFrom(doc: DocumentSnapshot) = CareTask(
    id = doc.id,
    name = doc.getString("name").orEmpty(),
    kind = runCatching { TaskKind.valueOf(doc.getString("kind").orEmpty()) }.getOrDefault(TaskKind.OTHER),
    intervalDays = (doc.getLong("intervalDays") ?: 7L).toInt().coerceAtLeast(1),
    lastDoneAt = doc.instant("lastDoneAt"),
    lastDoneBy = doc.getString("lastDoneBy"),
    order = doc.getLong("order") ?: 0L,
)

internal fun waterTestFrom(doc: DocumentSnapshot) = WaterTest(
    id = doc.id,
    at = doc.instant("at") ?: Instant.EPOCH,
    byName = doc.getString("byName").orEmpty(),
    ph = doc.number("ph"),
    ammonia = doc.number("ammonia"),
    nitrite = doc.number("nitrite"),
    nitrate = doc.number("nitrate"),
    temperature = doc.number("temperature"),
    note = doc.getString("note").orEmpty(),
)

internal fun inhabitantFrom(doc: DocumentSnapshot) = Inhabitant(
    id = doc.id,
    type = if (doc.getString("type") == "PLANT") LifeType.PLANT else LifeType.FISH,
    name = doc.getString("name").orEmpty(),
    count = (doc.getLong("count") ?: 1L).toInt(),
    addedAt = doc.instant("addedAt"),
    note = doc.getString("note").orEmpty(),
)

internal fun historyFrom(doc: DocumentSnapshot) = HistoryEntry(
    id = doc.id,
    at = doc.instant("at") ?: Instant.EPOCH,
    type = runCatching { HistoryType.valueOf(doc.getString("type").orEmpty()) }.getOrDefault(HistoryType.DEVICE),
    text = doc.getString("text").orEmpty(),
    byName = doc.getString("byName"),
)
