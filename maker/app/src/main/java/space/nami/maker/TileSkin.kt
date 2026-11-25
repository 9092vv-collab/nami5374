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

    private fun cellBitmap(name: String, i: Int): Bitmap? = cellCache.getOrPut("$name#$i") {
        val s = sheet(name) ?: return@getOrPut null
        val c = cell(name); val cols = s.width / c
        if (cols <= 0) return@getOrPut null
        val x = (i % cols) * c; val y = (i / cols) * c
        if (x + c > s.width || y + c > s.height) null else runCatching { Bitmap.createBitmap(s, x, y, c, c) }.getOrNull()
    }

    /** 기호(타일 id)에 정한 그림: (시트, 칸 번호) 또는 null. */
    fun of(tileId: Int): Pair<String, Int>? = mapObj().optJSONObject(tileId.toString())?.let { o ->
        o.optString("s").takeIf { it.isNotBlank() && sheetsObj().has(it) }?.let { it to o.optInt("i") }
    }
    fun assign(tileId: Int, name: String, i: Int) { mapObj().put(tileId.toString(), JSONObject().put("s", name).put("i", i)); save() }
    fun clear(tileId: Int) { mapObj().remove(tileId.toString()); save() }

    /** 맵 그림 한 칸 픽셀: 정한 그림 중 가장 큰 칸 크기(48이면 MV처럼 선명하게), 없으면 32. */
    fun mapTs(): Int {
        var best = 32
        mapObj().keys().forEach { k -> mapObj().optJSONObject(k)?.optString("s")?.let { s -> if (sheetsObj().has(s)) best = maxOf(best, cell(s)) } }
        return best.coerceAtMost(48)
    }

    /** 그 기호에 그림이 정해져 있으면 그려 넣고 true. */
    fun draw(cv: Canvas, tileId: Int, x0: Float, y0: Float, s: Float): Boolean {
        val (n, i) = of(tileId) ?: return false
        val b = cellBitmap(n, i) ?: return false
        rf.set(x0, y0, x0 + s, y0 + s)
        cv.drawBitmap(b, null, rf, paint)
        return true
    }
}
