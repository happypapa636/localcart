package app.localcart.media

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.util.Base64
import java.io.ByteArrayOutputStream
import java.util.Locale

/**
 * Nexconn location messages (RC:LBSMsg) require `content`: a Base64 JPEG thumbnail of the place.
 * LocalCart has no map tiles, so it draws a small pin card in the app palette with the coordinates,
 * which every Nexconn client can show as the location preview.
 */
object LocationThumbnail {
    private const val SIZE = 160
    private const val PAPER = 0xFFF4F6F3.toInt()
    private const val INK = 0xFF17223B.toInt()
    private const val LEAF = 0xFF0E6B4A.toInt()
    private const val RULE = 0x1F17223B

    fun base64(lat: Double, lng: Double): String {
        val bitmap = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(PAPER)

        // A quiet street grid so the tile reads as a map.
        val grid = Paint().apply { color = RULE; strokeWidth = 2f }
        for (i in 1..5) {
            val p = i * SIZE / 6f
            canvas.drawLine(p, 0f, p, SIZE.toFloat(), grid)
            canvas.drawLine(0f, p, SIZE.toFloat(), p, grid)
        }

        // Pin: a circle head with a pointed tail, in leaf green, with a paper dot.
        val pin = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = LEAF; style = Paint.Style.FILL }
        val cx = SIZE / 2f
        val cy = SIZE * 0.40f
        val r = SIZE * 0.13f
        val tail = Path().apply {
            moveTo(cx - r * 0.75f, cy + r * 0.55f)
            lineTo(cx, cy + r * 2.1f)
            lineTo(cx + r * 0.75f, cy + r * 0.55f)
            close()
        }
        canvas.drawPath(tail, pin)
        canvas.drawCircle(cx, cy, r, pin)
        canvas.drawCircle(cx, cy, r * 0.4f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = PAPER })

        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = INK
            textSize = SIZE * 0.075f
            textAlign = Paint.Align.CENTER
        }
        canvas.drawText(String.format(Locale.US, "%.5f, %.5f", lat, lng), cx, SIZE * 0.86f, text)

        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 60, out)
        bitmap.recycle()
        return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    }
}
