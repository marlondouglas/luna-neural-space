package space.neural.luna.voice

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import space.neural.luna.BuildConfig
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** Dados para entrar numa sala do LiveKit. */
data class ConnectionDetails(val serverUrl: String, val token: String)

/**
 * Fornece o token de acesso do LiveKit.
 *
 * O token PRECISA ser gerado num servidor (token server próprio ou o servidor de
 * tokens do LiveKit Cloud). A API secret do LiveKit nunca pode ficar dentro do app
 * nem no repositório.
 */
fun interface TokenProvider {
    suspend fun fetch(roomName: String, identity: String): ConnectionDetails
}

/**
 * Busca o token num endpoint HTTP configurado em LIVEKIT_TOKEN_ENDPOINT
 * (local.properties ou variável de ambiente, nunca no Git).
 *
 * Chamada: GET {endpoint}?room=<sala>&identity=<id>
 * Resposta JSON aceita: {"serverUrl": "...", "participantToken": "..."}
 * ou {"url": "...", "token": "..."}. Se a resposta não trouxer a URL,
 * usa LIVEKIT_URL.
 */
class HttpTokenProvider(
    private val endpoint: String = BuildConfig.LIVEKIT_TOKEN_ENDPOINT,
    private val defaultUrl: String = BuildConfig.LIVEKIT_URL,
) : TokenProvider {

    override suspend fun fetch(roomName: String, identity: String): ConnectionDetails =
        withContext(Dispatchers.IO) {
            check(endpoint.isNotBlank()) {
                "LIVEKIT_TOKEN_ENDPOINT não configurado (veja SETUP_FIREBASE_LIVEKIT.md)"
            }
            val q = "room=${enc(roomName)}&identity=${enc(identity)}"
            val sep = if (endpoint.contains('?')) "&" else "?"
            val conn = URL("$endpoint$sep$q").openConnection() as HttpURLConnection
            try {
                conn.connectTimeout = 10_000
                conn.readTimeout = 10_000
                check(conn.responseCode in 200..299) { "Token server respondeu ${conn.responseCode}" }
                val json = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
                val token = json.optString("participantToken").ifBlank { json.optString("token") }
                val url = json.optString("serverUrl").ifBlank { json.optString("url") }.ifBlank { defaultUrl }
                check(token.isNotBlank()) { "Resposta sem token" }
                check(url.isNotBlank()) { "LIVEKIT_URL não configurado" }
                ConnectionDetails(url, token)
            } finally {
                conn.disconnect()
            }
        }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
}
