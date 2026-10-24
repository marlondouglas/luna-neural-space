package space.neural.luna.collaboration

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.MetadataChanges
import com.google.firebase.firestore.Source
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.delay
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await
import java.util.UUID

/** All data is authenticated and room-scoped. Listener failures propagate to the UI. */
class CommunityRepository(private val db: FirebaseFirestore = FirebaseFirestore.getInstance(), private val auth: FirebaseAuth = FirebaseAuth.getInstance()) {
    private fun uid() = checkNotNull(auth.currentUser?.uid) { "Entre na conta para participar" }
    private fun room(id: String) = db.collection("rooms").document(CommunityRooms.slug(id))
    private fun name() = auth.currentUser?.displayName?.take(80).orEmpty().ifBlank { "Motorista" }
    private fun queryFlow(q: Query, requireServer: Boolean = false): Flow<List<DocumentSnapshot>> = callbackFlow {
        val registration = q.addSnapshotListener(MetadataChanges.INCLUDE) { snapshot, error ->
            if (error != null) close(error) else if (snapshot != null) {
                if (requireServer && snapshot.metadata.isFromCache) trySend(emptyList()) else trySend(snapshot.documents)
            }
        }
        awaitClose { registration.remove() }
    }
    private fun coords(lat: Double, lon: Double) { require(lat.isFinite() && lon.isFinite() && lat in -90.0..90.0 && lon in -180.0..180.0) }
    suspend fun publishPresence(room: String, status: DriverStatus, share: Boolean, lat: Double? = null, lon: Double? = null, profile: DriverProfile = DriverProfile(), accuracyMeters: Double? = null, bearingDegrees: Double? = null, locationTimestamp: Long? = null) {
        val show = share && status == DriverStatus.ONLINE && lat != null && lon != null
            && locationTimestamp != null && System.currentTimeMillis() - locationTimestamp in 0..90_000
        if (show) coords(lat!!, lon!!)
        require(accuracyMeters == null || (accuracyMeters.isFinite() && accuracyMeters in 0.0..100_000.0))
        require(bearingDegrees == null || (bearingDegrees.isFinite() && bearingDegrees in 0.0..360.0))
        val gps = buildMap<String, Any> {
            if (show && locationTimestamp != null) put("locationTimestamp", locationTimestamp)
            if (show && accuracyMeters != null) put("accuracyMeters", accuracyMeters)
            if (show && bearingDegrees != null) put("bearingDegrees", bearingDegrees)
        }
        this.room(room).collection("presence").document(uid()).set(mapOf(
            "authorUid" to uid(), "name" to name(), "status" to status.name, "shareLocation" to show,
            "latitude" to if (show) lat else null, "longitude" to if (show) lon else null,
            "updatedAt" to FieldValue.serverTimestamp(),
        ) + profile.publicFields() + gps).await()
    }
    fun observePresence(room: String): Flow<List<DriverPresence>> = queryFlow(this.room(room).collection("presence")).map { docs ->
        docs.mapNotNull { d -> runCatching { DriverPresence(d.id, d.getString("name").orEmpty(), DriverStatus.valueOf(d.getString("status")!!), d.getBoolean("shareLocation") == true, d.getDouble("latitude"), d.getDouble("longitude"), d.time("updatedAt"), d.getString("vehicleModel"), d.getString("vehicleColor"), d.getString("licensePlate")?.takeIf { d.getBoolean("showPlate") == true }, d.getDouble("accuracyMeters"), d.getDouble("bearingDegrees"), d.getLong("locationTimestamp")) }.getOrNull() }
    }
    /** Expiry ticks even when Firestore has not emitted another snapshot. */
    fun observeActivePresence(room: String): Flow<List<DriverPresence>> = observePresence(room).combine(clock()) { drivers, now -> drivers.filter { it.status != DriverStatus.OFFLINE && it.recent(now) } }
    fun observeMapPresence(room: String): Flow<List<DriverPresence>> = observePresence(room).combine(clock()) { drivers, now -> drivers.filter { it.visibleOnMap(now) } }
    private fun clock(): Flow<Long> = flow {
        while (currentCoroutineContext().isActive) { emit(System.currentTimeMillis()); delay(5_000) }
    }
    private fun message(d: DocumentSnapshot) = RoomMessage(d.id, d.getString("authorUid").orEmpty(), d.getString("authorName").orEmpty(), d.getString("text").orEmpty(), d.time("createdAt"), d.metadata.hasPendingWrites())
    fun observeMessages(room: String): Flow<List<RoomMessage>> = queryFlow(this.room(room).collection("messages").orderBy("createdAt", Query.Direction.DESCENDING).limit(50)).map { docs -> docs.map(::message).distinctBy { it.id }.reversed() }
    suspend fun loadOlderMessages(room: String, beforeId: String): List<RoomMessage> {
        val collection = this.room(room).collection("messages")
        val cursor = collection.document(beforeId).get().await()
        require(cursor.exists())
        return collection.orderBy("createdAt", Query.Direction.DESCENDING).startAfter(cursor).limit(50).get().await().documents.map(::message).reversed()
    }
    /** Retry with the same id: transaction preserves the first message and timestamp. */
    suspend fun sendMessage(room: String, text: String, id: String = UUID.randomUUID().toString()): String {
        require(text.isNotBlank() && text.length <= 2000 && id.matches(Regex("[A-Za-z0-9_-]{1,80}")))
        val author = uid(); val ref = this.room(room).collection("messages").document(id)
        db.runTransaction { tx ->
            val old = tx.get(ref)
            if (old.exists()) { check(old.getString("authorUid") == author && old.getString("text") == text.trim()) { "ID já utilizado" } }
            else tx.set(ref, mapOf("authorUid" to author, "authorName" to name(), "text" to text.trim(), "createdAt" to FieldValue.serverTimestamp()))
        }.await()
        return id
    }
    suspend fun reportAlert(room: String, category: AlertCategory, lat: Double, lon: Double, description: String = "", clientId: String = UUID.randomUUID().toString()): String = createEvent(room, "alerts", category.name, lat, lon, description, 60 * 60_000, clientId)
    /** UI must obtain explicit confirmation before invoking this method. No official dispatch is implied. */
    suspend fun createSos(room: String, lat: Double, lon: Double, description: String = "", clientId: String = UUID.randomUUID().toString(), profile: DriverProfile = DriverProfile()): String = createEvent(room, "sos", "SOS", lat, lon, description, 30 * 60_000, clientId, profile)
    private suspend fun createEvent(room: String, collection: String, category: String, lat: Double, lon: Double, description: String, ttl: Long, clientId: String, profile: DriverProfile = DriverProfile()): String {
        coords(lat, lon); require(description.length <= 500 && clientId.matches(Regex("[A-Za-z0-9_-]{1,80}")))
        val author = uid()
        val sourceRoom = CommunityRooms.slug(room)
        val globalSos = collection == "sos"
        if (globalSos) {
            val eligibility = globalSosEligibility()
            check(eligibility.enabled && sourceRoom in eligibility.rooms) { eligibility.reason ?: "SOS global não autorizado nesta sala" }
        }
        val ref = if (globalSos) db.collection("globalSos").document(clientId) else this.room(room).collection(collection).document(clientId)
        val vehicle = profile.validated()
        val rateRef = db.collection("communityRate").document(author)
        db.runTransaction { tx ->
            val existing = tx.get(ref)
            if (existing.exists()) {
                check(existing.getString("authorUid") == author && existing.getString("category") == category
                    && existing.getDouble("latitude") == lat && existing.getDouble("longitude") == lon
                    && existing.getString("description") == description
                    && (!globalSos || existing.getString("sourceRoom") == sourceRoom)) { "ID de evento já utilizado" }
            } else {
                val rate = tx.get(rateRef)
                val zero = com.google.firebase.Timestamp(java.util.Date(0))
                tx.set(rateRef, mapOf("authorUid" to author,
                    "lastAlertAt" to if (collection == "alerts") FieldValue.serverTimestamp() else rate.getTimestamp("lastAlertAt") ?: zero,
                    "lastSosAt" to if (collection == "sos") FieldValue.serverTimestamp() else rate.getTimestamp("lastSosAt") ?: zero,
                    "lastAlertId" to if (collection == "alerts") "${CommunityRooms.slug(room)}/alerts/$clientId" else rate.getString("lastAlertId").orEmpty(),
                    "lastSosId" to if (globalSos) "globalSos/$clientId" else rate.getString("lastSosId").orEmpty()))
                val metadata = if (globalSos) buildMap<String, Any> {
                    put("sourceRoom", sourceRoom); put("authorName", name()); put("consentVersion", "global-sos-v1")
                    if (vehicle.vehicleModel.isNotBlank()) put("vehicleModel", vehicle.vehicleModel)
                    if (vehicle.vehicleColor.isNotBlank()) put("vehicleColor", vehicle.vehicleColor)
                    // Plate, brand, powertrain and icon never enter this global snapshot.
                } else emptyMap()
                tx.set(ref, mapOf("authorUid" to author, "category" to category, "latitude" to lat, "longitude" to lon, "description" to description,
                    "createdAt" to FieldValue.serverTimestamp(), "expiresAt" to com.google.firebase.Timestamp(java.util.Date(System.currentTimeMillis() + ttl)), "active" to true) + metadata)
            }
        }.await()
        return ref.id
    }
    /** Global SOS history is author-only. Legacy room history remains in its original scope. */
    suspend fun eventHistory(room: String, sos: Boolean): List<CommunityEvent> = if (!sos) observeEvents(room, "alerts").first() else
        (ownGlobalHistory().first() + observeEvents(room, "sos").first().filter { it.authorUid == uid() }).sortedByDescending { it.createdAt }
    suspend fun closeSos(room: String, id: String) {
        val global = db.collection("globalSos").document(id)
        val existing = global.get().await()
        val target = if (existing.exists()) global else this.room(room).collection("sos").document(id)
        target.update(mapOf("active" to false, "closedAt" to FieldValue.serverTimestamp())).await()
    }
    suspend fun closeAlert(room: String, id: String) { this.room(room).collection("alerts").document(id).update(mapOf("active" to false, "closedAt" to FieldValue.serverTimestamp())).await() }
    suspend fun voteAlert(room: String, id: String, confirms: Boolean) {
        this.room(room).collection("alerts").document(id).collection("votes").document(uid())
            .set(mapOf("authorUid" to uid(), "confirms" to confirms, "updatedAt" to FieldValue.serverTimestamp())).await()
    }
    fun observeAlertVotes(room: String, id: String): Flow<Pair<Int, Int>> = queryFlow(this.room(room).collection("alerts").document(id).collection("votes")).map { docs -> docs.count { it.getBoolean("confirms") == true } to docs.count { it.getBoolean("confirms") == false } }
    fun observeAlerts(room: String) = observeEvents(room, "alerts").combine(clock()) { events, now -> events.filter { it.valid(now) } }
    fun observeSos(room: String): Flow<List<CommunityEvent>> = combine(globalOthers(), ownGlobalHistory(), observeEvents(room, "sos"), clock()) { others, own, legacy, now ->
        (others + own + legacy).filter { it.valid(now) }.distinctBy { "${it.global}:${it.id}" }.sortedByDescending { it.createdAt }
    }
    suspend fun globalSosEligibility(): GlobalSosEligibility = eligibility(db.collection("eligibleDrivers").document(uid()).get(Source.SERVER).await())
    fun observeGlobalSosEligibility(): Flow<GlobalSosEligibility> = callbackFlow {
        val listener = db.collection("eligibleDrivers").document(uid()).addSnapshotListener(MetadataChanges.INCLUDE) { d, e ->
            if (e != null) close(e) else if (d != null) trySend(if (d.metadata.isFromCache) GlobalSosEligibility(false) else eligibility(d))
        }
        awaitClose { listener.remove() }
    }
    private fun eligibility(d: DocumentSnapshot): GlobalSosEligibility {
        val rooms = (d.get("rooms") as? List<*>)?.filterIsInstance<String>()?.toSet().orEmpty()
        return GlobalSosEligibility(d.getBoolean("enabled") == true && rooms.isNotEmpty() && rooms.all { it in CommunityRooms.labels }, rooms)
    }
    private fun ownGlobalHistory(): Flow<List<CommunityEvent>> = queryFlow(db.collection("globalSos").whereEqualTo("authorUid", uid()).orderBy("createdAt", Query.Direction.DESCENDING).limit(100)).map { docs -> docs.mapNotNull { event(it, global = true) } }
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private fun globalOthers(): Flow<List<CommunityEvent>> = observeGlobalSosEligibility().flatMapLatest { access ->
        if (!access.enabled) flowOf(emptyList()) else flow {
            while (currentCoroutineContext().isActive) { emit(System.currentTimeMillis()); delay(30_000) }
        }.flatMapLatest { now ->
            // Future guard makes the query provably exclude expired locations under request.time.
            // Renew every 30s; other users' reports may disappear up to 60s before their TTL.
            queryFlow(db.collection("globalSos").whereEqualTo("active", true)
                .whereGreaterThan("expiresAt", com.google.firebase.Timestamp(java.util.Date(now + 60_000)))
                .orderBy("expiresAt").limit(100), requireServer = true).map { docs -> docs.mapNotNull { event(it, global = true) } }
                .catch { error ->
                    // A revoked grant can invalidate the location listener before its
                    // eligibility snapshot arrives. Clear data now, keep the outer
                    // grant/timer flows alive so later reauthorization can reconnect.
                    if (error is FirebaseFirestoreException && error.code == FirebaseFirestoreException.Code.PERMISSION_DENIED) emit(emptyList())
                    else throw error
                }
        }
    }
    private fun observeEvents(room: String, collection: String): Flow<List<CommunityEvent>> = queryFlow(this.room(room).collection(collection).orderBy("createdAt", Query.Direction.DESCENDING).limit(100)).map { docs ->
        docs.mapNotNull { event(it, sourceRoom = CommunityRooms.slug(room)) }
    }
    private fun event(d: DocumentSnapshot, global: Boolean = false, sourceRoom: String? = null): CommunityEvent? = runCatching {
        CommunityEvent(d.id, d.getString("authorUid").orEmpty(), d.getString("category").orEmpty(), d.getDouble("latitude")!!, d.getDouble("longitude")!!,
            d.getString("description").orEmpty(), d.time("createdAt"), d.time("expiresAt"), d.getBoolean("active") == true,
            d.getString("sourceRoom") ?: sourceRoom, d.getString("authorName"), d.getString("vehicleModel"), d.getString("vehicleColor"), global = global)
    }.getOrNull()
    private fun DocumentSnapshot.time(field: String) = getTimestamp(field)?.toDate()?.time ?: 0L
}

