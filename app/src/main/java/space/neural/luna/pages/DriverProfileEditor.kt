package space.neural.luna.pages

import android.content.Intent
import android.text.InputType
import android.widget.EditText
import android.widget.Switch
import android.widget.Spinner
import android.widget.ArrayAdapter
import android.widget.ScrollView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import space.neural.luna.collaboration.DriverProfile
import space.neural.luna.collaboration.DriverProfileRepository
import space.neural.luna.collaboration.VehiclePowertrain
import space.neural.luna.collaboration.VehicleIcon
import space.neural.luna.conta.Conta
import space.neural.luna.conta.LoginActivity
import space.neural.luna.dashboard.Cores
import space.neural.luna.dashboard.Ui

object DriverProfileEditor {
    fun show(activity: AppCompatActivity, room: String? = null, saved: () -> Unit = {}) {
        if (Conta.uid() == null) { activity.startActivity(Intent(activity, LoginActivity::class.java)); return }
        val repository = runCatching { DriverProfileRepository(activity) }.getOrNull() ?: return
        val ui = Ui(activity); val profile = repository.cached()
        fun field(label: String, value: String) = EditText(activity).apply {
            hint = label; setText(value); textSize = 16f; setTextColor(Cores.BRANCO); setHintTextColor(Cores.BRANCO_70)
        }
        val brand = field("Marca do carro (privada)", profile.vehicleBrand)
        val model = field("Modelo do carro", profile.vehicleModel)
        val color = field("Cor do carro", profile.vehicleColor)
        fun selection(labels: List<String>, selected: Int) = Spinner(activity).apply {
            adapter = ArrayAdapter(activity, android.R.layout.simple_spinner_dropdown_item, labels)
            minimumHeight = ui.dp(48); setSelection(selected)
        }
        val powertrain = selection(VehiclePowertrain.entries.map { it.label }, profile.vehiclePowertrain.ordinal)
        val icon = selection(VehicleIcon.entries.map { it.label }, profile.vehicleIcon.ordinal)
        val plate = field("Placa", profile.licensePlate).apply { inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS }
        val sharePlate = Switch(activity).apply { text = "Mostrar minha placa aos demais"; setTextColor(Cores.BRANCO); isChecked = profile.showPlate }
        val status = ui.text("Marca, tipo e ícone ficam privados na sua conta. O desenho é representativo. Modelo e cor aparecem aos demais na sala. A placa começa oculta e só será compartilhada se você ativar esta opção.", 13f, Cores.BRANCO_70)
        val form = ui.vbox(ui.dp(16)).apply {
            addView(brand); addView(model); addView(color); addView(plate); addView(sharePlate, ui.mp(ui.dp(12))); addView(status, ui.mp(ui.dp(8)))
            addView(ui.text("Tipo do veículo (privado)", 14f, Cores.BRANCO_70)); addView(powertrain)
            addView(ui.text("Ícone representativo (privado)", 14f, Cores.BRANCO_70)); addView(icon)
            addView(ui.botao("Carregar cadastro sincronizado", Ui.Estilo.CONTORNO) {
                activity.lifecycleScope.launch { runCatching { repository.load() }.onSuccess { loaded ->
                    brand.setText(loaded.vehicleBrand); model.setText(loaded.vehicleModel); color.setText(loaded.vehicleColor); plate.setText(loaded.licensePlate); sharePlate.isChecked = loaded.showPlate
                    powertrain.setSelection(loaded.vehiclePowertrain.ordinal); icon.setSelection(loaded.vehicleIcon.ordinal)
                    status.text = if (repository.hasPendingPrivacyChange()) "Ocultação local ativa; retirada da placa no servidor ainda aguardando conexão." else "Cadastro carregado."
                }.onFailure { status.text = RoomPanel.communityError(it) } }
            }, ui.mp(ui.dp(12)))
        }
        val dialog = AlertDialog.Builder(activity).setTitle("Meu veículo e privacidade").setView(ScrollView(activity).apply { addView(form) }).setPositiveButton("Salvar", null).setNegativeButton("Cancelar", null).create()
        dialog.setOnShowListener { dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val updated = runCatching { DriverProfile(model.text.toString(), color.text.toString(), plate.text.toString(), sharePlate.isChecked, brand.text.toString(),
                VehiclePowertrain.entries[powertrain.selectedItemPosition], VehicleIcon.entries[icon.selectedItemPosition]).validated() }
                .getOrElse { status.text = it.message; return@setOnClickListener }
            val button = dialog.getButton(AlertDialog.BUTTON_POSITIVE); button.isEnabled = false; status.text = "Salvando cadastro…"
            activity.lifecycleScope.launch {
                runCatching { repository.save(updated, room) }.onSuccess { saved(); dialog.dismiss() }.onFailure {
                    button.isEnabled = true
                    status.text = if (!updated.showPlate && repository.hasPendingPrivacyChange()) "A placa ficou oculta neste aparelho. A remoção pública precisa de conexão e ainda não foi confirmada pelo servidor. Tente salvar novamente."
                        else RoomPanel.communityError(it)
                }
            }
        } }; dialog.show()
    }
}
