package space.neural.luna.carro

import android.content.Context
import android.content.Intent
import android.net.Uri

/**
 * Lançador de apps de mídia / navegação (Spotify, YouTube, Waze).
 * Usa intents padrão; se o app não estiver instalado, abre a loja ou fallback.
 */
object MediaLauncher {

    fun abrirSpotify(ctx: Context) = launch(ctx, "com.spotify.music", "spotify:")
    fun abrirYouTube(ctx: Context) = launch(ctx, "com.google.android.youtube", "vnd.youtube:")
    fun abrirWaze(ctx: Context) = launch(ctx, "com.waze", "waze://")

    private fun launch(ctx: Context, pkg: String, uriScheme: String) {
        val intent = ctx.packageManager.getLaunchIntentForPackage(pkg)
            ?: Intent(Intent.ACTION_VIEW, Uri.parse(uriScheme)).apply {
                setPackage(pkg)
            }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { ctx.startActivity(intent) }
            .onFailure {
                // Fallback: Play Store
                val store = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$pkg"))
                store.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                runCatching { ctx.startActivity(store) }
            }
    }
}
