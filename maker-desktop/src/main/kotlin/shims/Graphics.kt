// PC판용 android.graphics 흉내(shim): 공용 소스(Tiles.kt 등)가 그대로 컴파일되도록 쓰는 부분만 Skia로 만든 것.
@file:JvmName("MakerGraphicsShimsKt")
package android.graphics

import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import org.jetbrains.skia.PaintMode
import org.jetbrains.skia.RRect
import org.jetbrains.skia.Rect
import org.jetbrains.skia.SamplingMode
import java.io.OutputStream

/** 고칠 수 있는 Skia 비트맵을 감싼 Bitmap. Canvas로 그리면 바로 바뀜. */
class Bitmap internal constructor(val sk: org.jetbrains.skia.Bitmap) {
    val width: Int get() = sk.width
    val height: Int get() = sk.height

    fun copy(config: Config, mutable: Boolean): Bitmap {
        val b = blank(width, height)
        org.jetbrains.skia.Canvas(b.sk).drawImage(Image.makeFromBitmap(sk), 0f, 0f)
        b.sk.notifyPixelsChanged()
        return b
    }

    fun compress(format: CompressFormat, quality: Int, out: OutputStream): Boolean {
        val f = if (format == CompressFormat.JPEG) EncodedImageFormat.JPEG else EncodedImageFormat.PNG
        val data = Image.makeFromBitmap(sk).encodeToData(f, quality.coerceIn(0, 100)) ?: return false
        out.write(data.bytes); return true
    }

    fun recycle() {}

    enum class Config { ARGB_8888, RGB_565 }
    enum class CompressFormat { JPEG, PNG, WEBP }

    companion object {
        private fun blank(w: Int, h: Int): Bitmap {
            val b = org.jetbrains.skia.Bitmap()
            b.allocN32Pixels(w.coerceAtLeast(1), h.coerceAtLeast(1), false)
            b.erase(0)
            return Bitmap(b)
        }

        fun createBitmap(w: Int, h: Int, config: Config): Bitmap = blank(w, h)

        fun createScaledBitmap(src: Bitmap, w: Int, h: Int, filter: Boolean): Bitmap {
            val b = blank(w, h)
            org.jetbrains.skia.Canvas(b.sk).drawImageRect(
                Image.makeFromBitmap(src.sk), Rect.makeWH(src.width.toFloat(), src.height.toFloat()), Rect.makeWH(w.toFloat(), h.toFloat()),
                SamplingMode.LINEAR, null, true,
            )
            b.sk.notifyPixelsChanged()
            return b
        }
    }
}

class RectF {
    @JvmField var left = 0f
    @JvmField var top = 0f
    @JvmField var right = 0f
    @JvmField var bottom = 0f
    fun set(l: Float, t: Float, r: Float, b: Float) { left = l; top = t; right = r; bottom = b }
    internal fun sk() = Rect.makeLTRB(left, top, right, bottom)
}

class Paint(flags: Int = 0) {
    val sk = org.jetbrains.skia.Paint().also { it.isAntiAlias = flags and ANTI_ALIAS_FLAG != 0 }
    var color: Int
        get() = sk.color
        set(v) { sk.color = v }
    var strokeWidth: Float
        get() = sk.strokeWidth
        set(v) { sk.strokeWidth = v }
    var style: Style = Style.FILL
        set(v) { field = v; sk.mode = if (v == Style.STROKE) PaintMode.STROKE else if (v == Style.FILL_AND_STROKE) PaintMode.STROKE_AND_FILL else PaintMode.FILL }

    enum class Style { FILL, STROKE, FILL_AND_STROKE }
    companion object { const val ANTI_ALIAS_FLAG = 1 }
}

class Path {
    val sk = org.jetbrains.skia.Path()
    fun reset() { sk.reset() }
    fun moveTo(x: Float, y: Float) { sk.moveTo(x, y) }
    fun lineTo(x: Float, y: Float) { sk.lineTo(x, y) }
    fun arcTo(oval: RectF, startAngle: Float, sweepAngle: Float) { sk.arcTo(oval.sk(), startAngle, sweepAngle, false) }
    fun close() { sk.closePath() }
}

/** Skia 캔버스를 감싼 Canvas. 비트맵에 그릴 때는 그릴 때마다 바뀐 걸 알려 줌. */
class Canvas private constructor(val sk: org.jetbrains.skia.Canvas, private val target: Bitmap?) {
    constructor(b: Bitmap) : this(org.jetbrains.skia.Canvas(b.sk), b)
    constructor(c: org.jetbrains.skia.Canvas) : this(c, null)

    private fun done() { target?.sk?.notifyPixelsChanged() }

    fun drawRect(l: Float, t: Float, r: Float, b: Float, p: Paint) { sk.drawRect(Rect.makeLTRB(l, t, r, b), p.sk); done() }
    fun drawRoundRect(r: RectF, rx: Float, ry: Float, p: Paint) { sk.drawRRect(RRect.makeLTRB(r.left, r.top, r.right, r.bottom, rx, ry), p.sk); done() }
    fun drawCircle(cx: Float, cy: Float, rad: Float, p: Paint) { sk.drawCircle(cx, cy, rad, p.sk); done() }
    fun drawLine(ax: Float, ay: Float, bx: Float, by: Float, p: Paint) {
        // android draws lines with the stroke width whatever the style
        val m = p.sk.mode; p.sk.mode = PaintMode.STROKE
        sk.drawLine(ax, ay, bx, by, p.sk); p.sk.mode = m; done()
    }
    fun drawArc(r: RectF, start: Float, sweep: Float, useCenter: Boolean, p: Paint) { sk.drawArc(r.left, r.top, r.right, r.bottom, start, sweep, useCenter, p.sk); done() }
    fun drawOval(r: RectF, p: Paint) { sk.drawOval(r.sk(), p.sk); done() }
    fun drawPath(path: Path, p: Paint) { sk.drawPath(path.sk, p.sk); done() }
}

object Color {
    const val WHITE = -0x1
    const val BLACK = -0x1000000
    const val TRANSPARENT = 0
    fun argb(a: Int, r: Int, g: Int, b: Int): Int = (a shl 24) or (r shl 16) or (g shl 8) or b
}
