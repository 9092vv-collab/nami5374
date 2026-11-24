package space.nami.maker

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipInputStream

/**
 * 불러온 캐릭터 그림(RPG Maker 형식 걷기 시트: 3열 × 4행 = 걷는 동작 3장 × 아래·왼쪽·오른쪽·위).
 * 앱 전체에서 같이 씀. 그래픽 id는 "c:이름", 파일은 filesDir/chars/이름.png.
 * 8명이 한 장에 모인 시트(12열 × 8행)는 불러올 때 한 명씩 나눠 저장.
 */
object Chars {
    private var dir: File? = null
    private var list: List<String> = emptyList()
    private val frames = HashMap<String, List<Bitmap>?>()
    private val paint = Paint().also { it.isFilterBitmap = false }
    private val rf = RectF()
    var rev by mutableIntStateOf(0)
        private set

    /** 레퍼런스로 만들기에 같이 보내는 '이런 모양으로' 예시 시트. */
    private var exampleFile: File? = null
    fun example(): ByteArray? = exampleFile?.takeIf { it.exists() }?.readBytes()
    fun setExample(b: ByteArray?) { exampleFile?.let { if (b == null) it.delete() else it.writeBytes(b) }; rev++ }

    fun init(ctx: Context) {
        exampleFile = File(ctx.filesDir, "sheet_example.img")
        dir = File(ctx.filesDir, "chars").apply { mkdirs() }
        refresh()
    }

    private fun refresh() {
        list = dir?.listFiles { f -> f.name.endsWith(".png", true) }?.map { "c:" + it.nameWithoutExtension }
            ?.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it }) ?: emptyList()
        frames.clear(); rev++
    }

    val ids: List<String> get() = list
    fun isChar(id: String) = id.startsWith("c:")

    fun delete(id: String) { file(id)?.delete(); refresh() }

    private fun file(id: String) = dir?.let { File(it, id.removePrefix("c:") + ".png") }

    private fun safe(n: String) = n.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim().ifEmpty { "캐릭터" }.take(40)

    private fun unique(base: String): String {
        var n = safe(base); var k = 2
        while (File(dir, "$n.png").exists()) n = safe(base) + " " + k++
        return n
    }

    /** AI에 보낼 그림이 크면(1.5MB 넘음) 긴 변 1536픽셀로 줄여 보냄. 요청이 무거워지거나 한도(파일당 7MB)에 걸리지 않게. */
    fun shrinkRef(bytes: ByteArray, maxSide: Int = 1536): ByteArray {
        if (bytes.size < 1_500_000) return bytes
        val b = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return bytes
        val m = maxOf(b.width, b.height)
        if (m <= maxSide) return bytes
        val k = maxSide.toFloat() / m
        return runCatching { png(Bitmap.createScaledBitmap(b, (b.width * k).toInt().coerceAtLeast(1), (b.height * k).toInt().coerceAtLeast(1), true)) }.getOrDefault(bytes)
    }

    private fun png(b: Bitmap): ByteArray = ByteArrayOutputStream().also { b.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()

    /** PNG 한 장을 넣음. 넣은 캐릭터 수(시트 모양이 아니면 0). */
    fun addPng(name: String, bytes: ByteArray, tidy: Boolean = false, oneBack: Boolean = false): Int {
        val d = dir ?: return 0
        val b = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return 0
        val base = name.substringAfterLast('/').substringAfterLast('\\').substringBeforeLast('.').removePrefix("!").removePrefix("$")
        val w = b.width; val h = b.height
        if (w % 12 == 0 && h % 8 == 0 && w * 2 == h * 3 && !name.substringAfterLast('/').startsWith("$") && w >= 12 * 16) {
            // 8명 시트: 4 × 2 칸
            val cw = w / 4; val ch = h / 2
            for (k in 0 until 8) {
                val part = Bitmap.createBitmap(b, (k % 4) * cw, (k / 4) * ch, cw, ch)
                File(d, unique("$base ${k + 1}") + ".png").writeBytes(png(part))
            }
            return 8
        }
        if (w < 24 || h < 32) return 0
        if ((w % 3 != 0 || h % 4 != 0) && !tidy) return 0
        val r = (w / 3f) / (h / 4f)
        if (r < 0.5f || r > 2f) return 0
        val out = if (tidy || oneBack) png(process(b, tidy, oneBack)) else bytes
        File(d, unique(base) + ".png").writeBytes(out)
        return 1
    }

    /** zip(받은 그대로) 또는 PNG. 넣은 캐릭터 수. */
    fun import(name: String, bytes: ByteArray, tidy: Boolean = false, oneBack: Boolean = false): Int {
        var n = 0
        if (bytes.size > 4 && bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte()) {
            ZipInputStream(bytes.inputStream()).use { z ->
                while (true) {
                    val e = z.nextEntry ?: break
                    val nm = e.name
                    if (!e.isDirectory && nm.endsWith(".png", true) && !nm.contains("__MACOSX")) n += runCatching { addPng(nm, z.readBytes(), tidy, oneBack) }.getOrDefault(0)
                }
            }
        } else n = addPng(name, bytes, tidy, oneBack)
        refresh()
        return n
    }

    /**
     * AI 시트 다듬기: 가장자리와 이어진 배경색을 투명하게, 칸이 64픽셀보다 크면 48픽셀 도트로(구역마다 가장 많은 색),
     * oneBack이면 뒷모습 줄을 가운데 칸 하나로.
     */
    private fun process(b: Bitmap, tidy: Boolean, oneBack: Boolean): Bitmap {
        val w = b.width; val h = b.height
        val px = IntArray(w * h)
        b.getPixels(px, 0, w, 0, 0, w, h)
        if (tidy) clearBackground(px, w, h)
        val cw = w / 3; val ch = h / 4
        val tw = if (tidy && cw > 64) 48 else cw
        val th = maxOf(1, Math.round(ch * tw / cw.toFloat()))
        val ow = tw * 3; val oh = th * 4
        val out = IntArray(ow * oh)
        val cnt = HashMap<Int, Int>()
        for (row in 0 until 4) for (col in 0 until 3) {
            val sc = if (oneBack && row == 3) 1 else col
            val sx0 = sc * cw; val sy0 = row * ch
            for (ty in 0 until th) for (tx in 0 until tw) {
                val v: Int
                if (tw == cw && th == ch) v = px[(sy0 + ty) * w + sx0 + tx]
                else {
                    val x0 = sx0 + tx * cw / tw; val x1 = maxOf(x0 + 1, sx0 + (tx + 1) * cw / tw)
                    val y0 = sy0 + ty * ch / th; val y1 = maxOf(y0 + 1, sy0 + (ty + 1) * ch / th)
                    cnt.clear(); var clear = 0; var all = 0
                    for (yy in y0 until y1) for (xx in x0 until x1) {
                        val c = px[yy * w + xx]; all++
                        if ((c ushr 24) < 128) clear++
                        else { val q = (c and 0xF0F0F0) or (0xFF shl 24); cnt[q] = (cnt[q] ?: 0) + 1 }
                    }
                    v = if (clear * 2 > all || cnt.isEmpty()) 0 else {
                        // 가장 많은 색(양자화) 그대로 쓰되 원래 색에 가깝게: 그 색 묶음의 첫 픽셀 대신 양자화 값 + 8
                        val q = cnt.maxByOrNull { it.value }!!.key
                        q or 0x080808
                    }
                }
                out[(row * th + ty) * ow + col * tw + tx] = v
            }
        }
        val r = Bitmap.createBitmap(ow, oh, Bitmap.Config.ARGB_8888)
        r.setPixels(out, 0, ow, 0, 0, ow, oh)
        return r
    }

    /** 테두리에서 시작해 배경색(테두리에 가장 많은 색)과 비슷한 이어진 픽셀을 투명하게. */
    private fun clearBackground(px: IntArray, w: Int, h: Int) {
        val border = HashMap<Int, Int>()
        fun q(c: Int) = c and 0xE0E0E0
        for (x in 0 until w) { border.merge(q(px[x]), 1, Int::plus); border.merge(q(px[(h - 1) * w + x]), 1, Int::plus) }
        for (y in 0 until h) { border.merge(q(px[y * w]), 1, Int::plus); border.merge(q(px[y * w + w - 1]), 1, Int::plus) }
        // 테두리에 가장 많은 색 두 가지: AI가 체크무늬(밝은 회색 두 톤)를 그림으로 그려 넣는 경우까지 지움
        val top = border.entries.sortedByDescending { it.value }.take(2)
        if (top.isEmpty()) return
        val total = border.values.sum()
        fun real(qc: Int): Int { for (x in 0 until w) if (q(px[x]) == qc) return px[x] and 0xFFFFFF; for (y in 0 until h) if (q(px[y * w]) == qc) return px[y * w] and 0xFFFFFF; return qc }
        fun neutralLight(c: Int): Boolean { val r = c shr 16 and 255; val g = c shr 8 and 255; val b = c and 255
            return minOf(r, g, b) > 150 && maxOf(r, g, b) - minOf(r, g, b) < 20 }
        val bgs = mutableListOf(real(top[0].key))
        if (top.size > 1 && top[1].value * 5 > total && neutralLight(bgs[0]) && neutralLight(real(top[1].key))) bgs.add(real(top[1].key))
        fun near(c: Int): Boolean {
            if ((c ushr 24) < 128) return true
            return bgs.any { bg -> Math.abs((c shr 16 and 255) - (bg shr 16 and 255)) < 40 && Math.abs((c shr 8 and 255) - (bg shr 8 and 255)) < 40 && Math.abs((c and 255) - (bg and 255)) < 40 }
        }
        val seen = BooleanArray(w * h)
        val stack = IntArray(w * h); var sp = 0
        fun push(i: Int) { if (!seen[i] && near(px[i])) { seen[i] = true; stack[sp++] = i } }
        for (x in 0 until w) { push(x); push((h - 1) * w + x) }
        for (y in 0 until h) { push(y * w); push(y * w + w - 1) }
        while (sp > 0) {
            val i = stack[--sp]; px[i] = 0
            val x = i % w; val y = i / w
            if (x > 0) push(i - 1); if (x < w - 1) push(i + 1)
            if (y > 0) push(i - w); if (y < h - 1) push(i + w)
        }
    }

    private fun load(id: String): List<Bitmap>? = frames.getOrPut(id) {
        runCatching {
            val f = file(id) ?: return@runCatching null
            val b = BitmapFactory.decodeFile(f.path) ?: return@runCatching null
            val cw = b.width / 3; val ch = b.height / 4
            List(12) { i -> Bitmap.createBitmap(b, (i % 3) * cw, (i / 3) * ch, cw, ch) }
        }.getOrNull()
    }

    /** frame: 0·2 = 걷는 발, 1 = 서 있기. 그림 아래쪽을 칸 아래에 맞춰 그림(키 큰 캐릭터는 위로 삐져나옴). false면 그림이 없음. */
    fun draw(cv: Canvas, id: String, dir: Int, frame: Int, x0: Float, y0: Float, s: Float): Boolean {
        val l = load(id) ?: return false
        val bm = l[(dir.coerceIn(0, 3)) * 3 + frame.coerceIn(0, 2)]
        val h = s * bm.height / bm.width
        rf.set(x0, y0 + s - h, x0 + s, y0 + s)
        cv.drawBitmap(bm, null, rf, paint)
        return true
    }
}
