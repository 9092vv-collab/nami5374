package space.nami.maker

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF

class TileDef(val id: Int, val name: String, val ch: Char, val pass: Boolean, val upper: Boolean)

object Tiles {
    val list = listOf(
        TileDef(0, "없음", '%', false, false),
        TileDef(1, "풀", '.', true, false),
        TileDef(2, "흙길", ',', true, false),
        TileDef(3, "돌바닥", '_', true, false),
        TileDef(4, "나무 바닥", '=', true, false),
        TileDef(5, "모래", ':', true, false),
        TileDef(6, "카펫", '+', true, false),
        TileDef(7, "어두운 바닥", '-', true, false),
        TileDef(8, "물", '~', false, false),
        TileDef(9, "벽", '#', false, false),
        TileDef(10, "창문 벽", 'w', false, false),
        TileDef(11, "지붕", '^', false, false),
        TileDef(12, "바위", 'O', false, true),
        TileDef(13, "나무", 'T', false, true),
        TileDef(14, "꽃", '*', true, true),
        TileDef(15, "덤불", '"', false, true),
        TileDef(16, "문", 'D', true, true),
        TileDef(17, "탁자", 'n', false, true),
        TileDef(18, "침대", 'b', false, true),
        TileDef(19, "책장", 'B', false, true),
        TileDef(20, "울타리", 'f', false, true),
        TileDef(21, "계단", 'S', true, true),
        TileDef(22, "묘비", 't', false, true),
        TileDef(23, "통", 'x', false, true),
        TileDef(24, "촛대", 'i', false, true),
    )

    fun get(id: Int) = list.getOrNull(id) ?: list[0]
    fun byChar(c: Char) = list.firstOrNull { it.ch == c }

    /** Upper layer overrides passability (a door on a wall is walkable, a tree on grass isn't). */
    fun passable(ground: Int, upper: Int) = if (upper != 0) get(upper).pass else get(ground).pass

    fun legend(): String = list.joinToString("\n") { t ->
        "'${t.ch}' ${t.name}" + (if (t.upper) " (위층)" else " (바닥)") + (if (t.pass) "" else ", 못 지나감")
    }
}

object Sprites {
    val ids = listOf("none", "hero", "villager", "girl", "elder", "guard", "ghost", "cat", "chest", "sign", "crystal")
    fun name(id: String) = when (id) {
        "none" -> "없음(투명)"; "hero" -> "주인공"; "villager" -> "마을 사람"; "girl" -> "소녀"; "elder" -> "노인"
        "guard" -> "경비병"; "ghost" -> "유령"; "cat" -> "고양이"; "chest" -> "상자"; "sign" -> "표지판"; "crystal" -> "수정"
        else -> id
    }
}

/** All the art is drawn with shapes, so the app needs no image files. */
object Art {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private val rf = RectF()

    private fun c(v: Long) = v.toInt()
    private fun c(v: Int) = v
    private fun rnd(x: Int, y: Int, k: Int): Int {
        var h = x * 374761393 + y * 668265263 + k * 1442695041
        h = (h xor (h ushr 13)) * 1274126177
        return (h xor (h ushr 16)) and 0x7fffffff
    }

    fun tile(cv: Canvas, id: Int, x0: Float, y0: Float, s: Float, gx: Int, gy: Int) {
        fun r(col: Int, l: Float, t: Float, rr: Float, b: Float) {
            p.style = Paint.Style.FILL; p.color = col; cv.drawRect(x0 + l * s, y0 + t * s, x0 + rr * s, y0 + b * s, p)
        }
        fun rr(col: Int, l: Float, t: Float, r2: Float, b: Float, rad: Float) {
            p.style = Paint.Style.FILL; p.color = col; rf.set(x0 + l * s, y0 + t * s, x0 + r2 * s, y0 + b * s)
            cv.drawRoundRect(rf, rad * s, rad * s, p)
        }
        fun o(col: Int, cx: Float, cy: Float, rad: Float) {
            p.style = Paint.Style.FILL; p.color = col; cv.drawCircle(x0 + cx * s, y0 + cy * s, rad * s, p)
        }
        fun line(col: Int, ax: Float, ay: Float, bx: Float, by: Float, w: Float) {
            p.style = Paint.Style.STROKE; p.strokeWidth = w * s; p.color = col
            cv.drawLine(x0 + ax * s, y0 + ay * s, x0 + bx * s, y0 + by * s, p); p.style = Paint.Style.FILL
        }
        fun specks(col: Int, n: Int, w: Float, h: Float) {
            for (k in 0 until n) {
                val a = rnd(gx, gy, k + id * 7)
                val px = (a % 1000) / 1000f * (1 - w); val py = ((a / 1000) % 1000) / 1000f * (1 - h)
                r(col, px, py, px + w, py + h)
            }
        }
        when (id) {
            0 -> r(c(0xFF141317), 0f, 0f, 1f, 1f)
            1 -> { r(c(0xFF7DB45A), 0f, 0f, 1f, 1f); specks(c(0xFF6A9E4A), 4, 0.06f, 0.14f); specks(c(0xFF93C670), 2, 0.05f, 0.1f) }
            2 -> { r(c(0xFFC9A46C), 0f, 0f, 1f, 1f); specks(c(0xFFB48F58), 4, 0.1f, 0.07f); specks(c(0xFFD8B987), 2, 0.07f, 0.05f) }
            3 -> {
                r(c(0xFF9A9A9E), 0f, 0f, 1f, 1f)
                line(c(0xFF808085), 0f, 0.5f, 1f, 0.5f, 0.04f)
                val off = if (gy % 2 == 0) 0.25f else 0.75f
                line(c(0xFF808085), off, 0f, off, 0.5f, 0.04f); line(c(0xFF808085), (off + 0.5f) % 1f, 0.5f, (off + 0.5f) % 1f, 1f, 0.04f)
            }
            4 -> {
                r(c(0xFFB07A48), 0f, 0f, 1f, 1f)
                for (k in 1..3) line(c(0xFF8F5E33), 0f, k * 0.25f, 1f, k * 0.25f, 0.03f)
                specks(c(0xFF9A6A3D), 2, 0.12f, 0.03f)
            }
            5 -> { r(c(0xFFE6D3A0), 0f, 0f, 1f, 1f); specks(c(0xFFD4BE86), 5, 0.05f, 0.05f) }
            6 -> { r(c(0xFFA8323A), 0f, 0f, 1f, 1f); r(c(0xFFC9A045), 0.08f, 0.08f, 0.92f, 0.12f); r(c(0xFFC9A045), 0.08f, 0.88f, 0.92f, 0.92f) }
            7 -> { r(c(0xFF3A3540), 0f, 0f, 1f, 1f); specks(c(0xFF2E2A33), 3, 0.15f, 0.08f) }
            8 -> {
                r(c(0xFF4A8FD0), 0f, 0f, 1f, 1f)
                val a = rnd(gx, gy, 3) % 3
                line(c(0xFF7FB6E8), 0.15f, 0.3f + a * 0.12f, 0.45f, 0.3f + a * 0.12f, 0.05f)
                line(c(0xFF7FB6E8), 0.55f, 0.65f + a * 0.06f, 0.85f, 0.65f + a * 0.06f, 0.05f)
            }
            9, 10 -> {
                r(c(0xFFC8B8A0), 0f, 0f, 1f, 1f)
                line(c(0xFFAD9C82), 0f, 0.33f, 1f, 0.33f, 0.03f); line(c(0xFFAD9C82), 0f, 0.66f, 1f, 0.66f, 0.03f)
                line(c(0xFFAD9C82), 0.5f, 0f, 0.5f, 0.33f, 0.03f); line(c(0xFFAD9C82), 0.25f, 0.33f, 0.25f, 0.66f, 0.03f)
                line(c(0xFFAD9C82), 0.75f, 0.33f, 0.75f, 0.66f, 0.03f); line(c(0xFFAD9C82), 0.5f, 0.66f, 0.5f, 1f, 0.03f)
                r(c(0xFF9C8C74), 0f, 0.92f, 1f, 1f)
                if (id == 10) { r(c(0xFF6B4A2E), 0.22f, 0.2f, 0.78f, 0.72f); r(c(0xFF9FD3F0), 0.28f, 0.26f, 0.72f, 0.66f); line(c(0xFF6B4A2E), 0.5f, 0.26f, 0.5f, 0.66f, 0.05f) }
            }
            11 -> {
                r(c(0xFFB8503F), 0f, 0f, 1f, 1f)
                for (k in 0..3) line(c(0xFF93392C), 0f, 0.12f + k * 0.25f, 1f, 0.12f + k * 0.25f, 0.05f)
            }
            12 -> { o(c(0x33000000), 0.5f, 0.72f, 0.36f); o(c(0xFF8A8680), 0.5f, 0.55f, 0.36f); o(c(0xFFA6A29B), 0.42f, 0.46f, 0.16f) }
            13 -> {
                o(c(0x33000000), 0.5f, 0.86f, 0.26f)
                r(c(0xFF7A5232), 0.42f, 0.6f, 0.58f, 0.92f)
                o(c(0xFF2F7A3A), 0.5f, 0.42f, 0.36f); o(c(0xFF3C9147), 0.42f, 0.34f, 0.2f); o(c(0xFF4FA85A), 0.38f, 0.3f, 0.08f)
            }
            14 -> {
                for (k in 0 until 3) {
                    val a = rnd(gx, gy, 20 + k); val px = 0.2f + (a % 60) / 100f; val py = 0.2f + ((a / 60) % 60) / 100f
                    val col = listOf(c(0xFFF2E15C), c(0xFFF08AB0), c(0xFFFFFFFF))[k]
                    o(col, px, py, 0.08f); o(c(0xFFE8A33C), px, py, 0.03f)
                }
            }
            15 -> { o(c(0xFF3F8A3E), 0.32f, 0.6f, 0.24f); o(c(0xFF3F8A3E), 0.68f, 0.6f, 0.24f); o(c(0xFF4FA14C), 0.5f, 0.45f, 0.26f) }
            16 -> {
                r(c(0xFF7A4E2A), 0.18f, 0.08f, 0.82f, 1f); r(c(0xFF94613A), 0.24f, 0.14f, 0.76f, 1f)
                line(c(0xFF7A4E2A), 0.5f, 0.14f, 0.5f, 1f, 0.03f); o(c(0xFFE0C060), 0.66f, 0.6f, 0.05f)
            }
            17 -> { o(c(0x33000000), 0.5f, 0.8f, 0.36f); rr(c(0xFF8F5E33), 0.1f, 0.3f, 0.9f, 0.7f, 0.08f); rr(c(0xFFB07A48), 0.1f, 0.24f, 0.9f, 0.6f, 0.08f) }
            18 -> {
                rr(c(0xFF7A4E2A), 0.12f, 0.04f, 0.88f, 0.98f, 0.06f)
                rr(c(0xFFF4F1E8), 0.18f, 0.08f, 0.82f, 0.34f, 0.08f)
                rr(c(0xFF5D7FC9), 0.16f, 0.34f, 0.84f, 0.94f, 0.06f); line(c(0xFF4A68A8), 0.16f, 0.42f, 0.84f, 0.42f, 0.03f)
            }
            19 -> {
                r(c(0xFF6B4426), 0.06f, 0f, 0.94f, 0.96f)
                val cols = listOf(c(0xFFC0473F), c(0xFF4A79B8), c(0xFF5FA35A), c(0xFFD9B44A), c(0xFF8E5BA8))
                for (row in 0..1) {
                    val yy = 0.08f + row * 0.45f
                    r(c(0xFF3F2814), 0.12f, yy, 0.88f, yy + 0.38f)
                    for (k in 0..4) r(cols[(k + row * 2 + gx) % 5], 0.14f + k * 0.145f, yy + 0.06f + (k % 2) * 0.04f, 0.26f + k * 0.145f, yy + 0.38f)
                }
            }
            20 -> {
                r(c(0xFFD7B98A), 0f, 0.42f, 1f, 0.52f); r(c(0xFFD7B98A), 0f, 0.66f, 1f, 0.74f)
                r(c(0xFFBF9F6E), 0.1f, 0.3f, 0.22f, 0.9f); r(c(0xFFBF9F6E), 0.78f, 0.3f, 0.9f, 0.9f)
            }
            21 -> { for (k in 0..3) { r(c(0xFF8C8C90), 0.1f, k * 0.25f, 0.9f, k * 0.25f + 0.2f); r(c(0xFF6E6E73), 0.1f, k * 0.25f + 0.18f, 0.9f, k * 0.25f + 0.25f) } }
            22 -> {
                o(c(0x33000000), 0.5f, 0.88f, 0.26f)
                rr(c(0xFF8E8C94), 0.26f, 0.22f, 0.74f, 0.9f, 0.2f); line(c(0xFF6B6970), 0.5f, 0.36f, 0.5f, 0.66f, 0.05f)
                line(c(0xFF6B6970), 0.38f, 0.46f, 0.62f, 0.46f, 0.05f)
            }
            23 -> {
                o(c(0x33000000), 0.5f, 0.86f, 0.3f)
                rr(c(0xFF8F5E33), 0.22f, 0.18f, 0.78f, 0.88f, 0.14f)
                r(c(0xFF5A4636), 0.22f, 0.32f, 0.78f, 0.38f); r(c(0xFF5A4636), 0.22f, 0.68f, 0.78f, 0.74f)
            }
            24 -> {
                r(c(0xFF6E6250), 0.44f, 0.4f, 0.56f, 0.86f); r(c(0xFF6E6250), 0.3f, 0.82f, 0.7f, 0.9f)
                r(c(0xFFF2EBD8), 0.45f, 0.24f, 0.55f, 0.42f)
                o(c(0x55FFC94A), 0.5f, 0.16f, 0.18f); o(c(0xFFFFB13B), 0.5f, 0.17f, 0.06f)
            }
        }
    }

    /** dir: 0 down, 1 left, 2 right, 3 up. bob: walking frame offset (0/1). */
    fun sprite(cv: Canvas, g: String, dir: Int, x0: Float, y0: Float, s: Float, bob: Int = 0) {
        fun o(col: Int, cx: Float, cy: Float, rad: Float) {
            p.style = Paint.Style.FILL; p.color = col; cv.drawCircle(x0 + cx * s, y0 + cy * s, rad * s, p)
        }
        fun rr(col: Int, l: Float, t: Float, r2: Float, b: Float, rad: Float) {
            p.style = Paint.Style.FILL; p.color = col; rf.set(x0 + l * s, y0 + t * s, x0 + r2 * s, y0 + b * s)
            cv.drawRoundRect(rf, rad * s, rad * s, p)
        }
        fun shadow() { p.color = c(0x33000000); rf.set(x0 + 0.22f * s, y0 + 0.84f * s, x0 + 0.78f * s, y0 + 0.98f * s); cv.drawOval(rf, p) }
        val b = bob * 0.03f
        fun person(skin: Int, hair: Int, body: Int, long: Boolean = false, beard: Boolean = false, helmet: Boolean = false) {
            shadow()
            rr(body, 0.28f, 0.52f - b, 0.72f, 0.92f, 0.12f)
            o(skin, 0.5f, 0.36f - b, 0.21f)
            p.color = hair
            if (helmet) {
                rf.set(x0 + 0.27f * s, y0 + (0.12f - b) * s, x0 + 0.73f * s, y0 + (0.5f - b) * s)
                cv.drawArc(rf, 180f, 180f, true, p)
            } else {
                rf.set(x0 + 0.28f * s, y0 + (0.13f - b) * s, x0 + 0.72f * s, y0 + (0.52f - b) * s)
                cv.drawArc(rf, 175f, 190f, true, p)
                if (long) { rr(hair, 0.27f, 0.3f - b, 0.36f, 0.66f - b, 0.04f); rr(hair, 0.64f, 0.3f - b, 0.73f, 0.66f - b, 0.04f) }
            }
            if (dir == 3) { o(hair, 0.5f, 0.38f - b, 0.2f); return }
            val eye = c(0xFF2A2320)
            when (dir) {
                1 -> o(eye, 0.4f, 0.4f - b, 0.03f)
                2 -> o(eye, 0.6f, 0.4f - b, 0.03f)
                else -> { o(eye, 0.43f, 0.4f - b, 0.03f); o(eye, 0.57f, 0.4f - b, 0.03f) }
            }
            if (beard) { p.color = c(0xFFEDEDED); rf.set(x0 + 0.36f * s, y0 + (0.42f - b) * s, x0 + 0.64f * s, y0 + (0.62f - b) * s); cv.drawArc(rf, 0f, 180f, true, p) }
        }
        val skin = c(0xFFF4D2B0)
        when (g) {
            "hero" -> person(skin, c(0xFF3B2A20), c(0xFF3F6FB8))
            "villager" -> person(skin, c(0xFF7A4A28), c(0xFF6E9A4A))
            "girl" -> person(skin, c(0xFFD9864A), c(0xFFD86A8A), long = true)
            "elder" -> person(skin, c(0xFFD8D8D8), c(0xFF7A5AA0), beard = true)
            "guard" -> person(skin, c(0xFF9AA0A8), c(0xFF5A5E66), helmet = true)
            "ghost" -> {
                p.color = c(0xCCF2F4FA)
                path.reset()
                path.moveTo(x0 + 0.22f * s, y0 + 0.9f * s)
                path.lineTo(x0 + 0.22f * s, y0 + (0.45f - b) * s)
                rf.set(x0 + 0.22f * s, y0 + (0.14f - b) * s, x0 + 0.78f * s, y0 + (0.7f - b) * s)
                path.arcTo(rf, 180f, 180f)
                path.lineTo(x0 + 0.78f * s, y0 + 0.9f * s)
                path.lineTo(x0 + 0.64f * s, y0 + 0.8f * s); path.lineTo(x0 + 0.5f * s, y0 + 0.9f * s); path.lineTo(x0 + 0.36f * s, y0 + 0.8f * s)
                path.close(); cv.drawPath(path, p)
                if (dir != 3) { o(c(0xFF2A2A3A), 0.42f, 0.42f - b, 0.04f); o(c(0xFF2A2A3A), 0.58f, 0.42f - b, 0.04f) }
            }
            "cat" -> {
                shadow()
                rr(c(0xFF5A5A62), 0.3f, 0.6f, 0.74f, 0.9f, 0.14f)
                o(c(0xFF5A5A62), 0.5f, 0.52f, 0.17f)
                p.color = c(0xFF5A5A62)
                path.reset(); path.moveTo(x0 + 0.36f * s, y0 + 0.46f * s); path.lineTo(x0 + 0.38f * s, y0 + 0.3f * s); path.lineTo(x0 + 0.48f * s, y0 + 0.4f * s); path.close(); cv.drawPath(path, p)
                path.reset(); path.moveTo(x0 + 0.64f * s, y0 + 0.46f * s); path.lineTo(x0 + 0.62f * s, y0 + 0.3f * s); path.lineTo(x0 + 0.52f * s, y0 + 0.4f * s); path.close(); cv.drawPath(path, p)
                if (dir != 3) { o(c(0xFFE8D25A), 0.44f, 0.52f, 0.03f); o(c(0xFFE8D25A), 0.56f, 0.52f, 0.03f) }
            }
            "chest" -> {
                shadow()
                rr(c(0xFF8F5E33), 0.16f, 0.36f, 0.84f, 0.88f, 0.08f)
                rr(c(0xFFA8703F), 0.16f, 0.28f, 0.84f, 0.52f, 0.1f)
                rr(c(0xFFE0C060), 0.44f, 0.46f, 0.56f, 0.62f, 0.02f)
            }
            "sign" -> {
                shadow()
                rr(c(0xFF7A4E2A), 0.45f, 0.4f, 0.55f, 0.92f, 0.02f)
                rr(c(0xFFB07A48), 0.18f, 0.2f, 0.82f, 0.56f, 0.06f)
                p.color = c(0xFF7A4E2A); p.strokeWidth = 0.03f * s
                cv.drawLine(x0 + 0.28f * s, y0 + 0.32f * s, x0 + 0.72f * s, y0 + 0.32f * s, p)
                cv.drawLine(x0 + 0.28f * s, y0 + 0.44f * s, x0 + 0.62f * s, y0 + 0.44f * s, p)
            }
            "crystal" -> {
                shadow()
                p.color = c(0xFF6FC8F0)
                path.reset(); path.moveTo(x0 + 0.5f * s, y0 + (0.12f - b) * s); path.lineTo(x0 + 0.72f * s, y0 + (0.5f - b) * s)
                path.lineTo(x0 + 0.5f * s, y0 + (0.86f - b) * s); path.lineTo(x0 + 0.28f * s, y0 + (0.5f - b) * s); path.close(); cv.drawPath(path, p)
                p.color = c(0xFFB8E8FA)
                path.reset(); path.moveTo(x0 + 0.5f * s, y0 + (0.12f - b) * s); path.lineTo(x0 + 0.58f * s, y0 + (0.5f - b) * s)
                path.lineTo(x0 + 0.5f * s, y0 + (0.86f - b) * s); path.lineTo(x0 + 0.4f * s, y0 + (0.5f - b) * s); path.close(); cv.drawPath(path, p)
            }
            else -> {}
        }
    }
}

/** A full-size picture of one map, redrawn cell by cell while painting. */
class MapImage(var map: GameMap, val ts: Int = 32) {
    val w = map.w
    val h = map.h
    val bmp: Bitmap = Bitmap.createBitmap(w * ts, h * ts, Bitmap.Config.ARGB_8888)
    private val cv = Canvas(bmp)

    fun all() { for (y in 0 until h) for (x in 0 until w) cell(x, y) }

    fun cell(x: Int, y: Int) {
        if (x !in 0 until w || y !in 0 until h || !map.inside(x, y)) return
        val k = map.i(x, y)
        val px = (x * ts).toFloat(); val py = (y * ts).toFloat()
        Art.tile(cv, map.ground[k], px, py, ts.toFloat(), x, y)
        if (map.upper[k] != 0) Art.tile(cv, map.upper[k], px, py, ts.toFloat(), x, y)
    }
}

/** A picture of the map with its events, for the AI. */
object Shot {
    fun bitmap(p: Project, m: GameMap): Bitmap {
        val img = MapImage(m, 32); img.all()
        val out = img.bmp.copy(Bitmap.Config.ARGB_8888, true)
        val cv = Canvas(out)
        m.events.forEach { e -> val pg = e.pages.firstOrNull() ?: return@forEach
            Art.sprite(cv, if (pg.graphic == "none") "none" else pg.graphic, pg.dir, e.x * 32f, e.y * 32f, 32f)
            if (pg.graphic == "none") {
                val pt = Paint().apply { color = Color.argb(140, 217, 119, 87); style = Paint.Style.STROKE; strokeWidth = 3f }
                cv.drawRect(e.x * 32f + 3, e.y * 32f + 3, e.x * 32f + 29, e.y * 32f + 29, pt)
            }
        }
        if (p.startMap == m.id) Art.sprite(cv, "hero", 0, p.startX * 32f, p.startY * 32f, 32f)
        val big = maxOf(out.width, out.height)
        return if (big > 1024) Bitmap.createScaledBitmap(out, out.width * 1024 / big, out.height * 1024 / big, true) else out
    }

    fun base64(p: Project, m: GameMap): String {
        val bos = java.io.ByteArrayOutputStream()
        bitmap(p, m).compress(Bitmap.CompressFormat.PNG, 100, bos)
        return android.util.Base64.encodeToString(bos.toByteArray(), android.util.Base64.NO_WRAP)
    }
}
