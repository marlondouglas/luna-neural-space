package space.neural.luna

import android.app.Application
import space.neural.luna.sync.ConfigSync
import space.neural.luna.sync.Nuvem
import space.neural.luna.sync.PrimeiraCarga
import space.neural.luna.sync.RealtimeSync
import space.neural.luna.sync.Relogio
import space.neural.luna.sync.SyncManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class LunaApp : Application() {
    private val voiceSetup = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        // v0.1.6: recomeço do zero (uma única vez), antes de abrir o banco ou sincronizar.
        // Só apaga dados LOCAIS: nada na nuvem é apagado (users/{uid} continua lá para os outros aparelhos).
        space.neural.luna.licenca.Limpeza016.executarSeNecessario(this) {
            if (BuildConfig.FIREBASE_CONFIGURED) com.google.firebase.auth.FirebaseAuth.getInstance().signOut()
        }
        Relogio.carregar(this)
        // v0.1.6: conta obrigatória + teste grátis de 7 dias controlado pelo servidor.
        space.neural.luna.licenca.Licenca.iniciar(this)
        // v0.1.7: migração (quem já estava logado é dono dos dados locais e já tinha baixado a nuvem).
        Nuvem.uid(this)?.let { uid ->
            space.neural.luna.conta.TrocaConta.migrar(this, uid)
            PrimeiraCarga.migrar(this, uid)
        }
        ConfigSync.migrar(this)
        ConfigSync.iniciar(this)
        // Sem google-services.json o worker termina na hora, sem fazer nada.
        SyncManager.schedulePeriodic(this)
        SyncManager.requestSync(this, agora = true)
        // v0.1.7: tempo real enquanto o app está aberto.
        RealtimeSync.iniciar(this)

        // A voz escolhida vem no próprio APK. Instale-a em segundo plano para que a Luna
        // esteja pronta sem expor controles técnicos nas configurações comuns.
        voiceSetup.launch {
            runCatching {
                val context = applicationContext
                if (!space.neural.luna.voz.SupertonicVoice.bundled(context)) return@runCatching
                val target = space.neural.luna.voz.SupertonicVoice.modelDirectory(context)
                val ready = space.neural.luna.voz.SupertonicModelFiles.required.all { (name, entry) ->
                    space.neural.luna.voz.SupertonicModelFiles.verified(java.io.File(target, name), entry)
                }
                if (!ready) space.neural.luna.voz.SupertonicVoice.installBundled(context)
                space.neural.luna.voz.SupertonicVoice.select(context, true)
            }.onFailure { error ->
                space.neural.luna.voz.LocalVoiceRuntime.reportUnavailable(
                    error.message ?: "Não foi possível preparar a voz da Luna neste aparelho."
                )
            }
        }
    }
}
