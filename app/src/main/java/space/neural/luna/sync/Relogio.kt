package space.neural.luna.sync

import android.content.Context

/**
 * Relógio corrigido pelo servidor (v0.1.7). Centrais de carro costumam estar com a hora errada; como o
 * "último a alterar vence" e o contador do turno usam horários de relógio, todo carimbo de tempo do app
 * passa por aqui: agora = relógio do aparelho + diferença medida contra a hora do Firestore.
 */
object Relogio {
    private const val PREFS = "relogio"
    @Volatile var offsetMs: Long = 0L
        private set

    /** Só para testes: relógio fixo/simulado. */
    @Volatile var fonte: () -> Long = { System.currentTimeMillis() }

    fun agora(): Long = fonte() + offsetMs

    fun carregar(ctx: Context) { offsetMs = ctx.getSharedPreferences(PREFS, 0).getLong("offset", 0L) }

    /**
     * Ajusta com uma medição: [servidorMs] lido entre [antes] e [depois] (relógio do aparelho).
     * Diferenças menores que 1 s são ignoradas (ruído da rede).
     */
    fun ajustar(ctx: Context?, servidorMs: Long, antes: Long, depois: Long) {
        val novo = calcularOffset(servidorMs, antes, depois)
        if (kotlin.math.abs(novo - offsetMs) < 1000) return
        offsetMs = novo
        ctx?.getSharedPreferences(PREFS, 0)?.edit()?.putLong("offset", novo)?.putLong("medido_em", depois)?.apply()
    }

    fun calcularOffset(servidorMs: Long, antes: Long, depois: Long): Long = servidorMs - (antes + (depois - antes) / 2)

    fun medidoEm(ctx: Context): Long = ctx.getSharedPreferences(PREFS, 0).getLong("medido_em", 0L)

    internal fun redefinirParaTestes() { offsetMs = 0; fonte = { System.currentTimeMillis() } }
}
