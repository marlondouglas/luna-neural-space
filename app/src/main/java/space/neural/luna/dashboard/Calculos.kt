package space.neural.luna.dashboard

import space.neural.luna.data.local.AppCorrida
import space.neural.luna.data.local.CategoriaGasto
import space.neural.luna.data.local.EstadoTurno
import space.neural.luna.data.local.Ganho
import space.neural.luna.data.local.Gasto
import space.neural.luna.data.local.Meta
import space.neural.luna.data.local.TipoMeta
import space.neural.luna.data.local.Turno
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters

/** Cálculos do dashboard. Sem dependência de Android, para poder testar. */
object Calculos {

    /** Tempo de trabalho do turno até [agora] (sem pausa e sem café). Igual ao contador da orb. */
    fun trabalhoMs(t: Turno?, agora: Long): Long {
        if (t == null) return 0
        return t.trabalhoMs + if (t.estado == EstadoTurno.ATIVO) (agora - t.ultimoEvento).coerceAtLeast(0) else 0
    }

    /** Minutos parado (pausa + café) até [agora]. */
    fun paradoMs(t: Turno?, agora: Long): Long {
        if (t == null) return 0
        val emPausa = t.estado == EstadoTurno.PAUSADO || t.estado == EstadoTurno.CAFE
        return t.paradoMs + if (emPausa) (agora - t.ultimoEvento).coerceAtLeast(0) else 0
    }

    /**
     * Lançamentos que contam no "dia atual" do dashboard: os do dia [hoje] que não
     * pertencem a um turno já concluído, mais os do turno corrente (mesmo que ele
     * tenha começado ontem). Depois de "Concluir", o resumo volta a zero e o
     * histórico guarda o dia.
     */
    fun <T> sessao(
        itens: List<T>, hoje: String, corrente: Turno?, concluidos: Set<String>,
        data: (T) -> String, turnoId: (T) -> String?,
    ): List<T> = itens.filter {
        val tid = turnoId(it)
        (corrente != null && tid == corrente.id) ||
            (data(it) == hoje && (tid == null || tid !in concluidos))
    }

    data class Resumo(
        val brutoCentavos: Long,
        val gastosCentavos: Long,
        val energiaCentavos: Long,
        /** kWh lançados nas recargas do dia (0 se nenhuma recarga foi por kWh). */
        val energiaKwh: Double,
        val energiaInformadaCentavos: Long,
        val energiaCalculadaCentavos: Long,
        val manutencaoCentavos: Long,
        val outrosCentavos: Long,
        val temRecarga: Boolean,
        val temGanho: Boolean,
    ) {
        val liquidoCentavos: Long get() = brutoCentavos - gastosCentavos
    }

    fun resumo(ganhos: List<Ganho>, gastos: List<Gasto>): Resumo {
        val energia = gastos.filter { it.categoria == CategoriaGasto.ENERGIA }
        return Resumo(
            brutoCentavos = ganhos.sumOf { it.valorCentavos },
            gastosCentavos = gastos.sumOf { it.valorCentavos },
            energiaCentavos = energia.sumOf { it.valorCentavos },
            energiaKwh = energia.sumOf { it.kwh ?: 0.0 },
            energiaInformadaCentavos = energia.filter { it.kwh == null }.sumOf { it.valorCentavos },
            energiaCalculadaCentavos = energia.filter { it.kwh != null }.sumOf { it.valorCentavos },
            manutencaoCentavos = gastos.filter { it.categoria == CategoriaGasto.MANUTENCAO }.sumOf { it.valorCentavos },
            outrosCentavos = gastos.filter { it.categoria != CategoriaGasto.ENERGIA && it.categoria != CategoriaGasto.MANUTENCAO }
                .sumOf { it.valorCentavos },
            temRecarga = energia.isNotEmpty(),
            temGanho = ganhos.isNotEmpty(),
        )
    }

    /** Ganho por hora (líquido ÷ horas de trabalho), em centavos/h. Null com menos de 15 min de trabalho. */
    fun porHoraCentavos(liquidoCentavos: Long, trabalhoMs: Long): Long? =
        if (trabalhoMs < 15 * 60_000L) null else liquidoCentavos * 3_600_000L / trabalhoMs

    /** Sugestão de parar: turno ativo, pelo menos 2 h de trabalho e ganho por hora abaixo do limite. */
    fun sugerirParar(turno: Turno?, trabalhoMs: Long, porHora: Long?, limiteCentavosHora: Long): Boolean =
        turno?.estado == EstadoTurno.ATIVO && trabalhoMs >= 2 * 3_600_000L &&
            porHora != null && porHora < limiteCentavosHora

    // ---- Metas ----

    /** Semana começa na segunda-feira. */
    fun periodo(tipo: String, hoje: LocalDate): Pair<LocalDate, LocalDate> = when (tipo) {
        TipoMeta.SEMANA -> {
            val ini = hoje.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
            ini to ini.plusDays(6)
        }
        TipoMeta.MES -> hoje.withDayOfMonth(1) to hoje.with(TemporalAdjusters.lastDayOfMonth())
        else -> hoje to hoje
    }

    /** A meta mais recente de cada tipo vale até ser trocada. */
    fun metaVigente(metas: List<Meta>, tipo: String): Meta? =
        metas.filter { it.tipo == tipo && !it.deleted }
            .maxWithOrNull(compareBy<Meta>({ it.data }, { it.updatedAt }))

    data class ProgressoMeta(
        val tipo: String,
        val inicio: LocalDate,
        val metaCentavos: Long?,
        val liquidoCentavos: Long,
        val brutoCentavos: Long,
    ) {
        /** Percentual atingido (pode passar de 100). 0 sem meta. */
        val percentual: Int get() =
            if (metaCentavos == null || metaCentavos <= 0) 0
            else ((liquidoCentavos.coerceAtLeast(0) * 100) / metaCentavos).toInt()
        val faltaCentavos: Long get() = ((metaCentavos ?: 0) - liquidoCentavos).coerceAtLeast(0)
        val acimaCentavos: Long get() = (liquidoCentavos - (metaCentavos ?: 0)).coerceAtLeast(0)
    }

    /** Progresso pelo líquido (bruto − gastos) de todos os lançamentos do período. */
    fun progresso(
        tipo: String, hoje: LocalDate, metas: List<Meta>, ganhos: List<Ganho>, gastos: List<Gasto>,
    ): ProgressoMeta {
        val (ini, fim) = periodo(tipo, hoje)
        val a = ini.toString(); val b = fim.toString()
        val bruto = ganhos.filter { it.data in a..b }.sumOf { it.valorCentavos }
        val gasto = gastos.filter { it.data in a..b }.sumOf { it.valorCentavos }
        return ProgressoMeta(tipo, ini, metaVigente(metas, tipo)?.valorCentavos, bruto - gasto, bruto)
    }

    /** Limiar atingido para o alerta motivacional: 100, 80 ou null. */
    fun limiar(p: ProgressoMeta): Int? = when {
        p.metaCentavos == null || p.metaCentavos <= 0 -> null
        p.percentual >= 100 -> 100
        p.percentual >= 80 -> 80
        else -> null
    }

    // ---- Ganhos por app ----

    data class PorApp(val app: String, val centavos: Long, val corridas: Int, val participacao: Int)

    val APPS = listOf(
        AppCorrida.NOVENTA_E_NOVE,
        AppCorrida.UBER,
        AppCorrida.INDRIVE,
        AppCorrida.GOGETA,
        AppCorrida.PARTICULAR,
        AppCorrida.OUTRO,
    )

    fun porApp(ganhos: List<Ganho>): List<PorApp> {
        val total = ganhos.sumOf { it.valorCentavos }
        return APPS.map { app ->
            val g = ganhos.filter { it.app == app }
            val v = g.sumOf { it.valorCentavos }
            PorApp(app, v, g.size, if (total > 0) ((v * 100) / total).toInt() else 0)
        }
    }

    // ---- Entrada de valores ----

    /** "12,50", "12.50", "1.234,56", "R$ 30" -> centavos. Null se inválido ou ≤ 0. */
    fun parseCentavos(texto: String): Long? {
        var s = texto.trim().replace("R$", "").replace(" ", "")
        if (s.isEmpty()) return null
        if (s.contains(',')) s = s.replace(".", "").replace(',', '.')
        val v = s.toBigDecimalOrNull() ?: return null
        val c = v.movePointRight(2).setScale(0, java.math.RoundingMode.HALF_UP).toLong()
        return c.takeIf { it > 0 }
    }

    fun parseDecimal(texto: String): Double? {
        var s = texto.trim().replace(" ", "")
        if (s.contains(',')) s = s.replace(".", "").replace(',', '.')
        return s.toDoubleOrNull()?.takeIf { it > 0 }
    }
}
