package space.sgu.next.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.setValue
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import java.io.File

/**
 * 참고 자료 (original modules 915 store, 932 defaults, 914 activation, 913 other characters).
 * Same item shape as the original: {id,name,kind text|persona|image|file, ext, mime, bytes, createdAt, note,
 * mode always|sub|keyword|off, folderId, keywords?, keywordDepth?, sessionOf?, sendNote?, sendImage?, personaImage?{ext,mime}}.
 * Files: files/references/index.json, folders.json, seeded.json, <id>.<ext>, <id>_img.<ext>.
 */
object References {
    val MODE_LABELS = linkedMapOf("always" to "상시", "sub" to "서브 판단", "keyword" to "키워드", "off" to "비활성화")
    private val TEXT_EXT = setOf("txt", "md", "json", "csv", "yaml", "yml", "html", "xml", "lua")
    private val IMAGE_EXT = setOf("png", "jpg", "jpeg", "webp", "gif", "bmp")

    val items = mutableStateListOf<JsonObject>()
    val folders = mutableStateListOf<JsonObject>()
    var version by mutableIntStateOf(0)
        private set
    private lateinit var dir: File
    private lateinit var ctx: Context
    private var loaded = false

    fun init(c: Context) {
        ctx = c.applicationContext
        dir = File(c.filesDir, "references").apply { mkdirs() }
        load()
    }

    private fun readList(f: String): List<JsonObject> = runCatching {
        (CardIO.json.parseToJsonElement(File(dir, f).readText()) as JsonArray).mapNotNull { it as? JsonObject }
    }.getOrDefault(emptyList())

    private fun write(f: String, list: List<JsonObject>) = File(dir, f).writeText(CardIO.json.encodeToString(JsonElement.serializer(), JsonArray(list)))
    private fun saveItems() { write("index.json", items.toList()); version++ }
    private fun saveFolders() { write("folders.json", folders.toList()); version++ }

    fun file(r: JsonObject) = File(dir, "${r.s("id")}.${r.s("ext")}")
    fun imageFile(r: JsonObject) = r.o("personaImage")?.let { File(dir, "${r.s("id")}_img.${it.s("ext")}") }

    fun load() {
        if (loaded) return
        loaded = true
        items.clear(); items.addAll(readList("index.json"))
        folders.clear(); folders.addAll(readList("folders.json"))
        seed()
    }

    /** First run: the original's default folders (베이직 / 템플릿 / 페르소나) and their texts (seed v6). */
    private fun seed() {
        val seededFile = File(dir, "seeded.json")
        val v = runCatching { CardIO.json.parseToJsonElement(seededFile.readText()).jsonObject.d("v")?.toInt() }.getOrNull() ?: 0
        if (v >= 6) return
        val def = runCatching { CardIO.json.parseToJsonElement(ctx.assets.open("default_references.json").bufferedReader().readText()).jsonObject }.getOrNull() ?: return
        for (f in def.arr("folders")) {
            var folder = folders.firstOrNull { it.s("name") == f.s("name") }
            if (folder == null) { folder = obj("id" to Scripts.newId("fld"), "name" to f.s("name"), "createdAt" to System.currentTimeMillis()); folders.add(folder) }
            for (r in f.arr("refs")) {
                if (items.any { it.s("name") == r.s("name") && it.s("folderId") == folder.s("id") }) continue
                val text = r.s("text")
                val item = obj("id" to Scripts.newId("ref"), "name" to r.s("name"), "kind" to "text", "ext" to "md", "mime" to "text/markdown",
                    "bytes" to text.toByteArray().size, "createdAt" to System.currentTimeMillis(), "note" to r.s("note"),
                    "mode" to r.s("mode").ifBlank { "sub" }, "folderId" to folder.s("id"))
                file(item).writeText(text)
                items.add(item)
            }
        }
        saveFolders(); saveItems()
        seededFile.writeText("{\"v\":6}")
    }

    private fun obj(vararg p: Pair<String, Any?>) = JsonObject(p.associate { (k, v) ->
        k to when (v) { null -> JsonNull; is JsonElement -> v; is String -> JsonPrimitive(v); is Number -> JsonPrimitive(v); is Boolean -> JsonPrimitive(v); else -> JsonPrimitive(v.toString()) }
    })

    fun get(id: String) = items.firstOrNull { it.s("id") == id }

    // ---------- folders ----------

    fun addFolder(name: String): JsonObject {
        val f = obj("id" to Scripts.newId("fld"), "name" to name.trim().ifBlank { "새 폴더" }, "createdAt" to System.currentTimeMillis())
        folders.add(f); saveFolders(); return f
    }

    fun renameFolder(id: String, name: String) {
        val i = folders.indexOfFirst { it.s("id") == id }; if (i < 0) return
        folders[i] = JsonObject(folders[i] + ("name" to JsonPrimitive(name.trim().ifBlank { folders[i].s("name") }))); saveFolders()
    }

    fun removeFolder(id: String) {
        folders.removeAll { it.s("id") == id }
        for (i in items.indices) if (items[i].s("folderId") == id) items[i] = JsonObject(items[i] + ("folderId" to JsonNull))
        saveFolders(); saveItems()
    }

    fun setFolderMode(folderId: String?, mode: String) {
        for (i in items.indices) if (items[i].sOrNull("folderId") == folderId && items[i].sOrNull("sessionOf") == null) items[i] = JsonObject(items[i] + ("mode" to JsonPrimitive(mode)))
        saveItems()
    }

    // ---------- items ----------

    fun update(id: String, patch: Map<String, JsonElement?>) {
        val i = items.indexOfFirst { it.s("id") == id }; if (i < 0) return
        val m = items[i].toMutableMap()
        patch.forEach { (k, v) -> if (v == null) m.remove(k) else m[k] = v }
        items[i] = JsonObject(m); saveItems()
    }

    fun readText(id: String): String? = get(id)?.let { r -> runCatching { file(r).readText() }.getOrNull() }

    fun updateText(id: String, text: String) {
        val r = get(id) ?: return
        file(r).writeText(text)
        update(id, mapOf("bytes" to JsonPrimitive(text.toByteArray().size)))
    }

    fun addText(name: String, text: String, note: String = "", folderId: String? = null): JsonObject {
        val item = obj("id" to Scripts.newId("ref"), "name" to name.ifBlank { "메모" }, "kind" to "text", "ext" to "md", "mime" to "text/markdown",
            "bytes" to text.toByteArray().size, "createdAt" to System.currentTimeMillis(), "note" to note, "mode" to "sub", "folderId" to folderId)
        file(item).writeText(text)
        items.add(0, item); saveItems(); return item
    }

    fun addPersona(name: String, text: String, note: String = "", folderId: String? = null): JsonObject {
        val item = obj("id" to Scripts.newId("ref"), "name" to name.ifBlank { "페르소나" }, "kind" to "persona", "ext" to "md", "mime" to "text/markdown",
            "bytes" to text.toByteArray().size, "createdAt" to System.currentTimeMillis(), "note" to note, "mode" to "sub", "folderId" to folderId,
            "sendNote" to false, "sendImage" to false)
        file(item).writeText(text)
        items.add(0, item); saveItems(); return item
    }

    private fun displayName(c: Context, u: Uri): String = runCatching {
        c.contentResolver.query(u, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { if (it.moveToFirst()) it.getString(0) else null }
    }.getOrNull() ?: "file"

    private fun mimeOf(ext: String) = when (ext) {
        "png" -> "image/png"; "jpg" -> "image/jpeg"; "webp" -> "image/webp"; "gif" -> "image/gif"; "bmp" -> "image/bmp"
        "md" -> "text/markdown"; "json" -> "application/json"; "csv" -> "text/csv"; "html" -> "text/html"; "xml" -> "application/xml"
        "txt", "lua", "yaml", "yml" -> "text/plain"; "pdf" -> "application/pdf"; else -> "application/octet-stream"
    }

    // ---------- RisuAI 페르소나 자동 감지 (original 1072 / 1071) ----------

    data class Persona(val name: String, val prompt: String, val note: String, val png: Boolean)

    fun detectPersona(text: String): Persona? {
        val root = runCatching { CardIO.json.parseToJsonElement(text) }.getOrNull() ?: return null
        fun one(e: kotlinx.serialization.json.JsonElement?): Persona? {
            val o = e as? JsonObject ?: return null
            val p = (o["personaPrompt"] as? kotlinx.serialization.json.JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
            return Persona(o.s("name").ifEmpty { "페르소나" }, p, o.s("note"), false)
        }
        if (root is kotlinx.serialization.json.JsonArray) return one(root.firstOrNull())
        val o = root as? JsonObject ?: return null
        return one(o) ?: one(o["data"]) ?: one(o["persona"]) ?: (o["personas"] as? kotlinx.serialization.json.JsonArray)?.let { one(it.firstOrNull()) }
    }

    fun detectPersonaPng(b: ByteArray): Persona? {
        val sig = byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10)
        if (b.size < 16 || (0 until 8).any { b[it] != sig[it] }) return null
        var pos = 8
        while (pos + 12 <= b.size) {
            val len = ((b[pos].toInt() and 0xFF) shl 24) or ((b[pos + 1].toInt() and 0xFF) shl 16) or ((b[pos + 2].toInt() and 0xFF) shl 8) or (b[pos + 3].toInt() and 0xFF)
            val type = String(b, pos + 4, 4, Charsets.ISO_8859_1)
            if (len < 0 || pos + 12 + len > b.size) break
            if (type == "tEXt") {
                val body = b.copyOfRange(pos + 8, pos + 8 + len)
                val z = body.indexOf(0.toByte())
                if (z > 0 && String(body, 0, z, Charsets.ISO_8859_1) == "persona") return runCatching {
                    detectPersona(String(android.util.Base64.decode(String(body, z + 1, body.size - z - 1, Charsets.ISO_8859_1), android.util.Base64.DEFAULT), Charsets.UTF_8))?.copy(png = true)
                }.getOrNull()
            }
            if (type == "IEND") break
            pos += 12 + len
        }
        return null
    }

    /** Looks for a RisuAI persona in a picked .png / .json / .txt file. */
    fun peekPersona(c: Context, u: Uri): Persona? = runCatching {
        val ext = displayName(c, u).substringAfterLast('.', "bin").lowercase()
        if (ext != "png" && ext != "json" && ext != "txt") return@runCatching null
        val bytes = c.contentResolver.openInputStream(u)?.use { it.readBytes() } ?: return@runCatching null
        if (ext == "png") detectPersonaPng(bytes) else detectPersona(bytes.toString(Charsets.UTF_8))
    }.getOrNull()

    /** Images are re-saved (max 2048px) like the original normalizeImage; text files keep their body; other files only name + memo. */
    fun addFile(c: Context, u: Uri, folderId: String? = null, sessionOf: String? = null): JsonObject? = runCatching {
        val name = displayName(c, u)
        var ext = name.substringAfterLast('.', "bin").lowercase().let { if (it == "jpeg") "jpg" else it }
        val id = Scripts.newId("ref")
        var kind = "file"
        val tmp = File(c.cacheDir, "ref-in-${System.nanoTime()}")
        c.contentResolver.openInputStream(u)?.use { i -> tmp.outputStream().use { i.copyTo(it) } } ?: return@runCatching null
        if (ext in IMAGE_EXT) {
            kind = "image"
            if (ext != "gif") { ext = if (ext == "png") "png" else "jpg"; normalizeImage(tmp, File(dir, "$id.$ext"), ext) } else tmp.copyTo(File(dir, "$id.$ext"), true)
        } else {
            if (ext in TEXT_EXT) kind = "text"
            tmp.copyTo(File(dir, "$id.$ext"), true)
        }
        tmp.delete()
        val item = obj("id" to id, "name" to name, "kind" to kind, "ext" to ext, "mime" to mimeOf(ext), "bytes" to File(dir, "$id.$ext").length(),
            "createdAt" to System.currentTimeMillis(), "note" to "", "mode" to "sub", "folderId" to folderId, "sessionOf" to sessionOf)
        items.add(0, if (sessionOf == null) JsonObject(item - "sessionOf") else item); saveItems(); item
    }.getOrNull()

    private fun normalizeImage(src: File, dst: File, ext: String) {
        val bmp = BitmapFactory.decodeFile(src.path) ?: run { src.copyTo(dst, true); return }
        val big = maxOf(bmp.width, bmp.height)
        val out = if (big > 2048) Bitmap.createScaledBitmap(bmp, bmp.width * 2048 / big, bmp.height * 2048 / big, true) else bmp
        dst.outputStream().use { out.compress(if (ext == "png") Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG, 90, it) }
        if (out !== bmp) out.recycle(); bmp.recycle()
    }

    fun setPersonaImage(c: Context, id: String, u: Uri?) {
        val r = get(id) ?: return
        imageFile(r)?.delete()
        if (u == null) { update(id, mapOf("personaImage" to null)); return }
        val tmp = File(c.cacheDir, "persona-${System.nanoTime()}")
        runCatching { c.contentResolver.openInputStream(u)?.use { i -> tmp.outputStream().use { i.copyTo(it) } } }
        normalizeImage(tmp, File(dir, "${id}_img.png"), "png"); tmp.delete()
        update(id, mapOf("personaImage" to obj("ext" to "png", "mime" to "image/png")))
    }

    fun remove(id: String) {
        val r = get(id) ?: return
        imageFile(r)?.delete(); file(r).delete()
        items.removeAll { it.s("id") == id }; saveItems()
    }

    fun sizeLabel(bytes: Long): String = when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "%.1f KB".format(bytes / 1024.0)
        else -> "%.1f MB".format(bytes / 1024.0 / 1024.0)
    }

    fun kindLabel(k: String) = when (k) { "text" -> "텍스트"; "persona" -> "페르소나"; "image" -> "이미지"; else -> "파일" }

    // ---------- activation in a session (module 914) ----------

    /** Keyword mode: a keyword (a|b|c, case-insensitive) in the command or the last N user commands. */
    fun keywordMatches(doc: JsonObject, r: JsonObject, command: String?, skipTurnId: String? = null): Boolean {
        val keys = r.s("keywords").split("|").map { it.trim().lowercase() }.filter { it.isNotEmpty() }
        if (keys.isEmpty()) return false
        val depth = maxOf(1, r.d("keywordDepth")?.toInt() ?: 1)
        val texts = mutableListOf<String>()
        if (command != null) texts.add(command)
        val turns = doc.o("session").arr("turns")
        for (i in turns.indices.reversed()) { if (texts.size >= depth) break; val t = turns[i]; if (t.s("id") != skipTurnId && t.s("command").isNotEmpty()) texts.add(t.s("command")) }
        return texts.take(depth).any { t -> val l = t.lowercase(); keys.any { l.contains(it) } }
    }

    /** "on" / "sub" / "off" for this session. */
    fun state(doc: JsonObject, r: JsonObject, command: String? = null, skipTurnId: String? = null): String {
        r.sOrNull("sessionOf")?.let { if (it != doc.s("id")) return "off" }
        doc.o("refOverrides")?.sOrNull(r.s("id"))?.let { return it }
        if ((doc["activeReferences"] as? JsonArray)?.any { (it as? JsonPrimitive)?.content == r.s("id") } == true) return "on"
        return when (r.s("mode").ifBlank { "sub" }) {
            "keyword" -> if (keywordMatches(doc, r, command, skipTurnId)) "on" else "off"
            "always" -> "on"; "sub" -> "sub"; else -> "off"
        }
    }

    // ---------- other characters as references (module 913, ids xc:<charId>:<fieldId>) ----------

    const val XC = "xc:"
    private val xcTexts = mutableMapOf<String, String>()
    data class Xc(val ref: JsonObject, val charName: String, val fieldId: String, val label: String, val text: String)

    private fun xcList(doc: JsonObject): List<Xc> {
        val ids = (doc["refChars"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content }?.filter { it != doc.s("id") } ?: return emptyList()
        val chars = ids.mapNotNull { id -> CharacterStore.items.firstOrNull { it.id == id }?.let { id to SguData.from(CharacterStore.readCard(id)["data"] as? JsonObject) } }
        val counts = chars.groupingBy { it.second.s("name").ifBlank { "Unnamed" } }.eachCount()
        val seen = mutableMapOf<String, Int>()
        val out = mutableListOf<Xc>()
        for ((id, data) in chars) {
            val base = data.s("name").ifBlank { "Unnamed" }
            val name = if ((counts[base] ?: 0) > 1) { val k = seen.getOrDefault(base, 0); seen[base] = k + 1; "$base.${'a' + k % 26}" } else base
            for (f in WFields.all) {
                if (!f.viewable || SguData.isEmpty(data, f.id)) continue
                val text = SguData.serialize(data, f.id); if (text.isBlank()) continue
                val rid = "$XC$id:${f.id}"
                xcTexts[rid] = text
                out.add(Xc(obj("id" to rid, "name" to "$name's ${BuiltinPrompts.label(f.id)}", "kind" to "text", "ext" to "txt", "mime" to "text/plain", "bytes" to text.length,
                    "createdAt" to 0, "note" to "${text.length} characters", "mode" to "sub"), name, f.id, BuiltinPrompts.label(f.id), text))
            }
        }
        return out
    }

    /** All references this session can use: the library plus other characters' fields. */
    fun all(doc: JsonObject): List<JsonObject> = items.toList() + xcList(doc).map { it.ref }

    /** Line describing a candidate for the sub processor (original 934 w). */
    fun describe(r: JsonObject): String {
        val id = r.s("id")
        val start = if (r.s("kind") == "text" || r.s("kind") == "persona")
            ((if (id.startsWith(XC)) xcTexts[id] else readText(id)) ?: "").replace(Regex("\\s+"), " ").take(160) else ""
        val bits = listOf(when (r.s("kind")) { "image" -> "image"; "persona" -> "persona of {{user}}"; "text" -> "text"; else -> "file" }, r.s("note"),
            if (start.isNotEmpty()) BuiltinPrompts.t("ref.starts", "text" to start) else "").filter { it.isNotEmpty() && it != r.s("note") }
        return BuiltinPrompts.t("ref.line", "id" to id, "name" to r.s("name"),
            "memo" to (if (r.s("note").isNotEmpty()) BuiltinPrompts.t("ref.memo", "note" to r.s("note")) else BuiltinPrompts.get("ref.memoNone")), "bits" to bits.joinToString("; "))
    }

    fun subCandidates(doc: JsonObject): List<JsonObject> = all(doc).filter { state(doc, it) == "sub" }

    data class RefAccess(val on: List<String>, val elevation: List<Elevation>)

    /** Module 914 resolveRefAccess: "on" refs are sent; "sub" refs the sub processor picked need the user's OK (all "sub" when the sub is off). */
    fun resolveAccess(doc: JsonObject, chosen: List<String>, reasons: Map<String, String>, subOff: Boolean, command: String?, skipTurnId: String? = null): RefAccess {
        val set = chosen.toSet(); val on = mutableListOf<String>(); val elev = mutableListOf<Elevation>()
        for (r in all(doc)) {
            val st = state(doc, r, command, skipTurnId)
            if (st == "on" || (subOff && st == "sub")) on.add(r.s("id"))
            else if (st == "sub" && r.s("id") in set) elev.add(Elevation("ref:" + r.s("id"), "view", reasons["view:ref:${r.s("id")}"]))
        }
        return RefAccess(on, elev)
    }

    /** Texts / images for the main prompt (module 915 loadForPrompt + 913 loadXcRefs). */
    fun loadForPrompt(doc: JsonObject, ids: List<String>, vision: Boolean): List<RefText> {
        val out = mutableListOf<RefText>()
        for (id in ids.filter { !it.startsWith(XC) }) {
            val r = get(id) ?: continue
            when (r.s("kind")) {
                "text", "persona" -> {
                    val img = if (r.s("kind") == "persona" && r.b("sendImage") && vision) imageFile(r)?.takeIf { it.exists() }?.let { Part(mime = r.o("personaImage").s("mime"), base64 = Base64.encodeToString(it.readBytes(), Base64.NO_WRAP)) } else null
                    out.add(RefText(r.s("name"), r.s("note"), r.s("kind"), runCatching { file(r).readText() }.getOrDefault(""), img, r.b("sendNote")))
                }
                "image" -> if (!vision) out.add(RefText(r.s("name"), r.s("note"), "text",
                    "(image: ${r.s("name")}${if (r.s("note").isNotEmpty()) " — ${r.s("note")}" else ""}) [The user attached this image, but image input (vision) is off for the current model, so you cannot see it. Tell the user to switch to a vision-capable model or enable Vision in the model settings.]"))
                else file(r).takeIf { it.exists() }?.let { out.add(RefText(r.s("name"), r.s("note"), "image", null, Part(mime = r.s("mime"), base64 = Base64.encodeToString(it.readBytes(), Base64.NO_WRAP)))) }
                else -> out.add(RefText(r.s("name"), r.s("note"), "text", "(file: ${r.s("name")}${if (r.s("note").isNotEmpty()) " — ${r.s("note")}" else ""})"))
            }
        }
        val want = ids.filter { it.startsWith(XC) }.toSet()
        if (want.isNotEmpty()) {
            xcList(doc).filter { it.ref.s("id") in want }.groupBy { it.fieldId }.forEach { (_, list) ->
                out.add(RefText("${list[0].label} from other characters (read-only reference; do not edit)", null, "text",
                    list.joinToString("\n") { "${it.charName}'s ${it.label}\n${it.text}\n~" }))
            }
        }
        return out
    }

    fun nameOf(doc: JsonObject, id: String) = all(doc).firstOrNull { it.s("id") == id }?.s("name") ?: id
}
