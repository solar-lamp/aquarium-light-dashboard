package com.solarlamp.aquariumlight.data

import android.app.Activity
import android.content.Context
import android.util.Log
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.firestore.CollectionReference
import com.google.firebase.firestore.DocumentReference
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.SetOptions
import com.solarlamp.aquariumlight.AppSettings
import com.solarlamp.aquariumlight.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Shared family data in Firestore, plus Google sign-in and approval.
 *
 * Layout:
 *   members/{uid}                      who may use the app
 *   config/broker                      MQTT login, shared with approved members
 *   tanks/main/meta/feeding            feeding times
 *   tanks/main/feedings/{id}           feeding log
 *   tanks/main/tasks/{id}              maintenance tasks
 *   tanks/main/waterTests/{id}         water test log
 *   tanks/main/inhabitants/{id}        fish and plants
 *   tanks/main/history/{id}            everything that happened
 */
@OptIn(ExperimentalCoroutinesApi::class)
object Cloud {

    private const val TAG = "Cloud"

    /** The owner. Always approved, always admin; also enforced by the security rules. */
    const val ADMIN_EMAIL = "souradeep241@gmail.com"

    private val auth: FirebaseAuth get() = FirebaseAuth.getInstance()
    private val db: FirebaseFirestore get() = FirebaseFirestore.getInstance()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val tank: DocumentReference get() = db.collection("tanks").document("main")
    private val members: CollectionReference get() = db.collection("members")
    private val feedings get() = tank.collection("feedings")
    private val tasks get() = tank.collection("tasks")
    private val waterTests get() = tank.collection("waterTests")
    private val inhabitants get() = tank.collection("inhabitants")
    private val history get() = tank.collection("history")
    private val feedingMeta get() = tank.collection("meta").document("feeding")
    private val brokerConfig get() = db.collection("config").document("broker")

    // -----------------------------------------------------------------
    // Auth and access
    // -----------------------------------------------------------------

    val user: Flow<FirebaseUser?> = callbackFlow {
        val listener = FirebaseAuth.AuthStateListener { trySend(it.currentUser) }
        auth.addAuthStateListener(listener)
        awaitClose { auth.removeAuthStateListener(listener) }
    }

    val access: StateFlow<Access> = user
        .flatMapLatest { u ->
            if (u == null) flowOf(Access.SignedOut)
            else members.document(u.uid).listen().map { doc ->
                val member = Member.from(doc)
                // Sign-in interrupted before the request was written: write it now.
                if (member == null) scope.launch { runCatching { ensureMemberDoc(u) } }
                when {
                    member == null -> Access.Waiting(u.email.orEmpty())
                    member.status == MemberStatus.APPROVED -> Access.Approved(member)
                    member.status == MemberStatus.PENDING -> Access.Waiting(member.email)
                    else -> Access.Declined(member.email)
                }
            }
        }
        .stateIn(scope, SharingStarted.Eagerly, Access.Loading)

    val me: Member? get() = (access.value as? Access.Approved)?.member
    val isAdmin: Boolean get() = me?.isAdmin == true
    private val myName: String get() = me?.displayName ?: auth.currentUser?.displayName.orEmpty()

    /** Shows Google's account picker and signs in. Returns an error message, or null on success. */
    suspend fun signIn(activity: Activity): String? = try {
        val option = GetSignInWithGoogleOption.Builder(activity.getString(R.string.default_web_client_id)).build()
        val request = GetCredentialRequest.Builder().addCredentialOption(option).build()
        val result = CredentialManager.create(activity).getCredential(activity, request)
        val credential = result.credential

        if (credential is CustomCredential &&
            credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL
        ) {
            val idToken = GoogleIdTokenCredential.createFrom(credential.data).idToken
            val signedIn = auth.signInWithCredential(GoogleAuthProvider.getCredential(idToken, null)).await().user
            if (signedIn != null) ensureMemberDoc(signedIn)
            null
        } else {
            "Unexpected sign-in response"
        }
    } catch (e: androidx.credentials.exceptions.GetCredentialCancellationException) {
        null   // closed the picker
    } catch (e: Exception) {
        Log.e(TAG, "Sign-in failed", e)
        e.message ?: "Sign-in failed"
    }

    suspend fun signOut(context: Context) {
        auth.signOut()
        runCatching { CredentialManager.create(context).clearCredentialState(ClearCredentialStateRequest()) }
    }

    /** First sign-in creates the access request (or the admin's own record). */
    private suspend fun ensureMemberDoc(user: FirebaseUser) {
        val ref = members.document(user.uid)
        if (ref.get().await().exists()) return

        val isOwner = user.email.equals(ADMIN_EMAIL, ignoreCase = true)
        ref.set(
            mapOf(
                "email" to user.email.orEmpty(),
                "name" to (user.displayName ?: "").substringBefore(" "),
                "status" to if (isOwner) "approved" else "pending",
                "role" to if (isOwner) "admin" else "member",
                "requestedAt" to FieldValue.serverTimestamp(),
                "lastSeen" to FieldValue.serverTimestamp(),
            ),
        ).await()
    }

    /** Called when the app opens: refresh "last active" and sync the broker login. */
    fun onAppOpened(context: Context) {
        val member = me ?: return
        scope.launch {
            runCatching {
                members.document(member.uid).update("lastSeen", FieldValue.serverTimestamp()).await()
            }
            runCatching { syncBroker(context, member) }
        }
    }

    /**
     * The admin's phone publishes its broker login; everyone else's picks it up,
     * so family members never have to type it.
     */
    private suspend fun syncBroker(context: Context, member: Member) {
        val settings = AppSettings(context)
        if (member.isAdmin) {
            if (!settings.hasCredentials) return
            brokerConfig.set(
                mapOf(
                    "host" to settings.host,
                    "port" to settings.tlsPort,
                    "username" to settings.username,
                    "password" to settings.password,
                ),
            ).await()
        } else {
            val doc = brokerConfig.get().await()
            val host = doc.getString("host") ?: return
            val username = doc.getString("username") ?: return
            val password = doc.getString("password") ?: return
            val port = (doc.getLong("port") ?: AppSettings.DEFAULT_TLS_PORT.toLong()).toInt()

            if (settings.host != host || settings.username != username ||
                settings.password != password || settings.tlsPort != port
            ) {
                settings.host = host
                settings.username = username
                settings.password = password
                settings.tlsPort = port
                DeviceRepository.restart()
                com.solarlamp.aquariumlight.MonitorService.sync(context, reconnect = true)
            }
        }
    }

    /** Admin: publish a changed broker login straight away. */
    fun publishBroker(context: Context) {
        val member = me ?: return
        if (member.isAdmin) scope.launch { runCatching { syncBroker(context, member) } }
    }

    fun setMyName(name: String) {
        val uid = auth.currentUser?.uid ?: return
        if (name.isBlank()) return
        members.document(uid).update("name", name.trim())
    }

    // -----------------------------------------------------------------
    // Members (admin)
    // -----------------------------------------------------------------

    fun membersFlow(): Flow<List<Member>> =
        members.listen().map { s -> s.documents.mapNotNull { Member.from(it) }.sortedBy { it.requestedAt } }

    fun setMemberStatus(member: Member, status: MemberStatus) {
        members.document(member.uid).update("status", status.name.lowercase())
        val verb = when (status) {
            MemberStatus.APPROVED -> "Approved"
            MemberStatus.DECLINED -> "Declined"
            MemberStatus.REMOVED -> "Removed"
            MemberStatus.PENDING -> "Reset"
        }
        log(HistoryType.MEMBERS, "$verb ${member.displayName} (${member.email})")
    }

    // -----------------------------------------------------------------
    // History
    // -----------------------------------------------------------------

    fun log(type: HistoryType, text: String) {
        history.add(
            mapOf(
                "at" to FieldValue.serverTimestamp(),
                "type" to type.name,
                "text" to text,
                "byName" to myName,
                "byUid" to auth.currentUser?.uid,
            ),
        )
    }

    /**
     * Device events seen by any phone (light switched by the schedule, controller
     * offline). Several phones may see the same event, so the id is derived from
     * the event itself and writing it twice is harmless.
     */
    fun logDeviceEvent(key: String, text: String) {
        if (me == null) return
        val minute = Instant.now().epochSecond / 60
        history.document("device-$key-$minute").set(
            mapOf(
                "at" to FieldValue.serverTimestamp(),
                "type" to HistoryType.DEVICE.name,
                "text" to text,
                "byName" to null,
            ),
        )
    }

    /** Controller log entries, saved once each whatever phone uploads them. */
    fun saveControllerEvents(log: String, entries: List<ControllerLog.Entry>): com.google.android.gms.tasks.Task<Void> {
        val batch = db.batch()
        for (e in entries) {
            val type = if (e.text.startsWith("Light")) HistoryType.LIGHT else HistoryType.DEVICE
            batch.set(
                history.document("device-ev-$log-${e.seq}"),
                mapOf(
                    "at" to e.at.toTimestamp(),
                    "type" to type.name,
                    "text" to if (e.clockKnown) e.text else "${e.text} (time unknown)",
                    "byName" to "Controller",
                ),
            )
        }
        return batch.commit()
    }

    fun historyFlow(limit: Long = 200): Flow<List<HistoryEntry>> =
        history.orderBy("at", Query.Direction.DESCENDING).limit(limit).listen()
            .map { s -> s.documents.map(::historyFrom) }

    fun deleteHistory(entry: HistoryEntry) {
        history.document(entry.id).delete()
    }

    // -----------------------------------------------------------------
    // Feeding
    // -----------------------------------------------------------------

    fun feedingTimesFlow(): Flow<List<TimeOfDay>> = feedingMeta.listen().map { doc ->
        @Suppress("UNCHECKED_CAST")
        ((doc.get("times") as? List<String>) ?: emptyList())
            .mapNotNull { TimeOfDay.parse(it) }
            .sortedBy { it.minutes }
    }

    fun setFeedingTimes(times: List<TimeOfDay>) {
        val sorted = times.distinct().sortedBy { it.minutes }
        feedingMeta.set(mapOf("times" to sorted.map { it.toString() }), SetOptions.merge())
        log(
            HistoryType.FEEDING,
            if (sorted.isEmpty()) "Removed all feeding times"
            else "Set feeding times to ${sorted.joinToString(", ")}",
        )
    }

    fun feedingsFlow(limit: Long = 60): Flow<List<Feeding>> =
        feedings.orderBy("at", Query.Direction.DESCENDING).limit(limit).listen()
            .map { s -> s.documents.map(::feedingFrom) }

    fun logFeeding(note: String) {
        feedings.add(
            mapOf(
                "at" to FieldValue.serverTimestamp(),
                "byName" to myName,
                "byUid" to auth.currentUser?.uid,
                "note" to note.trim(),
            ),
        )
        log(HistoryType.FEEDING, if (note.isBlank()) "Fed the fish" else "Fed the fish: ${note.trim()}")
    }

    fun deleteFeeding(feeding: Feeding) {
        feedings.document(feeding.id).delete()
        log(HistoryType.FEEDING, "Removed a feeding entry")
    }

    /** Used by reminders: has anyone fed since [since]? */
    suspend fun fedSince(since: Instant): Boolean =
        feedings.whereGreaterThanOrEqualTo("at", since.toTimestamp()).limit(1).get().await().isEmpty.not()

    // -----------------------------------------------------------------
    // Care
    // -----------------------------------------------------------------

    fun tasksFlow(): Flow<List<CareTask>> =
        tasks.listen().map { s -> s.documents.map(::taskFrom).sortedWith(compareBy({ it.order }, { it.name })) }

    suspend fun tasksOnce(): List<CareTask> = tasks.get().await().documents.map(::taskFrom)

    /** First run: a sensible starting set the family can edit. */
    fun seedTasksIfEmpty(current: List<CareTask>) {
        if (current.isNotEmpty() || !isAdmin) return
        listOf(
            Triple("Water change", TaskKind.WATER_CHANGE, 7),
            Triple("Glass clean", TaskKind.GLASS, 7),
            Triple("Full tank clean", TaskKind.FULL_CLEAN, 30),
            Triple("Filter 1", TaskKind.FILTER, 14),
        ).forEachIndexed { i, (name, kind, days) ->
            tasks.add(mapOf("name" to name, "kind" to kind.name, "intervalDays" to days, "order" to i))
        }
    }

    fun addTask(name: String, kind: TaskKind, intervalDays: Int, order: Long) {
        tasks.add(mapOf("name" to name.trim(), "kind" to kind.name, "intervalDays" to intervalDays, "order" to order))
        log(HistoryType.CARE, "Added \"${name.trim()}\" (every $intervalDays days)")
    }

    fun updateTask(task: CareTask, name: String, intervalDays: Int) {
        tasks.document(task.id).update(mapOf("name" to name.trim(), "intervalDays" to intervalDays))
        log(HistoryType.CARE, "Changed \"${name.trim()}\" to every $intervalDays days")
    }

    fun deleteTask(task: CareTask) {
        tasks.document(task.id).delete()
        log(HistoryType.CARE, "Removed \"${task.name}\"")
    }

    fun markDone(task: CareTask, on: LocalDate = LocalDate.now()) {
        val at = if (on == LocalDate.now()) Timestamp.now()
        else on.atTime(12, 0).atZone(ZoneId.systemDefault()).toInstant().toTimestamp()
        tasks.document(task.id).update(mapOf("lastDoneAt" to at, "lastDoneBy" to myName))
        log(HistoryType.CARE, if (on == LocalDate.now()) "Did: ${task.name}" else "Did: ${task.name} (on $on)")
    }

    fun waterTestsFlow(limit: Long = 100): Flow<List<WaterTest>> =
        waterTests.orderBy("at", Query.Direction.DESCENDING).limit(limit).listen()
            .map { s -> s.documents.map(::waterTestFrom) }

    fun addWaterTest(values: Map<WaterParam, Double>, note: String) {
        val data = mutableMapOf<String, Any?>(
            "at" to FieldValue.serverTimestamp(),
            "byName" to myName,
            "note" to note.trim(),
        )
        values.forEach { (param, value) -> data[param.key] = value }
        waterTests.add(data)
        val summary = values.entries.joinToString(", ") { (p, v) -> "${p.label} ${formatNumber(v)}${p.unit.let { u -> if (u.isEmpty()) "" else " $u" }}" }
        log(HistoryType.WATER, "Logged a water test: $summary")
    }

    fun deleteWaterTest(test: WaterTest) {
        waterTests.document(test.id).delete()
        log(HistoryType.WATER, "Removed a water test")
    }

    // -----------------------------------------------------------------
    // Fish & plants
    // -----------------------------------------------------------------

    fun inhabitantsFlow(): Flow<List<Inhabitant>> =
        inhabitants.listen().map { s -> s.documents.map(::inhabitantFrom).sortedBy { it.name.lowercase() } }

    fun addInhabitant(type: LifeType, name: String, count: Int, note: String) {
        inhabitants.add(
            mapOf(
                "type" to type.name,
                "name" to name.trim(),
                "count" to count,
                "note" to note.trim(),
                "addedAt" to FieldValue.serverTimestamp(),
            ),
        )
        log(HistoryType.TANK, "Added $count × ${name.trim()}")
    }

    fun changeCount(item: Inhabitant, newCount: Int) {
        if (newCount < 0 || newCount == item.count) return
        inhabitants.document(item.id).update("count", newCount)
        val diff = newCount - item.count
        log(HistoryType.TANK, "${item.name}: ${if (diff > 0) "+$diff" else "$diff"} (now $newCount)")
    }

    fun updateInhabitant(item: Inhabitant, name: String, note: String) {
        inhabitants.document(item.id).update(mapOf("name" to name.trim(), "note" to note.trim()))
    }

    fun deleteInhabitant(item: Inhabitant) {
        inhabitants.document(item.id).delete()
        log(HistoryType.TANK, "Removed ${item.name} from the list")
    }
}

// =========================================================
// Firestore → Flow
// =========================================================

fun DocumentReference.listen(): Flow<com.google.firebase.firestore.DocumentSnapshot> = callbackFlow {
    val registration = addSnapshotListener { snapshot, error ->
        if (error != null) Log.w("Cloud", "Listen failed: $path", error)
        if (snapshot != null) trySend(snapshot)
    }
    awaitClose { registration.remove() }
}

fun Query.listen(): Flow<com.google.firebase.firestore.QuerySnapshot> = callbackFlow {
    val registration = addSnapshotListener { snapshot, error ->
        if (error != null) Log.w("Cloud", "Listen failed", error)
        if (snapshot != null) trySend(snapshot)
    }
    awaitClose { registration.remove() }
}

fun formatNumber(v: Double): String =
    if (v == v.toLong().toDouble()) v.toLong().toString() else "%.2f".format(v).trimEnd('0').trimEnd('.')
