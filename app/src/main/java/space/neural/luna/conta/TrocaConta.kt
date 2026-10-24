package space.neural.luna.conta

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import space.neural.luna.data.local.LunaDatabase
import space.neural.luna.sync.ConfigSync
import space.neural.luna.sync.PrimeiraCarga

/**
 * Dono dos dados locais (v0.1.7). Na 0.1.6, "Sair da conta" deixava tudo no Room: se outra conta entrasse
 * no mesmo aparelho, ela via os lançamentos da anterior e os pendentes subiam para a conta nova.
 *
 * Agora o aparelho lembra de qual conta são os dados locais. Ao entrar:
 * - mesma conta (ou conta criada a partir da anônima, que mantém o uid): nada muda;
 * - outra conta: o banco local é apagado e a conta nova baixa os dela da nuvem ("Baixando seus dados…").
 *   Os dados da conta anterior continuam na nuvem dela. Antes de sair, o app tenta subir o pendente e avisa
 *   se não conseguir (ConfiguracoesActivity).
 */
object TrocaConta {
    private const val PREFS = "conta_local"
    private const val CHAVE = "uid"

    enum class Acao { MANTER, ADOTAR, APAGAR }

    /**
     * [dono] = conta dona dos dados locais (null = desconhecida), [novo] = conta que entrou,
     * [pendentes] = registros locais que ainda não subiram, [temDados] = há algum registro local.
     */
    fun decidir(dono: String?, novo: String, temDados: Boolean, pendentes: Int): Acao = when {
        dono == novo -> Acao.MANTER
        dono == null && (!temDados || pendentes > 0) -> Acao.ADOTAR // aparelho novo, ou dados sem dono que ainda não subiram: não perde nada
        else -> Acao.APAGAR // de outra conta (ou sem dono e já na nuvem do dono verdadeiro)
    }

    fun dono(ctx: Context): String? = ctx.getSharedPreferences(PREFS, 0).getString(CHAVE, null)

    fun definirDono(ctx: Context, uid: String) { ctx.getSharedPreferences(PREFS, 0).edit().putString(CHAVE, uid).commit() }

    /** Chamar logo depois de entrar/criar conta, antes de abrir o painel. Retorna a ação tomada. */
    suspend fun aoEntrar(ctx: Context, uid: String): Acao = withContext(Dispatchers.IO) {
        val app = ctx.applicationContext
        // Memory is independent of finance: remove only the previous account's opt-in summaries.
        space.neural.luna.memory.LunaMemoryRepository.onAccountSwitch(app, dono(app), uid)
        val db = LunaDatabase.get(app)
        val acao = decidir(dono(app), uid, temDados(db), db.syncQueries().pendentes())
        if (acao == Acao.APAGAR) apagarLocal(app, uid)
        definirDono(app, uid)
        acao
    }

    suspend fun temDados(db: LunaDatabase): Boolean = db.syncQueries().total() > 0

    /** Apaga os dados locais (Room inteiro, cursores e estado do turno/config desta conta neste aparelho). */
    fun apagarLocal(ctx: Context, novoUid: String) {
        LunaDatabase.get(ctx).clearAllTables()
        for (n in listOf("luna_sync_$novoUid", "turno_compartilhado_$novoUid")) ctx.getSharedPreferences(n, 0).edit().clear().commit()
        PrimeiraCarga.redefinir(ctx, novoUid)
        ConfigSync.redefinir(ctx)
    }

    /** 0.1.6 → 0.1.7: quem já estava logado é o dono dos dados locais. */
    fun migrar(ctx: Context, uidAtual: String?) {
        if (uidAtual != null && dono(ctx) == null) definirDono(ctx, uidAtual)
    }
}
