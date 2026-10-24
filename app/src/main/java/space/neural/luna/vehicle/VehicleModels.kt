package space.neural.luna.vehicle

enum class VehicleDataType { FLOAT, INT, BOOLEAN }
enum class VehicleUnit(val label: String) { WH("Wh"), METERS("m"), SPEED_MS("m/s"), MILLIWATTS("mW"), CELSIUS("°C"), LEVEL("nível"), FLAG("estado"), GEAR("marcha") }
enum class VehicleProperty(val id: Int, val label: String, val type: VehicleDataType, val unit: VehicleUnit, val permission: String, val comfort: Boolean = false) {
    BATTERY_ENERGY(0x11600309, "Energia da bateria", VehicleDataType.FLOAT, VehicleUnit.WH, "android.car.permission.CAR_ENERGY"),
    BATTERY_CAPACITY(0x1160030d, "Capacidade atual da bateria", VehicleDataType.FLOAT, VehicleUnit.WH, "android.car.permission.CAR_ENERGY"),
    BATTERY_NOMINAL_CAPACITY(0x11600106, "Capacidade nominal da bateria", VehicleDataType.FLOAT, VehicleUnit.WH, "android.car.permission.CAR_INFO"),
    RANGE(0x11600308, "Autonomia", VehicleDataType.FLOAT, VehicleUnit.METERS, "android.car.permission.CAR_ENERGY"),
    CHARGE_RATE(0x1160030c, "Taxa instantânea de carga", VehicleDataType.FLOAT, VehicleUnit.MILLIWATTS, "android.car.permission.CAR_ENERGY"),
    CHARGE_CONNECTED(0x1120030b, "Cabo de recarga conectado", VehicleDataType.BOOLEAN, VehicleUnit.FLAG, "android.car.permission.CAR_ENERGY_PORTS"),
    SPEED(0x11600207, "Velocidade", VehicleDataType.FLOAT, VehicleUnit.SPEED_MS, "android.car.permission.CAR_SPEED"),
    GEAR(0x11400401, "Marcha atual", VehicleDataType.INT, VehicleUnit.GEAR, "android.car.permission.CAR_POWERTRAIN"),
    TEMPERATURE(0x15600503, "Temperatura da cabine", VehicleDataType.FLOAT, VehicleUnit.CELSIUS, "android.car.permission.CONTROL_CAR_CLIMATE", true),
    FAN(0x15400500, "Ventilação da cabine", VehicleDataType.INT, VehicleUnit.LEVEL, "android.car.permission.CONTROL_CAR_CLIMATE", true)
}
enum class VehicleReadingState { LIVE, STALE, UNAVAILABLE, UNSUPPORTED, PERMISSION_DENIED, ERROR }
data class VehicleReading(val property: VehicleProperty, val areaId: Int, val value: Any? = null,
    val state: VehicleReadingState = VehicleReadingState.UNAVAILABLE, val sampleElapsedNanos: Long = 0,
    val receivedAtMillis: Long = 0, val source: String = "Android CarPropertyManager", val detail: String = "") {
    fun current(nowElapsedNanos: Long, ttlMillis: Long = 5000): Boolean = state == VehicleReadingState.LIVE && value != null &&
        sampleElapsedNanos > 0 && nowElapsedNanos >= sampleElapsedNanos && (nowElapsedNanos - sampleElapsedNanos) / 1_000_000 <= ttlMillis
    fun effective(nowElapsedNanos: Long): VehicleReading = if (state == VehicleReadingState.LIVE && !current(nowElapsedNanos)) copy(value = null, state = VehicleReadingState.STALE, detail = "Leitura antiga ou timestamp inválido") else this
}
data class VehicleAreaCapability(val areaId: Int, val minimum: Double?, val maximum: Double?)
data class VehicleCapability(val property: VehicleProperty, val readable: Boolean, val writable: Boolean,
    val areas: List<VehicleAreaCapability> = emptyList(), val reason: String = "")
data class VehicleConnection(val connected: Boolean, val reason: String)
data class ParsedVehicleProperty(val id: Int, val name: String, val access: Int?)

object VehicleCatalogParser {
    fun parse(text: String): List<ParsedVehicleProperty> = Regex("Property:0x([0-9a-fA-F]{1,8}),Property name:([A-Za-z0-9_]+)(?:,access:0x([0-9a-fA-F]+))?")
        .findAll(text).mapNotNull { row -> runCatching { ParsedVehicleProperty(row.groupValues[1].toLong(16).toInt(), row.groupValues[2], row.groupValues[3].takeIf { it.isNotEmpty() }?.toInt(16)) }.getOrNull() }.distinctBy { it.id }.toList()
}
object VehicleSafety {
    fun parked(speed: VehicleReading, gear: VehicleReading, now: Long, officialParkCode: Int): Boolean =
        speed.property == VehicleProperty.SPEED && gear.property == VehicleProperty.GEAR && speed.current(now, 2000) && gear.current(now, 2000) &&
            (speed.value as? Number)?.toDouble()?.let { it.isFinite() && it == 0.0 } == true && (gear.value as? Int) == officialParkCode
    fun validateComfort(property: VehicleProperty, area: VehicleAreaCapability, value: Double): Boolean {
        val minimum = area.minimum ?: return false
        val maximum = area.maximum ?: return false
        return property.comfort && property in setOf(VehicleProperty.TEMPERATURE, VehicleProperty.FAN) && value.isFinite() &&
            minimum.isFinite() && maximum.isFinite() && minimum <= maximum && value in minimum..maximum &&
            (if (property == VehicleProperty.TEMPERATURE) value in 16.0..32.0 else value in 0.0..10.0) &&
            (property.type != VehicleDataType.INT || value % 1.0 == 0.0)
    }
    fun socPercent(energy: VehicleReading, capacity: VehicleReading, now: Long): Double? {
        if (energy.property != VehicleProperty.BATTERY_ENERGY || capacity.property != VehicleProperty.BATTERY_CAPACITY || !energy.current(now) || !capacity.current(now)) return null
        val wh = (energy.value as? Number)?.toDouble() ?: return null
        val total = (capacity.value as? Number)?.toDouble() ?: return null
        return if (wh.isFinite() && total.isFinite() && total > 0 && wh in 0.0..total) wh / total * 100 else null
    }
}
