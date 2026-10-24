package space.neural.luna.vehicle

import org.junit.Assert.*
import org.junit.Test

class VehicleSafetyTest {
    private val now = 10_000_000_000L
    private fun reading(property: VehicleProperty, value: Any, time: Long = now) = VehicleReading(property,0,value,VehicleReadingState.LIVE,time,1000)
    @Test fun parsedCatalogIsMetadataOnlyAndKeepsDistinctOemAndStandardIds() {
        val data = "Property:0x2140800f,Property name:GEAR_SELECTION,access:0x3,config:0x[]\nProperty:0x11400401,Property name:CURRENT_GEAR,access:0x1\nProperty:0x11400401,Property name:CURRENT_GEAR"
        val parsed = VehicleCatalogParser.parse(data)
        assertEquals(2, parsed.size); assertEquals(3, parsed[0].access)
        assertEquals(0x11400401, parsed[1].id)
    }
    @Test fun futureMissingAndExpiredTimestampsHideValues() {
        val sample = reading(VehicleProperty.SPEED, 0.0)
        assertTrue(sample.current(now))
        assertFalse(sample.copy(sampleElapsedNanos = 0).current(now))
        assertFalse(sample.copy(sampleElapsedNanos = now+1).current(now))
        assertEquals(VehicleReadingState.STALE, sample.effective(now+6_000_000_000).state)
        assertNull(sample.effective(now+6_000_000_000).value)
    }
    @Test fun writesNeedTwoFreshAuthorizedMeasurementsWithExactStationarySpeed() {
        val speed = reading(VehicleProperty.SPEED,0.0)
        val gear = reading(VehicleProperty.GEAR,4)
        assertTrue(VehicleSafety.parked(speed,gear,now,4))
        assertFalse(VehicleSafety.parked(speed.copy(value=.01),gear,now,4))
        assertFalse(VehicleSafety.parked(speed,gear.copy(value=2),now,4))
        assertFalse(VehicleSafety.parked(speed.copy(state=VehicleReadingState.PERMISSION_DENIED),gear,now,4))
        assertFalse(VehicleSafety.parked(speed,gear.copy(sampleElapsedNanos=now-3_000_000_000),now,4))
        assertFalse(VehicleSafety.parked(speed.copy(value=Double.NaN),gear,now,4))
    }
    @Test fun comfortWhitelistIntersectsRealAreaLimitsAndRejectsCriticalOrFractionalFan() {
        val temperature = VehicleAreaCapability(31,16.0,32.0)
        assertTrue(VehicleSafety.validateComfort(VehicleProperty.TEMPERATURE,temperature,23.0))
        assertFalse(VehicleSafety.validateComfort(VehicleProperty.TEMPERATURE,temperature,33.0))
        assertFalse(VehicleSafety.validateComfort(VehicleProperty.TEMPERATURE,temperature,Double.NaN))
        assertFalse(VehicleSafety.validateComfort(VehicleProperty.TEMPERATURE,temperature.copy(minimum=null),23.0))
        assertFalse(VehicleSafety.validateComfort(VehicleProperty.SPEED,temperature,23.0))
        assertTrue(VehicleSafety.validateComfort(VehicleProperty.FAN,VehicleAreaCapability(75,1.0,7.0),3.0))
        assertFalse(VehicleSafety.validateComfort(VehicleProperty.FAN,VehicleAreaCapability(75,1.0,7.0),3.5))
    }
    @Test fun socNeedsCurrentCapacityAndNeverUsesNominalAsCurrent() {
        val energy = reading(VehicleProperty.BATTERY_ENERGY,20000.0)
        val capacity = reading(VehicleProperty.BATTERY_CAPACITY,40000.0)
        assertEquals(50.0, VehicleSafety.socPercent(energy,capacity,now)!!,.001)
        assertNull(VehicleSafety.socPercent(energy,capacity.copy(property=VehicleProperty.BATTERY_NOMINAL_CAPACITY),now))
        assertNull(VehicleSafety.socPercent(energy,capacity.copy(value=0.0),now))
        assertNull(VehicleSafety.socPercent(energy.copy(value=50000.0),capacity,now))
        assertNull(VehicleSafety.socPercent(energy.copy(state=VehicleReadingState.UNAVAILABLE),capacity,now))
    }
}
