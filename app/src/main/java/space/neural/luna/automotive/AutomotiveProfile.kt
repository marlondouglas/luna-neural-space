package space.neural.luna.automotive

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
import android.provider.MediaStore
import androidx.core.content.ContextCompat

data class AutomotiveFacts(val model: String, val carUiMode: Boolean, val automotiveFeature: Boolean,
    val widthDp: Int, val heightDp: Int, val fontScale: Float = 1f, val cameraFeature: Boolean = false,
    val captureActivityAvailable: Boolean = false, val microphoneFeature: Boolean = false, val microphonePermission: Boolean = false)

/** No serial/VIN/proprietary system APIs, network, privileged permission or implicit VHAL access. */
data class AutomotiveProfile(val automotive: Boolean, val minimumTouchDp: Int, val mapSidePanels: Boolean,
    val cameraCaptureOffered: Boolean, val microphonePermissionGranted: Boolean, val microphoneDeclared: Boolean) {
    val telemetryAuthorized: Boolean get() = false
    companion object {
        fun evaluate(facts: AutomotiveFacts, manualOptIn: Boolean = false): AutomotiveProfile {
            val car = manualOptIn || facts.carUiMode || facts.automotiveFeature || facts.model.trim().equals("IHU629G", true)
            return AutomotiveProfile(car, if (car) 56 else 48,
                facts.widthDp >= 840 && facts.fontScale < 1.6f && (!car || facts.heightDp >= 480),
                !car && facts.cameraFeature && facts.captureActivityAvailable,
                facts.microphonePermission, facts.microphoneFeature)
        }
        fun current(context: Context, manualOptIn: Boolean = false): AutomotiveProfile {
            val config = context.resources.configuration
            val pm = context.packageManager
            return evaluate(AutomotiveFacts(Build.MODEL.orEmpty(), config.uiMode and Configuration.UI_MODE_TYPE_MASK == Configuration.UI_MODE_TYPE_CAR,
                pm.hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE), config.screenWidthDp, config.screenHeightDp, config.fontScale,
                pm.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY),
                runCatching { Intent(MediaStore.ACTION_IMAGE_CAPTURE).resolveActivity(pm) != null }.getOrDefault(false),
                pm.hasSystemFeature(PackageManager.FEATURE_MICROPHONE),
                ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED), manualOptIn)
        }
    }
}

/** A focused PTT button may consume only standard activation keys. No global steering-wheel/media binding. */
class FocusedPttKeyState {
    private var pressedKey: Int? = null
    fun press(key: Int, repeat: Int, focused: Boolean, foreground: Boolean, ready: Boolean): Boolean {
        if (key != 23 && key != 66 && key != 160) return false // DPAD_CENTER, ENTER, NUMPAD_ENTER
        if (repeat != 0 || pressedKey != null || !focused || !foreground || !ready) return false
        pressedKey = key; return true
    }
    fun release(key: Int): Boolean = if (pressedKey == key) { pressedKey = null; true } else false
    fun holds(key: Int): Boolean = pressedKey == key
    fun cancel(): Boolean = (pressedKey != null).also { pressedKey = null }
}
