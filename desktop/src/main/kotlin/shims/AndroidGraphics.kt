package android.graphics

import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import org.jetbrains.skia.Surface
import org.jetbrains.skia.SamplingMode
import org.jetbrains.skia.FilterMipmap
import org.jetbrains.skia.FilterMode
import org.jetbrains.skia.MipmapMode
import org.jetbrains.skia.Rect
import java.io.OutputStream

/** Skia 그림 하나를 감싼 Bitmap 흉내. */
class Bitmap internal constructor(internal var image: Image) {
    val width: Int get() = image.width
    val height: Int get() = image.height
    fun compress(format: CompressFormat, quality: Int, out: OutputStream): Boolean {
        val f = when (format) { CompressFormat.PNG -> EncodedImageFormat.PNG; CompressFormat.JPEG -> EncodedImageFormat.JPEG; else -> EncodedImageFormat.WEBP }
        val data = image.encodeToData(f, quality.coerceIn(0, 100)) ?: image.encodeToData(EncodedImageFormat.PNG, 100) ?: return false
        out.write(data.bytes); return true
    }
    fun recycle() {}
    enum class CompressFormat { JPEG, PNG, WEBP, WEBP_LOSSY, WEBP_LOSSLESS }
    enum class Config { ARGB_8888, RGB_565 }
    companion object {
        fun createScaledBitmap(src: Bitmap, w: Int, h: Int, filter: Boolean): Bitmap {
            val s = Surface.makeRasterN32Premul(w.coerceAtLeast(1), h.coerceAtLeast(1))
            s.canvas.drawImageRect(src.image, Rect.makeWH(src.width.toFloat(), src.height.toFloat()), Rect.makeWH(w.toFloat(), h.toFloat()),
                SamplingMode.LINEAR, null, true)
            return Bitmap(s.makeImageSnapshot())
        }
        fun createBitmap(w: Int, h: Int, config: Config): Bitmap = Bitmap(Surface.makeRasterN32Premul(w.coerceAtLeast(1), h.coerceAtLeast(1)).makeImageSnapshot())
    }
}

object BitmapFactory {
    class Options {
        @JvmField var inJustDecodeBounds = false
        @JvmField var inSampleSize = 1
        @JvmField var outWidth = -1
        @JvmField var outHeight = -1
    }
    private fun decode(bytes: ByteArray, opts: Options?): Bitmap? {
        val img = runCatching { Image.makeFromEncoded(bytes) }.getOrNull() ?: return null
        if (opts != null) { opts.outWidth = img.width; opts.outHeight = img.height; if (opts.inJustDecodeBounds) return null }
        val k = (opts?.inSampleSize ?: 1).coerceAtLeast(1)
        val b = Bitmap(img)
        return if (k > 1) Bitmap.createScaledBitmap(b, img.width / k, img.height / k, true) else b
    }
    fun decodeFile(path: String): Bitmap? = decodeFile(path, null)
    fun decodeFile(path: String, opts: Options?): Bitmap? = runCatching { java.io.File(path).readBytes() }.getOrNull()?.let { decode(it, opts) }
    fun decodeByteArray(b: ByteArray, off: Int, len: Int): Bitmap? = decode(b.copyOfRange(off, off + len), null)
    fun decodeByteArray(b: ByteArray, off: Int, len: Int, opts: Options?): Bitmap? = decode(b.copyOfRange(off, off + len), opts)
    fun decodeStream(s: java.io.InputStream?): Bitmap? = s?.readBytes()?.let { decode(it, null) }
}

class Canvas(private val target: Bitmap) {
    private val surface = Surface.makeRasterN32Premul(target.width, target.height).also { it.canvas.drawImage(target.image, 0f, 0f) }
    private fun flush() { target.image = surface.makeImageSnapshot() }
    fun drawColor(color: Int) { surface.canvas.clear(color); flush() }
    fun drawBitmap(b: Bitmap, left: Float, top: Float, paint: Any?) { surface.canvas.drawImage(b.image, left, top); flush() }
}

object Color {
    const val WHITE = -0x1
    const val BLACK = -0x1000000
    const val TRANSPARENT = 0
}
