package space.neural.luna.vehicle

import android.os.Bundle
import android.os.SystemClock
import android.text.InputType
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject
import space.neural.luna.dashboard.Cores
import space.neural.luna.dashboard.Ui

/** Local vehicle diagnostics. Never grants OEM permissions or polls private services. */
class VehicleTelemetryActivity : AppCompatActivity() {
    private lateinit var ui: Ui
    private lateinit var provider: VehicleProvider
    private var capabilities = emptyList<VehicleCapability>()
    private var readings = emptyList<VehicleReading>()
    private var status = "Conecte para consultar capacidades e permissões reais. Leituras não são enviadas à nuvem."
    private var working = false
    private var updateJob: Job? = null
    private var expiryJob: Job? = null
    private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        status = "Permissões respondidas pelo Android. A concessão OEM e cada propriedade ainda serão verificadas."; render()
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState); ui = Ui(this); provider = VehicleProviderFactory.create(this); render()
    }
    private fun render() {
        val body = ui.vbox(ui.dp(16)).apply { setBackgroundColor(Cores.OBSIDIAN) }
        body.addView(ui.botao("Voltar", Ui.Estilo.CONTORNO) { finish() }, ui.mp())
        body.addView(ui.text("Veículo · telemetria e capacidades", 22f, Cores.BRANCO, true), ui.mp(ui.dp(12)))
        body.addView(ui.text("Consulte e configure com o veículo estacionado. Não há acesso a freio, direção, ADAS ou firmware. O catálogo não concede controle do veículo.", 15f, Cores.BRANCO_70), ui.mp(ui.dp(8)))
        body.addView(ui.text(status, 15f, Cores.CIANO), ui.mp(ui.dp(12)))
        body.addView(ui.botao(if (working) "Consultando…" else "Conectar / atualizar leituras", Ui.Estilo.CHEIO_VIOLETA) { refresh() }.apply { isEnabled = !working; minimumHeight = ui.dp(56) }, ui.mp(ui.dp(10)))
        body.addView(ui.botao("Solicitar permissões de leitura", Ui.Estilo.CONTORNO) {
            permissions.launch(arrayOf("android.car.permission.CAR_ENERGY", "android.car.permission.CAR_ENERGY_PORTS", "android.car.permission.CAR_SPEED", "android.car.permission.CAR_POWERTRAIN", "android.car.permission.CAR_INFO"))
        }.apply { minimumHeight = ui.dp(56); isEnabled = !working }, ui.mp(ui.dp(8)))
        body.addView(ui.botao("Ver catálogo coletado (sem autorização implícita)", Ui.Estilo.CONTORNO) { catalog() }.apply { minimumHeight = ui.dp(56) }, ui.mp(ui.dp(8)))
        val now = SystemClock.elapsedRealtimeNanos()
        val current = readings.map { it.effective(now) }
        val energy = current.firstOrNull { it.property == VehicleProperty.BATTERY_ENERGY }
        val capacity = current.firstOrNull { it.property == VehicleProperty.BATTERY_CAPACITY }
        val soc = if (energy != null && capacity != null) VehicleSafety.socPercent(energy, capacity, now) else null
        body.addView(ui.text(if (soc == null) "SOC: indisponível — exige energia e capacidade ATUAL autorizadas, com unidades compatíveis." else "SOC calculado: ${"%.1f".format(soc)}% · energia/capacidade atual (não nominal)", 15f, Cores.BRANCO), ui.mp(ui.dp(14)))
        VehicleProperty.entries.forEach { property ->
            val cap = capabilities.firstOrNull { it.property == property }
            val values = current.filter { it.property == property }
            val text = buildString {
                append(property.label); append(" · 0x${property.id.toUInt().toString(16)}")
                if (values.isEmpty()) append("\nSem leitura confirmada")
                values.forEach { value ->
                    val display = if (!value.current(now)) "Indisponível (${value.state})" else when (val raw = value.value) {
                        is Boolean -> if (raw) "Sim" else "Não"
                        is Number -> "$raw ${property.unit.label}"
                        else -> "Não confirmado"
                    }
                    append("\nÁrea ${value.areaId}: $display\n${value.source}")
                    if (value.receivedAtMillis > 0) append(" · recebido ${java.text.DateFormat.getTimeInstance().format(java.util.Date(value.receivedAtMillis))}")
                    if (value.detail.isNotBlank()) append("\n${value.detail}")
                }
                append("\n${cap?.reason ?: "Capacidade ainda não consultada"}")
            }
            body.addView(ui.text(text, 14f, Cores.BRANCO_70), ui.mp(ui.dp(14)))
            if (property.comfort) cap?.areas?.forEach { area ->
                body.addView(ui.botao("Ajustar ${property.label} · área ${area.areaId}", Ui.Estilo.CONTORNO) { comfort(property, area) }.apply {
                    minimumHeight = ui.dp(56); isEnabled = cap.readable && cap.writable && parked(current, now) && !working
                }, ui.mp(ui.dp(6)))
            }
        }
        body.addView(ui.text("Ajustes exigem permissão já concedida pelo fabricante, configuração real, Park e velocidade zero recentes, confirmação e leitura de retorno. Não transformamos propriedades OEM em comandos por semelhança de nome.", 13f, Cores.BRANCO_70), ui.mp(ui.dp(16)))
        val scroll = ScrollView(this).apply { addView(body) }; setContentView(scroll)
        ViewCompat.setOnApplyWindowInsetsListener(scroll) { view, insets -> val e = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime()); view.setPadding(e.left,e.top,e.right,e.bottom); insets }
    }
    private fun parked(values: List<VehicleReading>, now: Long): Boolean {
        val speed = values.firstOrNull { it.property == VehicleProperty.SPEED } ?: return false
        val gear = values.firstOrNull { it.property == VehicleProperty.GEAR } ?: return false
        return VehicleSafety.parked(speed, gear, now, android.car.VehicleGear.GEAR_PARK)
    }
    private fun refresh() {
        if (working) return
        working = true; render()
        updateJob = lifecycleScope.launch {
            try {
                val connection = provider.connect(); status = connection.reason
                capabilities = provider.capabilities()
                readings = if (connection.connected) capabilities.flatMap { cap -> cap.areas.ifEmpty { listOf(VehicleAreaCapability(0, null, null)) }.map { provider.read(cap.property, it.areaId) } } else emptyList()
            } catch (cancel: CancellationException) { throw cancel }
            catch (error: Exception) { status = error.message ?: "Não foi possível consultar o veículo"; readings = emptyList() }
            finally { working = false; render() }
        }
    }
    private fun comfort(property: VehicleProperty, area: VehicleAreaCapability) {
        val value = EditText(this).apply { hint = "Limites do firmware: ${area.minimum} a ${area.maximum}"; inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL }
        AlertDialog.Builder(this).setTitle("Confirmar ajuste de conforto")
            .setMessage("${property.label}, área ${area.areaId}. Somente com Park e velocidade zero comprovados. Esta confirmação vale para uma única operação.")
            .setView(value).setPositiveButton("Confirmar alteração estacionado") { _, _ ->
                val number = value.text.toString().replace(',', '.').toDoubleOrNull()
                if (number == null) { status = "Informe um valor válido"; render(); return@setPositiveButton }
                updateJob = lifecycleScope.launch {
                    working = true; render()
                    try {
                        val result = provider.writeComfort(property, area.areaId, number, userConfirmed = true)
                        readings = readings.filterNot { it.property == property && it.areaId == area.areaId } + result
                        status = if (result.current(SystemClock.elapsedRealtimeNanos())) "Alteração confirmada pela leitura de retorno" else result.detail
                    } catch (cancel: CancellationException) { throw cancel }
                    catch (error: Exception) { status = error.message ?: "Ajuste não confirmado" }
                    finally { working = false; render() }
                }
            }.setNegativeButton("Cancelar", null).show()
    }
    private fun catalog() {
        val text = runCatching {
            val rows = JSONObject(assets.open("vehicle/geely-properties.json").bufferedReader().use { it.readText() }).getJSONArray("properties")
            "${rows.length()} propriedades catalogadas. Nenhuma leitura/escrita é autorizada por esta lista.\n\n" + (0 until rows.length()).joinToString("\n") { index -> rows.getJSONObject(index).let { "${it.getString("id")} · ${it.getString("name")}" } }
        }.getOrElse { "Catálogo não disponível" }
        val view = TextView(this).apply { this.text = text; textSize = 13f; setPadding(ui.dp(16),ui.dp(16),ui.dp(16),ui.dp(16)) }
        AlertDialog.Builder(this).setTitle("Catálogo VHAL observado").setView(ScrollView(this).apply { addView(view) }).setPositiveButton("Fechar", null).show()
    }
    override fun onResume() { super.onResume(); render(); expiryJob = lifecycleScope.launch { while (isActive) { delay(2000); if (!working && readings.isNotEmpty()) { readings = readings.map { it.effective(SystemClock.elapsedRealtimeNanos()) }; render() } } } }
    override fun onPause() { expiryJob?.cancel(); updateJob?.cancel(); provider.close(); readings = emptyList(); capabilities = emptyList(); status = "Conexão encerrada ao sair da tela. Conecte para atualizar os dados."; super.onPause() }
    override fun onDestroy() { provider.close(); super.onDestroy() }
}
