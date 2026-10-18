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

/** Consultas extras de turno. */
@Dao
interface TurnoQueries {
    @Query("SELECT * FROM turnos WHERE deleted = 0 AND estado IN ('ATIVO','PAUSADO','CAFE') ORDER BY inicio DESC LIMIT 1")
    fun observeAtual(): Flow<Turno?>
}
