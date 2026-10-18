package space.neural.luna.dashboard

import android.content.Context
import android.util.Xml
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * Notícias e clima de Governador Valadares (precisa de internet). O último dado
 * baixado fica salvo no aparelho e aparece quando não há internet.
 *
 * - Clima: Open-Meteo (sem chave).
 * - Notícias: Google Notícias (RSS) filtrando o site do Diário do Rio Doce, e uma
 *   busca por "BR-381" + Valadares para os avisos da estrada.
 */
object Externo {
    data class Hora(val hora: String, val tempC: Double, val chuva: Int)
    data class Clima(val tempC: Double, val codigo: Int, val minC: Double, val maxC: Double, val chuvaProx: Int, val horas: List<Hora>)
    data class Noticia(val titulo: String, val fonte: String, val link: String, val quando: Long, val br381: Boolean)
    data class Dados(val clima: Clima?, val noticias: List<Noticia>, val atualizadoEm: Long)

    private const val LAT = -18.85
    private const val LON = -41.95
    private const val PREFS = "externo"

    fun cache(ctx: Context): Dados? {
        val s = ctx.getSharedPreferences(PREFS, 0).getString("dados", null) ?: return null
        return runCatching { deJson(JSONObject(s)) }.getOrNull()
    }

    /** Baixa clima e notícias. Mantém o que já havia se uma das partes falhar. */
    suspend fun atualizar(ctx: Context): Dados? = withContext(Dispatchers.IO) {
        val antigo = cache(ctx)
        val (clima, noticias) = coroutineScope {
            val c = async { runCatching { baixarClima() }.getOrNull() }
            val n = async { runCatching { baixarNoticias() }.getOrNull() }
            c.await() to n.await()
        }
        if (clima == null && noticias == null) return@withContext null
        val novo = Dados(clima ?: antigo?.clima, noticias ?: antigo?.noticias.orEmpty(), System.currentTimeMillis())
        ctx.getSharedPreferences(PREFS, 0).edit().putString("dados", paraJson(novo).toString()).apply()
        novo
    }

    private fun get(url: String): String {
        val c = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000; readTimeout = 15_000
            setRequestProperty("User-Agent", "LunaNeuralSpace-Android")
        }
        try {
            if (c.responseCode !in 200..299) error("HTTP ${c.responseCode}")
            return c.inputStream.bufferedReader().use { it.readText() }
        } finally { c.disconnect() }
    }

    private fun baixarClima(): Clima {
        val j = JSONObject(get(
            "https://api.open-meteo.com/v1/forecast?latitude=$LAT&longitude=$LON" +
                "&current=temperature_2m,weather_code&hourly=temperature_2m,precipitation_probability" +
                "&daily=temperature_2m_max,temperature_2m_min&timezone=America%2FSao_Paulo&forecast_days=2"
        ))
        val cur = j.getJSONObject("current")
        val agora = cur.getString("time").take(13) // yyyy-MM-ddTHH
        val h = j.getJSONObject("hourly")
        val tempos = h.getJSONArray("time"); val temps = h.getJSONArray("temperature_2m")
        val chuvas = h.getJSONArray("precipitation_probability")
        val horas = mutableListOf<Hora>()
        var ini = 0
        for (i in 0 until tempos.length()) if (tempos.getString(i).take(13) == agora) { ini = i; break }
        for (i in ini until minOf(ini + 12, tempos.length())) {
            horas += Hora(tempos.getString(i).substring(11, 16), temps.optDouble(i), chuvas.optInt(i))
        }
        val d = j.getJSONObject("daily")
        return Clima(
            cur.getDouble("temperature_2m"), cur.getInt("weather_code"),
            d.getJSONArray("temperature_2m_min").getDouble(0), d.getJSONArray("temperature_2m_max").getDouble(0),
            horas.take(3).maxOfOrNull { it.chuva } ?: 0, horas,
        )
    }

    private fun rss(q: String): String =
        "https://news.google.com/rss/search?q=" + URLEncoder.encode(q, "UTF-8") + "&hl=pt-BR&gl=BR&ceid=BR:pt-419"

    private fun baixarNoticias(): List<Noticia> {
        val drd = parseRss(get(rss("site:drd.com.br when:3d")), false).sortedByDescending { it.quando }.take(3)
        val br = parseRss(get(rss("\"BR-381\" Valadares when:3d")), true).sortedByDescending { it.quando }.take(2)
        return drd + br
    }

    private val rfc822 = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US)

    private fun parseRss(xml: String, br381: Boolean): List<Noticia> {
        val out = mutableListOf<Noticia>()
        val p = Xml.newPullParser(); p.setInput(xml.reader())
        var titulo = ""; var link = ""; var data = ""; var fonte = ""; var dentro = false
        var ev = p.eventType
        while (ev != XmlPullParser.END_DOCUMENT) {
            if (ev == XmlPullParser.START_TAG) {
                when (p.name) {
                    "item" -> { dentro = true; titulo = ""; link = ""; data = ""; fonte = "" }
                    "title" -> if (dentro) titulo = p.nextText()
                    "link" -> if (dentro) link = p.nextText()
                    "pubDate" -> if (dentro) data = p.nextText()
                    "source" -> if (dentro) fonte = p.nextText()
                }
            } else if (ev == XmlPullParser.END_TAG && p.name == "item") {
                dentro = false
                if (fonte.isNotBlank() && titulo.endsWith(" - $fonte")) titulo = titulo.removeSuffix(" - $fonte")
                val t = synchronized(rfc822) { runCatching { rfc822.parse(data)?.time }.getOrNull() } ?: 0L
                if (titulo.isNotBlank()) out += Noticia(titulo, fonte.ifBlank { "Google Notícias" }, link, t, br381)
            }
            ev = p.next()
        }
        return out
    }

    fun descricaoTempo(code: Int): Pair<String, String> = when (code) {
        0 -> "☀" to "Céu limpo"
        1, 2 -> "⛅" to "Parcialmente nublado"
        3 -> "☁" to "Nublado"
        45, 48 -> "🌫" to "Neblina"
        in 51..57 -> "🌦" to "Garoa"
        in 61..67 -> "🌧" to "Chuva"
        in 71..77 -> "❄" to "Neve"
        in 80..82 -> "🌧" to "Pancadas de chuva"
        in 95..99 -> "⛈" to "Trovoadas"
        else -> "☁" to "Tempo instável"
    }

    private fun paraJson(d: Dados) = JSONObject().apply {
        put("em", d.atualizadoEm)
        d.clima?.let { c ->
            put("clima", JSONObject().apply {
                put("t", c.tempC); put("c", c.codigo); put("min", c.minC); put("max", c.maxC); put("ch", c.chuvaProx)
                put("h", JSONArray().apply { c.horas.forEach { put(JSONObject().put("h", it.hora).put("t", it.tempC).put("c", it.chuva)) } })
            })
        }
        put("n", JSONArray().apply {
            d.noticias.forEach { put(JSONObject().put("t", it.titulo).put("f", it.fonte).put("l", it.link).put("q", it.quando).put("b", it.br381)) }
        })
    }

    private fun deJson(j: JSONObject): Dados {
        val c = j.optJSONObject("clima")?.let { o ->
            val hs = o.optJSONArray("h") ?: JSONArray()
            Clima(o.getDouble("t"), o.getInt("c"), o.getDouble("min"), o.getDouble("max"), o.getInt("ch"),
                (0 until hs.length()).map { hs.getJSONObject(it).let { h -> Hora(h.getString("h"), h.getDouble("t"), h.getInt("c")) } })
        }
        val ns = j.optJSONArray("n") ?: JSONArray()
        val n = (0 until ns.length()).map { ns.getJSONObject(it) }.map {
            Noticia(it.getString("t"), it.getString("f"), it.getString("l"), it.getLong("q"), it.getBoolean("b"))
        }
        return Dados(c, n, j.getLong("em"))
    }
}
