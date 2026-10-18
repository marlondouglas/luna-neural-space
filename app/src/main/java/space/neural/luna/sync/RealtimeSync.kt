package space.neural.luna.sync

import android.content.Context
import android.util.Log
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Sincronização em tempo real com o app aberto (v0.1.7).
 *
 * Enquanto o app está na frente e há conta: ouvintes do Firestore (snapshot listeners) em cada coleção
 * de users/{uid}/, no turno compartilhado e nas configurações. O que o outro aparelho grava chega em ~1–2 s
 * e entra no Room (as telas observam o Room, então atualizam sozinhas). Cada alteração local sobe na hora
 * (300 ms de espera para juntar toques seguidos). O [SyncWorker] continua como reserva (sem internet,
 * app em segundo plano, falhas).
 */
object RealtimeSync {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var engine: SyncEngine? = null
    private val desligadores = mutableListOf<() -> Unit>()
    @Volatile var ativo = false
        private set
    private var envio: Job? = null
    private var app: Context? = null

    fun iniciar(ctx: Context) {
        app = ctx.applicationContext
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) { ligar(ctx) }
            override fun onStop(owner: LifecycleOwner) { desligar() }
        })
        ConfigSync.aoMudar = { pedirEnvio() }
    }

    /** Liga (ou religa, se a conta mudou) os ouvintes. Seguro chamar várias vezes. */
    @Synchronized fun ligar(ctx: Context) {
        val e = Nuvem.engine(ctx.applicationContext) ?: run { desligar(); return }
        if (ativo && engine?.uid == e.uid) return
        desligar()
        engine = e; ativo = true
        scope.launch {
            runCatching { e.sincronizar() }.onFailure { Log.w(TAG, "Sincronização inicial falhou (sem internet?)", it) }
            synchronized(this@RealtimeSync) {
                if (engine !== e) return@launch
                for (c in e.colecoes) desligadores += e.remote.ouvir(c.name, e.cursor(c.name)) { docs ->
                    scope.launch { runCatching { e.aplicar(c, docs) }.onFailure { Log.w(TAG, "Falha ao aplicar ${c.name}", it) } }
                }
                desligadores += e.remote.ouvirDoc(TurnoCompartilhado.PATH) { d -> scope.launch { runCatching { e.turno.aoReceber(d) } } }
                desligadores += e.remote.ouvirDoc(ConfigSync.PATH) { d -> scope.launch { runCatching { e.config.aoReceber(d) } } }
            }
        }
    }

    @Synchronized fun desligar() {
        desligadores.forEach { runCatching { it() } }
        desligadores.clear()
        engine = null; ativo = false
    }

    /** Depois de cada alteração local. */
    fun pedirEnvio() {
        val e = engine ?: return
        synchronized(this) {
            envio?.cancel()
            envio = scope.launch {
                delay(300)
                runCatching { e.subir() }.onFailure { Log.w(TAG, "Envio adiado para o worker", it) }
            }
        }
    }

    /** Desce e sobe tudo agora (botão de sincronizar, conflito de turno). */
    fun sincronizarAgora() { engine?.let { e -> scope.launch { runCatching { e.sincronizar() } } } }

    /** Para "Sair da conta": tenta subir o pendente. true = conseguiu. */
    suspend fun enviarAntesDeSair(ctx: Context, timeoutMs: Long = 8000): Boolean {
        val e = engine ?: Nuvem.engine(ctx) ?: return false
        return withTimeoutOrNull(timeoutMs) { runCatching { e.subir() }.isSuccess } ?: false
    }

    /** Guarda do turno: na nuvem quando há conta e internet; senão, local. */
    /** Só para testes/prints: guarda fixa (ex.: simular o outro aparelho). */
    @Volatile var guardaTeste: TurnoGuard? = null

    fun guarda(ctx: Context): TurnoGuard {
        guardaTeste?.let { return it }
        val c = ctx.applicationContext
        val e = engine ?: if (Nuvem.temInternet(c)) Nuvem.engine(c) else null
        if (e == null || !Nuvem.temInternet(c)) return TurnoGuard.Local
        return object : TurnoGuard {
            override suspend fun confirmar(alterados: List<space.neural.luna.data.local.Turno>): TurnoGuard.Resultado =
                withTimeoutOrNull(5000) {
                    runCatching { e.turno.confirmar(alterados) }.onFailure { Log.w(TAG, "Turno sem confirmação da nuvem; vale no aparelho", it) }.getOrNull()
                } ?: TurnoGuard.Resultado.Ok(enviado = false)
        }
    }

    private const val TAG = "LunaSync"
}
