package space.neural.luna.pages

import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.catch
import space.neural.luna.R
import space.neural.luna.collaboration.*
import space.neural.luna.conta.Conta
import space.neural.luna.dashboard.*
import space.neural.luna.voice.VoiceRoomManager
import java.text.SimpleDateFormat
import java.util.*

/** Room chat and real participants. A failed write remains visible with a retry using the same ID. */
class RoomPanel(private val activity: AppCompatActivity, private val onDrivers: (List<DriverPresence>) -> Unit = {}, showParticipants: Boolean = true) {
    private val ui = Ui(activity)
    private val manager = VoiceRoomManager.get(activity)
    private val repository = runCatching { CommunityRepository() }.getOrNull()
    private val title = ui.text("NO AR AGORA · 0", 15f, Cores.BRANCO_70, true)
    private val people = ui.vbox()
    private val messages = ui.vbox()
    val status = ui.text("Conecte sua conta à comunidade", 12f, Cores.BRANCO_70)
    private val input = EditText(activity).apply {
        hint = "Mensagem para a sala…"; textSize = 14f; setTextColor(Cores.BRANCO); setHintTextColor(Cores.BRANCO_70)
        background = ui.fundo(Cores.ELEVADA, Cores.alpha(Cores.VIOLETA, .6f), 22f)
        setPadding(ui.dp(12), ui.dp(10), ui.dp(12), ui.dp(10)); maxLines = 3
    }
    private val rows = linkedMapOf<String, RoomMessage>()
    private val failed = linkedMapOf<String, RoomMessage>()
    private val outbox = activity.getSharedPreferences("room_chat_outbox_v1", 0)
    private var observing: Job? = null
    private var room = "luna-eletricos-gv"
    private var firstMessage: String? = null
    val view = RotaDesign.panelBox(ui)

    init {
        val header = ui.hbox().apply {
            addView(title, ui.peso(1f)); addView(ui.botao("Ver todos", Ui.Estilo.TEXTO) { people.visibility = if (people.visibility == View.VISIBLE) View.GONE else View.VISIBLE }.apply { textSize = 12f }, ui.wc())
        }
        if (showParticipants) {
            view.addView(header)
            view.addView(ScrollView(activity).apply { addView(people); isVerticalScrollBarEnabled = false }, LinearLayout.LayoutParams(-1, ui.dp(96)))
            view.addView(ui.divisor())
        }
        view.addView(ui.hbox().apply {
            addView(ui.text("CHAT DA SALA", 15f, Cores.BRANCO_70, true), ui.peso(1f))
            addView(ui.botao("Anteriores", Ui.Estilo.TEXTO) { loadOlder() }.apply { textSize = 12f }, ui.wc())
        })
        view.addView(status, ui.mp())
        view.addView(ScrollView(activity).apply { addView(messages); isFillViewport = false }, LinearLayout.LayoutParams(-1, 0, 1f))
        val compose = ui.hbox()
        compose.addView(input, ui.peso(1f))
        compose.addView(RotaDesign.roundIcon(ui, R.drawable.ic_send, "Enviar mensagem", Cores.MAGENTA) {
            val text = input.text.toString().trim()
            if (text.isNotEmpty()) {
                if (Conta.uid() == null) { status.text = "Entre na conta antes de enviar"; return@roundIcon }
                val id = UUID.randomUUID().toString()
                val msg = RoomMessage(id, Conta.uid().orEmpty(), Conta.primeiroNome().ifBlank { "Você" }, text, System.currentTimeMillis(), true)
                input.text.clear(); send(msg)
            }
        }, ui.wc(ui.dp(6)))
        view.addView(compose, ui.mp(ui.dp(8)))
    }
    fun selectRoom(value: String) {
        observing?.cancel(); room = value; rows.clear(); failed.clear(); messages.removeAllViews()
        restoreOutbox(); renderMessages()
        observing = activity.lifecycleScope.launch {
            activity.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { manager.participants.collect { names ->
                    title.text = "NO AR AGORA · ${names.size}"
                    people.removeAllViews()
                    if (names.isEmpty()) people.addView(ui.text("Nenhum participante conectado", 13f, Cores.BRANCO_70))
                    names.forEach { name -> people.addView(person(name, "Conectado ao rádio", Cores.ESMERALDA)) }
                } }
                if (repository != null && Conta.uid() != null) {
                    launch { repository.observeActivePresence(room).catch { status.text = communityError(it) }.collect { drivers ->
                        onDrivers(drivers)
                        people.removeAllViews()
                        if (drivers.isNotEmpty()) {
                            title.text = "PARTICIPANTES · ${drivers.size}"
                            drivers.forEach { driver -> people.addView(person(driver.name,
                                listOfNotNull(if (driver.status == DriverStatus.OCUPADO) "Ocupado" else "Online", driver.vehicleModel, driver.vehicleColor, driver.licensePlate).filter { it.isNotBlank() }.joinToString(" · "),
                                if (driver.status == DriverStatus.OCUPADO) Cores.LARANJA else Cores.ESMERALDA)) }
                        } else {
                            val names = manager.participants.value
                            title.text = "NO AR AGORA · ${names.size}"
                            if (names.isEmpty()) people.addView(ui.text("Nenhum participante conectado", 13f, Cores.BRANCO_70))
                            names.forEach { people.addView(person(it, "Conectado ao rádio", Cores.ESMERALDA)) }
                        }
                    } }
                    launch { repository.observeMessages(room).catch { status.text = communityError(it) }.collect { incoming ->
                        status.text = "● Chat conectado"; status.setTextColor(Cores.ESMERALDA)
                        firstMessage = incoming.firstOrNull()?.id
                        incoming.forEach { rows[it.id] = it; if (!it.pending) { failed.remove(it.id); removePending(room, it.authorUid, it.id) } }
                        renderMessages()
                    } }
                } else status.text = "Entre na conta para acessar o histórico da sala"
            }
        }
    }
    private fun send(message: RoomMessage) {
        val sendingRoom = room
        savePending(sendingRoom, message)
        rows[message.id] = message; failed.remove(message.id); renderMessages()
        activity.lifecycleScope.launch {
            try {
                checkNotNull(repository) { "Comunidade indisponível" }.sendMessage(sendingRoom, message.text, message.id)
                removePending(sendingRoom, message.authorUid, message.id)
                if (room == sendingRoom) { rows[message.id] = message.copy(pending = false); renderMessages() }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { if (room == sendingRoom) { failed[message.id] = message; status.text = communityError(e); renderMessages() } }
        }
    }
    private fun outboxKey(room: String, uid: String) = "$uid/$room/"
    private fun savePending(room: String, message: RoomMessage) {
        val data = org.json.JSONObject().put("id", message.id).put("author", message.authorName)
            .put("text", message.text).put("time", message.createdAt)
        outbox.edit().putString(outboxKey(room, message.authorUid) + message.id, data.toString()).apply()
    }
    private fun removePending(room: String, uid: String, id: String) {
        outbox.edit().remove(outboxKey(room, uid) + id).apply()
    }
    private fun restoreOutbox() {
        val uid = Conta.uid() ?: return
        val prefix = outboxKey(room, uid)
        outbox.all.filterKeys { it.startsWith(prefix) }.values.forEach { raw -> runCatching {
            val data = org.json.JSONObject(raw as String)
            val message = RoomMessage(data.getString("id"), uid, data.getString("author"), data.getString("text"), data.getLong("time"), true)
            rows[message.id] = message; failed[message.id] = message
        } }
    }
    private fun loadOlder() {
        val id = firstMessage ?: return
        val loadingRoom = room
        activity.lifecycleScope.launch {
            runCatching { repository?.loadOlderMessages(loadingRoom, id).orEmpty() }.onSuccess { older ->
                if (room != loadingRoom) return@onSuccess
                older.forEach { rows[it.id] = it }; firstMessage = older.firstOrNull()?.id ?: firstMessage; renderMessages()
            }.onFailure { status.text = communityError(it) }
        }
    }
    private fun person(name: String, detail: String, color: Int): View = ui.hbox().apply {
        setPadding(0, ui.dp(5), 0, ui.dp(5))
        addView(RotaDesign.badge(ui, name.take(1).uppercase().ifBlank { "•" }, color).apply { gravity = Gravity.CENTER }, LinearLayout.LayoutParams(ui.dp(40), ui.dp(40)))
        addView(ui.vbox().apply { addView(ui.text(name, 15f, Cores.BRANCO, true)); addView(ui.text(detail, 12f, color)) }, ui.peso(1f, ui.dp(10)))
    }
    private fun renderMessages() {
        messages.removeAllViews()
        rows.values.sortedBy { it.createdAt }.forEach { message ->
            val bubble = ui.vbox(ui.dp(10)).apply { background = ui.fundo(Cores.ELEVADA, Cores.alpha(Cores.VIOLETA, .25f), 14f) }
            val header = ui.hbox()
            header.addView(ui.text(message.authorName, 14f, if (message.authorUid == Conta.uid()) Cores.CIANO else Cores.BRANCO, true), ui.peso(1f))
            header.addView(ui.text(if (message.createdAt > 0) SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(message.createdAt)) else "Enviando", 11f, Cores.BRANCO_70), ui.wc())
            bubble.addView(header); bubble.addView(ui.text(message.text, 14f, Cores.BRANCO), ui.mp(ui.dp(4)))
            val delivery = if (failed.containsKey(message.id)) "Falhou · toque para tentar novamente" else if (message.pending) "Enviando…" else "Enviada"
            bubble.addView(ui.text(delivery, 11f, if (failed.containsKey(message.id)) Cores.CORAL else Cores.BRANCO_70), ui.mp(ui.dp(3)))
            if (failed.containsKey(message.id)) bubble.setOnClickListener { send(message.copy(pending = true)) }
            messages.addView(bubble, ui.mp(ui.dp(7)))
        }
    }
    companion object {
        fun communityError(error: Throwable): String = when (error) {
            is com.google.firebase.firestore.FirebaseFirestoreException -> when (error.code) {
                com.google.firebase.firestore.FirebaseFirestoreException.Code.PERMISSION_DENIED -> "A sala recusou o acesso. Sua conta precisa de autorização no servidor."
                com.google.firebase.firestore.FirebaseFirestoreException.Code.UNAUTHENTICATED -> "Sua sessão expirou. Entre novamente."
                else -> "Não foi possível sincronizar a sala. Verifique a conexão e tente novamente."
            }
            else -> error.message?.take(180) ?: "Comunidade indisponível. Tente novamente."
        }
    }
}
