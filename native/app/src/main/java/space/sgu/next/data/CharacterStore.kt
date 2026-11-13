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
                "system_prompt", "post_history_instructions", "nickname")) put(k, JsonPrimitive(""))
            // 설정 › 제작자 이름 (module 1073 creatorName) is the default creator of a new project
            put("creator", JsonPrimitive(SettingsStore.root.s("creatorName").trim()))
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
        Workshop.forget(id)
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
        scriptKey(key)?.let { (kind, i) ->
            return when (kind) {
                "lore" -> Scripts.str(Scripts.lore(data).getOrNull(i), "content")
                "trig" -> Scripts.trigger(data).code
                else -> Scripts.str(Scripts.onboarding(data), "html")
            }
        }
        val f = Fields.byId[key] ?: return CardIO.str(data, key)
        return Fields.getString(data, f.path)
    }

    fun altGreetings(card: JsonObject): List<String> =
        ((card["data"] as? JsonObject)?.get("alternate_greetings") as? JsonArray)?.map { (it as? JsonPrimitive)?.contentOrNull ?: "" } ?: emptyList()

    /** Replaces card.data without adding an undo step (used by 되돌리기/다시 실행). */
    fun replaceDataSilently(id: String, data: JsonObject) = EditHistory.silently { updateData(id) { data } }

    /** Replaces card.data with transform(card.data). */
    fun updateData(id: String, transform: (JsonObject) -> JsonObject) = editData(id) { m ->
        // transform이 받은 값을 그대로 돌려주면(바꿀 게 없을 때) next는 m을 감싼 것 → 복사 없이 clear()하면 카드 내용이 전부 지워짐
        val next = LinkedHashMap(transform(JsonObject(m))); m.clear(); m.putAll(next)
    }

    private fun editData(id: String, change: (MutableMap<String, JsonElement>) -> Unit) {
        val card = readCard(id)
        val before = (card["data"] as? JsonObject) ?: JsonObject(emptyMap())
        val data = before.toMutableMap()
        change(data)
        val after = JsonObject(data)
        if (after == before) return
        EditHistory.record(id, before, after)
        writeCard(id, JsonObject(card + ("data" to after)))
        writeMeta(id, items.firstOrNull { it.id == id }?.folder)
        refresh(id)
    }

    /** Full-screen editor keys owned by the script editors: lore:N:content, trig:code, onb:html. */
    private fun scriptKey(key: String): Pair<String, Int>? = when {
        key.startsWith("lore:") && key.endsWith(":content") -> "lore" to (key.removePrefix("lore:").removeSuffix(":content").toIntOrNull() ?: -1)
        key == "trig:code" -> "trig" to 0
        key == "onb:html" -> "onb" to 0
        else -> null
    }

    fun setField(id: String, key: String, value: String) = editData(id) { data ->
        scriptKey(key)?.let { (kind, i) ->
            val d = JsonObject(data)
            val next = when (kind) {
                "lore" -> Scripts.lore(d).getOrNull(i)?.let { Scripts.saveLore(d, i, JsonObject(it + ("content" to JsonPrimitive(value)))) } ?: d
                "trig" -> Scripts.setTrigger(d, Scripts.trigger(d).copy(code = value))
                else -> Fields.set(d, Scripts.ONBOARDING_PATH, JsonObject(Scripts.onboarding(d) + ("html" to JsonPrimitive(value))))
            }
            replaceAll(data, next)
            return@editData
        }
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
        val next = Fields.set(JsonObject(data), path, JsonPrimitive(value))
        replaceAll(data, if (key == "lowLevelAccess") Scripts.resyncLowLevel(next) else next)
    }

    /** Lorebook settings: null = "use global setting" (same as the original's loreSettings). */
    data class LoreSettings(val scanDepth: Int?, val tokenBudget: Int?, val recursiveScanning: Boolean?, val fullWordMatching: Boolean?) {
        val usesGlobal get() = scanDepth == null && tokenBudget == null && recursiveScanning == null && fullWordMatching == null
    }

    fun loreSettings(card: JsonObject): LoreSettings = loreSettingsOf(card["data"] as? JsonObject)

    fun loreSettingsOf(data: JsonObject?): LoreSettings {
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

    fun withLoreSettings(data: JsonObject, s: LoreSettings): JsonObject {
        var d = data
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
        return d
    }

    fun setLoreSettings(id: String, s: LoreSettings) = editData(id) { data -> replaceAll(data, withLoreSettings(JsonObject(data), s)) }

    private fun replaceAll(target: MutableMap<String, JsonElement>, with: Map<String, JsonElement>) {
        // with가 target을 그대로 감싼 것일 수 있어서(바뀐 게 없을 때) 먼저 복사해 둠 — 안 그러면 clear()가 내용까지 지움
        val copy = LinkedHashMap(with)
        target.clear(); target.putAll(copy)
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
                        val c = CardIO.mergeRisum(CardIO.unzipCharx(input, x), x)
                        val icons = CardIO.assets(c).filter { CardIO.str(it, "type") == "icon" }
                        val main = icons.firstOrNull { CardIO.str(it, "name") == "main" } ?: icons.firstOrNull()
                        main?.let { CardIO.assetFile(x, CardIO.str(it, "uri")) }?.copyTo(File(d, "icon.png"), overwrite = true)
                        c
                    }
                    CardIO.Kind.JpegCharx -> {
                        // CHARX (JPEG 임베디드): 아이콘 JPEG 뒤에 붙은 ZIP부터 읽는다
                        val bytes = input.readBytes()
                        var at = -1
                        for (i in 0 until bytes.size - 3) if (bytes[i] == 'P'.code.toByte() && bytes[i + 1] == 'K'.code.toByte() && bytes[i + 2] == 3.toByte() && bytes[i + 3] == 4.toByte()) { at = i; break }
                        if (at < 0) error("이 JPEG에는 캐릭터 정보가 없어요")
                        val x = File(d, "x")
                        val c = CardIO.mergeRisum(CardIO.unzipCharx(java.io.ByteArrayInputStream(bytes, at, bytes.size - at), x), x)
                        val icons = CardIO.assets(c).filter { CardIO.str(it, "type") == "icon" }
                        val main = icons.firstOrNull { CardIO.str(it, "name") == "main" } ?: icons.firstOrNull()
                        val mf = main?.let { CardIO.assetFile(x, CardIO.str(it, "uri")) }
                        if (mf != null) mf.copyTo(File(d, "icon.png"), overwrite = true) else File(d, "icon.png").writeBytes(bytes.copyOfRange(0, at))
                        c
                    }
                    CardIO.Kind.Png -> {
                        val bytes = input.readBytes()
                        // 추가 에셋·감정 이미지(chara-ext-asset_:N 청크)도 x/ 로 풀어 둔다
                        val (c, _) = Assets.unpackImported(CardIO.cardFromPng(bytes), Assets.pngTextChunks(bytes), File(d, "x"))
                        File(d, "icon.png").writeBytes(bytes)
                        c
                    }
                    CardIO.Kind.Json -> {
                        // JSON은 에셋이 data:…;base64 로 들어 있음
                        val (c, main) = Assets.unpackImported(CardIO.parseJson(input.readBytes().toString(Charsets.UTF_8)), emptyMap(), File(d, "x"))
                        main?.let { File(d, "icon.png").writeBytes(it) }
                        c
                    }
                }
                // SGU 이미지 블록 정규식이 있으면 이미지 템플릿으로 복원하고 카드에서는 뺀다
                val (clean, gen) = GenEmbed.extract(card)
                writeCard(id, clean)
                gen?.let { ImageGenStore.writeImported(id, it) }
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
        val out = ctx.contentResolver.openOutputStream(uri) ?: error("저장할 곳을 열 수 없어요")
        out.use { writeCharxTo(id, it) }
    }

    /** Card with a main icon asset guaranteed (PNG/JSON imports keep the icon only as icon.png) + the extra zip entries for it. */
    fun cardWithMainIcon(id: String): Pair<JsonObject, Map<String, File>> {
        // 이미지 템플릿(블록·프리셋)을 정규식 스크립트 하나에 실어 보낸다 (GenEmbed)
        var card = GenEmbed.withEmbedded(id, readCard(id))
        val x = File(dir(id), "x").takeIf { it.exists() }
        val extra = mutableMapOf<String, File>()
        val icon = File(dir(id), "icon.png")
        val hasMain = x != null && CardIO.assets(card).any {
            CardIO.str(it, "type") == "icon" && CardIO.str(it, "name") == "main" && CardIO.assetFile(x, CardIO.str(it, "uri")) != null
        }
        if (!hasMain && icon.exists()) {
            // PNG/JSON imports (or an icon added later): put the icon in as the main icon asset.
            var path = "assets/icon/image/main.png"
            var k = 1
            while (x != null && File(x, path).exists()) { path = "assets/icon/image/main_$k.png"; k++ }
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
        return card to extra
    }

    fun writeCharxTo(id: String, out: java.io.OutputStream) {
        val (card, extra) = cardWithMainIcon(id)
        CardIO.writeCharx(out, card, File(dir(id), "x").takeIf { it.exists() }, extra)
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
