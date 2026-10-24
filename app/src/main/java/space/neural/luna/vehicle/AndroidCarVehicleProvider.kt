package space.neural.luna.vehicle

import android.car.Car
import android.car.VehicleGear
import android.car.hardware.CarPropertyConfig
import android.car.hardware.CarPropertyValue
import android.car.hardware.property.CarPropertyManager
import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import android.os.SystemClock
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Public Android Car API only. No OEM binder, reflection, shell, hidden methods or privileged grant. */
@Suppress("DEPRECATION")
class AndroidCarVehicleProvider(private val context: Context) : VehicleProvider {
    private var car: Car? = null
    private var manager: CarPropertyManager? = null
    override suspend fun connect(): VehicleConnection = try {
        if (manager != null) VehicleConnection(true, "Serviço Android Car conectado; cada propriedade ainda exige autorização")
        else withContext(Dispatchers.Main.immediate) {
            withTimeout(8000) {
                suspendCancellableCoroutine<VehicleConnection> { continuation ->
                    val connection = object : ServiceConnection {
                        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                            try {
                                manager = car?.getCarManager(Car.PROPERTY_SERVICE) as? CarPropertyManager
                                check(manager != null) { "CarPropertyManager não oferecido por esta central" }
                                if (continuation.isActive) continuation.resume(VehicleConnection(true, "Android Car conectado"))
                            } catch (error: Exception) { if (continuation.isActive) continuation.resumeWithException(error) }
                            catch (error: LinkageError) { if (continuation.isActive) continuation.resumeWithException(error) }
                        }
                        override fun onServiceDisconnected(name: ComponentName?) { manager = null }
                    }
                    continuation.invokeOnCancellation { close() }
                    try { car = Car.createCar(context.applicationContext, connection); checkNotNull(car).connect() }
                    catch (error: Exception) { if (continuation.isActive) continuation.resumeWithException(error) }
                    catch (error: LinkageError) { if (continuation.isActive) continuation.resumeWithException(error) }
                }
            }
        }
    } catch (error: kotlinx.coroutines.TimeoutCancellationException) { close(); VehicleConnection(false, "Tempo limite ao conectar ao Android Car. Verifique o serviço e a autorização OEM.") }
    catch (error: kotlinx.coroutines.CancellationException) { close(); throw error }
    catch (error: Exception) { close(); VehicleConnection(false, diagnostic(error)) }
    catch (error: LinkageError) { close(); VehicleConnection(false, "API Android Car ausente ou incompatível com este firmware") }

    private fun granted(permission: String) = ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
    private fun configs(): List<CarPropertyConfig<*>> = checkNotNull(manager) { "Conecte o serviço veicular" }.propertyList
    private fun config(property: VehicleProperty) = configs().firstOrNull { it.propertyId == property.id }
    private fun expected(property: VehicleProperty, type: Class<*>): Boolean = when (property.type) {
        VehicleDataType.FLOAT -> type == java.lang.Float::class.java || type == Float::class.javaPrimitiveType
        VehicleDataType.INT -> type == java.lang.Integer::class.java || type == Int::class.javaPrimitiveType
        VehicleDataType.BOOLEAN -> type == java.lang.Boolean::class.java || type == Boolean::class.javaPrimitiveType
    }
    override suspend fun capabilities(): List<VehicleCapability> = withContext(Dispatchers.IO) {
        VehicleProperty.entries.map { property ->
            try {
                val config = config(property)
                val permission = granted(property.permission)
                val typed = config != null && expected(property, config.propertyType)
                val areas = config?.areaIds?.toList()?.ifEmpty { listOf(0) }.orEmpty().map { area ->
                    VehicleAreaCapability(area, runCatching { (config?.getMinValue(area) as? Number)?.toDouble() }.getOrNull(), runCatching { (config?.getMaxValue(area) as? Number)?.toDouble() }.getOrNull())
                }
                VehicleCapability(property, permission && typed && (config!!.access and 1) != 0,
                    permission && typed && property.comfort && (config!!.access and 2) != 0, areas,
                    when { !permission -> "Permissão não concedida: ${property.permission}"; config == null -> "ID padrão não oferecido pelo firmware"; !typed -> "Tipo do firmware diverge do contrato público; sem conversão presumida"; else -> "Configuração consultada no serviço real" })
            } catch (error: Exception) { VehicleCapability(property, false, false, reason = diagnostic(error)) }
            catch (error: LinkageError) { VehicleCapability(property, false, false, reason = "API incompatível com o firmware") }
        }
    }
    override suspend fun read(property: VehicleProperty, areaId: Int): VehicleReading = withContext(Dispatchers.IO) {
        fun unavailable(state: VehicleReadingState, reason: String) = VehicleReading(property, areaId, state = state, detail = reason)
        if (!granted(property.permission)) return@withContext unavailable(VehicleReadingState.PERMISSION_DENIED, "Permissão não concedida: ${property.permission}")
        try {
            val config = config(property) ?: return@withContext unavailable(VehicleReadingState.UNSUPPORTED, "ID padrão não oferecido; IDs OEM não são substitutos automáticos")
            if (!expected(property, config.propertyType) || (config.access and 1) == 0) return@withContext unavailable(VehicleReadingState.UNSUPPORTED, "Tipo/acesso de leitura não compatível")
            if (areaId !in config.areaIds.toList().ifEmpty { listOf(0) }) return@withContext unavailable(VehicleReadingState.UNSUPPORTED, "Área não oferecida pelo firmware")
            val pm = checkNotNull(manager)
            val value: CarPropertyValue<*> = when (property.type) {
                VehicleDataType.FLOAT -> pm.getProperty(java.lang.Float::class.java, property.id, areaId)
                VehicleDataType.INT -> pm.getProperty(java.lang.Integer::class.java, property.id, areaId)
                VehicleDataType.BOOLEAN -> pm.getProperty(java.lang.Boolean::class.java, property.id, areaId)
            }
            if (value.status != CarPropertyValue.STATUS_AVAILABLE) return@withContext unavailable(VehicleReadingState.UNAVAILABLE, "Controlador informa dado indisponível")
            val raw = value.value
            val converted: Any = when (property.type) {
                VehicleDataType.FLOAT -> (raw as Number).toDouble().also { require(it.isFinite()) }
                VehicleDataType.INT -> (raw as Number).toInt()
                VehicleDataType.BOOLEAN -> raw as Boolean
            }
            if (property in setOf(VehicleProperty.BATTERY_ENERGY, VehicleProperty.RANGE)) require((converted as Number).toDouble() >= 0) { "Valor físico inválido informado pelo controlador" }
            if (property in setOf(VehicleProperty.BATTERY_CAPACITY, VehicleProperty.BATTERY_NOMINAL_CAPACITY)) require((converted as Number).toDouble() > 0) { "Capacidade da bateria não confirmada" }
            VehicleReading(property, areaId, converted, VehicleReadingState.LIVE, value.timestamp, System.currentTimeMillis()).effective(SystemClock.elapsedRealtimeNanos())
        } catch (error: SecurityException) { unavailable(VehicleReadingState.PERMISSION_DENIED, "A API negou a leitura desta propriedade") }
        catch (error: Exception) { unavailable(VehicleReadingState.ERROR, diagnostic(error)) }
        catch (error: LinkageError) { unavailable(VehicleReadingState.UNSUPPORTED, "Método público indisponível neste firmware/API") }
    }
    override suspend fun writeComfort(property: VehicleProperty, areaId: Int, value: Double, userConfirmed: Boolean): VehicleReading = withContext(Dispatchers.IO) {
        try {
            check(userConfirmed) { "Confirmação explícita necessária para esta operação" }
            check(property.comfort) { "Propriedade não pertence à whitelist de conforto" }
            val capability = capabilities().first { it.property == property }
            check(capability.readable && capability.writable) { capability.reason }
            val area = capability.areas.firstOrNull { it.areaId == areaId }
            check(area != null && VehicleSafety.validateComfort(property, area, value)) { "Valor/área fora dos limites reais e da política local" }
            val speed = read(VehicleProperty.SPEED)
            val gear = read(VehicleProperty.GEAR)
            check(VehicleSafety.parked(speed, gear, SystemClock.elapsedRealtimeNanos(), VehicleGear.GEAR_PARK)) { "É necessário Park e velocidade zero confirmados por leituras autorizadas recentes" }
            check(granted(property.permission)) { "A autorização do controle foi revogada" }
            when (property.type) {
                VehicleDataType.FLOAT -> checkNotNull(manager).setFloatProperty(property.id, areaId, value.toFloat())
                VehicleDataType.INT -> checkNotNull(manager).setIntProperty(property.id, areaId, value.toInt())
                else -> error("Tipo não permitido para escrita")
            }
            var readback = read(property, areaId)
            for (attempt in 0 until 5) {
                if (readback.current(SystemClock.elapsedRealtimeNanos()) && (readback.value as? Number)?.toDouble()?.let { kotlin.math.abs(it - value) <= 0.05 } == true) break
                kotlinx.coroutines.delay(200); readback = read(property, areaId)
            }
            check(readback.current(SystemClock.elapsedRealtimeNanos()) && (readback.value as? Number)?.toDouble()?.let { kotlin.math.abs(it - value) <= 0.05 } == true) { "Comando enviado, mas leitura de retorno não confirmou a alteração. Confira o painel original." }
            readback
        } catch (error: kotlinx.coroutines.CancellationException) { throw error }
        catch (error: Exception) { VehicleReading(property, areaId, state = if (error is SecurityException) VehicleReadingState.PERMISSION_DENIED else VehicleReadingState.ERROR, detail = diagnostic(error)) }
        catch (error: LinkageError) { VehicleReading(property, areaId, state = VehicleReadingState.UNSUPPORTED, detail = "API pública não disponível neste firmware") }
    }
    override fun close() { manager = null; runCatching { car?.disconnect() }; car = null }
    private fun diagnostic(error: Throwable) = when (error) {
        is SecurityException -> "O sistema negou acesso. É necessária autorização OEM legítima; não será contornada."
        else -> error.message?.take(240) ?: "Serviço veicular indisponível"
    }
}
