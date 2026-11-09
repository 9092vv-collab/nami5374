package space.sgu.next.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import java.io.File

/**
 * Character icons the RisuAI way (original 1670 Q / 1653): one main icon (icon.png here, CCv3 asset `icon` named "main")
 * plus extra icons (CCv3 assets `type: "icon"` with other names, files in x/assets/icon/image/).
 */
object CharIcons {
    data class Icon(val id: String, val file: File, val main: Boolean, val name: String)

    private fun dir(cid: String) = CharacterStore.dir(cid)
    private fun x(cid: String) = File(dir(cid), "x")
    private fun mainFile(cid: String) = File(dir(cid), "icon.png")

    private fun isExtra(a: JsonObject) = CardIO.str(a, "type") == "icon" && CardIO.str(a, "name") != "main"

    fun list(cid: String): List<Icon> {
        val card = CharacterStore.readCard(cid)
        val out = mutableListOf<Icon>()
        mainFile(cid).takeIf { it.exists() }?.let { out.add(Icon("main", it, true, "main")) }
        CardIO.assets(card).filter { isExtra(it) }.forEach { a ->
            val uri = CardIO.str(a, "uri")
            CardIO.assetFile(x(cid), uri)?.let { out.add(Icon(uri, it, false, CardIO.str(a, "name"))) }
        }
        return out
    }

    private fun writeMain(cid: String, src: File) {
        val dst = mainFile(cid)
        val tmp = File(dir(cid), "icon.tmp")
        toPng(src, tmp, 0); tmp.renameTo(dst).also { if (!it) { tmp.copyTo(dst, true); tmp.delete() } }
        // A CHARX-imported card keeps its main icon inside x/: keep that file in step.
        CardIO.assets(CharacterStore.readCard(cid)).firstOrNull { CardIO.str(it, "type") == "icon" && CardIO.str(it, "name") == "main" }
            ?.let { CardIO.assetFile(x(cid), CardIO.str(it, "uri")) }?.let { dst.copyTo(it, true) }
    }

    /** Decode → (shrink to [maxPx] if > 0) → PNG. */
    private fun toPng(src: File, dst: File, maxPx: Int) {
        val bmp = BitmapFactory.decodeFile(src.path) ?: run { src.copyTo(dst, true); return }
        val scaled = shrink(bmp, maxPx)
        dst.outputStream().use { scaled.compress(Bitmap.CompressFormat.PNG, 100, it) }
        if (scaled !== bmp) scaled.recycle(); bmp.recycle()
    }

    private fun shrink(bmp: Bitmap, maxPx: Int): Bitmap {
        if (maxPx <= 0) return bmp
        val big = maxOf(bmp.width, bmp.height)
        if (big <= maxPx) return bmp
        val k = maxPx.toFloat() / big
        return Bitmap.createScaledBitmap(bmp, (bmp.width * k).toInt().coerceAtLeast(1), (bmp.height * k).toInt().coerceAtLeast(1), true)
    }

    private fun addExtra(cid: String, src: File, name: String) {
        val path = "assets/icon/image/${Scripts.newId("ic")}.png"
        val f = File(x(cid), path).apply { parentFile?.mkdirs() }
        toPng(src, f, 0)
        CharacterStore.updateData(cid) { d -> d.withAssets(add = listOf(buildJsonObject {
            put("type", JsonPrimitive("icon")); put("uri", JsonPrimitive("embeded://$path")); put("name", JsonPrimitive(name.ifBlank { "icon" })); put("ext", JsonPrimitive("png"))
        })) }
    }

    private fun JsonObject.withAssets(remove: Set<String> = emptySet(), add: List<JsonObject> = emptyList()): JsonObject {
        val list = ((this["assets"] as? JsonArray) ?: JsonArray(emptyList())).filter { a -> ((a as? JsonObject)?.let { CardIO.str(it, "uri") } ?: "") !in remove } + add
        return JsonObject(this + ("assets" to JsonArray(list)))
    }

    /** No icon yet → main; otherwise an extra icon (the original "아이콘으로 추가"). Returns true when it became the main icon. */
    fun add(cid: String, src: File, name: String): Boolean {
        if (!src.exists()) return false
        if (!mainFile(cid).exists()) { writeMain(cid, src); CharacterStore.updateData(cid) { it }; return true }
        addExtra(cid, src, name); return false
    }

    /** 대표 이미지로 지정: the extra becomes main, the old main takes the extra's place and name. */
    fun setMain(cid: String, id: String) {
        val icon = list(cid).firstOrNull { it.id == id && !it.main } ?: return
        val main = mainFile(cid)
        val oldMain = File(dir(cid), "icon.old").also { if (main.exists()) main.copyTo(it, true) }
        writeMain(cid, icon.file)
        icon.file.delete()
        CharacterStore.updateData(cid) { d -> d.withAssets(remove = setOf(id)) }
        if (oldMain.exists()) { addExtra(cid, oldMain, icon.name); oldMain.delete() }
    }

    /** 삭제: removing the main icon promotes the first extra (original behaviour). */
    fun remove(cid: String, id: String) {
        val icons = list(cid)
        val icon = icons.firstOrNull { it.id == id } ?: return
        if (icon.main) {
            val next = icons.firstOrNull { !it.main }
            if (next != null) {
                writeMain(cid, next.file); next.file.delete()
                CharacterStore.updateData(cid) { d -> d.withAssets(remove = setOf(next.id)) }
            } else { mainFile(cid).delete(); CharacterStore.updateData(cid) { it } }
        } else {
            icon.file.delete()
            CharacterStore.updateData(cid) { d -> d.withAssets(remove = setOf(id)) }
        }
    }

    /** 이미지 첨부: several files; shrunk to 2048px; the first becomes main if there is none. */
    fun attach(ctx: Context, cid: String, uris: List<Uri>): Int {
        var n = 0
        for (u in uris) runCatching {
            val tmp = File(ctx.cacheDir, "icon-in-${System.nanoTime()}")
            ctx.contentResolver.openInputStream(u)?.use { i -> tmp.outputStream().use { i.copyTo(it) } } ?: return@runCatching
            val png = File(ctx.cacheDir, "icon-png-${System.nanoTime()}.png")
            toPng(tmp, png, 2048); tmp.delete()
            val raw = runCatching {
                ctx.contentResolver.query(u, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
            }.getOrNull() ?: "icon"
            val name = raw.replace(Regex("\\.[^.]+$"), "").replace(Regex("[^a-zA-Z0-9가-힣_-]"), "_").ifBlank { "icon" }
            add(cid, png, name); png.delete(); n++
        }
        return n
    }
}
