package space.neural.luna.navigation

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import space.neural.luna.collaboration.VehicleIcon
import java.text.Normalizer
import java.util.Locale

/** Pure appearance rules. Unknown heading stays unknown; vehicle name never selects a body style. */
object VehicleMarkerAppearance {
    fun bearing(value: Float?): Float? = value?.takeIf { it.isFinite() }?.let { ((it % 360f) + 360f) % 360f }
    fun color(name: String?): Int {
        val key = Normalizer.normalize(name.orEmpty().trim().lowercase(Locale.ROOT), Normalizer.Form.NFD).replace(Regex("\\p{M}"), "")
        if (key.matches(Regex("#[0-9a-f]{6}"))) return (0xFF000000L or key.drop(1).toLong(16)).toInt()
        return when (key) {
            "branco", "branca" -> 0xFFF2F2F5.toInt()
            "preto", "preta" -> 0xFF252938.toInt()
            "prata", "cinza claro" -> 0xFFBEC8D1.toInt()
            "cinza", "cinza escuro" -> 0xFF6F7887.toInt()
            "azul" -> 0xFF2680DD.toInt()
            "vermelho", "vermelha" -> 0xFFEF4463.toInt()
            "verde pistache", "pistache" -> 0xFFB8D8A1.toInt()
            "verde" -> 0xFF24AB80.toInt()
            "amarelo", "amarela" -> 0xFFF3C84D.toInt()
            "laranja" -> 0xFFEE9240.toInt()
            "roxo", "roxa" -> 0xFF9560D8.toInt()
            "bege", "dourado", "dourada" -> 0xFFCBBB91.toInt()
            "marrom" -> 0xFF9E735D.toInt()
            else -> 0xFF7D86A6.toInt()
        }
    }
}

/** Original vector drawing rendered into a bitmap; representative shape, never an exact make/model. */
object VehicleMarkerDrawable {
    fun create(context: Context, colorName: String?, bearingDegrees: Float? = null,
        icon: VehicleIcon = VehicleIcon.GENERIC, sizeDp: Int = 56): Drawable {
        val size = (sizeDp.coerceIn(32, 96) * context.resources.displayMetrics.density).toInt().coerceAtLeast(1)
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.scale(size / 64f, size / 64f)
        VehicleMarkerAppearance.bearing(bearingDegrees)?.let { canvas.rotate(it, 32f, 32f) }
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val halfWidth = if (icon == VehicleIcon.SUV) 13f else 11f
        val rear = when (icon) { VehicleIcon.SEDAN -> 57f; VehicleIcon.HATCH -> 50f; else -> 54f }
        paint.color = 0x7000091D
        canvas.drawRoundRect(18f, 10f, 49f, rear + 3f, 10f, 10f, paint)
        paint.color = 0xFF070B17.toInt()
        for (y in listOf(18f, rear - 13f)) {
            canvas.drawRoundRect(30f - halfWidth, y, 35f - halfWidth, y + 10f, 2f, 2f, paint)
            canvas.drawRoundRect(29f + halfWidth, y, 34f + halfWidth, y + 10f, 2f, 2f, paint)
        }
        paint.color = VehicleMarkerAppearance.color(colorName)
        canvas.drawRoundRect(32f - halfWidth, 8f, 32f + halfWidth, rear, 8f, 8f, paint)
        paint.style = Paint.Style.STROKE; paint.strokeWidth = 1.3f; paint.color = 0xFF00DDF3.toInt()
        canvas.drawRoundRect(32f - halfWidth, 8f, 32f + halfWidth, rear, 8f, 8f, paint)
        paint.style = Paint.Style.FILL; paint.color = 0xFF102440.toInt()
        canvas.drawRoundRect(RectF(24f, 17f, 40f, 28f), 4f, 4f, paint)
        canvas.drawRoundRect(RectF(25f, rear - 14f, 39f, rear - 7f), 3f, 3f, paint)
        paint.color = 0xFFF5F4CC.toInt()
        canvas.drawRoundRect(23f, 10f, 27f, 14f, 1f, 1f, paint)
        canvas.drawRoundRect(37f, 10f, 41f, 14f, 1f, 1f, paint)
        paint.color = 0xFFEF4463.toInt()
        canvas.drawRect(23f, rear - 4f, 27f, rear - 2f, paint)
        canvas.drawRect(37f, rear - 4f, 41f, rear - 2f, paint)
        return BitmapDrawable(context.resources, bitmap)
    }
}
