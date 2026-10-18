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

    private val _state = MutableStateFlow<State>(State.Disconnected)
    val state: StateFlow<State> = _state.asStateFlow()

    private val _messages = MutableSharedFlow<TextMessage>(extraBufferCapacity = 64)
    val messages: SharedFlow<TextMessage> = _messages.asSharedFlow()

    private val _talking = MutableStateFlow(false)
    val talking: StateFlow<Boolean> = _talking.asStateFlow()

    suspend fun join(
        roomName: String,
        identity: String,
        tokenProvider: TokenProvider = HttpTokenProvider(),
    ) {
        if (room != null) return
        _state.value = State.Connecting(roomName)
        try {
            val details = tokenProvider.fetch(roomName, identity)
            val newRoom = LiveKit.create(appContext)
            room = newRoom
            eventsJob = scope.launch {
                newRoom.events.collect { event ->
                    when (event) {
                        is RoomEvent.DataReceived -> if (event.topic == TOPIC_CHAT) {
                            _messages.tryEmit(
                                TextMessage(
                                    from = event.participant?.identity?.value,
                                    text = event.data.decodeToString(),
                                    mine = false,
                                )
                            )
                        }
                        is RoomEvent.Disconnected -> cleanup(State.Disconnected)
                        else -> Unit
                    }
                }
            }
            newRoom.connect(details.serverUrl, details.token)
            // Push-to-talk: o microfone começa desligado.
            newRoom.localParticipant.setMicrophoneEnabled(false)
            _state.value = State.Connected(roomName)
            VoiceRoomService.start(appContext)
        } catch (e: Exception) {
            Log.w(TAG, "Falha ao entrar na sala", e)
            cleanup(State.Error(e.message ?: "Erro ao entrar na sala"))
        }
    }

    /** Sai da sala e encerra o serviço em primeiro plano. */
    fun leave() {
        room?.disconnect()
        cleanup(State.Disconnected)
    }

    suspend fun sendText(text: String): Boolean {
        val r = room ?: return false
        val result = r.localParticipant.publishData(
            data = text.encodeToByteArray(),
            reliability = DataPublishReliability.RELIABLE,
            topic = TOPIC_CHAT,
        )
        if (result.isSuccess) _messages.tryEmit(TextMessage(from = null, text = text, mine = true))
        return result.isSuccess
    }

    /** Chamar ao apertar o botão de falar. */
    suspend fun startTalking() {
        val r = room ?: return
        r.localParticipant.setMicrophoneEnabled(true)
        _talking.value = true
    }

    /** Chamar ao soltar o botão de falar. */
    suspend fun stopTalking() {
        val r = room ?: return
        r.localParticipant.setMicrophoneEnabled(false)
        _talking.value = false
    }

    private fun cleanup(newState: State) {
        eventsJob?.cancel()
        eventsJob = null
        room?.release()
        room = null
        _talking.value = false
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
