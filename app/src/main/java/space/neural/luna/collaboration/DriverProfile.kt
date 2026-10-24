package space.neural.luna.collaboration

import java.util.Locale

enum class VehiclePowertrain(val label: String) {
    UNINFORMED("Tipo não informado"), ELECTRIC("Elétrico"), HYBRID("Híbrido"), COMBUSTION("Combustão");
    companion object { fun fromStored(value: String?) = entries.firstOrNull { it.name == value } ?: UNINFORMED }
}
/** A manual pictogram preference, not a claim that artwork matches the user's exact car. */
enum class VehicleIcon(val label: String) {
    GENERIC("Carro genérico"), HATCH("Hatch representativo"), SEDAN("Sedã representativo"), SUV("SUV representativo");
    companion object { fun fromStored(value: String?) = entries.firstOrNull { it.name == value } ?: GENERIC }
}

/** Stored privately by account. Plate publication always requires its own explicit consent. */
data class DriverProfile(
    val vehicleModel: String = "",
    val vehicleColor: String = "",
    val licensePlate: String = "",
    val showPlate: Boolean = false,
    val vehicleBrand: String = "",
    val vehiclePowertrain: VehiclePowertrain = VehiclePowertrain.UNINFORMED,
    val vehicleIcon: VehicleIcon = VehicleIcon.GENERIC,
) {
    fun normalized(): DriverProfile = copy(vehicleBrand = vehicleBrand.trim(), vehicleModel = vehicleModel.trim(), vehicleColor = vehicleColor.trim(),
        licensePlate = licensePlate.uppercase(Locale.ROOT).replace(Regex("[\\s-]"), ""))
    fun validated(): DriverProfile = normalized().also {
        require(it.vehicleBrand.length <= 60) { "Marca deve ter até 60 caracteres" }
        require(it.vehicleModel.length <= 80) { "Modelo deve ter até 80 caracteres" }
        require(it.vehicleColor.length <= 40) { "Cor deve ter até 40 caracteres" }
        require(it.licensePlate.isEmpty() || it.licensePlate.matches(Regex("[A-Z]{3}[0-9][A-Z0-9][0-9]{2}"))) { "Informe placa brasileira válida, como ABC1234 ou ABC1D23" }
    }
    /** Brand remains private. Hidden plate is absent, never sent as a masked real value. */
    fun publicFields(): Map<String, Any> {
        val p = validated()
        return buildMap {
            if (p.vehicleModel.isNotBlank()) put("vehicleModel", p.vehicleModel)
            if (p.vehicleColor.isNotBlank()) put("vehicleColor", p.vehicleColor)
            put("showPlate", p.showPlate && p.licensePlate.isNotBlank())
            if (p.showPlate && p.licensePlate.isNotBlank()) put("licensePlate", p.licensePlate)
        }
    }
}
