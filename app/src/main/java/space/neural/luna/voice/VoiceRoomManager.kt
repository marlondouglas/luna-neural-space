package space.neural.luna.voice

import android.content.Context
import android.util.Log
import io.livekit.android.LiveKit
import io.livekit.android.events.RoomEvent
import io.livekit.android.events.collect
import io.livekit.android.room.Room
import io.livekit.android.room.track.DataPublishReliability
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Sala de voz / rádio dos parceiros (LiveKit).
 *
 * - [join]: entra na sala e liga o serviço em primeiro plano, para continuar
 *   na ligação mesmo com o app minimizado.
 * - [leave]: sai da sala e encerra o serviço.
 * - [sendText]: mensagem de texto pelo canal de dados.
 * - [startTalking]/[stopTalking]: aperte e segure para falar (push-to-talk).
 */
class VoiceRoomManager private constructor(private val appContext: Context) {

    sealed interface State {
        data object Disconnected : State
        data class Connecting(val roomName: String) : State
        data class Connected(val roomName: String) : State
        data class Error(val message: String) : State
    }

    data class TextMessage(val from: String?, val text: String, val mine: Boolean)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var room: Room? = null
    private var eventsJob: Job? = null
    private val microphoneLock = Mutex()
    private var transmissionGeneration = 0L
    private var connectionGeneration = 0L

    private val _state = MutableStateFlow<State>(State.Disconnected)
    val state: StateFlow<State> = _state.asStateFlow()

    private val _messages = MutableSharedFlow<TextMessage>(extraBufferCapacity = 64)
    val messages: SharedFlow<TextMessage> = _messages.asSharedFlow()

    private val _participants = MutableStateFlow<List<String>>(emptyList())
    val participants: StateFlow<List<String>> = _participants.asStateFlow()

    private val _talking = MutableStateFlow(false)
    val talking: StateFlow<Boolean> = _talking.asStateFlow()

    data class Speaker(val identity: String, val name: String, val local: Boolean)
    private val _activeSpeakers = MutableStateFlow<List<Speaker>>(emptyList())
    val activeSpeakers: StateFlow<List<Speaker>> = _activeSpeakers.asStateFlow()
    private fun updateSpeechRadio() = space.neural.luna.avatar.LunaVoz.radioOcupado(
        _talking.value || _activeSpeakers.value.any { !it.local })

    /** Device names are taken from currently available SDK routes; unsupported handlers return none. */
    fun availableAudioDevices(): List<String> = room?.audioSwitchHandler?.availableAudioDevices
        ?.map { it.javaClass.simpleName }?.distinct().orEmpty()

    fun selectedAudioDevice(): String? = room?.audioSwitchHandler?.selectedAudioDevice?.javaClass?.simpleName

    fun selectAudioDevice(device: String?): Boolean {
        val handler = room?.audioSwitchHandler ?: return false
        if (device == null) { handler.selectDevice(null); return true }
        val route = handler.availableAudioDevices.firstOrNull { it.javaClass.simpleName == device } ?: return false
        handler.selectDevice(route)
        return true
    }

    suspend fun join(
        roomName: String,
        identity: String,
        tokenProvider: TokenProvider = HttpTokenProvider(),
    ) {
        if (room != null || _state.value is State.Connecting) return
        val generation = ++connectionGeneration
        _state.value = State.Connecting(roomName)
        try {
            val details = tokenProvider.fetch(roomName, identity)
            if (generation != connectionGeneration) return
            val newRoom = LiveKit.create(appContext)
            room = newRoom
            eventsJob = scope.launch {
                newRoom.events.collect { event ->
                    if (room !== newRoom) return@collect
                    when (event) {
                        is RoomEvent.Reconnecting -> {
                            _state.value = State.Connecting(roomName)
                            cancelTransmission()
                            _participants.value = emptyList()
                            _activeSpeakers.value = emptyList()
                            updateSpeechRadio()
                        }
                        is RoomEvent.ActiveSpeakersChanged -> {
                            _activeSpeakers.value = event.speakers.map { participant ->
                                Speaker(participant.identity?.value.orEmpty(), participant.name?.takeIf { it.isNotBlank() }
                                    ?: participant.identity?.value ?: "Motorista", participant === newRoom.localParticipant)
                            }
                            updateSpeechRadio()
                        }
                        is RoomEvent.Reconnected -> {
                            newRoom.localParticipant.setMicrophoneEnabled(false)
                            _talking.value = false
                            updateSpeechRadio()
                            atualizarParticipantes(newRoom)
                            _state.value = State.Connected(roomName)
                        }
                        is RoomEvent.DataReceived -> if (event.topic == TOPIC_CHAT) {
                            _messages.tryEmit(
                                TextMessage(
                                    from = event.participant?.name?.takeIf { it.isNotBlank() } ?: event.participant?.identity?.value,
                                    text = event.data.decodeToString(),
                                    mine = false,
                                )
                            )
                        }
                        is RoomEvent.ParticipantConnected,
                        is RoomEvent.ParticipantDisconnected -> {
                            atualizarParticipantes(newRoom)
                        }
                        is RoomEvent.Disconnected -> cleanup(State.Disconnected)
                        else -> Unit
                    }
                }
            }
            newRoom.connect(details.serverUrl, details.token)
            if (generation != connectionGeneration || room !== newRoom) return
            // Push-to-talk: o microfone começa desligado.
            newRoom.localParticipant.setMicrophoneEnabled(false)
            atualizarParticipantes(newRoom)
            _state.value = State.Connected(roomName)
            VoiceRoomService.start(appContext)
        } catch (e: Exception) {
            Log.w(TAG, "Falha ao entrar na sala", e)
            val detail = e.message.orEmpty()
            val message = if (detail.contains("401")) {
                "Rádio não autorizado (401). Verifique no backend se LIVEKIT_URL e as chaves pertencem ao mesmo projeto LiveKit; entre novamente na conta."
            } else detail.ifBlank { "Erro ao entrar na sala" }
            if (generation == connectionGeneration) cleanup(State.Error(message))
        }
    }

    private fun atualizarParticipantes(r: Room) {
        val eu = r.localParticipant.name?.takeIf { it.isNotBlank() }
            ?: r.localParticipant.identity?.value
            ?: "Você"
        val remotos = r.remoteParticipants.values.map { p ->
            p.name?.takeIf { it.isNotBlank() } ?: p.identity?.value ?: "Motorista"
        }
        _participants.value = listOf(eu) + remotos
    }

    /** Sai da sala e encerra o serviço em primeiro plano. */
    fun leave() {
        room?.disconnect()
        cleanup(State.Disconnected)
    }

    suspend fun sendText(text: String): Boolean {
        if (_state.value !is State.Connected || text.isBlank() || text.length > 2000) return false
        val r = room ?: return false
        val generation = connectionGeneration
        val result = r.localParticipant.publishData(
            data = text.encodeToByteArray(),
            reliability = DataPublishReliability.RELIABLE,
            topic = TOPIC_CHAT,
        )
        val current = room === r && connectionGeneration == generation && _state.value is State.Connected
        if (result.isSuccess && current) _messages.tryEmit(TextMessage(from = null, text = text, mine = true))
        return result.isSuccess && current
    }

    /** Chamar ao apertar o botão de falar. */
    suspend fun startTalking() = microphoneLock.withLock {
        check(_state.value is State.Connected) { "Rádio desconectado" }
        check(_activeSpeakers.value.none { !it.local }) { "Outra pessoa está falando. Aguarde para transmitir." }
        val r = checkNotNull(room) { "Sala indisponível" }
        val generation = transmissionGeneration
        // Release synthesized audio before opening TX; never feed Luna speech into the microphone.
        space.neural.luna.avatar.LunaVoz.radioOcupado(true)
        val published = try { r.localParticipant.setMicrophoneEnabled(true) }
        catch (error: Exception) { updateSpeechRadio(); throw error }
        if (!published) updateSpeechRadio()
        check(published) { "Não foi possível ativar o microfone" }
        if (generation != transmissionGeneration || room !== r || _state.value !is State.Connected) {
            r.localParticipant.setMicrophoneEnabled(false)
            _talking.value = false
            updateSpeechRadio()
            error("Transmissão cancelada")
        }
        _talking.value = r.localParticipant.isMicrophoneEnabled
        updateSpeechRadio()
        check(_talking.value) { "Microfone não publicado" }
    }

    /** Chamar ao soltar o botão de falar. */
    suspend fun stopTalking() = microphoneLock.withLock {
        val r = room
        try { r?.localParticipant?.setMicrophoneEnabled(false) }
        finally { _talking.value = r?.localParticipant?.isMicrophoneEnabled == true; updateSpeechRadio() }
    }

    /** Cancellation invalidates an in-flight start and is owned by the process, not the Activity. */
    fun cancelTransmission() {
        transmissionGeneration++
        scope.launch { runCatching { stopTalking() }.onFailure { leave() } }
    }

    private fun cleanup(newState: State) {
        connectionGeneration++
        transmissionGeneration++
        eventsJob?.cancel()
        eventsJob = null
        room?.release()
        room = null
        _talking.value = false
        _participants.value = emptyList()
        _activeSpeakers.value = emptyList()
        updateSpeechRadio()
        _state.value = newState
        VoiceRoomService.stop(appContext)
    }

    companion object {
        private const val TAG = "LunaVoice"
        const val TOPIC_CHAT = "chat"

        @Volatile private var instance: VoiceRoomManager? = null

        fun get(context: Context): VoiceRoomManager =
            instance ?: synchronized(this) {
                instance ?: VoiceRoomManager(context.applicationContext).also { instance = it }
            }
    }
}
