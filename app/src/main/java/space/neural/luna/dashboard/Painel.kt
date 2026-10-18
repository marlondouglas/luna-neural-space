package space.neural.luna.dashboard

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import org.json.JSONObject
import space.neural.luna.data.local.ContaFixa
import space.neural.luna.data.local.EstadoTurno
import space.neural.luna.data.local.Limite
import space.neural.luna.data.local.MaquinaTurno
import space.neural.luna.data.local.Planejamento
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
    val ganhosTodos: List<Ganho> = emptyList(),
    val gastosTodos: List<Gasto> = emptyList(),
    val turnosTodos: List<Turno> = emptyList(),
    val metasTodas: List<Meta> = emptyList(),
    val situacao: MaquinaTurno.Situacao = MaquinaTurno.Situacao(1, MaquinaTurno.Fase.PRONTO, null, null),
    val plano: Planejamento = Planejamento(),
    val contas: List<ContaFixa> = emptyList(),
    val limites: List<Limite> = emptyList(),
    /** Gastos pessoais ligados a um envelope (não entram no líquido do trabalho). */
    val gastosLimites: List<Gasto> = emptyList(),
) {
    /** Trabalho do dia: soma dos turnos de hoje ainda não concluídos (+ o turno aberto). */
    fun trabalhoDia(agora: Long = space.neural.luna.sync.Relogio.agora()): Long {
        val h = hoje.toString()
        val dia = turnosTodos.filter { !it.deleted && it.data == h && it.estado != EstadoTurno.CONCLUIDO && it.id != turno?.id }
        return dia.sumOf { MaquinaTurno.trabalhoMs(it, agora) } + MaquinaTurno.trabalhoMs(turno, agora)
    }

    /** Envelopes do mês: gasto, limite e quanto ainda pode gastar. */
    fun envelopes(): List<Plano.Envelope> = Plano.envelopes(limites, gastosLimites, hoje)

    fun sugestao(): Plano.Sugestao = Plano.sugerir(plano, contas, limites, hoje)

    /** Plano refeito para o resto do mês (o que passou dos envelopes entra no necessário). */
    fun recalculo(): Plano.Recalculo {
        val dia = metas.first { it.tipo == TipoMeta.DIA }.liquidoCentavos
        val sem = metas.first { it.tipo == TipoMeta.SEMANA }.liquidoCentavos
        val mes = metas.first { it.tipo == TipoMeta.MES }.liquidoCentavos
        return Plano.recalcular(plano, contas, limites, envelopes(), mes - dia, hoje, sem - dia)
    }

    fun estatisticas(p: Periodo, agora: Long = space.neural.luna.sync.Relogio.agora()) =
        Estatisticas.calcular(p, hoje, agora, ganhosTodos, gastosTodos, turnosTodos, metasTodas)

    /** Resumo para a conversa com a Luna (vai para o servidor de chat; sem dados pessoais além dos números). */
    fun contexto(agora: Long = space.neural.luna.sync.Relogio.agora()): JSONObject = JSONObject().apply {
        val trab = trabalhoDia(agora)
        put("hoje", hoje.toString())
        put("turno_numero", "${situacao.numero}/${MaquinaTurno.TOTAL}")
        put("dia_da_semana", hoje.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.forLanguageTag("pt-BR")))
        put("turno", when (turno?.estado) {
            EstadoTurno.ATIVO -> "trabalhando"; EstadoTurno.PAUSADO -> "pausado"; EstadoTurno.CAFE -> "no café"
            EstadoTurno.FECHADO -> "turno 4 fechado (pode retomar ou concluir o dia)"; EstadoTurno.PRONTO -> "pronto para iniciar"
            else -> if (situacao.fase == MaquinaTurno.Fase.CONCLUIDO) "dia concluído" else "turno ${situacao.numero} pronto para iniciar"
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
        put("envelopes", JSONObject().apply {
            envelopes().forEach { put(it.limite.nome, "gasto ${Fmt.reais(it.gastoCentavos)} de ${Fmt.reais(it.limite.limiteCentavos)}, pode gastar ${Fmt.reais(it.restanteCentavos)}") }
        })
        put("ultimos_7_dias_bruto", JSONObject().apply { semana.forEach { put(it.rotulo, Fmt.reais(it.porApp.values.sum())) } })
    }

    companion object {
        private fun hojeFlow(): Flow<LocalDate> = flow {
            while (true) { emit(LocalDate.now()); delay(30_000) }
        }.distinctUntilChanged()

        fun observar(repo: LunaRepositories): Flow<Painel> = combine(
            listOf(hojeFlow(), repo.observeTurnos(), repo.observeGanhos(),
                repo.observeGastos(), repo.observeMetas(), repo.observePendentes(),
                repo.observePlanejamento(), repo.observeContasFixas(), repo.observeLimites()),
        ) { a ->
            @Suppress("UNCHECKED_CAST")
            montar(
                a[0] as LocalDate, a[1] as List<Turno>, a[2] as List<Ganho>,
                a[3] as List<Gasto>, a[4] as List<Meta>, a[5] as Int,
                a[6] as Planejamento?, a[7] as List<ContaFixa>, a[8] as List<Limite>,
            )
        }

        fun montar(
            hoje: LocalDate, turnos: List<Turno>, ganhos: List<Ganho>,
            todosGastos: List<Gasto>, metas: List<Meta>, pendentes: Int,
            plano: Planejamento? = null, contas: List<ContaFixa> = emptyList(), limites: List<Limite> = emptyList(),
        ): Painel {
            val h = hoje.toString()
            val situacao = MaquinaTurno.situacao(turnos, h)
            val corrente = situacao.turno?.takeIf { it.estado != EstadoTurno.CONCLUIDO }
            val idsLimites = limites.map { it.id }.toSet()
            // Gasto com envelope é pessoal (supermercado, lanche): fica fora do líquido do trabalho.
            val gastos = todosGastos.filter { it.limiteId == null }
            val gastosLimites = todosGastos.filter { it.limiteId != null && it.limiteId in idsLimites }
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
                semana, pendentes, ganhos, gastos, turnos, metas,
                situacao, plano ?: Planejamento(), contas, limites, gastosLimites,
            )
        }
    }
}
