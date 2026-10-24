package space.neural.luna.automotive

import org.junit.Assert.*
import org.junit.Test

class AutomotiveProfileTest {
    private fun facts(model: String = "tablet", width: Int = 1280, height: Int = 720) = AutomotiveFacts(model, false, false, width, height)
    @Test fun landscapeTabletDoesNotBecomeAutomotive() {
        assertFalse(AutomotiveProfile.evaluate(facts()).automotive)
        assertTrue(AutomotiveProfile.evaluate(facts(), manualOptIn = true).automotive)
    }
    @Test fun observedHeadUnitUsesLargerTargetsWithoutInferringCameraOrTelemetry() {
        val profile = AutomotiveProfile.evaluate(facts("IHU629G").copy(cameraFeature = true, captureActivityAvailable = true, microphoneFeature = true))
        assertTrue(profile.automotive); assertEquals(56, profile.minimumTouchDp)
        assertFalse(profile.cameraCaptureOffered); assertFalse(profile.telemetryAuthorized)
        assertFalse(profile.microphonePermissionGranted)
    }
    @Test fun shortCarDisplayAndLargeFontUseSlidingPanels() {
        assertFalse(AutomotiveProfile.evaluate(facts("IHU629G", 915, 412)).mapSidePanels)
        assertFalse(AutomotiveProfile.evaluate(facts("IHU629G").copy(fontScale = 1.6f)).mapSidePanels)
        assertTrue(AutomotiveProfile.evaluate(facts("IHU629G")).mapSidePanels)
    }
    @Test fun pttStandardFocusedPressReleaseAndCancellation() {
        val state = FocusedPttKeyState()
        assertFalse(state.press(85, 0, true, true, true)) // Media play/pause is never captured.
        assertFalse(state.press(23, 0, false, true, true))
        assertFalse(state.press(23, 0, true, false, true))
        assertFalse(state.press(23, 0, true, true, false))
        assertTrue(state.press(23, 0, true, true, true))
        assertFalse(state.press(23, 1, true, true, true)); assertFalse(state.press(66, 0, true, true, true))
        assertFalse(state.release(66)); assertTrue(state.release(23)); assertFalse(state.release(23))
        assertTrue(state.press(66, 0, true, true, true)); assertTrue(state.cancel()); assertFalse(state.cancel())
    }
}
