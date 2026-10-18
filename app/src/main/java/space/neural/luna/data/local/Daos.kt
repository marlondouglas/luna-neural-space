package space.neural.luna.data.local

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface TurnoDao {
    @Upsert
    suspend fun upsert(item: Turno)

    @Query("SELECT * FROM turnos WHERE id = :id")
    suspend fun byId(id: String): Turno?

    @Query("SELECT * FROM turnos WHERE deleted = 0 ORDER BY inicio DESC")
    fun observeAll(): Flow<List<Turno>>

    @Query("SELECT * FROM turnos WHERE dirty = 1")
    suspend fun dirty(): List<Turno>

    /** Só limpa a marca se o registro não mudou desde o envio. */
    @Query("UPDATE turnos SET dirty = 0 WHERE id = :id AND updatedAt = :updatedAt")
    suspend fun markClean(id: String, updatedAt: Long)
}

@Dao
interface GanhoDao {
    @Upsert
    suspend fun upsert(item: Ganho)

    @Query("SELECT * FROM ganhos WHERE id = :id")
    suspend fun byId(id: String): Ganho?

    @Query("SELECT * FROM ganhos WHERE deleted = 0 ORDER BY data DESC")
    fun observeAll(): Flow<List<Ganho>>

    @Query("SELECT * FROM ganhos WHERE dirty = 1")
    suspend fun dirty(): List<Ganho>

    @Query("SELECT * FROM ganhos WHERE deleted = 0 AND turnoId IS NULL AND data = :data")
    suspend fun soltos(data: String): List<Ganho>

    /** Só limpa a marca se o registro não mudou desde o envio. */
    @Query("UPDATE ganhos SET dirty = 0 WHERE id = :id AND updatedAt = :updatedAt")
    suspend fun markClean(id: String, updatedAt: Long)
}

@Dao
interface GastoDao {
    @Upsert
    suspend fun upsert(item: Gasto)

    @Query("SELECT * FROM gastos WHERE id = :id")
    suspend fun byId(id: String): Gasto?

    @Query("SELECT * FROM gastos WHERE deleted = 0 ORDER BY data DESC")
    fun observeAll(): Flow<List<Gasto>>

    @Query("SELECT * FROM gastos WHERE dirty = 1")
    suspend fun dirty(): List<Gasto>

    @Query("SELECT * FROM gastos WHERE deleted = 0 AND turnoId IS NULL AND data = :data")
    suspend fun soltos(data: String): List<Gasto>

    /** Só limpa a marca se o registro não mudou desde o envio. */
    @Query("UPDATE gastos SET dirty = 0 WHERE id = :id AND updatedAt = :updatedAt")
    suspend fun markClean(id: String, updatedAt: Long)
}

@Dao
interface MetaDao {
    @Upsert
    suspend fun upsert(item: Meta)

    @Query("SELECT * FROM metas WHERE id = :id")
    suspend fun byId(id: String): Meta?

    @Query("SELECT * FROM metas WHERE deleted = 0 ORDER BY data DESC")
    fun observeAll(): Flow<List<Meta>>

    @Query("SELECT * FROM metas WHERE dirty = 1")
    suspend fun dirty(): List<Meta>

    /** Só limpa a marca se o registro não mudou desde o envio. */
    @Query("UPDATE metas SET dirty = 0 WHERE id = :id AND updatedAt = :updatedAt")
    suspend fun markClean(id: String, updatedAt: Long)
}

@Dao
interface AnotacaoDao {
    @Upsert
    suspend fun upsert(item: Anotacao)

    @Query("SELECT * FROM anotacoes WHERE id = :id")
    suspend fun byId(id: String): Anotacao?

    @Query("SELECT * FROM anotacoes WHERE deleted = 0 ORDER BY criadaEm DESC")
    fun observeAll(): Flow<List<Anotacao>>

    @Query("SELECT * FROM anotacoes WHERE dirty = 1")
    suspend fun dirty(): List<Anotacao>

    /** Só limpa a marca se o registro não mudou desde o envio. */
    @Query("UPDATE anotacoes SET dirty = 0 WHERE id = :id AND updatedAt = :updatedAt")
    suspend fun markClean(id: String, updatedAt: Long)
}

@Dao
interface PlanejamentoDao {
    @Upsert
    suspend fun upsert(item: Planejamento)

    @Query("SELECT * FROM planejamento WHERE id = :id")
    suspend fun byId(id: String): Planejamento?

    @Query("SELECT * FROM planejamento WHERE deleted = 0 ORDER BY id")
    fun observeAll(): Flow<List<Planejamento>>

    @Query("SELECT * FROM planejamento WHERE dirty = 1")
    suspend fun dirty(): List<Planejamento>

    @Query("UPDATE planejamento SET dirty = 0 WHERE id = :id AND updatedAt = :updatedAt")
    suspend fun markClean(id: String, updatedAt: Long)
}

@Dao
interface ContaFixaDao {
    @Upsert
    suspend fun upsert(item: ContaFixa)

    @Query("SELECT * FROM contas_fixas WHERE id = :id")
    suspend fun byId(id: String): ContaFixa?

    @Query("SELECT * FROM contas_fixas WHERE deleted = 0 ORDER BY diaVencimento")
    fun observeAll(): Flow<List<ContaFixa>>

    @Query("SELECT * FROM contas_fixas WHERE dirty = 1")
    suspend fun dirty(): List<ContaFixa>

    @Query("UPDATE contas_fixas SET dirty = 0 WHERE id = :id AND updatedAt = :updatedAt")
    suspend fun markClean(id: String, updatedAt: Long)
}

@Dao
interface LimiteDao {
    @Upsert
    suspend fun upsert(item: Limite)

    @Query("SELECT * FROM limites WHERE id = :id")
    suspend fun byId(id: String): Limite?

    @Query("SELECT * FROM limites WHERE deleted = 0 ORDER BY nome")
    fun observeAll(): Flow<List<Limite>>

    @Query("SELECT * FROM limites WHERE dirty = 1")
    suspend fun dirty(): List<Limite>

    @Query("UPDATE limites SET dirty = 0 WHERE id = :id AND updatedAt = :updatedAt")
    suspend fun markClean(id: String, updatedAt: Long)
}

/** Consultas extras de turno. */
@Dao
interface TurnoQueries {
    @Query("SELECT * FROM turnos WHERE deleted = 0 AND estado IN ('ATIVO','PAUSADO','CAFE') ORDER BY inicio DESC LIMIT 1")
    fun observeAtual(): Flow<Turno?>

    /** Turno em andamento ou fechado aguardando "Concluir" (o que o dashboard mostra). */
    @Query("SELECT * FROM turnos WHERE deleted = 0 AND estado IN ('PRONTO','ATIVO','PAUSADO','CAFE','FECHADO') ORDER BY data DESC, numero DESC, inicio DESC LIMIT 1")
    fun observeCorrente(): Flow<Turno?>
}

/** Quantos registros ainda não foram enviados ao Firestore (indicador de sincronização). */
@Dao
interface SyncQueries {
    @Query(
        "SELECT (SELECT COUNT(*) FROM turnos WHERE dirty = 1) + (SELECT COUNT(*) FROM ganhos WHERE dirty = 1) + " +
            "(SELECT COUNT(*) FROM gastos WHERE dirty = 1) + (SELECT COUNT(*) FROM metas WHERE dirty = 1) + " +
            "(SELECT COUNT(*) FROM anotacoes WHERE dirty = 1) + (SELECT COUNT(*) FROM planejamento WHERE dirty = 1) + " +
            "(SELECT COUNT(*) FROM contas_fixas WHERE dirty = 1) + (SELECT COUNT(*) FROM limites WHERE dirty = 1)"
    )
    fun observePendentes(): Flow<Int>
}

/** Histórico da conversa com a Luna. */
@Dao
interface ChatDao {
    @androidx.room.Insert
    suspend fun inserir(m: MensagemChat): Long

    @Query("SELECT * FROM (SELECT * FROM mensagens_chat ORDER BY criadaEm DESC, id DESC LIMIT :limite) ORDER BY criadaEm, id")
    suspend fun ultimas(limite: Int): List<MensagemChat>

    @Query("SELECT * FROM (SELECT * FROM mensagens_chat ORDER BY criadaEm DESC, id DESC LIMIT :limite) ORDER BY criadaEm, id")
    fun observarUltimas(limite: Int): Flow<List<MensagemChat>>

    @Query("DELETE FROM mensagens_chat")
    suspend fun apagarTudo()
}
