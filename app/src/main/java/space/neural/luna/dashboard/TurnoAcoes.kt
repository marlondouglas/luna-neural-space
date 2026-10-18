package space.neural.luna.dashboard

import space.neural.luna.data.local.EstadoTurno
import space.neural.luna.data.local.MaquinaTurno
import space.neural.luna.data.local.Turno
import space.neural.luna.data.repo.LunaRepositories
import java.time.LocalDate

/** Ações do turno (4 turnos por dia), usadas pelo dashboard e pela conversa com a Luna. */
class TurnoAcoes(private val repo: LunaRepositories) {
    private fun agora() = System.currentTimeMillis()

    suspend fun iniciar(s: MaquinaTurno.Situacao): Turno {
        val t = MaquinaTurno.iniciar(s, LocalDate.now().toString(), agora())
        repo.salvarTurno(t)
        return t
    }
    suspend fun pausar(t: Turno) = repo.mudarEstado(t, EstadoTurno.PAUSADO)
    suspend fun cafe(t: Turno) = repo.mudarEstado(t, EstadoTurno.CAFE)
    /** Retomar depois de pausa/café, ou reabrir o 4º turno (estende o turno 4). */
    suspend fun retomar(t: Turno) = repo.mudarEstado(t, EstadoTurno.ATIVO)

    /** Fecha o turno e liga a ele os lançamentos soltos do dia. Não abre nada: o próximo turno fica pronto. */
    suspend fun fechar(t: Turno) {
        repo.mudarEstado(t, EstadoTurno.FECHADO)
        repo.vincularSoltos(t.id, t.data)
        val hoje = LocalDate.now().toString()
        if (hoje != t.data) repo.vincularSoltos(t.id, hoje)
    }

    /** Zerar tempo: para o contador do turno atual e volta a 00:00:00. */
    suspend fun zerar(t: Turno) = repo.salvarTurno(MaquinaTurno.zerar(t, agora()))

    /** Voltar para o turno anterior (desfaz o último "Fechar turno"). Retorna o turno reaberto, ou null. */
    suspend fun voltar(s: MaquinaTurno.Situacao, turnos: List<Turno>): Turno? {
        val dia = MaquinaTurno.doDia(turnos, s.turno?.data ?: LocalDate.now().toString())
        val v = MaquinaTurno.voltar(s, dia, agora()) ?: return null
        v.excluir?.let { repo.religar(it.id, v.reaberto.id); repo.excluirTurno(it.id) }
        repo.salvarTurno(v.reaberto)
        return v.reaberto
    }

    /** Zerar o dia: apaga todos os turnos de hoje (volta ao Turno 1/4). Ganhos e gastos do dia ficam, soltos no dia. */
    suspend fun zerarDia(turnos: List<Turno>, s: MaquinaTurno.Situacao? = null): Int {
        val hoje = LocalDate.now().toString()
        val doDia = (MaquinaTurno.doDia(turnos, hoje) + listOfNotNull(s?.turno?.takeIf { it.data == hoje })).distinctBy { it.id }
        for (t in doDia) { repo.religar(t.id, null); repo.excluirTurno(t.id) }
        return doDia.size
    }

    /** Concluir dia: fecha o que estiver aberto e manda todos os turnos do dia para o histórico. */
    suspend fun concluirDia(turnos: List<Turno>, s: MaquinaTurno.Situacao) {
        val hoje = LocalDate.now().toString()
        val doDia = (MaquinaTurno.doDia(turnos, hoje) + listOfNotNull(s.turno)).distinctBy { it.id }
        // Lançamentos soltos do dia vão para o último turno trabalhado, para saírem do resumo junto.
        doDia.lastOrNull { it.estado != EstadoTurno.PRONTO }?.let { repo.vincularSoltos(it.id, hoje) }
        for (t0 in doDia) {
            val t = repo.turnoPorId(t0.id) ?: t0
            when (t.estado) {
                EstadoTurno.CONCLUIDO -> Unit
                EstadoTurno.PRONTO -> repo.excluirTurno(t.id) // nunca começou
                EstadoTurno.FECHADO -> repo.mudarEstado(t, EstadoTurno.CONCLUIDO)
                else -> { fechar(t); repo.turnoPorId(t.id)?.let { repo.mudarEstado(it, EstadoTurno.CONCLUIDO) } }
            }
        }
    }
}
