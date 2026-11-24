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

    private class Cell(val x: Int, val y: Int)

    /** PNG 한 장을 넣음. 넣은 캐릭터 수(시트 모양이 아니면 0). 빈 캐릭터 칸은 건너뜀. */
    fun addPng(name: String, bytes: ByteArray, tidy: Boolean = false, oneBack: Boolean = false): Int {
        val d = dir ?: return 0
        val b = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return 0
        val base = name.substringAfterLast('/').substringAfterLast('\\').substringBeforeLast('.').removePrefix("!").removePrefix("$")
        val w = b.width; val h = b.height
        if (w < 24 || h < 32) return 0
        val px = IntArray(w * h)
        b.getPixels(px, 0, w, 0, 0, w, h)
        // 배경이 칠해져 있으면(투명이 아니면) 늘 지움 — AI가 체크무늬를 그려 넣은 경우 포함
        if (tidy || !borderClear(px, w, h)) clearBackground(px, w, h)
        val sheets = ArrayList<Triple<List<Cell>, Int, Int>>() // 칸 12개(왼쪽 위 모서리), 칸 너비, 칸 높이
        val box = contentBox(px, w, h) ?: return 0
        val fills = (box[2] - box[0]) >= w * 0.7f && (box[3] - box[1]) >= h * 0.7f
        if (w % 12 == 0 && h % 8 == 0 && w * 2 == h * 3 && !name.substringAfterLast('/').startsWith("$") && w >= 12 * 16) {
            // 8명 시트: 4 × 2 칸 (비어 있는 자리는 빼고)
            val cw = w / 12; val ch = h / 8
            for (k in 0 until 8) sheets.add(Triple(List(12) { i -> Cell((k % 4) * 3 * cw + (i % 3) * cw, (k / 4) * 4 * ch + (i / 3) * ch) }, cw, ch))
        } else if (w % 3 == 0 && h % 4 == 0 && fills) {
            val cw = w / 3; val ch = h / 4
            sheets.add(Triple(List(12) { i -> Cell((i % 3) * cw, (i / 3) * ch) }, cw, ch))
        } else {
            // 큰 그림 안에 캐릭터만 있는 경우(여백·워터마크): 그림이 있는 열·줄 묶음을 찾아 칸을 정함
            fun ok(c: List<Pair<Int, Int>>, k: Int) = c.size >= k && c.size % k == 0
            var cols = bands(px, w, h, true, false); if (!ok(cols, 3)) cols = bands(px, w, h, true, true)
            var rows = bands(px, w, h, false, false); if (!ok(rows, 4)) rows = bands(px, w, h, false, true)
            if (cols.size >= 3 && rows.size >= 4 && cols.size % 3 == 0 && rows.size % 4 == 0) {
                for (gr in 0 until rows.size / 4) for (gc in 0 until cols.size / 3) {
                    val cs = cols.subList(gc * 3, gc * 3 + 3); val rs = rows.subList(gr * 4, gr * 4 + 4)
                    val cx = cs.map { (it.first + it.second) / 2 }
                    val maxW = cs.maxOf { it.second - it.first }; val maxH = rs.maxOf { it.second - it.first }
                    val cw = maxOf((cx[2] - cx[0]) / 2, maxW + 2)
                    val ch = maxOf((rs[3].second - rs[0].second) / 3, maxH + 2)
                    val pad = (ch - maxH) / 2
                    sheets.add(Triple(List(12) { i -> Cell(cx[i % 3] - cw / 2, rs[i / 3].second + pad - ch) }, cw, ch))
                }
            } else if (tidy) {
                val r = (w / 3f) / (h / 4f)
                if (r < 0.5f || r > 2f) return 0
                val cw = w / 3; val ch = h / 4
                sheets.add(Triple(List(12) { i -> Cell((i % 3) * cw, (i / 3) * ch) }, cw, ch))
            } else return 0
        }
        var n = 0
        val many = sheets.size > 1
        sheets.forEachIndexed { k, (cells, cw, ch) ->
            if (cells.none { c -> filled(px, w, h, c.x, c.y, cw, ch) }) return@forEachIndexed
            val nm = if (many) "$base ${k + 1}" else base
            File(d, unique(nm) + ".png").writeBytes(png(build(px, w, h, cells, cw, ch, oneBack)))
            n++
        }
        return n
    }

    private fun borderClear(px: IntArray, w: Int, h: Int): Boolean {
        var t = 0; var all = 0
        fun c(i: Int) { all++; if ((px[i] ushr 24) < 128) t++ }
        for (x in 0 until w) { c(x); c((h - 1) * w + x) }
        for (y in 0 until h) { c(y * w); c(y * w + w - 1) }
        return t * 10 >= all * 9
    }

    private fun contentBox(px: IntArray, w: Int, h: Int): IntArray? {
        var x0 = w; var y0 = h; var x1 = -1; var y1 = -1
        for (y in 0 until h) for (x in 0 until w) if ((px[y * w + x] ushr 24) >= 128) { if (x < x0) x0 = x; if (x > x1) x1 = x; if (y < y0) y0 = y; if (y > y1) y1 = y }
        return if (x1 < 0) null else intArrayOf(x0, y0, x1 + 1, y1 + 1)
    }

    /** 그림이 있는 열(또는 줄) 묶음. 조각(워터마크 등)은 버리고, merge면 작은 틈을 이어 붙임. */
    private fun bands(px: IntArray, w: Int, h: Int, vertical: Boolean, merge: Boolean): List<Pair<Int, Int>> {
        val n = if (vertical) w else h
        val mass = IntArray(n)
        for (y in 0 until h) for (x in 0 until w) if ((px[y * w + x] ushr 24) >= 128) mass[if (vertical) x else y]++
        val raw = ArrayList<IntArray>() // start, end, mass
        var s = -1; var m = 0
        for (i in 0..n) {
            val on = i < n && mass[i] > 0
            if (on) { if (s < 0) { s = i; m = 0 }; m += mass[i] }
            else if (s >= 0) { raw.add(intArrayOf(s, i, m)); s = -1 }
        }
        if (raw.isEmpty()) return emptyList()
        val big = raw.maxOf { it[2] }
        val keep = raw.filter { it[2] * 8 >= big }
        if (keep.isEmpty()) return emptyList()
        val med = keep.map { it[1] - it[0] }.sorted()[keep.size / 2]
        val out = ArrayList<IntArray>()
        for (r in keep) {
            val last = out.lastOrNull()
            if (merge && last != null && r[0] - last[1] < med / 4) { last[1] = r[1]; last[2] += r[2] } else out.add(r.copyOf())
        }
        return out.map { it[0] to it[1] }
    }

    private fun filled(px: IntArray, w: Int, h: Int, x0: Int, y0: Int, cw: Int, ch: Int): Boolean {
        var c = 0
        for (y in maxOf(0, y0) until minOf(h, y0 + ch)) for (x in maxOf(0, x0) until minOf(w, x0 + cw)) if ((px[y * w + x] ushr 24) >= 128) c++
        return c * 200 > cw * ch
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
     * 칸 12개를 모아 시트 한 장으로. 칸이 64픽셀보다 크면 48픽셀 도트로(구역마다 가장 많은 색),
     * oneBack이면 뒷모습 줄을 가운데 칸 하나로.
     */
    private fun build(px: IntArray, w: Int, h: Int, cells: List<Cell>, cw: Int, ch: Int, oneBack: Boolean): Bitmap {
        fun at(x: Int, y: Int) = if (x in 0 until w && y in 0 until h) px[y * w + x] else 0
        val tw = if (cw > 64) 48 else cw
        val th = maxOf(1, Math.round(ch * tw / cw.toFloat()))
        val ow = tw * 3; val oh = th * 4
        val out = IntArray(ow * oh)
        val cnt = HashMap<Int, Int>()
        for (row in 0 until 4) for (col in 0 until 3) {
            val sc = if (oneBack && row == 3) 1 else col
            val cell = cells[row * 3 + sc]
            val sx0 = cell.x; val sy0 = cell.y
            for (ty in 0 until th) for (tx in 0 until tw) {
                val v: Int
                if (tw == cw && th == ch) v = at(sx0 + tx, sy0 + ty)
                else {
                    val x0 = sx0 + tx * cw / tw; val x1 = maxOf(x0 + 1, sx0 + (tx + 1) * cw / tw)
                    val y0 = sy0 + ty * ch / th; val y1 = maxOf(y0 + 1, sy0 + (ty + 1) * ch / th)
                    cnt.clear(); var clear = 0; var all = 0
                    for (yy in y0 until y1) for (xx in x0 until x1) {
                        val c = at(xx, yy); all++
                        if ((c ushr 24) < 128) clear++
                        else { val q = (c and 0xF0F0F0) or (0xFF shl 24); cnt[q] = (cnt[q] ?: 0) + 1 }
                    }
                    v = if (clear * 2 > all || cnt.isEmpty()) 0 else cnt.maxByOrNull { it.value }!!.key or 0x080808
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
