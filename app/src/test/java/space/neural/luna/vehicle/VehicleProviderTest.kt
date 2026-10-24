package space.neural.luna.vehicle

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class VehicleProviderTest {
    @Test fun unsupportedProviderNeverCreatesValuesOrSuccessfulWrites() = runBlocking {
        val provider = UnavailableVehicleProvider("SDK OEM não fornecido")
        assertFalse(provider.connect().connected)
        assertTrue(provider.capabilities().all { !it.readable && !it.writable })
        val read = provider.read(VehicleProperty.SPEED)
        assertNull(read.value); assertEquals(VehicleReadingState.UNAVAILABLE, read.state)
        val write = provider.writeComfort(VehicleProperty.TEMPERATURE,31,23.0,true)
        assertNull(write.value); assertEquals(VehicleReadingState.UNAVAILABLE, write.state)
        provider.close()
    }
}
