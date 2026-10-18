package space.neural.luna.data.repo

import android.content.Context
import kotlinx.coroutines.flow.Flow
import space.neural.luna.data.local.Anotacao
import space.neural.luna.data.local.EstadoTurno
import space.neural.luna.data.local.Ganho
import space.neural.luna.data.local.Gasto
import space.neural.luna.data.local.LunaDatabase
import space.neural.luna.data.local.Meta
import space.neural.luna.data.local.Turno
import space.neural.luna.sync.SyncManager

/**
 * Repositórios "local primeiro": toda escrita vai para o Room na hora
 * (marcada como dirty) e só depois a sincronização é agendada.
 * Sem internet, o app continua funcionando com os dados locais.
 */
class LunaRepositories(context: Context) {
    private val appContext = context.applicationContext
    private val db = LunaDatabase.get(appContext)
    private fun now() = System.currentTimeMillis()
    private fun sync() = SyncManager.requestSync(appContext)

    // ---- Turno ----
    fun observeTurnos(): Flow<List<Turno>> = db.turnoDao().observeAll()
    fun observeTurnoAtual(): Flow<Turno?> = db.turnoQueries().observeAtual()
    fun observeTurnoCorrente(): Flow<Turno?> = db.turnoQueries().observeCorrente()
    fun observePendentes(): Flow<Int> = db.syncQueries().observePendentes()
    suspend fun turnoPorId(id: String): Turno? = db.turnoDao().byId(id)

    /**
     * Liga ao turno os ganhos e gastos do dia [data] lançados sem turno, para que
     * fiquem no histórico daquele turno (usado ao fechar/concluir).
     */
    suspend fun vincularSoltos(turnoId: String, data: String) {
        db.ganhoDao().soltos(data).forEach { salvarGanho(it.copy(turnoId = turnoId)) }
        db.gastoDao().soltos(data).forEach { salvarGasto(it.copy(turnoId = turnoId)) }
    }

    suspend fun salvarTurno(turno: Turno) {
        db.turnoDao().upsert(turno.copy(updatedAt = now(), dirty = true))
        sync()
    }

    /**
     * Muda o estado do turno somando o tempo corrido desde o último evento:
     * tempo em ATIVO vai para trabalho; tempo em PAUSADO ou CAFE vai para parado.
     */
    suspend fun mudarEstado(turno: Turno, novoEstado: String, agora: Long = now()) {
        val corrido = (agora - turno.ultimoEvento).coerceAtLeast(0)
        val atualizado = when (turno.estado) {
            EstadoTurno.ATIVO -> turno.copy(trabalhoMs = turno.trabalhoMs + corrido)
            EstadoTurno.PAUSADO, EstadoTurno.CAFE -> turno.copy(paradoMs = turno.paradoMs + corrido)
            else -> turno
        }
        val fim = if (novoEstado == EstadoTurno.FECHADO || novoEstado == EstadoTurno.CONCLUIDO) {
            atualizado.fim ?: agora
        } else {
            atualizado.fim
        }
        salvarTurno(atualizado.copy(estado = novoEstado, ultimoEvento = agora, fim = fim))
    }

    suspend fun excluirTurno(id: String) {
        db.turnoDao().byId(id)?.let { salvarTurno(it.copy(deleted = true)) }
    }

    // ---- Ganho ----
    fun observeGanhos(): Flow<List<Ganho>> = db.ganhoDao().observeAll()
    suspend fun salvarGanho(item: Ganho) {
        db.ganhoDao().upsert(item.copy(updatedAt = now(), dirty = true)); sync()
    }
    suspend fun excluirGanho(id: String) {
        db.ganhoDao().byId(id)?.let { salvarGanho(it.copy(deleted = true)) }
    }

    // ---- Gasto ----
    fun observeGastos(): Flow<List<Gasto>> = db.gastoDao().observeAll()
    suspend fun salvarGasto(item: Gasto) {
        db.gastoDao().upsert(item.copy(updatedAt = now(), dirty = true)); sync()
    }
    suspend fun excluirGasto(id: String) {
        db.gastoDao().byId(id)?.let { salvarGasto(it.copy(deleted = true)) }
    }

    // ---- Meta ----
    fun observeMetas(): Flow<List<Meta>> = db.metaDao().observeAll()
    suspend fun salvarMeta(item: Meta) {
        db.metaDao().upsert(item.copy(updatedAt = now(), dirty = true)); sync()
    }
    suspend fun excluirMeta(id: String) {
        db.metaDao().byId(id)?.let { salvarMeta(it.copy(deleted = true)) }
    }

    // ---- Anotação ----
    fun observeAnotacoes(): Flow<List<Anotacao>> = db.anotacaoDao().observeAll()
    suspend fun salvarAnotacao(item: Anotacao) {
        db.anotacaoDao().upsert(item.copy(updatedAt = now(), dirty = true)); sync()
    }
    suspend fun excluirAnotacao(id: String) {
        db.anotacaoDao().byId(id)?.let { salvarAnotacao(it.copy(deleted = true)) }
    }
}
