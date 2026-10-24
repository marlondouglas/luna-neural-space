package space.neural.luna.media

import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import java.io.File
import java.util.UUID

/** External camera owns CAMERA permission; this app receives only a granted content URI. */
class ReceiptCapture(private val activity: AppCompatActivity, private val ready: (Uri) -> Unit,
    private val error: (String) -> Unit, private val beforeOpen: () -> Unit = {},
    private val contentUri: (File) -> Uri = { file -> FileProvider.getUriForFile(activity, "${activity.packageName}.fileprovider", file) }) {
    private val stateKey = "receipt_capture"
    private var pending: Uri? = null
    private var restored = false
    private val picture = activity.registerForActivityResult(ActivityResultContracts.TakePicture()) { success ->
        restore()
        val uri = pending; pending = null
        if (success && uri != null) {
            val valid = runCatching { activity.contentResolver.openFileDescriptor(uri, "r")?.use { it.statSize > 0 } == true }.getOrDefault(false)
            if (valid) ready(uri) else error("A câmera não devolveu uma foto válida. Tente novamente ou escolha uma imagem.")
        } else if (!success) error("Captura cancelada. Nenhum lançamento foi criado.")
    }
    private val picker = activity.registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) ready(uri)
    }
    init {
        activity.savedStateRegistry.registerSavedStateProvider(stateKey) { restore(); Bundle().apply { putString("uri", pending?.toString()) } }
    }
    private fun restore() {
        if (restored) return
        pending = activity.savedStateRegistry.consumeRestoredStateForKey(stateKey)?.getString("uri")?.let(Uri::parse) ?: pending
        restored = true
    }
    fun canTakePhoto(): Boolean = !space.neural.luna.automotive.AutomotiveProfile.current(activity).automotive && activity.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY) &&
        activity.getSharedPreferences("onboarding", 0).getString("dispositivo", "PHONE") != "CARRO"
    fun capture() {
        restore(); beforeOpen()
        if (!canTakePhoto()) { error("Câmera não disponível neste dispositivo. Escolha uma imagem da galeria."); return }
        runCatching {
            val directory = File(activity.cacheDir, "fotos").apply { check(exists() || mkdirs()) }
            val file = File.createTempFile("receipt-${UUID.randomUUID()}-", ".jpg", directory)
            pending = contentUri(file)
            picture.launch(pending!!)
        }.onFailure {
            pending = null; error("Não foi possível abrir a câmera. Use a galeria ou verifique o aplicativo de câmera.")
        }
    }
    fun selectImage() {
        beforeOpen()
        runCatching { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
            .onFailure { error("Não foi possível abrir o seletor de imagens. Tente novamente.") }
    }
    fun restorePending() = restore()
}
