package space.neural.luna.sync

import android.content.Context
import android.util.Log
import androidx.room.withTransaction
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import space.neural.luna.data.local.LunaDatabase
import space.neural.luna.data.local.Syncable
import space.neural.luna.data.local.Turno

/**
 * Sincronização de duas vias entre o Room deste aparelho e users/{uid}/ (v0.1.7).
 * Usado pelo [SyncWorker] (segundo plano / reserva) e pelo [RealtimeSync] (app aberto, ouvintes em tempo real).
 *
 * Ordem de uma sincronização completa: acerta o relógio → DESCE o que mudou na nuvem → SOBE o pendente
 * (cada registro numa transação que só grava se este aparelho tiver a versão mais nova, ver [Conflito])
 * → reconcilia o turno compartilhado → configurações → marca a [PrimeiraCarga].
 */
class SyncEngine(
    private val ctx: Context,
    val db: LunaDatabase,
    val remote: RemoteStore,
    val uid: String,
    val deviceId: String,
) {
    private val prefs = ctx.getSharedPreferences("luna_sync_$uid", Context.MODE_PRIVATE)
    val turno = TurnoRemoto(ctx, db, remote, uid, deviceId)
    val config = ConfigSync.Remoto(ctx, remote, uid, deviceId)

    val colecoes: List<SyncCollection<*>> = listOf(
        SyncCollection("turnos", db.turnoDao()::dirty, db.turnoDao()::markClean, db.turnoDao()::byId,
            db.turnoDao()::upsert, FirestoreMappers::turno, FirestoreMappers::turno),
        SyncCollection("ganhos", db.ganhoDao()::dirty, db.ganhoDao()::markClean, db.ganhoDao()::byId,
            db.ganhoDao()::upsert, FirestoreMappers::ganho, FirestoreMappers::ganho),
        SyncCollection("gastos", db.gastoDao()::dirty, db.gastoDao()::markClean, db.gastoDao()::byId,
            db.gastoDao()::upsert, FirestoreMappers::gasto, FirestoreMappers::gasto),
        SyncCollection("metas", db.metaDao()::dirty, db.metaDao()::markClean, db.metaDao()::byId,
            db.metaDao()::upsert, FirestoreMappers::meta, FirestoreMappers::meta),
        SyncCollection("anotacoes", db.anotacaoDao()::dirty, db.anotacaoDao()::markClean, db.anotacaoDao()::byId,
            db.anotacaoDao()::upsert, FirestoreMappers::anotacao, FirestoreMappers::anotacao),
        SyncCollection("planejamento", db.planejamentoDao()::dirty, db.planejamentoDao()::markClean, db.planejamentoDao()::byId,
            db.planejamentoDao()::upsert, FirestoreMappers::planejamento, FirestoreMappers::planejamento),
        SyncCollection("contas_fixas", db.contaFixaDao()::dirty, db.contaFixaDao()::markClean, db.contaFixaDao()::byId,
            db.contaFixaDao()::upsert, FirestoreMappers::contaFixa, FirestoreMappers::contaFixa),
        SyncCollection("limites", db.limiteDao()::dirty, db.limiteDao()::markClean, db.limiteDao()::byId,
            db.limiteDao()::upsert, FirestoreMappers::limite, FirestoreMappers::limite),
    )

    fun colecao(nome: String) = colecoes.first { it.name == nome }

    // ---------------- cursores de descida ----------------

    fun cursor(c: String): RemoteStore.Cursor =
        RemoteStore.Cursor(prefs.getLong("lastPull_${c}_s", 0), prefs.getInt("lastPull_${c}_n", 0))

    private fun avancar(c: String, docs: List<RemoteStore.Doc>) {
        val max = docs.mapNotNull { it.cursor }.maxOrNull() ?: return
        if (max <= cursor(c)) return
        prefs.edit().putLong("lastPull_${c}_s", max.seconds).putInt("lastPull_${c}_n", max.nanos).commit()
    }

    // ---------------- descer ----------------

    /** Aplica documentos remotos no Room ("último a alterar vence"). Retorna quantos entraram. */
    suspend fun aplicar(c: SyncCollection<*>, docs: List<RemoteStore.Doc>): Int {
        var n = 0
        db.withTransaction {
            for (d in docs) {
                val remoto = c.fromMap(d.id, d.data) ?: continue
                val local = c.byId(d.id)
                val vLocal = local?.let { Conflito.Versao(it.updatedAt, if (it.dirty) deviceId else null) }
                if (Conflito.remotoVence(vLocal, Conflito.Versao(remoto.updatedAt, d.deviceId))) {
                    if (c.name in setOf("gastos", "ganhos") && local != null && local.toString() != remoto.toString()) {
                        db.auditoriaFinanceiraDao().inserir(space.neural.luna.data.local.AuditoriaFinanceira(
                            registroId = d.id, tipo = c.name,
                            acao = if (local.dirty) "CONFLITO_REMOTO" else "ATUALIZACAO_REMOTA",
                            antes = local.toString(), depois = remoto.toString()))
                    }
                    c.upsert(remoto); n++
                }
            }
        }
        avancar(c.name, docs)
        return n
    }

    suspend fun baixar(c: SyncCollection<*>): Int = aplicar(c, remote.mudancasDesde(c.name, cursor(c.name)))

    suspend fun baixarTudo(): Int = colecoes.sumOf { baixar(it) }

    // ---------------- subir ----------------

    /** Envia um registro pendente. Se o servidor tiver versão mais nova, ela vem para o aparelho. */
    suspend fun enviar(c: SyncCollection<*>, item: Syncable) {
        val path = "${c.name}/${item.id}"
        var perdeu: RemoteStore.Doc? = null
        remote.transacao(listOf(path)) { lidos ->
            val r = lidos[path]
            if (Conflito.envioVence(Conflito.Versao(item.updatedAt, deviceId), r?.versao)) {
                perdeu = null
                mapOf(path to c.toMap(item) + ("deviceId" to deviceId))
            } else { perdeu = r; emptyMap() }
        }
        val r = perdeu
        if (r == null) c.markClean(item.id, item.updatedAt)
        else {
            Log.i(TAG, "${c.name}/${item.id}: o servidor tem versão mais nova; a alteração deste aparelho foi descartada")
            aplicar(c, listOf(r.copy(cursor = null)))
        }
    }

    suspend fun enviarPendentes(): Int {
        var n = 0
        for (c in colecoes) for (item in c.dirty()) { enviar(c, item); n++ }
        return n
    }

    // ---------------- completo ----------------

    suspend fun acertarRelogio(forcar: Boolean = false) {
        if (!forcar && System.currentTimeMillis() - Relogio.medidoEm(ctx) < 6 * 3600_000L) return
        runCatching {
            val antes = System.currentTimeMillis()
            val s = remote.horaServidor(deviceId)
            Relogio.ajustar(ctx, s, antes, System.currentTimeMillis())
        }.onFailure { Log.w(TAG, "Hora do servidor indisponível", it) }
    }

    suspend fun sincronizar() = trava.withLock {
        acertarRelogio()
        baixarTudo()
        turno.baixar()
        config.baixar()
        PrimeiraCarga.marcar(ctx, uid)
        val turnos = db.turnoDao().dirty()
        enviarPendentes()
        turno.reconciliar(turnos)
        config.enviar()
    }

    /** Só sobe o pendente (depois de cada alteração local, com o app aberto). */
    suspend fun subir() = trava.withLock {
        val turnos = db.turnoDao().dirty()
        enviarPendentes()
        turno.reconciliar(turnos)
        config.enviar()
    }

    companion object {
        private const val TAG = "LunaSync"
        /** Uma sincronização por vez no processo (worker e tempo real). */
        val trava = Mutex()
    }
}

/** Descreve como sincronizar uma coleção. */
class SyncCollection<T : Syncable>(
    val name: String,
    private val dirtyFn: suspend () -> List<T>,
    val markClean: suspend (String, Long) -> Unit,
    val byId: suspend (String) -> T?,
    private val upsertFn: suspend (T) -> Unit,
    private val toMapFn: (T) -> Map<String, Any?>,
    val fromMap: (String, Map<String, Any?>) -> T?,
) {
    suspend fun dirty(): List<T> = dirtyFn()
    @Suppress("UNCHECKED_CAST")
    suspend fun upsert(item: Syncable) = upsertFn(item as T)
    @Suppress("UNCHECKED_CAST")
    fun toMap(item: Syncable): Map<String, Any?> = toMapFn(item as T)
}

/** Guarda das ações de turno (ver [TurnoCompartilhado]). */
interface TurnoGuard {
    sealed interface Resultado {
        /** [enviado] = já gravado no servidor (o registro local pode ficar "limpo"). */
        data class Ok(val enviado: Boolean) : Resultado
        data class Conflito(val estado: TurnoCompartilhado.Estado, val mensagem: String) : Resultado
    }

    suspend fun confirmar(alterados: List<Turno>): Resultado

    /** Sem nuvem (ou sem internet): vale no aparelho, sobe depois. */
    object Local : TurnoGuard {
        override suspend fun confirmar(alterados: List<Turno>) = Resultado.Ok(enviado = false)
    }
}
