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

    fun init(ctx: Context) {
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

    private fun png(b: Bitmap): ByteArray = ByteArrayOutputStream().also { b.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()

    /** PNG 한 장을 넣음. 넣은 캐릭터 수(시트 모양이 아니면 0). */
    fun addPng(name: String, bytes: ByteArray): Int {
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
        if (w % 3 != 0 || h % 4 != 0 || w < 24) return 0
        val r = (w / 3f) / (h / 4f)
        if (r < 0.5f || r > 2f) return 0
        File(d, unique(base) + ".png").writeBytes(bytes)
        return 1
    }

    /** zip(받은 그대로) 또는 PNG. 넣은 캐릭터 수. */
    fun import(name: String, bytes: ByteArray): Int {
        var n = 0
        if (bytes.size > 4 && bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte()) {
            ZipInputStream(bytes.inputStream()).use { z ->
                while (true) {
                    val e = z.nextEntry ?: break
                    val nm = e.name
                    if (!e.isDirectory && nm.endsWith(".png", true) && !nm.contains("__MACOSX")) n += runCatching { addPng(nm, z.readBytes()) }.getOrDefault(0)
                }
            }
        } else n = addPng(name, bytes)
        refresh()
        return n
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
