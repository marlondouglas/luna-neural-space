package space.neural.luna.conta

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import space.neural.luna.BuildConfig

/** Fala com o suporte (WhatsApp do administrador do Rota GV). */
object Suporte {
    fun whatsapp(ctx: Context, texto: String) {
        val n = BuildConfig.SUPPORT_WHATSAPP
        val uri = Uri.parse("https://wa.me/$n?text=" + Uri.encode(texto))
        runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            .onFailure { Toast.makeText(ctx, "Não foi possível abrir o WhatsApp.", Toast.LENGTH_SHORT).show() }
    }
}
