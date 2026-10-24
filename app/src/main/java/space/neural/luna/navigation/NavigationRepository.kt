package space.neural.luna.navigation

import android.content.Context
import android.location.Geocoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.osmdroid.util.GeoPoint
import java.net.URI
import java.util.Locale

data class SavedDestination(val id: String, val name: String, val latitude: Double, val longitude: Double) {
    val point get() = GeoPoint(latitude, longitude)
}

/** Local destinations never leave the device except when the user requests geocoding or routing. */
class NavigationRepository(context: Context) {
    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences("navigation_v1", Context.MODE_PRIVATE)
    fun favorites(): List<SavedDestination> = read("favorites")
    fun recent(): List<SavedDestination> = read("recent")
    fun home(): SavedDestination? = favorites().firstOrNull { it.id == "home" }
    fun work(): SavedDestination? = favorites().firstOrNull { it.id == "work" }
    @Synchronized fun saveFavorite(destination: SavedDestination) {
        require(validPoint(destination.latitude, destination.longitude))
        write("favorites", listOf(destination) + favorites().filterNot { it.id == destination.id })
    }
    @Synchronized fun removeFavorite(id: String) = write("favorites", favorites().filterNot { it.id == id })
    @Synchronized fun remember(destination: SavedDestination) {
        require(validPoint(destination.latitude, destination.longitude))
        write("recent", (listOf(destination) + recent().filterNot {
            kotlin.math.abs(it.latitude - destination.latitude) < 0.00001 && kotlin.math.abs(it.longitude - destination.longitude) < 0.00001
        }).take(20))
    }
    /** Suggestions are local; this deliberately does not send each keystroke to a public API. */
    fun suggestions(query: String): List<SavedDestination> = (favorites() + recent())
        .distinctBy { it.id }.filter { query.isBlank() || it.name.contains(query.trim(), ignoreCase = true) }.take(8)

    /** Execute only after an explicit search action. Android chooses the device's geocoding provider. */
    @Suppress("DEPRECATION")
    suspend fun search(query: String): List<SavedDestination> = withContext(Dispatchers.IO) {
        require(query.trim().length >= 3) { "Digite pelo menos três caracteres" }
        check(Geocoder.isPresent()) { "Busca de endereços indisponível neste dispositivo" }
        val result = Geocoder(app, Locale.forLanguageTag("pt-BR")).getFromLocationName(query.trim(), 6).orEmpty()
        result.filter { it.hasLatitude() && it.hasLongitude() && validPoint(it.latitude, it.longitude) }.map {
            SavedDestination("${it.latitude},${it.longitude}", it.getAddressLine(0) ?: query.trim(), it.latitude, it.longitude)
        }.distinctBy { it.id }
    }
    fun routingEndpoint(): String = prefs.getString("routing_endpoint", null).orEmpty()
    fun setRoutingEndpoint(endpoint: String) {
        require(endpoint.isBlank() || approvedEndpointFormat(endpoint)) { "Informe um endpoint OSRM HTTPS sem credenciais na URL" }
        prefs.edit().putString("routing_endpoint", endpoint.trim().trimEnd('/')).apply()
    }
    private fun read(key: String): List<SavedDestination> = runCatching {
        val array = JSONArray(prefs.getString(key, "[]"))
        (0 until array.length()).map { array.getJSONObject(it) }.map {
            SavedDestination(it.getString("id"), it.getString("name"), it.getDouble("latitude"), it.getDouble("longitude"))
        }.filter { validPoint(it.latitude, it.longitude) }
    }.getOrDefault(emptyList())
    private fun write(key: String, values: List<SavedDestination>) {
        val array = JSONArray()
        values.forEach { array.put(JSONObject().put("id", it.id).put("name", it.name).put("latitude", it.latitude).put("longitude", it.longitude)) }
        prefs.edit().putString(key, array.toString()).apply()
    }
    companion object {
        fun validPoint(latitude: Double, longitude: Double) = latitude.isFinite() && longitude.isFinite() && latitude in -90.0..90.0 && longitude in -180.0..180.0
        fun approvedEndpointFormat(value: String): Boolean = runCatching {
            val uri = URI(value.trim())
            uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null
        }.getOrDefault(false)
    }
}
