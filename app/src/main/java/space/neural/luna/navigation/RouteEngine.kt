package space.neural.luna.navigation

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.osmdroid.util.GeoPoint
import space.neural.luna.BuildConfig
import java.net.HttpURLConnection
import java.net.URL

data class RouteStep(val point: GeoPoint, val text: String, val meters: Double, val maneuverType: String = "", val modifier: String = "")
data class Route(val points: List<GeoPoint>, val meters: Double, val seconds: Double, val steps: List<RouteStep>)

/** OSRM protocol. Requires an operator-approved HTTPS endpoint, not an uncontracted public demo. */
class RouteEngine(private val baseUrl: String = BuildConfig.ROUTING_BASE_URL) {
    val configured get() = NavigationRepository.approvedEndpointFormat(baseUrl)
    suspend fun route(from: GeoPoint, to: GeoPoint): Route = withContext(Dispatchers.IO) {
        check(configured) { "Serviço de rotas não conectado. Informe o endereço HTTPS do provedor OSRM autorizado nas configurações do mapa." }
        require(NavigationRepository.validPoint(from.latitude, from.longitude) && NavigationRepository.validPoint(to.latitude, to.longitude)) { "Coordenadas inválidas" }
        val url = "${baseUrl.trimEnd('/')}/route/v1/driving/${from.longitude},${from.latitude};${to.longitude},${to.latitude}?overview=full&geometries=geojson&steps=true"
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10000; readTimeout = 15000; instanceFollowRedirects = false
            setRequestProperty("User-Agent", "RotaGV-Android/${BuildConfig.VERSION_NAME}")
        }
        try {
            when (connection.responseCode) {
                401, 403 -> error("Provedor de rotas recusou o acesso. Verifique a autorização no backend; não informe chaves secretas no aplicativo.")
                429 -> error("Limite de consultas do provedor de rotas atingido. Tente novamente mais tarde.")
                in 200..299 -> Unit
                else -> error("Serviço de rotas indisponível (HTTP ${connection.responseCode})")
            }
            parse(connection.inputStream.bufferedReader().use { it.readText() })
        } finally { connection.disconnect() }
    }

    companion object {
        fun parse(json: String): Route {
            val root = JSONObject(json)
            check(root.optString("code") == "Ok") { "Nenhuma rota encontrada" }
            val routes = root.getJSONArray("routes")
            check(routes.length() > 0) { "Nenhuma rota encontrada" }
            val route = routes.getJSONObject(0)
            val coords = route.getJSONObject("geometry").getJSONArray("coordinates")
            val points = (0 until coords.length()).map { i -> coords.getJSONArray(i).let { GeoPoint(it.getDouble(1), it.getDouble(0)) } }
            val steps = mutableListOf<RouteStep>()
            val legs = route.getJSONArray("legs")
            for (i in 0 until legs.length()) {
                val items = legs.getJSONObject(i).getJSONArray("steps")
                for (j in 0 until items.length()) {
                    val step = items.getJSONObject(j); val maneuver = step.getJSONObject("maneuver")
                    val xy = maneuver.getJSONArray("location")
                    val direction = when (maneuver.optString("modifier")) {
                        "left" -> "à esquerda"; "right" -> "à direita"; "slight left" -> "levemente à esquerda"
                        "slight right" -> "levemente à direita"; "uturn" -> "retorno"; else -> "em frente"
                    }
                    val action = when (maneuver.optString("type")) {
                        "arrive" -> "Chegada ao destino"; "depart" -> "Siga $direction"
                        "roundabout", "rotary" -> "Na rotatória, saída ${maneuver.optInt("exit")}"; else -> "Siga $direction"
                    }
                    steps += RouteStep(GeoPoint(xy.getDouble(1), xy.getDouble(0)), action + step.optString("name").takeIf { it.isNotBlank() }?.let { " — $it" }.orEmpty(), step.getDouble("distance"), maneuver.optString("type"), maneuver.optString("modifier"))
                }
            }
            val meters = route.getDouble("distance")
            val seconds = route.getDouble("duration")
            check(points.size >= 2 && points.all { NavigationRepository.validPoint(it.latitude, it.longitude) } && meters.isFinite() && seconds.isFinite() && meters >= 0 && seconds >= 0) { "Percurso inválido" }
            return Route(points, meters, seconds, steps)
        }
    }
}
