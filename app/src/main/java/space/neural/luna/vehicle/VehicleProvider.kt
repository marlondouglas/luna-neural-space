package space.neural.luna.vehicle

interface VehicleProvider : AutoCloseable {
    suspend fun connect(): VehicleConnection
    suspend fun capabilities(): List<VehicleCapability>
    suspend fun read(property: VehicleProperty, areaId: Int = 0): VehicleReading
    suspend fun writeComfort(property: VehicleProperty, areaId: Int, value: Double, userConfirmed: Boolean): VehicleReading
}

class UnavailableVehicleProvider(private val reason: String) : VehicleProvider {
    override suspend fun connect() = VehicleConnection(false, reason)
    override suspend fun capabilities() = VehicleProperty.entries.map { VehicleCapability(it, false, false, reason = reason) }
    override suspend fun read(property: VehicleProperty, areaId: Int) = VehicleReading(property, areaId, detail = reason)
    override suspend fun writeComfort(property: VehicleProperty, areaId: Int, value: Double, userConfirmed: Boolean): VehicleReading = read(property, areaId)
    override fun close() = Unit
}
