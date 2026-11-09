package space.sgu.next.data

import android.content.Context
import androidx.compose.runtime.mutableStateListOf
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import java.io.File

private fun newLibId(p: String) = p + "_" + System.currentTimeMillis().toString(36) + (1000..9999).random().toString(36)

/**
 * Image templates, ported from the original:
 *  - export/import a template file's data (module 907, `{sgu:"gen-template", version:1, data}`)
 *  - switching templates inside one character's imageGen (module 1684: templateId / templateName / templates stash)
 * SGU Next addition: blocks of type "folder" and a block's folderId survive export/import.
 */
object Templates {
    const val KIND = "gen-template"
    const val DEFAULT_NAME = "이미지 템플릿"
    const val DEFAULT_LABEL = "기본 템플릿"
    private lateinit var ctx: Context
    val defaultData: JsonObject by lazy {
        runCatching { CardIO.json.parseToJsonElement(ctx.assets.open("default_template.json").bufferedReader().readText()).jsonObject }
            .getOrElse { JsonObject(mapOf("name" to JsonPrimitive(DEFAULT_LABEL), "characters" to JsonArray(emptyList()), "presets" to JsonArray(emptyList()))) }
    }

    fun init(c: Context) { ctx = c }

    fun exportTemplate(g: JsonObject): JsonObject {
        val chars = g.arr("characters")
        val cMap = chars.mapIndexed { i, c -> c.s("id") to "c${i + 1}" }.toMap()
        val bMap = mutableMapOf<String, String>()
        chars.forEachIndexed { i, c -> c.arr("blocks").forEachIndexed { j, b -> bMap[b.s("id")] = "c${i + 1}b${j + 1}" } }
        return buildJsonObject {
            put("name", JsonPrimitive(g.s("templateName")))
            put("characters", JsonArray(chars.mapIndexed { i, c ->
                buildJsonObject {
                    put("id", JsonPrimitive("c${i + 1}")); put("name", JsonPrimitive("character ${i + 1}"))
                    put("size", c.o("size") ?: ImageGenStore.size())
                    put("blocks", JsonArray(c.arr("blocks").mapIndexed { j, b ->
                        buildJsonObject {
                            put("id", JsonPrimitive("c${i + 1}b${j + 1}")); put("name", JsonPrimitive(b.s("name"))); put("type", JsonPrimitive(b.s("type").ifBlank { "prompt" }))
                            if (b.b("keep")) { put("keep", JsonPrimitive(true)); put("text", JsonPrimitive(b.s("text"))) }
                            b.sOrNull("folderId")?.let { f -> bMap[f]?.let { put("folderId", JsonPrimitive(it)) } }
                        }
                    }))
                }
            }))
            put("presets", JsonArray(g.arr("presets").map { p ->
                buildJsonObject {
                    put("name", JsonPrimitive(p.s("name"))); put("imageName", JsonPrimitive(p.s("imageName"))); put("imageType", JsonPrimitive(p.s("imageType").ifBlank { "asset" }))
                    put("size", p.o("size") ?: ImageGenStore.size())
                    put("assetPrompt", JsonPrimitive(p.s("assetPrompt"))); put("assetNegative", JsonPrimitive(p.s("assetNegative")))
                    put("chars", JsonArray(p.arr("chars").filter { cMap.containsKey(it.s("characterId")) }.map { pc ->
                        buildJsonObject {
                            put("characterId", JsonPrimitive(cMap[pc.s("characterId")]!!))
                            put("blockIds", JsonArray(ImageGenStore.blockIdList(pc).mapNotNull { bMap[it] }.map { JsonPrimitive(it) }))
                            put("prompt", JsonPrimitive(pc.s("prompt"))); put("negative", JsonPrimitive(pc.s("negative")))
                            put("pos", pc.o("pos") ?: buildJsonObject { put("mode", JsonPrimitive("auto")) })
                        }
                    }))
                }
            }))
        }
    }

    /** Returns [g] with the template's characters and presets (new ids), or null if [data] is not a template. */
    fun importTemplate(g: JsonObject, data: JsonObject?): JsonObject? {
        if (data == null || data["presets"] !is JsonArray) return null
        val cMap = mutableMapOf<String, String>()
        val bMap = mutableMapOf<String, String>()
        val chars = data.arr("characters").mapIndexed { i, c ->
            val id = newLibId("gc"); cMap[c.s("id")] = id
            val blocks = c.arr("blocks").map { b -> val nid = newLibId("gb"); bMap[b.s("id")] = nid; b to nid }.map { (b, nid) ->
                val keep = b.b("keep")
                buildJsonObject {
                    put("id", JsonPrimitive(nid)); put("name", JsonPrimitive(b.s("name")))
                    put("type", JsonPrimitive(when (b.s("type")) { "negative" -> "negative"; "folder" -> "folder"; else -> "prompt" }))
                    put("text", JsonPrimitive(if (keep) b.s("text") else ""))
                    if (keep) put("keep", JsonPrimitive(true))
                    b.sOrNull("folderId")?.let { f -> bMap[f]?.let { put("folderId", JsonPrimitive(it)) } }
                }
            }
            buildJsonObject {
                put("id", JsonPrimitive(id)); put("name", JsonPrimitive("character ${i + 1}"))
                put("size", c.o("size")?.let { ImageGenStore.normalizeSize(it) } ?: ImageGenStore.size())
                put("freePrompt", JsonPrimitive("")); put("freeNegative", JsonPrimitive(""))
                put("blocks", JsonArray(blocks))
            }
        }
        val presets = data.arr("presets").map { p ->
            val pcs = p.arr("chars").filter { cMap.containsKey(it.s("characterId")) }.map { pc ->
                buildJsonObject {
                    put("id", JsonPrimitive(newLibId("pc"))); put("characterId", JsonPrimitive(cMap[pc.s("characterId")]!!))
                    put("blockIds", JsonArray(((pc["blockIds"] as? JsonArray) ?: JsonArray(emptyList())).mapNotNull { (it as? JsonPrimitive)?.content?.let { k -> bMap[k] } }.map { JsonPrimitive(it) }))
                    put("prompt", JsonPrimitive(pc.s("prompt"))); put("negative", JsonPrimitive(pc.s("negative")))
                    put("pos", ImageGenStore.normalizePos(pc.o("pos")))
                }
            }
            ImageGenStore.newPreset(p.s("name").ifBlank { "가져온 프리셋" }, null).with(
                "imageName" to JsonPrimitive(p.s("imageName")),
                "imageType" to JsonPrimitive(if (p.s("imageType") == "emotion") "emotion" else "asset"),
                "size" to ImageGenStore.normalizeSize(p.o("size")),
                "assetPrompt" to JsonPrimitive(p.s("assetPrompt")), "assetNegative" to JsonPrimitive(p.s("assetNegative")),
                "chars" to JsonArray(ImageGenStore.normalizePositions(pcs)),
            )
        }
        return g.with(
            "characters" to JsonArray(chars), "selectedCharacterId" to str(chars.firstOrNull()?.s("id")),
            "preview" to JsonNull, "presets" to JsonArray(presets),
        )
    }

    // ---------- one character's templates (module 1684) ----------

    data class T(val id: String, val name: String, val characters: JsonElement, val selected: JsonElement, val presets: JsonElement) {
        fun json() = buildJsonObject {
            put("id", JsonPrimitive(id)); put("name", JsonPrimitive(name)); put("characters", characters)
            put("selectedCharacterId", selected); put("presets", presets)
        }
    }

    private fun tOf(o: JsonObject) = T(o.s("id"), o.s("name"), o["characters"] ?: JsonArray(emptyList()), o["selectedCharacterId"] ?: JsonNull, o["presets"] ?: JsonArray(emptyList()))

    fun current(g: JsonObject) = T(g.sOrNull("templateId") ?: "current", g.s("templateName").ifBlank { DEFAULT_NAME },
        g["characters"] ?: JsonArray(emptyList()), g["selectedCharacterId"] ?: JsonNull, g["presets"] ?: JsonArray(emptyList()))

    fun stored(g: JsonObject) = g.arr("templates").map { tOf(it) }

    /** Current template first, then the ones stashed in this character. */
    fun all(g: JsonObject): List<Pair<T, Boolean>> = listOf(current(g) to true) + stored(g).map { it to false }

    private fun apply(g: JsonObject, t: T): JsonObject {
        val firstChar = (t.characters as? JsonArray)?.firstOrNull()?.let { (it as JsonObject).s("id") }
        return g.with(
            "templateId" to JsonPrimitive(t.id), "templateName" to JsonPrimitive(t.name), "characters" to t.characters,
            "selectedCharacterId" to (if (t.selected is JsonPrimitive) t.selected else str(firstChar)), "presets" to t.presets, "preview" to JsonNull,
        )
    }

    private fun stashCurrent(g: JsonObject): List<T> = stored(g) + current(g).copy(id = g.sOrNull("templateId") ?: newLibId("tpl"))

    fun switchTo(g: JsonObject, id: String): JsonObject {
        val t = stored(g).firstOrNull { it.id == id } ?: return g
        val rest = stashCurrent(g).filter { it.id != id }
        return apply(g, t).with("templates" to JsonArray(rest.map { it.json() }))
    }

    private fun addImported(g: JsonObject, state: JsonObject, name: String, id: String?): JsonObject {
        val t = T(id ?: newLibId("tpl"), name.trim().ifBlank { DEFAULT_NAME }, state["characters"] ?: JsonArray(emptyList()),
            state["selectedCharacterId"] ?: JsonNull, state["presets"] ?: JsonArray(emptyList()))
        return apply(g, t).with("templates" to JsonArray(stashCurrent(g).map { it.json() }))
    }

    fun rename(g: JsonObject, id: String, name: String): JsonObject {
        val n = name.trim().ifBlank { DEFAULT_NAME }
        return if ((g.sOrNull("templateId") ?: "current") == id) g.with("templateId" to JsonPrimitive(g.sOrNull("templateId") ?: id), "templateName" to JsonPrimitive(n))
        else g.with("templates" to JsonArray(stored(g).map { if (it.id == id) it.copy(name = n).json() else it.json() }))
    }

    /** The whole imageGen as if template [t] were current (used to save/export a template). */
    fun asState(g: JsonObject, t: T) = g.with("characters" to t.characters, "presets" to t.presets, "selectedCharacterId" to t.selected,
        "templateName" to JsonPrimitive(t.name))

    /** Tapping a library template: switch to it (stashed copy if this character used it before, else a fresh import). */
    fun selectLibrary(g: JsonObject, libId: String, name: String, data: JsonObject): JsonObject {
        if ((g.sOrNull("templateId") ?: "current") == libId) return g
        if (stored(g).any { it.id == libId }) return switchTo(g, libId)
        val imported = importTemplate(g, data) ?: return g
        return addImported(g, imported, name, libId)
    }

    /**
     * "캐릭터 설정 가져오기" (asked for by the user): bring the characters of another template into the current one.
     * replace = same position's character gets the source's name and block texts (matched by block name + type, ids kept so
     * presets keep working; unmatched source blocks are added); extra source characters are added. replace = false adds all
     * source characters after the current ones.
     */
    fun importCharacters(g: JsonObject, source: List<JsonObject>, replace: Boolean): JsonObject {
        val cur = g.arr("characters").toMutableList()
        fun fresh(c: JsonObject): JsonObject {
            val map = c.arr("blocks").associate { it.s("id") to newLibId("gb") }
            return c.with("id" to JsonPrimitive(newLibId("gc")), "blocks" to JsonArray(c.arr("blocks").map { b ->
                val nb = b.with("id" to JsonPrimitive(map[b.s("id")]!!))
                b.sOrNull("folderId")?.let { f -> map[f]?.let { nb.with("folderId" to JsonPrimitive(it)) } } ?: JsonObject(nb - "folderId")
            }))
        }
        if (!replace) return g.with("characters" to JsonArray(cur + source.map { fresh(it) }))
        source.forEachIndexed { i, src ->
            if (i >= cur.size) { cur.add(fresh(src)); return@forEachIndexed }
            val target = cur[i]
            val used = mutableSetOf<String>()
            val srcBlocks = src.arr("blocks")
            val idMap = mutableMapOf<String, String>()
            val merged = target.arr("blocks").map { b ->
                val m = srcBlocks.firstOrNull { it.s("id") !in used && it.s("name") == b.s("name") && it.s("type") == b.s("type") }
                if (m == null) b else { used.add(m.s("id")); idMap[m.s("id")] = b.s("id"); b.with("text" to JsonPrimitive(m.s("text"))) }
            }.toMutableList()
            srcBlocks.filter { it.s("id") !in used }.forEach { b -> val nid = newLibId("gb"); idMap[b.s("id")] = nid; merged.add(b.with("id" to JsonPrimitive(nid))) }
            val fixed = merged.map { b ->
                val f = b.sOrNull("folderId") ?: return@map b
                if (merged.any { it.s("id") == f }) b else idMap[f]?.let { b.with("folderId" to JsonPrimitive(it)) } ?: JsonObject(b - "folderId")
            }
            cur[i] = target.with("name" to JsonPrimitive(src.s("name").ifBlank { target.s("name") }), "blocks" to JsonArray(fixed),
                "freePrompt" to JsonPrimitive(src.s("freePrompt")), "freeNegative" to JsonPrimitive(src.s("freeNegative")))
        }
        return g.with("characters" to JsonArray(cur))
    }
}

/** A list of {id,name,data,favorite?} saved under files/settings/<name>.json (zustand stores 905 and 909). */
open class JsonLibrary(private val fileName: String, private val idPrefix: String) {
    val items = mutableStateListOf<JsonObject>()
    protected var loaded = false
    private lateinit var file: File

    open fun init(ctx: Context) {
        file = File(File(ctx.filesDir, "settings").apply { mkdirs() }, fileName)
    }

    open fun defaults(): List<JsonObject> = emptyList()
    open fun fixUp(list: List<JsonObject>): List<JsonObject> = list

    fun load() {
        if (loaded) return
        loaded = true
        val list = runCatching { CardIO.json.parseToJsonElement(file.readText()).jsonObject.arr("items") }.getOrNull() ?: defaults()
        items.clear(); items.addAll(fixUp(list))
        save()
    }

    private fun save() {
        val text = CardIO.json.encodeToString(JsonElement.serializer(), JsonObject(mapOf("items" to JsonArray(items.toList()))))
        CharacterStore.io.launch { file.writeText(text) }
    }

    fun favorite(): JsonObject? { load(); return items.firstOrNull { it.b("favorite") } }

    fun add(name: String, data: JsonObject, id: String = newLibId(idPrefix)): String {
        load()
        if (items.any { it.s("id") == id }) return id
        items.add(JsonObject(mapOf("id" to JsonPrimitive(id), "name" to JsonPrimitive(name.trim().ifBlank { "템플릿" }), "data" to data)))
        save(); return id
    }

    fun rename(id: String, name: String) { edit(id) { it.with("name" to JsonPrimitive(name.trim().ifBlank { it.s("name") })) } }
    fun setData(id: String, data: JsonObject) { edit(id) { it.with("data" to data) } }

    fun toggleFavorite(id: String) {
        load()
        val was = items.firstOrNull { it.s("id") == id }.b("favorite")
        val next = items.map { if (it.s("id") == id && !was) it.with("favorite" to JsonPrimitive(true)) else JsonObject(it - "favorite") }
        items.clear(); items.addAll(next); save()
    }

    fun remove(id: String) { load(); items.removeAll { it.s("id") == id }; save() }

    private fun edit(id: String, f: (JsonObject) -> JsonObject) {
        load()
        val i = items.indexOfFirst { it.s("id") == id }
        if (i >= 0) { items[i] = f(items[i]); save() }
    }
}

/** settings/image-templates.json — shared by all characters; "default" is the built-in 기본 템플릿. */
object TemplateLibrary : JsonLibrary("image-templates.json", "lib") {
    override fun defaults() = listOf(JsonObject(mapOf("id" to JsonPrimitive("default"), "name" to JsonPrimitive(Templates.DEFAULT_LABEL), "data" to Templates.defaultData)))
    override fun fixUp(list: List<JsonObject>) = list.map { if (it.s("id") == "default") it.with("data" to Templates.defaultData.with("name" to JsonPrimitive(it.s("name")))) else it }

    /** Same as the original's newProjectTemplateState: ★ template, else the built-in default. */
    fun newProjectState(base: JsonObject): JsonObject {
        favorite()?.let { f -> Templates.importTemplate(base, f.o("data"))?.let { return it.with("templateName" to JsonPrimitive(f.s("name")), "templateId" to JsonPrimitive(f.s("id"))) } }
        return (Templates.importTemplate(base, Templates.defaultData) ?: base).with("templateName" to JsonPrimitive(Templates.DEFAULT_LABEL), "templateId" to JsonPrimitive("default"))
    }

    /** On opening the manager: every template this character has is put in the library (original TemplateManager effect). */
    fun syncFrom(cid: String) {
        load()
        val g = ImageGenStore.load(cid)
        Templates.all(g).forEach { (t, isCurrent) ->
            if (items.any { it.s("id") == t.id }) return@forEach
            val id = if (!isCurrent || (t.id != "current" && g.sOrNull("templateId") != null)) t.id else newLibId("tpl")
            add(t.name, Templates.exportTemplate(Templates.asState(g, t)), id)
            if (isCurrent && id != t.id) ImageGenStore.update(cid) { it.with("templateId" to JsonPrimitive(id)) }
        }
    }
}

/** settings/art-styles.json (module 909). data uses the original keys ("model name", Steps, …). */
object StyleLibrary : JsonLibrary("art-styles.json", "sty") {
    const val KIND = "art-style"

    fun fromStyle(style: JsonObject?): JsonObject = buildJsonObject {
        put("model name", JsonPrimitive(style.s("model"))); put("Steps", num(style.d("steps") ?: 28.0))
        put("Prompt Guidance", num(style.d("guidance") ?: 5.0)); put("Prompt Guidance Rescale", num(style.d("rescale") ?: 0.0))
        put("Sampler", JsonPrimitive(style.s("sampler"))); put("Art style prompt", JsonPrimitive(style.s("prompt"))); put("Art style negative", JsonPrimitive(style.s("negative")))
    }

    /** Fields to put into style (only the ones present and valid), like the original apply / favoriteStylePatch. */
    fun patch(data: JsonObject?): List<Pair<String, JsonElement>>? {
        if (data == null) return null
        fun n(k: String) = (data[k] as? JsonPrimitive)?.content?.toDoubleOrNull()
        return buildList {
            data.s("model name").takeIf { it.isNotBlank() }?.let { add("model" to JsonPrimitive(it)) }
            n("Steps")?.let { add("steps" to num(it)) }
            n("Prompt Guidance")?.let { add("guidance" to num(it)) }
            n("Prompt Guidance Rescale")?.let { add("rescale" to num(it)) }
            data.s("Sampler").takeIf { s -> NaiModels.samplers.any { it.first == s } }?.let { add("sampler" to JsonPrimitive(it)) }
            (data["Art style prompt"] as? JsonPrimitive)?.let { if (it.isString) add("prompt" to it) }
            (data["Art style negative"] as? JsonPrimitive)?.let { if (it.isString) add("negative" to it) }
        }
    }
}

/**
 * Batch generation and compression that keep running when you leave the screen (original useRunStore + runPresets).
 * One job per character; NovelAI only takes one image at a time, so presets run one after another.
 */
object GenRun {
    data class Progress(val done: Int, val total: Int, val startedAt: Long, val est: Long?)
    val progress = androidx.compose.runtime.mutableStateMapOf<String, Progress>()
    val current = androidx.compose.runtime.mutableStateMapOf<String, String>()
    val error = androidx.compose.runtime.mutableStateMapOf<String, String>()
    val compressing = androidx.compose.runtime.mutableStateMapOf<String, Pair<Int, Int>>()
    val compressMsg = androidx.compose.runtime.mutableStateMapOf<String, String>()
    private val jobs = mutableMapOf<String, kotlinx.coroutines.Job>()
    private val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Main)

    fun running(cid: String) = progress.containsKey(cid)

    /** Module 1686 remainingSeconds / formatEta. */
    fun eta(p: Progress?): String {
        val s = p?.est ?: return "계산 중"
        return "%02d분 %02d초".format(s / 60, s % 60)
    }

    fun run(cid: String, presetIds: List<String>, n: Int) {
        if (running(cid) || presetIds.isEmpty()) return
        val total = presetIds.size * n.coerceAtLeast(1)
        val start = System.currentTimeMillis()
        error.remove(cid)
        progress[cid] = Progress(0, total, start, null)
        jobs[cid] = scope.launch {
            var done = 0
            try {
                outer@ for (pid in presetIds) for (k in 0 until n.coerceAtLeast(1)) {
                    if (ImageGenStore.preset(ImageGenStore.load(cid), pid) == null) break@outer
                    current[cid] = pid
                    try { ImageGenStore.generateForPreset(cid, pid) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Throwable) {
                        error[cid] = e.message ?: "생성 실패"; break@outer
                    }
                    done++
                    val el = System.currentTimeMillis() - start
                    progress[cid] = Progress(done, total, start, if (done > 0 && el > 0) Math.ceil(el / 1000.0 * (total - done) / done).toLong() else null)
                }
            } finally {
                progress.remove(cid); current.remove(cid); jobs.remove(cid)
            }
        }
    }

    /** 작업실 free-prompt run (module 903 genFreeRun): n images, optionally added as character icons. */
    fun runFree(cid: String, charId: String, n: Int, addAsIcon: Boolean) {
        if (running(cid)) return
        val total = n.coerceIn(1, 2)
        val start = System.currentTimeMillis()
        error.remove(cid)
        progress[cid] = Progress(0, total, start, null)
        jobs[cid] = scope.launch {
            try {
                for (k in 0 until total) {
                    current[cid] = charId
                    val name = try { ImageGenStore.generateForCharacter(cid, charId) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Throwable) {
                        error[cid] = e.message ?: "생성 실패"; break
                    }
                    if (addAsIcon) {
                        val cname = ImageGenStore.character(ImageGenStore.load(cid), charId)?.s("name") ?: "image"
                        ImageGenStore.addAsIcon(cid, ImageGenStore.imageFile(cid, name), cname)
                    }
                    val el = System.currentTimeMillis() - start
                    progress[cid] = Progress(k + 1, total, start, if (el > 0) Math.ceil(el / 1000.0 * (total - k - 1) / (k + 1)).toLong() else null)
                }
            } finally { progress.remove(cid); current.remove(cid); jobs.remove(cid) }
        }
    }

    fun stop(cid: String) { jobs[cid]?.cancel() }

    fun compress(cid: String, webp: Boolean, quality: Int, onlyHeavy: Boolean) {
        if (compressing.containsKey(cid)) return
        val n = ImageGenStore.countCompressTargets(cid, onlyHeavy)
        if (n == 0) { compressMsg[cid] = "압축할 이미지가 없습니다."; return }
        compressMsg.remove(cid)
        compressing[cid] = 0 to n
        scope.launch {
            try {
                val r = ImageGenStore.compress(cid, webp, quality, onlyHeavy) { d, t -> compressing[cid] = d to t }
                compressMsg[cid] = "${r.converted}장 변환 · ${(maxOf(0L, r.saved) / 1024)}KB 절약"
            } catch (e: Throwable) {
                compressMsg[cid] = e.message ?: "압축 실패"
            } finally { compressing.remove(cid) }
        }
    }
}
