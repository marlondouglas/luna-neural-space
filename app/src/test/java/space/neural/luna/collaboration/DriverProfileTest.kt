package space.neural.luna.collaboration

import org.junit.Assert.*
import org.junit.Test

class DriverProfileTest {
    @Test fun manualTypeAndIconArePrivateAndNeverPresetFromVehicleName() {
        val p = DriverProfile(vehicleModel = "Geely EX2 Max", vehicleBrand = "Geely")
        assertEquals(VehiclePowertrain.UNINFORMED, p.vehiclePowertrain)
        assertEquals(VehicleIcon.GENERIC, p.vehicleIcon)
        val selected = p.copy(vehiclePowertrain = VehiclePowertrain.ELECTRIC, vehicleIcon = VehicleIcon.HATCH)
        assertFalse(selected.publicFields().containsKey("vehiclePowertrain"))
        assertFalse(selected.publicFields().containsKey("vehicleIcon"))
        assertFalse(selected.publicFields().containsKey("vehicleBrand"))
        assertFalse(selected.publicFields().containsKey("licensePlate"))
    }
    @Test fun unknownStoredEnumsSafelyMigrateWithoutGuessing() {
        assertEquals(VehiclePowertrain.UNINFORMED, VehiclePowertrain.fromStored(null))
        assertEquals(VehiclePowertrain.UNINFORMED, VehiclePowertrain.fromStored("future-type"))
        assertEquals(VehicleIcon.GENERIC, VehicleIcon.fromStored("future-icon"))
        assertEquals(VehiclePowertrain.HYBRID, VehiclePowertrain.fromStored("HYBRID"))
    }
    @Test fun brandIsNormalizedAndNeverPublishedEvenWhenPlateShared() {
        val p = DriverProfile(" EX2 ", " Prata ", "abc-1d23", true, " Geely ").validated()
        assertEquals("Geely", p.vehicleBrand)
        assertFalse(p.publicFields().containsKey("vehicleBrand"))
        assertEquals("ABC1D23", p.publicFields()["licensePlate"])
        assertEquals("EX2", p.publicFields()["vehicleModel"])
    }
    @Test fun existingPositionalConstructorKeepsConsentAndDefaultsBrand() {
        val legacy = DriverProfile("EX2", "Prata", "ABC1234", true)
        assertEquals("", legacy.vehicleBrand)
        assertTrue(legacy.showPlate)
    }
    @Test(expected = IllegalArgumentException::class) fun longBrandRejected() { DriverProfile(vehicleBrand = "a".repeat(61)).validated() }
    @Test fun plateIsPrivateByDefault() {
        assertFalse(DriverProfile().showPlate)
        val fields = DriverProfile("Geely EX2", "Prata", "ABC1D23").publicFields()
        assertFalse(fields.containsKey("licensePlate"))
        assertEquals(false, fields["showPlate"])
        assertEquals("Geely EX2", fields["vehicleModel"])
    }
    @Test fun explicitConsentPublishesNormalizedPlate() {
        val p = DriverProfile(" EX2 ", " Prata ", "abc-1d23", true).validated()
        assertEquals("ABC1D23", p.publicFields()["licensePlate"])
        assertEquals("EX2", p.vehicleModel)
        assertEquals("Prata", p.vehicleColor)
    }
    @Test fun withdrawingConsentRemovesPlateFromPayload() {
        val p = DriverProfile(licensePlate = "ABC1234", showPlate = true)
        assertTrue(p.publicFields().containsKey("licensePlate"))
        assertFalse(p.copy(showPlate = false).publicFields().containsKey("licensePlate"))
    }
    @Test fun blankPlateIsNeverPublishedEvenWithConsent() {
        val fields = DriverProfile(showPlate = true).publicFields()
        assertEquals(false, fields["showPlate"])
        assertFalse(fields.containsKey("licensePlate"))
    }
    @Test(expected = IllegalArgumentException::class) fun invalidPlateRejected() { DriverProfile(licensePlate = "INVALIDA").validated() }
    @Test(expected = IllegalArgumentException::class) fun longModelRejected() { DriverProfile(vehicleModel = "a".repeat(81)).validated() }
}
