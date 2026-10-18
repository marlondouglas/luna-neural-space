package space.neural.luna.chat

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import java.io.ByteArrayOutputStream

/** Reduz a foto da nota antes de enviar: lado maior ≤ 1600 px, JPEG 80 (normalmente 150–400 KB). */
object Foto {
    const val MAX_LADO = 1600

    fun reduzir(ctx: Context, uri: Uri): ByteArray {
        val cr = ctx.contentResolver
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        cr.openInputStream(uri).use { BitmapFactory.decodeStream(it, null, o) }
        var amostra = 1
        while (maxOf(o.outWidth, o.outHeight) / (amostra * 2) >= MAX_LADO) amostra *= 2
        val bmp = cr.openInputStream(uri).use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = amostra }) }
            ?: error("imagem inválida")
        val giro = runCatching {
            cr.openInputStream(uri).use {
                when (android.media.ExifInterface(it!!).getAttributeInt(android.media.ExifInterface.TAG_ORIENTATION, 1)) {
                    android.media.ExifInterface.ORIENTATION_ROTATE_90 -> 90
                    android.media.ExifInterface.ORIENTATION_ROTATE_180 -> 180
                    android.media.ExifInterface.ORIENTATION_ROTATE_270 -> 270
                    else -> 0
                }
            }
        }.getOrDefault(0)
        return comprimir(bmp, giro)
    }

    fun comprimir(bmp: Bitmap, giro: Int = 0): ByteArray {
        val maior = maxOf(bmp.width, bmp.height)
        val esc = if (maior > MAX_LADO) MAX_LADO.toFloat() / maior else 1f
        val m = Matrix().apply { postScale(esc, esc); if (giro != 0) postRotate(giro.toFloat()) }
        val final = if (esc != 1f || giro != 0) Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true) else bmp
        return ByteArrayOutputStream().use { final.compress(Bitmap.CompressFormat.JPEG, 80, it); it.toByteArray() }
    }
}
