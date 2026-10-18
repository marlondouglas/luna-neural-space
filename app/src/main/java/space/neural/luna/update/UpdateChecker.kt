package space.neural.luna.update

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlin.coroutines.coroutineContext

/** Última versão publicada no GitHub Releases. */
data class ReleaseInfo(val tag: String, val version: String, val apkUrl: String?, val apkSize: Long)

/** Erro com mensagem pronta para mostrar ao usuário. */
class UpdateException(message: String, cause: Throwable? = null) : Exception(message, cause)

object UpdateChecker {
    const val LATEST_URL = "https://api.github.com/repos/marlondouglas/luna-neural-space/releases/latest"

    /**
     * "v0.1.3-teste" -> "0.1.3". Tira o "v" do começo e qualquer sufixo depois de "-" ou "+".
     */
    fun normalize(version: String): String =
        version.trim().removePrefix("v").removePrefix("V").split('-', '+').first()

    /** Compara versões numericamente (0.1.10 > 0.1.9). Retorna >0 se a for mais nova que b. */
    fun compare(a: String, b: String): Int {
        val pa = normalize(a).split('.').map { it.takeWhile(Char::isDigit).toIntOrNull() ?: 0 }
        val pb = normalize(b).split('.').map { it.takeWhile(Char::isDigit).toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(pa.size, pb.size)) {
            val d = pa.getOrElse(i) { 0 }.compareTo(pb.getOrElse(i) { 0 })
            if (d != 0) return d
        }
        return 0
    }

    suspend fun fetchLatest(): ReleaseInfo = withContext(Dispatchers.IO) {
        val conn = try {
            (URL(LATEST_URL).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000
                readTimeout = 15_000
                setRequestProperty("Accept", "application/vnd.github+json")
                setRequestProperty("User-Agent", "LunaNeuralSpace-Android")
            }
        } catch (e: IOException) {
            throw UpdateException("Sem conexão com a internet.", e)
        }
        try {
            val code = try {
                conn.responseCode
            } catch (e: IOException) {
                throw UpdateException("Sem conexão com a internet (ou o GitHub não respondeu).", e)
            }
            when {
                code == 404 -> throw UpdateException("Nenhuma versão publicada ainda.")
                code == 429 || (code == 403 && conn.getHeaderField("X-RateLimit-Remaining") == "0") -> {
                    val reset = conn.getHeaderField("X-RateLimit-Reset")?.toLongOrNull()
                    val min = reset?.let { ((it * 1000 - System.currentTimeMillis()) / 60_000).coerceAtLeast(1) }
                    throw UpdateException(
                        "Limite de consultas ao GitHub atingido. Tente de novo" +
                            (min?.let { " em uns $it minuto(s)." } ?: " mais tarde.")
                    )
                }
                code !in 200..299 -> throw UpdateException("O GitHub respondeu com erro ($code). Tente mais tarde.")
            }
            val json = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
            val tag = json.optString("tag_name")
            if (tag.isBlank()) throw UpdateException("Resposta do GitHub sem versão.")
            var apkUrl: String? = null
            var size = -1L
            val assets = json.optJSONArray("assets")
            if (assets != null) {
                for (i in 0 until assets.length()) {
                    val a = assets.getJSONObject(i)
                    if (a.optString("name").endsWith(".apk", ignoreCase = true)) {
                        apkUrl = a.optString("browser_download_url").ifBlank { null }
                        size = a.optLong("size", -1)
                        break
                    }
                }
            }
            ReleaseInfo(tag, normalize(tag), apkUrl, size)
        } catch (e: UpdateException) {
            throw e
        } catch (e: Exception) {
            throw UpdateException("Não foi possível ler a resposta do GitHub: ${e.message}", e)
        } finally {
            conn.disconnect()
        }
    }

    /** Baixa o APK para [dest]. [onProgress] recebe 0..100, ou -1 se o tamanho for desconhecido. */
    suspend fun download(url: String, dest: File, expectedSize: Long, onProgress: (Int) -> Unit) =
        withContext(Dispatchers.IO) {
            dest.parentFile?.mkdirs()
            val tmp = File(dest.parentFile, dest.name + ".part")
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000
                readTimeout = 30_000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "LunaNeuralSpace-Android")
            }
            try {
                val code = try {
                    conn.responseCode
                } catch (e: IOException) {
                    throw UpdateException("Sem conexão com a internet.", e)
                }
                if (code !in 200..299) throw UpdateException("Falha no download ($code).")
                val total = conn.contentLengthLong.takeIf { it > 0 } ?: expectedSize
                var done = 0L
                var last = -2
                conn.inputStream.use { input ->
                    tmp.outputStream().use { out ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            coroutineContext.ensureActive()
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            done += n
                            val p = if (total > 0) ((done * 100) / total).toInt() else -1
                            if (p != last) { last = p; withContext(Dispatchers.Main) { onProgress(p) } }
                        }
                    }
                }
                if (total > 0 && done != total) throw UpdateException("Download incompleto. Tente de novo.")
                if (dest.exists()) dest.delete()
                if (!tmp.renameTo(dest)) throw UpdateException("Não foi possível salvar o arquivo.")
            } catch (e: UpdateException) {
                tmp.delete(); throw e
            } catch (e: IOException) {
                tmp.delete(); throw UpdateException("A conexão caiu durante o download. Tente de novo.", e)
            } finally {
                conn.disconnect()
            }
        }
}
