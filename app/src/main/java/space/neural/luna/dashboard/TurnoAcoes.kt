package space.neural.luna.dashboard

import space.neural.luna.data.local.EstadoTurno
import space.neural.luna.data.local.MaquinaTurno
import space.neural.luna.data.local.Turno
import space.neural.luna.data.repo.LunaRepositories
import space.neural.luna.sync.TurnoGuard
import space.neural.luna.sync.TurnoNoOutroAparelho
import java.time.LocalDate

/**
 * Ações do card de turno (usadas pelo painel e pela Luna).
 *
 * v0.1.7: cada ação calcula os turnos alterados (funções puras da [MaquinaTurno]) e passa pela [guarda]
 * do turno compartilhado antes de salvar. Se o turno mudou no outro aparelho, nada é salvo e a ação lança
 * [TurnoNoOutroAparelho] com a mensagem para o motorista (o estado do outro aparelho já vem para cá).
 */
class TurnoAcoes(private val repo: LunaRepositories, private val guarda: () -> TurnoGuard = { TurnoGuard.Local }) {
    private fun agora() = repo.now()
    private fun hoje() = LocalDate.now().toString()

    /** Carimba, confirma na guarda e salva. Lança [TurnoNoOutroAparelho] se o outro aparelho mexeu antes. */
    private suspend fun confirmar(alterados: List<Turno>): List<Turno> {
        if (alterados.isEmpty()) return alterados
        val agora = agora()
        val lista = alterados.map { it.copy(updatedAt = agora, dirty = true) }
        when (val r = guarda().confirmar(lista)) {
            is TurnoGuard.Resultado.Conflito -> throw TurnoNoOutroAparelho(r.mensagem)
            is TurnoGuard.Resultado.Ok -> repo.salvarTurnosConfirmados(lista, r.enviado)
        }
        return lista
    }

    private suspend fun mudar(t: Turno, estado: String) = confirmar(listOf(MaquinaTurno.mudar(t, estado, agora()))).first()

    suspend fun iniciar(s: MaquinaTurno.Situacao): Turno = confirmar(listOf(MaquinaTurno.iniciar(s, hoje(), agora()))).first()
    suspend fun pausar(t: Turno) { mudar(t, EstadoTurno.PAUSADO) }
    suspend fun cafe(t: Turno) { mudar(t, EstadoTurno.CAFE) }
    /** Retomar depois de pausa/café, ou reabrir o 4º turno (estende o turno 4). */
    suspend fun retomar(t: Turno) { mudar(t, EstadoTurno.ATIVO) }

    /** Fecha o turno e liga a ele os lançamentos soltos do dia. Não abre nada: o próximo turno fica pronto. */
    suspend fun fechar(t: Turno) {
        mudar(t, EstadoTurno.FECHADO)
        repo.vincularSoltos(t.id, t.data)
        val hoje = hoje()
        if (hoje != t.data) repo.vincularSoltos(t.id, hoje)
    }

    /** Zerar tempo: para o contador do turno atual e volta a 00:00:00. */
    suspend fun zerar(t: Turno) { confirmar(listOf(MaquinaTurno.zerar(t, agora()))) }

    /** Voltar para o turno anterior (desfaz o último "Fechar turno"). Retorna o turno reaberto, ou null. */
    suspend fun voltar(s: MaquinaTurno.Situacao, turnos: List<Turno>): Turno? {
        val dia = MaquinaTurno.doDia(turnos, s.turno?.data ?: hoje())
        val v = MaquinaTurno.voltar(s, dia, agora()) ?: return null
        confirmar(listOfNotNull(v.excluir?.copy(deleted = true), v.reaberto))
        v.excluir?.let { repo.religar(it.id, v.reaberto.id) }
        return repo.turnoPorId(v.reaberto.id) ?: v.reaberto
    }

    /** Zerar o dia: apaga todos os turnos de hoje (volta ao Turno 1/4). Ganhos e gastos do dia ficam, soltos no dia. */
    suspend fun zerarDia(turnos: List<Turno>, s: MaquinaTurno.Situacao? = null): Int {
        val hoje = hoje()
        val doDia = (MaquinaTurno.doDia(turnos, hoje) + listOfNotNull(s?.turno?.takeIf { it.data == hoje })).distinctBy { it.id }
        confirmar(doDia.map { it.copy(deleted = true) })
        for (t in doDia) repo.religar(t.id, null)
        return doDia.size
    }

    /** Concluir dia: fecha o que estiver aberto e manda todos os turnos do dia para o histórico. */
    suspend fun concluirDia(turnos: List<Turno>, s: MaquinaTurno.Situacao) {
        val hoje = hoje()
        val doDia = (MaquinaTurno.doDia(turnos, hoje) + listOfNotNull(s.turno)).distinctBy { it.id }
            .map { repo.turnoPorId(it.id) ?: it }
        val agora = agora()
        val abertos = mutableListOf<Turno>()
        val alterados = doDia.mapNotNull { t ->
            when (t.estado) {
                EstadoTurno.CONCLUIDO -> null
                EstadoTurno.PRONTO -> t.copy(deleted = true) // nunca começou
                EstadoTurno.FECHADO -> MaquinaTurno.mudar(t, EstadoTurno.CONCLUIDO, agora)
                else -> { abertos += t; MaquinaTurno.mudar(MaquinaTurno.mudar(t, EstadoTurno.FECHADO, agora), EstadoTurno.CONCLUIDO, agora) }
            }
        }
        confirmar(alterados)
        // Lançamentos soltos do dia vão para o último turno trabalhado, para saírem do resumo junto.
        doDia.lastOrNull { it.estado != EstadoTurno.PRONTO }?.let { repo.vincularSoltos(it.id, hoje) }
        for (t in abertos) repo.vincularSoltos(t.id, t.data)
    }
}
