package space.neural.luna.pages

import android.Manifest
import android.app.Application
import android.location.Location
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class MapGpsPrivacyTest {
    private fun fix(time: Long = System.currentTimeMillis(), latitude: Double = -18.85) = Location("gps").apply {
        this.time = time; this.latitude = latitude; longitude = -41.94; accuracy = 5f
    }
    private fun field(activity: MapRadioActivity, name: String): Any? = MapRadioActivity::class.java.getDeclaredField(name).apply { isAccessible = true }.get(activity)
    @Test fun revokedPermissionClearsOwnMarkerAndQueuedLocationCannotRestoreIt() {
        val app = RuntimeEnvironment.getApplication()
        shadowOf(app).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        val controller = Robolectric.buildActivity(MapRadioActivity::class.java).setup()
        val activity = controller.get()
        try {
            activity.onLocationChanged(fix())
            assertNotNull(field(activity, "fix")); assertNotNull(field(activity, "ownMarker"))
            shadowOf(app).denyPermissions(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
            MapRadioActivity::class.java.getDeclaredMethod("expireGpsFix").apply { isAccessible = true }.invoke(activity)
            assertNull(field(activity, "fix")); assertNull(field(activity, "ownMarker"))
            activity.onLocationChanged(fix())
            assertNull(field(activity, "fix")); assertNull(field(activity, "ownMarker"))
        } finally { controller.pause().stop().destroy() }
    }
    @Test fun lateProviderSampleAndInvalidCoordinateDoNotReplaceLatestMarker() {
        val app = RuntimeEnvironment.getApplication()
        shadowOf(app).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        val controller = Robolectric.buildActivity(MapRadioActivity::class.java).setup()
        val activity = controller.get()
        try {
            val latest = fix()
            activity.onLocationChanged(latest)
            activity.onLocationChanged(fix(latest.time - 5000, -18.84))
            assertEquals(latest.time, (field(activity, "fix") as Location).time)
            activity.onLocationChanged(fix(latitude = Double.NaN))
            assertEquals(-18.85, (field(activity, "fix") as Location).latitude, .00001)
        } finally { controller.pause().stop().destroy() }
    }
    @Test fun approximateOnlyPermissionDoesNotReusePreviouslyPreciseFix() {
        val app = RuntimeEnvironment.getApplication()
        shadowOf(app).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        val controller = Robolectric.buildActivity(MapRadioActivity::class.java).setup()
        val activity = controller.get()
        try {
            val previous = fix()
            activity.onLocationChanged(previous)
            shadowOf(app).denyPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
            MapRadioActivity::class.java.getDeclaredMethod("expireGpsFix").apply { isAccessible = true }.invoke(activity)
            assertNull(field(activity, "fix")); assertNull(field(activity, "ownMarker"))
            activity.onLocationChanged(previous)
            assertNull(field(activity, "fix"))
            activity.onLocationChanged(fix().apply { provider = "network"; accuracy = 1000f })
            assertNotNull(field(activity, "fix"))
        } finally { controller.pause().stop().destroy() }
    }
}
