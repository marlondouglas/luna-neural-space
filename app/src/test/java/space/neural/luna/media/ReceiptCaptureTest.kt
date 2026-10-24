package space.neural.luna.media

import android.app.Activity
import android.app.Application
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import androidx.appcompat.app.AppCompatActivity
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class ReceiptCaptureTest {
    class CaptureActivity : AppCompatActivity() {
        lateinit var capture: ReceiptCapture
        val errors = mutableListOf<String>()
        val images = mutableListOf<Uri>()
        val files = mutableListOf<java.io.File>()
        override fun onCreate(state: Bundle?) {
            setTheme(androidx.appcompat.R.style.Theme_AppCompat)
            super.onCreate(state)
            // AndroidX FileProvider compares '/' paths; Windows Robolectric uses '\\'.
            // Keep the platform URI adapter separate while testing the capture lifecycle.
            capture = ReceiptCapture(this, { images += it }, { errors += it }, contentUri = {
                files += it
                Uri.parse("content://space.neural.luna.fileprovider/fotos/${it.name}")
            })
        }
    }
    @Test fun noCameraDoesNotLaunchOrCreateAnEntry() {
        val controller = Robolectric.buildActivity(CaptureActivity::class.java).setup()
        val activity = controller.get()
        shadowOf(activity.packageManager).setSystemFeature(PackageManager.FEATURE_CAMERA_ANY, false)
        activity.capture.capture()
        assertTrue(activity.errors.single().contains("não disponível"))
        assertTrue(activity.images.isEmpty())
        assertNull(shadowOf(activity).nextStartedActivityForResult)
        controller.pause().stop().destroy()
    }
    @Test fun cameraUsesContentUriAndRejectsEmptyResult() {
        val controller = Robolectric.buildActivity(CaptureActivity::class.java).setup()
        val activity = controller.get()
        shadowOf(activity.packageManager).setSystemFeature(PackageManager.FEATURE_CAMERA_ANY, true)
        activity.capture.capture()
        val started = shadowOf(activity).nextStartedActivityForResult
        assertNotNull("Camera launch failed: ${activity.errors}", started)
        assertEquals(MediaStore.ACTION_IMAGE_CAPTURE, started.intent.action)
        val uri = started.intent.getParcelableExtra<Uri>(MediaStore.EXTRA_OUTPUT)!!
        assertEquals("content", uri.scheme)
        shadowOf(activity).receiveResult(started.intent, Activity.RESULT_OK, Intent())
        assertTrue(activity.images.isEmpty())
        assertTrue(activity.errors.single().contains("foto válida"))
        activity.capture.capture()
        assertEquals(2, activity.files.size)
        assertNotEquals(activity.files[0].canonicalPath, activity.files[1].canonicalPath)
        controller.pause().stop().destroy()
    }
    @Test fun pendingCaptureSurvivesActivityRecreation() {
        val old = Robolectric.buildActivity(CaptureActivity::class.java).setup()
        shadowOf(old.get().packageManager).setSystemFeature(PackageManager.FEATURE_CAMERA_ANY, true)
        old.get().capture.capture()
        val launch = shadowOf(old.get()).nextStartedActivityForResult!!
        val saved = Bundle()
        old.pause().saveInstanceState(saved).stop().destroy()
        val recreated = Robolectric.buildActivity(CaptureActivity::class.java).create(saved).start().resume().visible()
        assertTrue(recreated.get().activityResultRegistry.dispatchResult(launch.requestCode, Activity.RESULT_OK, Intent()))
        // An empty result is reported only if the pending URI was recovered, not lost on rotation.
        assertTrue(recreated.get().errors.single().contains("foto válida"))
        assertTrue(recreated.get().images.isEmpty())
        recreated.pause().stop().destroy()
    }
}
