package space.neural.luna.collaboration

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class DriverProfileCacheTest {
    private val context get() = ApplicationProvider.getApplicationContext<Application>()
    @Before fun clear() { context.getSharedPreferences("driver_profile_v1", Context.MODE_PRIVATE).edit().clear().commit() }
    @Test fun restoresProfileAfterRepositoryRecreation() {
        val profile = DriverProfile("EX2", "Prata", "ABC1D23", true, "Geely", VehiclePowertrain.ELECTRIC, VehicleIcon.HATCH)
        DriverProfileCache(context).write("ana", profile)
        assertEquals(profile, DriverProfileCache(context).read("ana"))
    }
    @Test fun switchingAccountDoesNotInheritPlateOrConsent() {
        val cache = DriverProfileCache(context)
        cache.write("ana", DriverProfile("EX2", "Prata", "ABC1D23", true, "Geely"))
        assertEquals(DriverProfile(), cache.read("bia"))
        assertEquals(DriverProfile(), cache.read(null))
        cache.write("bia", DriverProfile("Dolphin", "Azul", "DEF5678", false, "BYD"))
        assertTrue(cache.read("ana").showPlate)
        assertFalse(cache.read("bia").showPlate)
        assertEquals("DEF5678", cache.read("bia").licensePlate)
        assertEquals("Geely", cache.read("ana").vehicleBrand)
        assertEquals("BYD", cache.read("bia").vehicleBrand)
    }
    @Test fun cacheBeforeBrandMigrationRestoresExistingVehicleWithoutDataLoss() {
        context.getSharedPreferences("driver_profile_v1", Context.MODE_PRIVATE).edit()
            .putString("ana.model", "EX2").putString("ana.color", "Prata")
            .putString("ana.plate", "ABC1234").putBoolean("ana.showPlate", false).commit()
        val restored = DriverProfileCache(context).read("ana")
        assertEquals("", restored.vehicleBrand)
        assertEquals("EX2", restored.vehicleModel)
        assertEquals("ABC1234", restored.licensePlate)
        assertFalse(restored.showPlate)
        assertEquals(VehiclePowertrain.UNINFORMED, restored.vehiclePowertrain)
        assertEquals(VehicleIcon.GENERIC, restored.vehicleIcon)
    }
    @Test fun typeAndIconSelectionStayAccountScoped() {
        val cache = DriverProfileCache(context)
        cache.write("ana", DriverProfile(vehiclePowertrain = VehiclePowertrain.ELECTRIC, vehicleIcon = VehicleIcon.HATCH))
        cache.write("bia", DriverProfile(vehiclePowertrain = VehiclePowertrain.COMBUSTION, vehicleIcon = VehicleIcon.SEDAN))
        assertEquals(VehicleIcon.HATCH, DriverProfileCache(context).read("ana").vehicleIcon)
        assertEquals(VehiclePowertrain.COMBUSTION, DriverProfileCache(context).read("bia").vehiclePowertrain)
        assertEquals(VehicleIcon.GENERIC, DriverProfileCache(context).read(null).vehicleIcon)
        assertEquals(VehiclePowertrain.UNINFORMED, DriverProfileCache(context).read("new").vehiclePowertrain)
    }
    @Test fun revokedConsentRemainsRevokedAfterRecreation() {
        val cache = DriverProfileCache(context)
        cache.write("ana", DriverProfile(licensePlate = "ABC1234", showPlate = true))
        cache.write("ana", cache.read("ana").copy(showPlate = false))
        val restored = DriverProfileCache(context).read("ana")
        assertFalse(restored.showPlate)
        assertFalse(restored.publicFields().containsKey("licensePlate"))
        assertEquals("ABC1234", restored.licensePlate)
    }
    @Test fun pendingRevocationIsDurableAndAccountScoped() {
        DriverProfileCache(context).setPendingPrivacy("ana", true)
        val recreated = DriverProfileCache(context)
        assertTrue(recreated.pendingPrivacy("ana"))
        assertFalse(recreated.pendingPrivacy("bia"))
        recreated.setPendingPrivacy("ana", false)
        assertFalse(DriverProfileCache(context).pendingPrivacy("ana"))
    }
}
