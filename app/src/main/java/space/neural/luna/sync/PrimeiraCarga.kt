package space.neural.luna.sync

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * "Baixando seus dados…" (v0.1.7). Num aparelho novo (ou depois de trocar de conta), a tela fica vazia até
 * a primeira descida da nuvem. Na 0.1.6 isso levava a sobrescrever o Planejamento do outro aparelho com o
 * padrão. Agora, até a primeira descida completa daquela conta neste aparelho:
 * - a tela inicial mostra "Baixando seus dados…" (com "Continuar sem esperar" se estiver sem internet);
 * - o Planejamento fica só para leitura.
 */
object PrimeiraCarga {
    private const val PREFS = "sync_estado"

    /** Para testes/prints: força o estado (true = já baixou, false = baixando). */
    @Volatile var forcar: Boolean? = null

    private val _versao = MutableStateFlow(0)
    /** Muda a cada marcação: a UI observa e consulta [pronto]. */
    val mudou: StateFlow<Int> = _versao

    fun pronto(ctx: Context, uid: String?): Boolean {
        forcar?.let { return it }
        if (uid == null) return true // sem conta (sem Firebase): nada a baixar
        return ctx.getSharedPreferences(PREFS, 0).getBoolean("pull_ok_$uid", false)
    }

    fun marcar(ctx: Context, uid: String) {
        val p = ctx.getSharedPreferences(PREFS, 0)
        if (!p.getBoolean("pull_ok_$uid", false)) p.edit().putBoolean("pull_ok_$uid", true).commit()
        _versao.value++
    }

    fun redefinir(ctx: Context, uid: String) {
        ctx.getSharedPreferences(PREFS, 0).edit().remove("pull_ok_$uid").commit()
        _versao.value++
    }

    /**
     * Atualização 0.1.6 → 0.1.7: quem já sincronizou nesta conta neste aparelho (há cursores de descida)
     * não precisa esperar.
     */
    fun migrar(ctx: Context, uid: String) {
        val p = ctx.getSharedPreferences(PREFS, 0)
        if (p.getBoolean("migrado_v017", false)) return
        val s = ctx.getSharedPreferences("luna_sync_$uid", 0)
        if (s.all.keys.any { it.startsWith("lastPull_") }) marcar(ctx, uid)
        p.edit().putBoolean("migrado_v017", true).commit()
    }
}
