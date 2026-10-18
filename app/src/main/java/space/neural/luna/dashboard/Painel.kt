package space.neural.luna.dashboard

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import org.json.JSONObject
import space.neural.luna.data.local.EstadoTurno
import space.neural.luna.data.local.Ganho
import space.neural.luna.data.local.Gasto
import space.neural.luna.data.local.Meta
import space.neural.luna.data.local.TipoMeta
import space.neural.luna.data.local.Turno
import space.neural.luna.data.repo.LunaRepositories
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale

/** Tudo o que o dashboard mostra, calculado a partir do banco local. */
data class Painel(
    val hoje: LocalDate,
    val turno: Turno?,
    val ganhosSessao: List<Ganho>,
    val gastosSessao: List<Gasto>,
    val resumo: Calculos.Resumo,
    val porApp: List<Calculos.PorApp>,
    val metas: List<Calculos.ProgressoMeta>,
    val semana: List<GanhosChart.Dia>,
    val pendentes: Int,
) {
    /** Resumo para a conversa com a Luna (vai para o servidor de chat; sem dados pessoais além dos números). */
    fun contexto(agora: Long = System.currentTimeMillis()): JSONObject = JSONObject().apply {
        val trab = Calculos.trabalhoMs(turno, agora)
        put("hoje", hoje.toString())
        put("dia_da_semana", hoje.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.forLanguageTag("pt-BR")))
        put("turno", when (turno?.estado) {
            EstadoTurno.ATIVO -> "trabalhando"; EstadoTurno.PAUSADO -> "pausado"; EstadoTurno.CAFE -> "no café"
            EstadoTurno.FECHADO -> "fechado (falta concluir)"; else -> "sem turno"
        })
        put("tempo_trabalho", Fmt.hms(trab))
        put("minutos_parado", Fmt.minutos(Calculos.paradoMs(turno, agora)))
        put("bruto_hoje", Fmt.reais(resumo.brutoCentavos))
        put("gastos_hoje", Fmt.reais(resumo.gastosCentavos))
        put("energia_hoje", Fmt.reais(resumo.energiaCentavos))
        put("liquido_hoje", Fmt.reais(resumo.liquidoCentavos))
        Calculos.porHoraCentavos(resumo.liquidoCentavos, trab)?.let { put("ganho_por_hora", Fmt.reais(it) + "/h") }
        put("por_app", JSONObject().apply {
            porApp.forEach { put(Fmt.nomeApp(it.app), "${Fmt.reais(it.centavos)} (${it.corridas} corridas)") }
        })
        put("metas", JSONObject().apply {
            metas.forEach { m ->
                val nome = when (m.tipo) { TipoMeta.SEMANA -> "semana"; TipoMeta.MES -> "mes"; else -> "dia" }
                put(nome, if (m.metaCentavos == null) "não definida" else
                    "meta ${Fmt.reais(m.metaCentavos)}, líquido ${Fmt.reais(m.liquidoCentavos)}, ${m.percentual}%, faltam ${Fmt.reais(m.faltaCentavos)}")
            }
        })
        put("ultimos_7_dias_bruto", JSONObject().apply { semana.forEach { put(it.rotulo, Fmt.reais(it.porApp.values.sum())) } })
    }

    companion object {
        private fun hojeFlow(): Flow<LocalDate> = flow {
            while (true) { emit(LocalDate.now()); delay(30_000) }
        }.distinctUntilChanged()

        fun observar(repo: LunaRepositories): Flow<Painel> = combine(
            listOf(hojeFlow(), repo.observeTurnoCorrente(), repo.observeTurnos(), repo.observeGanhos(),
                repo.observeGastos(), repo.observeMetas(), repo.observePendentes()),
        ) { a ->
            @Suppress("UNCHECKED_CAST")
            montar(
                a[0] as LocalDate, a[1] as Turno?, a[2] as List<Turno>, a[3] as List<Ganho>,
                a[4] as List<Gasto>, a[5] as List<Meta>, a[6] as Int,
            )
        }

        fun montar(
            hoje: LocalDate, corrente: Turno?, turnos: List<Turno>, ganhos: List<Ganho>,
            gastos: List<Gasto>, metas: List<Meta>, pendentes: Int,
        ): Painel {
            val h = hoje.toString()
            val concluidos = turnos.filter { it.estado == EstadoTurno.CONCLUIDO }.map { it.id }.toSet()
            val gs = Calculos.sessao(ganhos, h, corrente, concluidos, { it.data }, { it.turnoId })
            val ds = Calculos.sessao(gastos, h, corrente, concluidos, { it.data }, { it.turnoId })
            val pt = Locale.forLanguageTag("pt-BR")
            val semana = (6 downTo 0).map { hoje.minusDays(it.toLong()) }.map { d ->
                val ds2 = d.toString()
                GanhosChart.Dia(
                    d.dayOfWeek.getDisplayName(TextStyle.SHORT, pt).replace(".", "").take(3),
                    Calculos.APPS.associateWith { app -> ganhos.filter { it.data == ds2 && it.app == app }.sumOf { it.valorCentavos } },
                )
            }
            return Painel(
                hoje, corrente, gs, ds, Calculos.resumo(gs, ds), Calculos.porApp(gs),
                listOf(TipoMeta.DIA, TipoMeta.SEMANA, TipoMeta.MES).map { Calculos.progresso(it, hoje, metas, ganhos, gastos) },
                semana, pendentes,
            )
        }
    }
}
