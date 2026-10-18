package space.neural.luna.data.local

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.util.UUID

/**
 * Campos comuns a todo registro sincronizável.
 *
 * - [updatedAt]: hora (ms) da última alteração feita no aparelho; decide conflitos.
 * - [dirty]: true enquanto a alteração local ainda não foi enviada ao Firestore.
 * - [deleted]: exclusão lógica, para que a exclusão também seja sincronizada.
 */
interface Syncable {
    val id: String
    val updatedAt: Long
    val dirty: Boolean
    val deleted: Boolean
}

fun newId(): String = UUID.randomUUID().toString()

/** Estados possíveis de um turno. */
object EstadoTurno {
    /** Turno criado (ou zerado) e ainda não iniciado: "pronto para iniciar". */
    const val PRONTO = "PRONTO"
    const val ATIVO = "ATIVO"
    const val PAUSADO = "PAUSADO"
    const val CAFE = "CAFE"
    const val FECHADO = "FECHADO"
    const val CONCLUIDO = "CONCLUIDO"
}

/** Período de uma meta. */
object TipoMeta {
    const val DIA = "DIA"
    const val SEMANA = "SEMANA"
    const val MES = "MES"
}

/** Categorias de gasto. */
object CategoriaGasto {
    /** Recarga elétrica do Geely (não é combustível). */
    const val ENERGIA = "ENERGIA"
    const val MANUTENCAO = "MANUTENCAO"
    const val OUTRO = "OUTRO"
}

/** Aplicativos de corrida. */
object AppCorrida {
    const val NOVENTA_E_NOVE = "99"
    const val UBER = "UBER"
    const val INDRIVE = "INDRIVE"
    const val GOGETA = "GOGETA"
    /** Corrida particular (fora dos apps). */
    const val PARTICULAR = "PARTICULAR"
    const val OUTRO = "OUTRO"
}

@Entity(tableName = "turnos", indices = [Index("data")])
data class Turno(
    @PrimaryKey override val id: String = newId(),
    /** Dia do turno no formato yyyy-MM-dd. */
    val data: String,
    val inicio: Long,
    val fim: Long? = null,
    val estado: String = EstadoTurno.ATIVO,
    /** Tempo de trabalho acumulado (sem pausa e sem café). */
    val trabalhoMs: Long = 0,
    /** Minutos parado acumulados (pausa + café). */
    val paradoMs: Long = 0,
    /**
     * Momento (relógio de parede, ms) da última mudança de estado. O tempo corrente é
     * sempre recalculado como agora − ultimoEvento, então o contador fica certo mesmo
     * com o app fechado ou o aparelho reiniciado.
     */
    val ultimoEvento: Long = inicio,
    /** Número do turno no dia: 1 a 4. */
    @ColumnInfo(defaultValue = "1")
    val numero: Int = 1,
    override val updatedAt: Long = System.currentTimeMillis(),
    override val dirty: Boolean = true,
    override val deleted: Boolean = false,
) : Syncable

@Entity(tableName = "ganhos", indices = [Index("data"), Index("turnoId")])
data class Ganho(
    @PrimaryKey override val id: String = newId(),
    val turnoId: String? = null,
    val data: String,
    /** Um dos valores de [AppCorrida]. */
    val app: String,
    /** Valor bruto em centavos. */
    val valorCentavos: Long,
    val descricao: String? = null,
    override val updatedAt: Long = System.currentTimeMillis(),
    override val dirty: Boolean = true,
    override val deleted: Boolean = false,
) : Syncable

@Entity(tableName = "gastos", indices = [Index("data"), Index("turnoId")])
data class Gasto(
    @PrimaryKey override val id: String = newId(),
    val turnoId: String? = null,
    val data: String,
    /** Ex.: "ENERGIA" para recarga elétrica. */
    val categoria: String,
    val valorCentavos: Long,
    /** kWh da recarga, quando a categoria for energia. */
    val kwh: Double? = null,
    val descricao: String? = null,
    /** Envelope (conta com limite) do gasto, ex.: Supermercado. Null = gasto do trabalho. */
    val limiteId: String? = null,
    override val updatedAt: Long = System.currentTimeMillis(),
    override val dirty: Boolean = true,
    override val deleted: Boolean = false,
) : Syncable

@Entity(tableName = "metas", indices = [Index("data")])
data class Meta(
    @PrimaryKey override val id: String = newId(),
    /**
     * Dia (yyyy-MM-dd) em que a meta passou a valer. A meta mais recente de cada
     * [tipo] vale até ser trocada.
     */
    val data: String,
    val valorCentavos: Long,
    /** Um dos valores de [TipoMeta]. */
    @ColumnInfo(defaultValue = "DIA")
    val tipo: String = TipoMeta.DIA,
    override val updatedAt: Long = System.currentTimeMillis(),
    override val dirty: Boolean = true,
    override val deleted: Boolean = false,
) : Syncable

@Entity(tableName = "anotacoes")
data class Anotacao(
    @PrimaryKey override val id: String = newId(),
    val texto: String,
    val criadaEm: Long = System.currentTimeMillis(),
    override val updatedAt: Long = System.currentTimeMillis(),
    override val dirty: Boolean = true,
    override val deleted: Boolean = false,
) : Syncable

/**
 * Planejamento do mês (Configurações > Planejamento). Registro único, id [ID].
 * [diasTrabalho]: 4 semanas separadas por vírgula, cada uma com 7 dígitos seg..dom
 * (1 = trabalha), ex.: "1111110,1111110,1111110,1111100".
 */
@Entity(tableName = "planejamento")
data class Planejamento(
    @PrimaryKey override val id: String = ID,
    val diasTrabalho: String = PADRAO_DIAS,
    /** Ganho líquido típico por hora, em centavos. */
    val ganhoHoraCentavos: Long = 3000,
    /** Renda desejada no mês além das contas (opcional), em centavos. */
    val rendaDesejadaCentavos: Long? = null,
    override val updatedAt: Long = System.currentTimeMillis(),
    override val dirty: Boolean = true,
    override val deleted: Boolean = false,
) : Syncable {
    companion object {
        const val ID = "plano"
        const val PADRAO_DIAS = "1111110,1111110,1111110,1111110"
    }
}

/** Conta fixa do mês (aluguel, internet...). */
@Entity(tableName = "contas_fixas")
data class ContaFixa(
    @PrimaryKey override val id: String = newId(),
    val nome: String,
    val valorCentavos: Long,
    /** Dia do vencimento (1–31). */
    val diaVencimento: Int,
    override val updatedAt: Long = System.currentTimeMillis(),
    override val dirty: Boolean = true,
    override val deleted: Boolean = false,
) : Syncable

/** Conta com limite / envelope mensal (Supermercado R$ 1.000, Lanche R$ 200...). */
@Entity(tableName = "limites")
data class Limite(
    @PrimaryKey override val id: String = newId(),
    val nome: String,
    val limiteCentavos: Long,
    override val updatedAt: Long = System.currentTimeMillis(),
    override val dirty: Boolean = true,
    override val deleted: Boolean = false,
) : Syncable

/**
 * Mensagem da conversa com a Luna (fica só no aparelho, sobrevive a reinícios).
 * [voz] = ele falou (texto transcrito).
 */
@Entity(tableName = "mensagens_chat", indices = [Index("criadaEm")])
data class MensagemChat(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** "user" ou "assistant". */
    val role: String,
    val texto: String,
    val voz: Boolean = false,
    val criadaEm: Long = System.currentTimeMillis(),
)
