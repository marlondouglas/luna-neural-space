package space.neural.luna.navigation

import org.junit.Assert.*
import org.junit.Test

class LocationFreshnessTest {
    @Test fun rejectsMissingFutureAndStaleFixes() {
        assertFalse(LocationFreshness.isCurrent(0, 200_000))
        assertFalse(LocationFreshness.isCurrent(200_001, 200_000))
        assertFalse(LocationFreshness.isCurrent(109_999, 200_000))
        assertTrue(LocationFreshness.isCurrent(110_000, 200_000))
        assertTrue(LocationFreshness.isCurrent(200_000, 200_000))
    }
    @Test fun heartbeatDoesNotRenewOriginalFixTime() {
        assertTrue(LocationFreshness.isCurrent(100_000, 150_000))
        assertFalse(LocationFreshness.isCurrent(100_000, 200_000))
    }
    @Test fun revokedPermissionDisablesEvenAJustMeasuredFix() {
        assertFalse(LocationFreshness.usable(false, 200_000, -18.85, -41.94, 5f, 200_000))
        assertTrue(LocationFreshness.usable(true, 200_000, -18.85, -41.94, 5f, 200_000))
    }
    @Test fun rejectsInvalidCoordinatesAndAccuracy() {
        assertFalse(LocationFreshness.usable(true, 200_000, Double.NaN, -41.94, 5f, 200_000))
        assertFalse(LocationFreshness.usable(true, 200_000, -18.85, 181.0, 5f, 200_000))
        assertFalse(LocationFreshness.usable(true, 200_000, -18.85, -41.94, Float.NaN, 200_000))
        assertFalse(LocationFreshness.usable(true, 200_000, -18.85, -41.94, -1f, 200_000))
        assertTrue(LocationFreshness.usable(true, 200_000, -18.85, -41.94, null, 200_000))
    }
    @Test fun delayedProviderCannotReplaceNewerMeasuredFix() {
        assertFalse(LocationFreshness.notOlderThan(199_999, 200_000))
        assertTrue(LocationFreshness.notOlderThan(200_000, 200_000))
        assertTrue(LocationFreshness.notOlderThan(200_001, 200_000))
        assertTrue(LocationFreshness.notOlderThan(200_000, null))
    }
}
