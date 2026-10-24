package space.neural.luna.navigation

import org.junit.Assert.*
import org.junit.Test

class VehicleMarkerAppearanceTest {
    @Test fun missingOrInvalidBearingIsNeverPresentedAsMeasuredNorth() {
        assertNull(VehicleMarkerAppearance.bearing(null))
        assertNull(VehicleMarkerAppearance.bearing(Float.NaN))
        assertNull(VehicleMarkerAppearance.bearing(Float.POSITIVE_INFINITY))
        assertEquals(90f, VehicleMarkerAppearance.bearing(450f))
        assertEquals(270f, VehicleMarkerAppearance.bearing(-90f))
    }
    @Test fun colorUsesExplicitInputWithNeutralFallback() {
        assertEquals(0xFFB8D8A1.toInt(), VehicleMarkerAppearance.color(" verde pistache "))
        assertEquals(0xFF2680DD.toInt(), VehicleMarkerAppearance.color("AZUL"))
        assertEquals(0xFF123ABC.toInt(), VehicleMarkerAppearance.color("#123abc"))
        assertEquals(VehicleMarkerAppearance.color(null), VehicleMarkerAppearance.color("Geely EX2"))
        assertNotEquals(VehicleMarkerAppearance.color("azul"), VehicleMarkerAppearance.color("vermelho"))
    }
}
