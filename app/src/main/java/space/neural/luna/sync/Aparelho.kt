package space.neural.luna.sync

import android.content.Context
import java.util.UUID

/** Identificador aleatório deste aparelho para a sincronização (desempate de conflitos e "quem mexeu no turno"). */
object Aparelho {
    private const val PREFS = "aparelho_sync"
    @Volatile private var cache: String? = null

    fun id(ctx: Context): String = cache ?: synchronized(this) {
        cache ?: ctx.getSharedPreferences(PREFS, 0).let { p ->
            p.getString("id", null) ?: UUID.randomUUID().toString().replace("-", "").take(16).also { p.edit().putString("id", it).commit() }
        }.also { cache = it }
    }

    internal fun redefinirParaTestes() { cache = null }
}
