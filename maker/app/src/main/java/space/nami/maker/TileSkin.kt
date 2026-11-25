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
import org.json.JSONObject
import java.io.File
import java.util.zip.ZipInputStream

/**
 * 타일 그림: 불러온 타일셋(Pipoya, RPG Maker B~E 시트 같은 격자 PNG)에서 칸 하나를 골라 기호([G] 풀 등)의 그림으로 씀.
 * 기호(AI가 보는 것)는 그대로, 보이는 것만 바뀜. 그림을 안 정한 기호는 도형 그림.
 * 앱 전체 공용: filesDir/tilesets/이름.png, 설정은 filesDir/tilesets.json {sheets:{이름:칸크기}, map:{타일id:{s:이름, i:칸번호}}}.
 */
object TileSkin {
    private var dir: File? = null
    private var metaFile: File? = null
    private var meta = JSONObject()
    private val sheetCache = HashMap<String, Bitmap?>()
    private val cellCache = HashMap<String, Bitmap?>()
    private val paint = Paint().also { it.isFilterBitmap = false }
    private val rf = RectF()
    var rev by mutableIntStateOf(0)
        private set

    fun init(ctx: Context) {
        dir = File(ctx.filesDir, "tilesets").apply { mkdirs() }
        metaFile = File(ctx.filesDir, "tilesets.json")
        meta = runCatching { JSONObject(metaFile!!.readText()) }.getOrDefault(JSONObject())
        if (!meta.has("sheets")) meta.put("sheets", JSONObject())
        if (!meta.has("map")) meta.put("map", JSONObject())
        // 파일은 있는데 기록이 없는 시트도 목록에
        dir?.listFiles { f -> f.name.endsWith(".png", true) }?.forEach { f ->
            val n = f.nameWithoutExtension
            if (!meta.getJSONObject("sheets").has(n)) meta.getJSONObject("sheets").put(n, guessCell(f))
        }
    }

    private fun save() { runCatching { metaFile?.writeText(meta.toString()) }; rev++ }

    private fun sheetsObj() = meta.getJSONObject("sheets")
    private fun mapObj() = meta.getJSONObject("map")

    fun sheets(): List<String> = sheetsObj().keys().asSequence().toList().sortedWith(String.CASE_INSENSITIVE_ORDER)
    fun cell(name: String) = sheetsObj().optInt(name, 32)
    fun setCell(name: String, c: Int) { sheetsObj().put(name, c); cellCache.keys.removeAll { it.startsWith("$name#") }; save() }

    /** 칸 크기 짐작: 768폭(RPG Maker B~E)·48로만 나뉘면 48, 32로 나뉘면 32, 아니면 16. */
    private fun guessCell(w: Int): Int = when {
        w == 768 -> 48
        w % 48 == 0 && w % 32 != 0 -> 48
        w % 32 == 0 -> 32
        w % 16 == 0 -> 16
        else -> 32
    }
    private fun guessCell(f: File): Int = runCatching {
        val b = BitmapFactory.decodeByteArray(f.readBytes().let { it }, 0, f.length().toInt()); guessCell(b!!.width)
    }.getOrDefault(32)

    private fun safe(n: String) = n.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim().ifEmpty { "타일셋" }.take(60)

    /** zip(받은 그대로) 또는 PNG. 넣은 시트 수. */
    fun import(name: String, bytes: ByteArray): Int {
        if (name.endsWith(".zip", true) || (bytes.size > 4 && bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte())) {
            var n = 0
            ZipInputStream(bytes.inputStream()).use { z ->
                while (true) {
                    val e = z.nextEntry ?: break
                    if (!e.isDirectory && e.name.endsWith(".png", true) && !e.name.contains("__MACOSX")) {
                        if (addPng(e.name, z.readBytes())) n++
                    }
                }
            }
            if (n > 0) save()
            return n
        }
        return if (addPng(name, bytes)) { save(); 1 } else 0
    }

    private fun addPng(name: String, bytes: ByteArray): Boolean {
        val d = dir ?: return false
        val b = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return false
        if (b.width < 16 || b.height < 16) return false
        val n = safe(name.substringAfterLast('/').substringAfterLast('\\').substringBeforeLast('.'))
        File(d, "$n.png").writeBytes(bytes)
        sheetsObj().put(n, guessCell(b.width))
        sheetCache.remove(n); cellCache.keys.removeAll { it.startsWith("$n#") }
        return true
    }

    fun delete(name: String) {
        dir?.let { File(it, "$name.png").delete() }
        sheetsObj().remove(name)
        val m = mapObj()
        m.keys().asSequence().toList().forEach { k -> if (m.optJSONObject(k)?.optString("s") == name) m.remove(k) }
        sheetCache.remove(name); cellCache.keys.removeAll { it.startsWith("$name#") }
        save()
    }

    fun sheet(name: String): Bitmap? = sheetCache.getOrPut(name) {
        runCatching { dir?.let { File(it, "$name.png") }?.takeIf { it.exists() }?.readBytes()?.let { BitmapFactory.decodeByteArray(it, 0, it.size) } }.getOrNull()
    }
    fun cols(name: String) = (sheet(name)?.width ?: 0) / cell(name)
    fun rows(name: String) = (sheet(name)?.height ?: 0) / cell(name)

    /** 칸 i(왼쪽 위)부터 가로 w × 세로 h 칸 그림. */
    private fun cellBitmap(name: String, i: Int, w: Int = 1, h: Int = 1): Bitmap? = cellCache.getOrPut("$name#$i#${w}x$h") {
        val s = sheet(name) ?: return@getOrPut null
        val c = cell(name); val cols = s.width / c
        if (cols <= 0) return@getOrPut null
        val x = (i % cols) * c; val y = (i / cols) * c
        if (x + c * w > s.width || y + c * h > s.height) null else runCatching { Bitmap.createBitmap(s, x, y, c * w, c * h) }.getOrNull()
    }

    /** 기호에 정한 그림. w·h > 1이면 큰 그림(그 기호 칸을 왼쪽 아래로 위·오른쪽으로 뻗음). */
    class Pick(val sheet: String, val i: Int, val w: Int, val h: Int) { val big get() = w > 1 || h > 1 }

    fun pick(tileId: Int): Pick? = mapObj().optJSONObject(tileId.toString())?.let { o ->
        o.optString("s").takeIf { it.isNotBlank() && sheetsObj().has(it) }?.let { Pick(it, o.optInt("i"), o.optInt("w", 1).coerceAtLeast(1), o.optInt("h", 1).coerceAtLeast(1)) }
    }
    fun of(tileId: Int): Pair<String, Int>? = pick(tileId)?.let { it.sheet to it.i }
    fun assign(tileId: Int, name: String, i: Int, w: Int = 1, h: Int = 1) {
        mapObj().put(tileId.toString(), JSONObject().put("s", name).put("i", i).put("w", w.coerceIn(1, 8)).put("h", h.coerceIn(1, 8))); save()
    }
    fun hasBig(): Boolean = Tiles.list.any { pick(it.id)?.big == true }

    /** 맵 그림을 그리는 동안(1차)엔 큰 그림을 건너뛰고, 다 그린 뒤 `stamp`로 위에 얹음. */
    var stampPass = false
    fun clear(tileId: Int) { mapObj().remove(tileId.toString()); save() }

    /** 맵 그림 한 칸 픽셀: 정한 그림 중 가장 큰 칸 크기(48이면 MV처럼 선명하게), 없으면 32. */
    fun mapTs(): Int {
        var best = 32
        mapObj().keys().forEach { k -> mapObj().optJSONObject(k)?.optString("s")?.let { s -> if (sheetsObj().has(s)) best = maxOf(best, cell(s)) } }
        return best.coerceAtMost(48)
    }

    /** 그 기호에 그림이 정해져 있으면 그려 넣고 true. 큰 그림은 맵 그리기 중엔 건너뛰고(나중에 stamp), 미리보기에선 한 칸에 맞춰 줄여 그림. */
    fun draw(cv: Canvas, tileId: Int, x0: Float, y0: Float, s: Float): Boolean {
        val p = pick(tileId) ?: return false
        val b = cellBitmap(p.sheet, p.i, p.w, p.h) ?: return false
        if (p.big && stampPass) return true
        if (p.big) {
            val k = s / maxOf(p.w, p.h); val dw = p.w * k; val dh = p.h * k
            rf.set(x0 + (s - dw) / 2, y0 + (s - dh), x0 + (s + dw) / 2, y0 + s)
        } else rf.set(x0, y0, x0 + s, y0 + s)
        cv.drawBitmap(b, null, rf, paint)
        return true
    }

    /** 큰 그림을 제 크기로: 기호 칸(x0, y0)이 왼쪽 아래, 위·오른쪽으로 뻗음. */
    fun stamp(cv: Canvas, tileId: Int, x0: Float, y0: Float, s: Float) {
        val p = pick(tileId)?.takeIf { it.big } ?: return
        val b = cellBitmap(p.sheet, p.i, p.w, p.h) ?: return
        rf.set(x0, y0 - (p.h - 1) * s, x0 + p.w * s, y0 + s)
        cv.drawBitmap(b, null, rf, paint)
    }
}
