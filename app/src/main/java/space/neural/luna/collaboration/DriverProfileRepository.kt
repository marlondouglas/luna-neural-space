package space.neural.luna.collaboration

import android.content.Context
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.tasks.await

/** Private Firestore profile plus account-scoped local cache. Never reuse one account's consent for another. */
class DriverProfileRepository(
    context: Context,
    private val db: FirebaseFirestore = FirebaseFirestore.getInstance(),
    private val auth: FirebaseAuth = FirebaseAuth.getInstance(),
) {
    private val localCache = DriverProfileCache(context)
    private fun uid() = checkNotNull(auth.currentUser?.uid) { "Entre na conta para salvar o veículo" }
    private fun ref(uid: String) = db.collection("users").document(uid).collection("profile").document("driver")
    fun cached(): DriverProfile {
        return localCache.read(auth.currentUser?.uid)
    }
    fun hasPendingPrivacyChange(): Boolean = auth.currentUser?.uid?.let(localCache::pendingPrivacy) == true
    private fun cache(uid: String, p: DriverProfile) {
        localCache.write(uid, p)
    }
    suspend fun load(): DriverProfile {
        val uid = uid()
        val document = ref(uid).get().await()
        // A stale server snapshot must not reverse a local revocation waiting for connectivity.
        if (localCache.pendingPrivacy(uid)) return localCache.read(uid)
        if (!document.exists()) return cached()
        val p = DriverProfile(document.getString("vehicleModel").orEmpty(), document.getString("vehicleColor").orEmpty(),
            document.getString("licensePlate").orEmpty(), document.getBoolean("showPlate") == true,
            document.getString("vehicleBrand").orEmpty(),
            VehiclePowertrain.fromStored(document.getString("vehiclePowertrain")),
            VehicleIcon.fromStored(document.getString("vehicleIcon"))).validated()
        cache(uid, p)
        return p
    }
    /** Throws on remote failure: UI must not claim public revocation completed while offline.
     * Local hidden preference is applied immediately so future heartbeats cannot send a plate.
     * The private profile and current public presence are updated atomically when connected.
     */
    suspend fun save(profile: DriverProfile, currentRoom: String? = null) {
        val uid = uid(); val p = profile.validated()
        if (!p.showPlate) { cache(uid, p); localCache.setPendingPrivacy(uid, true) }
        val rooms = (CommunityRooms.labels.keys + "luna-aeroporto-gv" + listOfNotNull(currentRoom?.let(CommunityRooms::slug))).distinct()
        val presences = rooms.map { db.collection("rooms").document(it).collection("presence").document(uid) }
        db.runTransaction { transaction ->
            // Read all documents before writing; a plate shared in a previous room must also be revoked.
            val existing = presences.map { it to transaction.get(it) }
            transaction.set(ref(uid), mapOf("vehicleBrand" to p.vehicleBrand, "vehicleModel" to p.vehicleModel, "vehicleColor" to p.vehicleColor,
                "vehiclePowertrain" to p.vehiclePowertrain.name, "vehicleIcon" to p.vehicleIcon.name,
                "licensePlate" to p.licensePlate, "showPlate" to p.showPlate, "updatedAt" to FieldValue.serverTimestamp()))
            existing.filter { it.second.exists() }.forEach { (presence, _) ->
                val update = mutableMapOf<String, Any?>("updatedAt" to FieldValue.serverTimestamp(),
                    "status" to "OFFLINE", "shareLocation" to false, "latitude" to null, "longitude" to null,
                    "vehicleModel" to p.vehicleModel, "vehicleColor" to p.vehicleColor,
                    "showPlate" to false, "licensePlate" to FieldValue.delete())
                transaction.update(presence, update)
            }
        }.await()
        cache(uid, p)
        localCache.setPendingPrivacy(uid, false)
    }
}

/** Separate from Firebase so cache restoration/account isolation are testable offline. */
internal class DriverProfileCache(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences("driver_profile_v1", Context.MODE_PRIVATE)
    fun read(uid: String?): DriverProfile {
        if (uid.isNullOrBlank()) return DriverProfile()
        return DriverProfile(preferences.getString("$uid.model", "").orEmpty(), preferences.getString("$uid.color", "").orEmpty(),
            preferences.getString("$uid.plate", "").orEmpty(), preferences.getBoolean("$uid.showPlate", false),
            preferences.getString("$uid.brand", "").orEmpty(),
            VehiclePowertrain.fromStored(preferences.getString("$uid.powertrain", null)),
            VehicleIcon.fromStored(preferences.getString("$uid.icon", null)))
    }
    fun write(uid: String, p: DriverProfile) {
        require(uid.isNotBlank())
        preferences.edit().putString("$uid.brand", p.vehicleBrand).putString("$uid.model", p.vehicleModel).putString("$uid.color", p.vehicleColor)
            .putString("$uid.powertrain", p.vehiclePowertrain.name).putString("$uid.icon", p.vehicleIcon.name)
            .putString("$uid.plate", p.licensePlate).putBoolean("$uid.showPlate", p.showPlate).apply()
    }
    fun pendingPrivacy(uid: String): Boolean = preferences.getBoolean("$uid.pendingPrivacy", false)
    fun setPendingPrivacy(uid: String, pending: Boolean) { preferences.edit().putBoolean("$uid.pendingPrivacy", pending).apply() }
}
