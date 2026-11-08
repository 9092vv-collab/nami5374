package space.sgu.next.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.LruCache
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import java.io.BufferedInputStream
import java.io.File

/** One character in the list (read from disk). */
data class CharMeta(
    val id: String,
    val name: String,
    val creator: String,
    val folder: String?,
    val updated: Long,
    val tagline: String,
    val icon: File?,
)

/**
 * Characters on disk:
 *   files/characters/<id>/card.json   — V3 card (raw JSON, unknown fields kept)
 *   files/characters/<id>/meta.json   — {folder, updated}
 *   files/characters/<id>/icon.png    — main icon (if any)
 *   files/characters/<id>/x/…         — unzipped CHARX assets (if imported from CHARX)
 *   files/characters/folders.json     — folder names (so empty folders survive)
 */
object CharacterStore {
    private lateinit var root: File
    val io = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val items = mutableStateListOf<CharMeta>()
    val folders = mutableStateListOf<String>()

    /** Bumped on every card write so open screens re-read. */
    var version by mutableIntStateOf(0)
        private set

    fun init(ctx: Context) {
        if (::root.isInitialized) return
        root = File(ctx.filesDir, "characters").apply { mkdirs() }
        reload()
    }

    private fun reload() {
        val list = root.listFiles()?.filter { it.isDirectory && File(it, "card.json").exists() }
            ?.mapNotNull { runCatching { loadMeta(it.name) }.getOrNull() }
            ?.sortedByDescending { it.updated } ?: emptyList()
        items.clear(); items.addAll(list)
        val saved = runCatching {
            (CardIO.json.parseToJsonElement(File(root, "folders.json").readText()) as JsonArray)
                .mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
        }.getOrDefault(emptyList())
        folders.clear(); folders.addAll((saved + list.mapNotNull { it.folder }).distinct())
    }

    fun dir(id: String) = File(root, id)
    private fun cardFile(id: String) = File(dir(id), "card.json")
    private fun metaFile(id: String) = File(dir(id), "meta.json")

    fun readCard(id: String): JsonObject =
        runCatching { CardIO.json.parseToJsonElement(cardFile(id).readText()).jsonObject }.getOrDefault(CardIO.normalize(JsonObject(emptyMap())))

    private fun readMetaJson(id: String): JsonObject =
        runCatching { CardIO.json.parseToJsonElement(metaFile(id).readText()).jsonObject }.getOrDefault(JsonObject(emptyMap()))

    private fun loadMeta(id: String): CharMeta {
        val card = readCard(id)
        val data = card["data"] as? JsonObject
        val meta = readMetaJson(id)
        val icon = File(dir(id), "icon.png").takeIf { it.exists() }
        val desc = CardIO.str(data, "description").replace(Regex("\\s+"), " ").trim()
        return CharMeta(
            id = id,
            name = CardIO.str(data, "name").ifBlank { "이름 없는 캐릭터" },
            creator = CardIO.str(data, "creator"),
            folder = CardIO.str(meta, "folder").ifBlank { null },
            updated = (meta["updated"] as? JsonPrimitive)?.longOrNull ?: cardFile(id).lastModified(),
            tagline = desc.take(80),
            icon = icon,
        )
    }

    private fun writeMeta(id: String, folder: String?) {
        val obj = buildJsonObject {
            if (folder != null) put("folder", JsonPrimitive(folder))
            put("updated", JsonPrimitive(System.currentTimeMillis()))
        }
        metaFile(id).writeText(CardIO.json.encodeToString(JsonElement.serializer(), obj))
    }

    private fun writeCard(id: String, card: JsonObject) {
        cardFile(id).writeText(CardIO.json.encodeToString(JsonElement.serializer(), card))
    }

    /** Re-reads one character's meta into the list (moves it to the top). */
    private fun refresh(id: String) {
        val m = runCatching { loadMeta(id) }.getOrNull() ?: return
        items.removeAll { it.id == id }
        items.add(0, m)
        version++
    }

    private fun newId() = "c" + System.currentTimeMillis().toString(36) + (100..999).random()

    private fun saveFolders() {
        File(root, "folders.json").writeText(CardIO.json.encodeToString(JsonElement.serializer(), JsonArray(folders.map { JsonPrimitive(it) })))
    }

    // ---------- list operations (main thread, small files) ----------

    fun create(): String {
        val id = newId()
        dir(id).mkdirs()
        val n = items.count { it.name.startsWith("프로젝트") } + 1
        val data = buildJsonObject {
            put("name", JsonPrimitive("프로젝트 $n"))
            for (k in listOf("description", "personality", "scenario", "first_mes", "mes_example", "creator_notes",
                "system_prompt", "post_history_instructions", "creator", "nickname")) put(k, JsonPrimitive(""))
            put("character_version", JsonPrimitive("1.0"))
            put("alternate_greetings", JsonArray(emptyList()))
            put("tags", JsonArray(emptyList()))
        }
        writeCard(id, CardIO.normalize(buildJsonObject { put("data", data) }))
        writeMeta(id, null)
        refresh(id)
        return id
    }

    fun duplicate(id: String): String {
        val nid = newId()
        dir(id).copyRecursively(dir(nid))
        val card = readCard(nid)
        val data = (card["data"] as? JsonObject).orEmpty().toMutableMap()
        data["name"] = JsonPrimitive(CardIO.str(card["data"] as? JsonObject, "name") + " 사본")
        writeCard(nid, JsonObject(card + ("data" to JsonObject(data))))
        writeMeta(nid, items.firstOrNull { it.id == id }?.folder)
        refresh(nid)
        return nid
    }

    fun delete(id: String) {
        dir(id).deleteRecursively()
        items.removeAll { it.id == id }
        Thumbs.forget(id)
        version++
    }

    fun moveToFolder(id: String, folder: String?) {
        writeMeta(id, folder)
        refresh(id)
    }

    fun createFolder(name: String) {
        val n = name.trim()
        if (n.isEmpty() || n in folders) return
        folders.add(n); saveFolders()
    }

    fun deleteFolder(name: String) {
        items.filter { it.folder == name }.forEach { writeMeta(it.id, null) }
        folders.remove(name); saveFolders()
        reload(); version++
    }

    // ---------- fields ----------

    /** Field keys: a Fields id ("description"…) or "alt:N" for alternate greeting N. */
    fun getField(card: JsonObject, key: String): String {
        val data = card["data"] as? JsonObject
        if (key.startsWith("alt:")) {
            val i = key.removePrefix("alt:").toIntOrNull() ?: return ""
            return ((data?.get("alternate_greetings") as? JsonArray)?.getOrNull(i) as? JsonPrimitive)?.contentOrNull ?: ""
        }
        val f = Fields.byId[key] ?: return CardIO.str(data, key)
        return Fields.getString(data, f.path)
    }

    fun altGreetings(card: JsonObject): List<String> =
        ((card["data"] as? JsonObject)?.get("alternate_greetings") as? JsonArray)?.map { (it as? JsonPrimitive)?.contentOrNull ?: "" } ?: emptyList()

    private fun editData(id: String, change: (MutableMap<String, JsonElement>) -> Unit) {
        val card = readCard(id)
        val data = (card["data"] as? JsonObject).orEmpty().toMutableMap()
        change(data)
        writeCard(id, JsonObject(card + ("data" to JsonObject(data))))
        writeMeta(id, items.firstOrNull { it.id == id }?.folder)
        refresh(id)
    }

    fun setField(id: String, key: String, value: String) = editData(id) { data ->
        if (key.startsWith("alt:")) {
            val i = key.removePrefix("alt:").toIntOrNull() ?: return@editData
            val list = ((data["alternate_greetings"] as? JsonArray) ?: JsonArray(emptyList())).toMutableList()
            while (list.size <= i) list.add(JsonPrimitive(""))
            list[i] = JsonPrimitive(value)
            data["alternate_greetings"] = JsonArray(list)
        } else {
            val path = Fields.byId[key]?.path ?: listOf(key)
            replaceAll(data, Fields.set(JsonObject(data), path, JsonPrimitive(value)))
        }
    }

    fun setBool(id: String, key: String, value: Boolean) = editData(id) { data ->
        val path = Fields.byId[key]?.path ?: return@editData
        replaceAll(data, Fields.set(JsonObject(data), path, JsonPrimitive(value)))
    }

    /** Lorebook settings: null = "use global setting" (same as the original's loreSettings). */
    data class LoreSettings(val scanDepth: Int?, val tokenBudget: Int?, val recursiveScanning: Boolean?, val fullWordMatching: Boolean?) {
        val usesGlobal get() = scanDepth == null && tokenBudget == null && recursiveScanning == null && fullWordMatching == null
    }

    fun loreSettings(card: JsonObject): LoreSettings {
        val data = card["data"] as? JsonObject
        val ls = Fields.get(data, listOf("extensions", "risuai", "loreSettings")) as? JsonObject
        val book = data?.get("character_book") as? JsonObject
        fun int(o: JsonObject?, k: String) = (o?.get(k) as? JsonPrimitive)?.contentOrNull?.toDoubleOrNull()?.toInt()
        fun bool(o: JsonObject?, k: String) = (o?.get(k) as? JsonPrimitive)?.contentOrNull?.toBooleanStrictOrNull()
        return LoreSettings(
            int(ls, "scanDepth") ?: int(book, "scan_depth"),
            int(ls, "tokenBudget") ?: int(book, "token_budget"),
            bool(ls, "recursiveScanning") ?: bool(book, "recursive_scanning"),
            bool(ls, "fullWordMatching") ?: bool(book?.get("extensions") as? JsonObject, "risu_fullWordMatching"),
        )
    }

    fun setLoreSettings(id: String, s: LoreSettings) = editData(id) { data ->
        var d = JsonObject(data)
        fun putPath(path: List<String>, v: JsonElement?) { d = if (v == null) Fields.remove(d, path) else Fields.set(d, path, v) }
        putPath(listOf("character_book", "scan_depth"), s.scanDepth?.let { JsonPrimitive(it) })
        putPath(listOf("character_book", "token_budget"), s.tokenBudget?.let { JsonPrimitive(it) })
        putPath(listOf("character_book", "recursive_scanning"), s.recursiveScanning?.let { JsonPrimitive(it) })
        putPath(listOf("character_book", "extensions", "risu_fullWordMatching"), s.fullWordMatching?.let { JsonPrimitive(it) })
        putPath(listOf("extensions", "risuai", "loreSettings"), if (s.usesGlobal) null else buildJsonObject {
            put("scanDepth", s.scanDepth?.let { JsonPrimitive(it) } ?: kotlinx.serialization.json.JsonNull)
            put("tokenBudget", s.tokenBudget?.let { JsonPrimitive(it) } ?: kotlinx.serialization.json.JsonNull)
            put("recursiveScanning", s.recursiveScanning?.let { JsonPrimitive(it) } ?: kotlinx.serialization.json.JsonNull)
            put("fullWordMatching", s.fullWordMatching?.let { JsonPrimitive(it) } ?: kotlinx.serialization.json.JsonNull)
        })
        replaceAll(data, d)
    }

    private fun replaceAll(target: MutableMap<String, JsonElement>, with: Map<String, JsonElement>) {
        target.clear(); target.putAll(with)
    }

    fun addAltGreeting(id: String): Int {
        var index = 0
        editData(id) { data ->
            val list = ((data["alternate_greetings"] as? JsonArray) ?: JsonArray(emptyList())).toMutableList()
            list.add(JsonPrimitive(""))
            index = list.size - 1
            data["alternate_greetings"] = JsonArray(list)
        }
        return index
    }

    fun removeAltGreeting(id: String, index: Int) = editData(id) { data ->
        val list = ((data["alternate_greetings"] as? JsonArray) ?: return@editData).toMutableList()
        if (index in list.indices) list.removeAt(index)
        data["alternate_greetings"] = JsonArray(list)
    }

    // ---------- import / export (IO thread) ----------

    suspend fun import(ctx: Context, uri: Uri): String = withContext(Dispatchers.IO) {
        val id = newId()
        val d = dir(id)
        try {
            val stream = BufferedInputStream(ctx.contentResolver.openInputStream(uri) ?: error("파일을 열 수 없어요"))
            stream.use { input ->
                input.mark(16)
                val head = ByteArray(8)
                val n = input.read(head)
                input.reset()
                d.mkdirs()
                val card: JsonObject = when (CardIO.detect(head.copyOf(maxOf(n, 0)))) {
                    CardIO.Kind.Charx -> {
                        val x = File(d, "x")
                        val c = CardIO.unzipCharx(input, x)
                        val icons = CardIO.assets(c).filter { CardIO.str(it, "type") == "icon" }
                        val main = icons.firstOrNull { CardIO.str(it, "name") == "main" } ?: icons.firstOrNull()
                        main?.let { CardIO.assetFile(x, CardIO.str(it, "uri")) }?.copyTo(File(d, "icon.png"), overwrite = true)
                        c
                    }
                    CardIO.Kind.Png -> {
                        val bytes = input.readBytes()
                        val c = CardIO.cardFromPng(bytes)
                        File(d, "icon.png").writeBytes(bytes)
                        c
                    }
                    CardIO.Kind.Json -> CardIO.parseJson(input.readBytes().toString(Charsets.UTF_8))
                }
                writeCard(id, card)
                writeMeta(id, null)
            }
            withContext(Dispatchers.Main) { refresh(id) }
            id
        } catch (e: Throwable) {
            d.deleteRecursively()
            throw e
        }
    }

    suspend fun exportCharx(ctx: Context, id: String, uri: Uri) = withContext(Dispatchers.IO) {
        var card = readCard(id)
        val x = File(dir(id), "x").takeIf { it.exists() }
        val extra = mutableMapOf<String, File>()
        val icon = File(dir(id), "icon.png")
        if (x == null && icon.exists()) {
            // PNG/JSON imports: put the icon in as the main icon asset.
            val path = "assets/icon/image/main.png"
            extra[path] = icon
            val data = (card["data"] as? JsonObject).orEmpty().toMutableMap()
            val assets = CardIO.assets(card).filterNot { CardIO.str(it, "type") == "icon" && CardIO.str(it, "name") == "main" } +
                buildJsonObject {
                    put("type", JsonPrimitive("icon")); put("uri", JsonPrimitive("embeded://$path"))
                    put("name", JsonPrimitive("main")); put("ext", JsonPrimitive("png"))
                }
            data["assets"] = JsonArray(assets)
            card = JsonObject(card + ("data" to JsonObject(data)))
        }
        val out = ctx.contentResolver.openOutputStream(uri) ?: error("저장할 곳을 열 수 없어요")
        out.use { CardIO.writeCharx(it, card, x, extra) }
    }
}

/** Small downsampling bitmap cache for icons. */
object Thumbs {
    private val cache = LruCache<String, ImageBitmap>(80)

    fun load(file: File, key: String, maxPx: Int): ImageBitmap? {
        cache.get(key)?.let { return it }
        if (!file.exists()) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= maxPx && bounds.outHeight / (sample * 2) >= maxPx) sample *= 2
        val bmp: Bitmap = BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
        return bmp.asImageBitmap().also { cache.put(key, it) }
    }

    fun forget(prefix: String) {
        cache.snapshot().keys.filter { it.startsWith(prefix) }.forEach { cache.remove(it) }
    }
}
