package space.sgu.next.data

import android.content.Context
import android.net.Uri
import android.util.Base64
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import java.io.File

/**
 * 감정 이미지 · 추가 에셋 (CCv3 `data.assets`, RisuAI 방식).
 * - 가져오기: PNG 카드의 `chara-ext-asset_:N` 청크 / JSON의 `data:…;base64` / V2 `extensions.risuai.additionalAssets·emotions`
 *   를 x/ 안의 파일(`embeded://assets/{emotion|other}/{image|audio|video}/이름.확장자`)로 풀어 둔다 → CHARX 내보내기에 그대로 들어감.
 * - 직접 추가·이름 바꾸기·삭제 (원본 SGU에는 없던 기능, 사용자 요청).
 */
object Assets {
    private val AUDIO = setOf("mp3", "wav", "ogg", "m4a", "flac", "aac", "opus")
    private val VIDEO = setOf("mp4", "webm", "mov", "mkv")

    fun itype(ext: String) = when (ext.lowercase()) { in AUDIO -> "audio"; in VIDEO -> "video"; else -> "image" }
    private fun folder(type: String) = when (type) { "emotion" -> "emotion"; "icon" -> "icon"; else -> "other" }
    private fun safe(s: String) = s.replace(Regex("[\\\\/:*?\"<>|\u0000-\u001f]+"), "_").trim().ifBlank { "asset" }.take(100)

    private fun sniffExt(b: ByteArray): String = when {
        b.size > 8 && b[0] == 0x89.toByte() && b[1] == 'P'.code.toByte() -> "png"
        b.size > 3 && b[0] == 0xFF.toByte() && b[1] == 0xD8.toByte() -> "jpg"
        b.size > 12 && String(b, 0, 4, Charsets.ISO_8859_1) == "RIFF" && String(b, 8, 4, Charsets.ISO_8859_1) == "WEBP" -> "webp"
        b.size > 6 && String(b, 0, 3, Charsets.ISO_8859_1) == "GIF" -> "gif"
        b.size > 3 && String(b, 0, 3, Charsets.ISO_8859_1) == "ID3" -> "mp3"
        b.size > 12 && String(b, 4, 4, Charsets.ISO_8859_1) == "ftyp" -> "mp4"
        else -> "png"
    }

    /** Writes [bytes] under x/ at a free path and returns the embeded:// uri path. */
    private fun place(xDir: File, type: String, name: String, ext0: String, bytes: ByteArray): Pair<String, String> {
        val ext = ext0.lowercase().let { if (it == "jpeg") "jpg" else it }.ifBlank { "png" }
        val dir = "assets/${folder(type)}/${itype(ext)}"
        val base = safe(name)
        var n = base; var k = 0
        while (File(xDir, "$dir/$n.$ext").exists()) { k++; n = "${base}_$k" }
        val path = "$dir/$n.$ext"
        File(xDir, path).apply { parentFile?.mkdirs() }.writeBytes(bytes)
        return path to ext
    }

    /** All tEXt chunks of a PNG (key → value). */
    fun pngTextChunks(bytes: ByteArray): Map<String, String> {
        val out = mutableMapOf<String, String>()
        var pos = 8
        while (pos + 8 <= bytes.size) {
            val len = ((bytes[pos].toInt() and 0xFF) shl 24) or ((bytes[pos + 1].toInt() and 0xFF) shl 16) or
                ((bytes[pos + 2].toInt() and 0xFF) shl 8) or (bytes[pos + 3].toInt() and 0xFF)
            val type = String(bytes, pos + 4, 4, Charsets.ISO_8859_1)
            val start = pos + 8
            if (len < 0 || start + len > bytes.size) break
            if (type == "tEXt") {
                val chunk = bytes.copyOfRange(start, start + len)
                val z = chunk.indexOf(0.toByte())
                if (z > 0) out[String(chunk, 0, z, Charsets.ISO_8859_1)] = String(chunk, z + 1, chunk.size - z - 1, Charsets.ISO_8859_1)
            }
            if (type == "IEND") break
            pos = start + len + 4
        }
        return out
    }

    private fun decodeUri(uri: String, chunks: Map<String, String>): ByteArray? = runCatching {
        when {
            uri.startsWith("__asset:") -> {
                val n = uri.removePrefix("__asset:")
                (chunks["chara-ext-asset_:$n"] ?: chunks["chara-ext-asset_$n"])?.let { Base64.decode(it.trim(), Base64.DEFAULT) }
            }
            uri.startsWith("data:") && ";base64," in uri -> Base64.decode(uri.substringAfter(";base64,"), Base64.DEFAULT)
            else -> null
        }
    }.getOrNull()

    /**
     * Unpacks inline assets of an imported PNG/JSON card into [xDir]. Returns the fixed card and the main icon bytes (if inline).
     */
    fun unpackImported(card: JsonObject, chunks: Map<String, String>, xDir: File): Pair<JsonObject, ByteArray?> {
        val data = (card["data"] as? JsonObject).orEmpty().toMutableMap()
        var mainIcon: ByteArray? = null
        val list = ((data["assets"] as? JsonArray)?.mapNotNull { it as? JsonObject } ?: emptyList()).toMutableList()

        // V2 RisuAI cards keep extra assets / emotions in extensions.risuai as [name, uri, ext?]
        val ext = (data["extensions"] as? JsonObject).orEmpty().toMutableMap()
        val risu = (ext["risuai"] as? JsonObject).orEmpty().toMutableMap()
        fun legacy(key: String, type: String) {
            val rows = (risu[key] as? JsonArray) ?: return
            for (r in rows) {
                val a = (r as? JsonArray) ?: continue
                val name = (a.getOrNull(0) as? JsonPrimitive)?.content ?: continue
                val uri = (a.getOrNull(1) as? JsonPrimitive)?.content ?: continue
                if (list.any { CardIO.str(it, "type") == type && CardIO.str(it, "name") == name }) continue
                list.add(buildJsonObject {
                    put("type", JsonPrimitive(type)); put("uri", JsonPrimitive(uri)); put("name", JsonPrimitive(name))
                    put("ext", JsonPrimitive((a.getOrNull(2) as? JsonPrimitive)?.content ?: ""))
                })
            }
            risu.remove(key)
        }
        legacy("additionalAssets", "x-risu-asset"); legacy("emotions", "emotion")
        if (risu.isNotEmpty() || ext.containsKey("risuai")) { ext["risuai"] = JsonObject(risu); data["extensions"] = JsonObject(ext) }

        var unpacked = 0
        val fixed = list.mapIndexed { i, a ->
            val uri = CardIO.str(a, "uri")
            val bytes = decodeUri(uri, chunks) ?: return@mapIndexed a
            val type = CardIO.str(a, "type")
            if (type == "icon" && CardIO.str(a, "name") == "main") mainIcon = bytes
            val (path, e) = place(xDir, type, CardIO.str(a, "name").ifBlank { "asset_$i" }, CardIO.str(a, "ext").ifBlank { sniffExt(bytes) }, bytes)
            unpacked++
            JsonObject(a + mapOf("uri" to JsonPrimitive("embeded://$path"), "ext" to JsonPrimitive(e)))
        }
        if (list.isNotEmpty() || data.containsKey("assets")) data["assets"] = JsonArray(fixed)
        return JsonObject(card + ("data" to JsonObject(data))) to mainIcon
    }

    // ---------- 직접 추가 / 이름 바꾸기 / 삭제 ----------

    private fun xDir(cid: String) = File(CharacterStore.dir(cid), "x")

    private fun displayName(c: Context, u: Uri): String = runCatching {
        c.contentResolver.query(u, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { if (it.moveToFirst()) it.getString(0) else null }
    }.getOrNull() ?: "asset"

    /** Copies picked files into x/ as [type] ("emotion" / "x-risu-asset") assets (IO thread); then call [append] on the main thread. */
    fun place(c: Context, cid: String, type: String, uris: List<Uri>): List<JsonObject> {
        val added = mutableListOf<JsonObject>()
        for (u in uris) {
            val bytes = runCatching { c.contentResolver.openInputStream(u)?.use { it.readBytes() } }.getOrNull() ?: continue
            val file = displayName(c, u)
            val name = file.substringBeforeLast('.').ifBlank { "asset" }
            val e = file.substringAfterLast('.', "").lowercase().takeIf { it.length in 2..5 } ?: sniffExt(bytes)
            val (path, ext) = place(xDir(cid), type, name, e, bytes)
            added.add(buildJsonObject {
                put("type", JsonPrimitive(type)); put("uri", JsonPrimitive("embeded://$path")); put("name", JsonPrimitive(name)); put("ext", JsonPrimitive(ext))
            })
        }
        return added
    }

    fun append(cid: String, added: List<JsonObject>) {
        if (added.isEmpty()) return
        EditHistory.silently { CharacterStore.updateData(cid) { d -> d.with("assets" to JsonArray(CardIO.assets(JsonObject(mapOf("data" to d))) + added)) } }
    }

    fun rename(cid: String, uri: String, name: String) {
        val n = name.trim().ifBlank { return }
        EditHistory.silently {
            CharacterStore.updateData(cid) { d ->
                d.with("assets" to JsonArray(CardIO.assets(JsonObject(mapOf("data" to d))).map { if (CardIO.str(it, "uri") == uri) JsonObject(it + ("name" to JsonPrimitive(n))) else it }))
            }
        }
    }

    fun remove(cid: String, uri: String) {
        EditHistory.silently {
            CharacterStore.updateData(cid) { d ->
                d.with("assets" to JsonArray(CardIO.assets(JsonObject(mapOf("data" to d))).filter { CardIO.str(it, "uri") != uri }))
            }
        }
        CardIO.assetFile(xDir(cid), uri)?.delete()
    }
}
