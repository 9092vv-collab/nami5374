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
import java.io.File

/**
 * 등장인물 스탠딩 일러스트와 표정 그림. filesDir/stands/<인물 id>/base.png, face_<표정>.png.
 * 표정 그림은 스탠딩의 얼굴 영역(CastMember.face)에 덮어 그림.
 */
object Stands {
    private var dir: File? = null
    private val cache = HashMap<String, Bitmap?>()
    private val paint = Paint().also { it.isFilterBitmap = true }
    private val rf = RectF()
    var rev by mutableIntStateOf(0)
        private set

    fun init(ctx: Context) { dir = File(ctx.filesDir, "stands").apply { mkdirs() } }

    private fun folder(id: String) = dir?.let { File(it, id.replace(Regex("[^A-Za-z0-9_-]"), "_")) }
    private fun safe(n: String) = n.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim().take(40)
    private fun changed() { cache.clear(); rev++ }

    private fun load(f: File?): Bitmap? = runCatching { f?.takeIf { it.exists() }?.readBytes()?.let { BitmapFactory.decodeByteArray(it, 0, it.size) } }.getOrNull()

    fun hasBase(id: String) = folder(id)?.let { File(it, "base.png").exists() } == true
    fun base(id: String): Bitmap? = cache.getOrPut("$id#base") { load(folder(id)?.let { File(it, "base.png") }) }

    /** 스탠딩 일러스트를 넣음. 그림이 아니면 false. */
    fun setBase(id: String, bytes: ByteArray): Boolean {
        val b = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return false
        if (b.width < 16 || b.height < 16) return false
        val f = folder(id) ?: return false
        f.mkdirs(); File(f, "base.png").writeBytes(bytes)
        changed(); return true
    }

    fun face(id: String, name: String): Bitmap? = cache.getOrPut("$id#f#$name") { load(folder(id)?.let { File(it, "face_" + safe(name) + ".png") }) }

    /**
     * 표정 그림을 넣음. 스탠딩과 같은 크기의 그림(같은 그림에서 표정만 바꾼 것)이면 얼굴 영역만 잘라 저장, 아니면 그대로(얼굴 영역에 맞춰 그림).
     */
    fun addFace(c: CastMember, name: String, bytes: ByteArray): Boolean {
        val b = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return false
        val f = folder(c.id) ?: return false
        f.mkdirs()
        val base = base(c.id)
        val out = if (base != null && c.hasFace() && b.width == base.width && b.height == base.height) {
            val (x, y, w, h) = c.face.toList()
            val cut = Bitmap.createBitmap(b, x.coerceIn(0, b.width - 1), y.coerceIn(0, b.height - 1), w.coerceAtMost(b.width - x), h.coerceAtMost(b.height - y))
            java.io.ByteArrayOutputStream().also { cut.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        } else bytes
        File(f, "face_" + safe(name) + ".png").writeBytes(out)
        changed(); return true
    }

    fun removeFace(id: String, name: String) { folder(id)?.let { File(it, "face_" + safe(name) + ".png").delete() }; changed() }
    fun renameFace(id: String, from: String, to: String) { folder(id)?.let { File(it, "face_" + safe(from) + ".png").renameTo(File(it, "face_" + safe(to) + ".png")) }; changed() }

    fun delete(id: String) { folder(id)?.deleteRecursively(); changed() }

    /** 스탠딩 + 표정(얼굴 영역에 덮어 그림). 표정이 없거나 이름이 비면 스탠딩 그대로. */
    fun composed(c: CastMember, faceName: String): Bitmap? = cache.getOrPut("${c.id}#c#$faceName#${c.face.joinToString(",")}") {
        val base = base(c.id) ?: return@getOrPut null
        val fb = if (faceName.isBlank() || !c.hasFace()) null else face(c.id, faceName)
        if (fb == null) return@getOrPut base
        val out = base.copy(Bitmap.Config.ARGB_8888, true)
        val (x, y, w, h) = c.face.toList()
        rf.set(x.toFloat(), y.toFloat(), (x + w).toFloat(), (y + h).toFloat())
        Canvas(out).drawBitmap(fb, null, rf, paint)
        out
    }

    /** AI에게 보낼 등장인물 목록(없으면 빈 글). */
    fun describe(p: Project): String {
        if (p.cast.isEmpty()) return ""
        val a = org.json.JSONArray()
        p.cast.forEach { c ->
            a.put(org.json.JSONObject().put("name", c.name).put("role", if (c.role == "support") "조연" else "주연")
                .put("desc", c.desc).put("sprite", c.sprite.ifBlank { "villager" })
                .put("stand", hasBase(c.id)).put("faces", org.json.JSONArray(if (hasBase(c.id) && c.hasFace()) c.faces else emptyList<String>())))
        }
        return a.toString()
    }
}
