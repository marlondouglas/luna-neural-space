package space.neural.luna.vehicle

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import space.neural.luna.automotive.AutomotiveFacts
import space.neural.luna.automotive.AutomotiveProfile

class VehicleProviderFactoryTest {
    private fun facts(model: String) = AutomotiveFacts(model,false,false,1280,720)
    @Test fun normalTabletNeverLoadsPublicCarSdkWithoutOptIn() = runBlocking {
        var attempted = false
        val provider = VehicleProviderFactory.createForProfile(AutomotiveProfile.evaluate(facts("tablet"))) {
            attempted = true; UnavailableVehicleProvider("Fixture")
        }
        assertFalse(attempted); assertFalse(provider.connect().connected)
    }
    @Test fun knownHeadUnitWithoutFeatureFlagAttemptsPublicSdkButNeverGrantsCapabilities() = runBlocking {
        var attempted = false
        val provider = VehicleProviderFactory.createForProfile(AutomotiveProfile.evaluate(facts("IHU629G"))) {
            attempted = true; UnavailableVehicleProvider("SDK não autorizado na fixture")
        }
        assertTrue(attempted); assertFalse(provider.connect().connected)
        assertTrue(provider.capabilities().all { !it.readable && !it.writable })
    }
    @Test fun manualChoiceAllowsAttemptAndMissingSdkRemainsUnavailable() = runBlocking {
        val profile = AutomotiveProfile.evaluate(facts("tablet"),manualOptIn=true)
        val missing = VehicleProviderFactory.createForProfile(profile) { throw NoClassDefFoundError("android.car.Car") }
        assertFalse(missing.connect().connected)
        val denied = VehicleProviderFactory.createForProfile(profile) { throw SecurityException("Fixture denied") }
        assertFalse(denied.connect().connected)
        assertTrue(denied.capabilities().all { !it.readable && !it.writable })
    }
}
