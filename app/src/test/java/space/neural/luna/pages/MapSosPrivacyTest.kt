package space.neural.luna.pages

import android.app.Application
import android.content.Context
import android.os.Bundle
import kotlinx.coroutines.Job
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.osmdroid.util.GeoPoint
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog
import space.neural.luna.collaboration.CommunityEvent
import space.neural.luna.navigation.NavigationRepository
import space.neural.luna.navigation.Route
import space.neural.luna.navigation.SavedDestination

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class MapSosPrivacyTest {
    @Before fun clearNavigation() { RuntimeEnvironment.getApplication().getSharedPreferences("navigation_v1", Context.MODE_PRIVATE).edit().clear().commit() }
    private fun select(activity: MapRadioActivity, value: SavedDestination, ephemeral: Boolean, key: String?) {
        MapRadioActivity::class.java.getDeclaredMethod("selectDestination", SavedDestination::class.java, Boolean::class.javaPrimitiveType, String::class.java).apply { isAccessible = true }.invoke(activity, value, ephemeral, key)
    }
    private fun render(activity: MapRadioActivity, events: List<CommunityEvent>) {
        MapRadioActivity::class.java.getDeclaredMethod("renderEvents", List::class.java, Boolean::class.javaPrimitiveType).apply { isAccessible = true }.invoke(activity, events, true)
    }
    private fun field(activity: MapRadioActivity, name: String) = MapRadioActivity::class.java.getDeclaredField(name).apply { isAccessible = true }.get(activity)
    private fun set(activity: MapRadioActivity, name: String, value: Any?) { MapRadioActivity::class.java.getDeclaredField(name).apply { isAccessible = true }.set(activity, value) }
    private fun event() = CommunityEvent("sos-fixture", "other-driver", "SOS", -18.85, -41.94, "Fixture", System.currentTimeMillis(), System.currentTimeMillis() + 60_000, true, global = true)
    @Test fun sosDestinationNeverEntersHistoryFavoritesOrSavedInstanceState() {
        val controller = Robolectric.buildActivity(MapRadioActivity::class.java).setup()
        val activity = controller.get()
        try {
            val sos = event(); render(activity, listOf(sos))
            select(activity, SavedDestination("sos:true:${sos.id}", "SOS", sos.latitude, sos.longitude), true, "sos:true:${sos.id}")
            val repository = NavigationRepository(activity)
            assertTrue(repository.recent().isEmpty()); assertTrue(repository.favorites().isEmpty())
            val bundle = Bundle(); controller.saveInstanceState(bundle)
            assertFalse(bundle.containsKey("destination_lat")); assertFalse(bundle.containsKey("destination_lon")); assertFalse(bundle.containsKey("destination_name"))
        } finally { controller.pause().stop().destroy() }
    }
    @Test fun removalCancelsPendingRouteAndErasesPrivateDestination() {
        val controller = Robolectric.buildActivity(MapRadioActivity::class.java).setup()
        val activity = controller.get()
        try {
            val sos = event(); render(activity, listOf(sos))
            select(activity, SavedDestination("sos:true:${sos.id}", "SOS", sos.latitude, sos.longitude), true, "sos:true:${sos.id}")
            val pending = Job(); set(activity, "routeJob", pending)
            set(activity, "route", Route(listOf(GeoPoint(-18.85, -41.94), GeoPoint(-18.84, -41.93)), 1000.0, 100.0, emptyList()))
            render(activity, emptyList())
            assertTrue(pending.isCancelled); assertNull(field(activity, "target")); assertNull(field(activity, "route"))
            assertEquals(false, field(activity, "targetEphemeral"))
        } finally { controller.pause().stop().destroy() }
    }
    @Test fun staleOpenSosDialogCannotReuseRevokedCoordinates() {
        val controller = Robolectric.buildActivity(MapRadioActivity::class.java).setup()
        val activity = controller.get()
        try {
            val sos = event(); render(activity, listOf(sos))
            MapRadioActivity::class.java.getDeclaredMethod("eventDetails", CommunityEvent::class.java, Boolean::class.javaPrimitiveType).apply { isAccessible = true }.invoke(activity, sos, true)
            val dialog = ShadowDialog.getLatestDialog() as androidx.appcompat.app.AlertDialog
            render(activity, emptyList())
            assertFalse(dialog.isShowing)
            dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).performClick()
            assertNull(field(activity, "target")); assertTrue(NavigationRepository(activity).recent().isEmpty())
        } finally { controller.pause().stop().destroy() }
    }
    @Test fun driverDestinationIsAlsoEphemeralButNormalDestinationRetainsHistory() {
        val controller = Robolectric.buildActivity(MapRadioActivity::class.java).setup()
        val activity = controller.get()
        try {
            select(activity, SavedDestination("driver:fixture", "Motorista", -18.85, -41.94), true, "driver:fixture")
            assertTrue(NavigationRepository(activity).recent().isEmpty())
            select(activity, SavedDestination("normal-place", "Destino escolhido", -18.85, -41.94), false, null)
            assertEquals("normal-place", NavigationRepository(activity).recent().single().id)
        } finally { controller.pause().stop().destroy() }
    }
}
