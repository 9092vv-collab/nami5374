package space.sgu.next.data

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import java.io.ByteArrayInputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream

/** NovelAI image models offered in the app (V4.5 Full and newer). */
object NaiModels {
    const val V5_MEDIUM = "nai-diffusion-5-full-medium"
    val list = listOf(
        "nai-diffusion-5-full" to "V5 Full",
        V5_MEDIUM to "V5 Full Medium (V5M)",
        "nai-diffusion-5-curated" to "V5 Curated",
        "nai-diffusion-4-5-full" to "V4.5 Full",
    )
    fun label(id: String) = list.firstOrNull { it.first == id }?.second ?: id

    /** Same samplers as the original (module 904). */
    val samplers = listOf(
        "k_euler" to "Euler", "k_euler_ancestral" to "Euler Ancestral", "k_dpmpp_2s_ancestral" to "DPM++ 2S Ancestral",
        "k_dpmpp_2m" to "DPM++ 2M", "k_dpmpp_sde" to "DPM++ SDE", "k_dpmpp_2m_sde" to "DPM++ 2M SDE",
    )

    /** Size presets (module 904 SIZE_PRESETS). */
    val sizes = linkedMapOf(
        "portrait" to Triple(832, 1216, "세로 832×1216"),
        "landscape" to Triple(1216, 832, "가로 1216×832"),
        "square" to Triple(1024, 1024, "정사각 1024×1024"),
    )
}

/**
 * Per-character image generation state, in the original app's `imageGen` shape (module 904):
 *   { style:{steps,guidance,rescale,sampler,prompt,negative,providerId,model},
 *     characters:[{id,name,size,freePrompt,freeNegative,blocks:[{id,name,type,text,keep?,free?,folderId?}]}],
 *     selectedCharacterId, preview, presets:[{id,name,images:[{id,file,selected?}],imageName,imageType,size,assetPrompt,assetNegative,
 *       chars:[{id,characterId,blockIds,prompt,negative,pos}]}] }
 * New in SGU Next: blocks with type "folder" group other blocks (child.folderId). A child is used only when both the
 * folder id and the child id are in the preset's blockIds, so turning a folder off keeps each child's own check.
 * Stored at files/characters/<id>/imagegen.json; generated images in files/characters/<id>/gen/.
 */
object ImageGenStore {
    const val DEFAULT_ASSET_PROMPT = "2.0::white background, clean background, empty background::"
    var version by mutableIntStateOf(0)
        private set

    private fun file(cid: String) = File(CharacterStore.dir(cid), "imagegen.json")
    fun genDir(cid: String) = File(CharacterStore.dir(cid), "gen").apply { mkdirs() }
    fun imageFile(cid: String, name: String) = File(genDir(cid), name)

    private fun newId(p: String) = p + System.currentTimeMillis().toString(36) + (1000..9999).random().toString(36)

    fun size(kind: String = "portrait", w: Int = 832, h: Int = 1216): JsonObject = buildJsonObject {
        put("kind", JsonPrimitive(kind)); put("w", JsonPrimitive(w)); put("h", JsonPrimitive(h))
    }

    fun sizeDims(sz: JsonObject?): Pair<Int, Int> {
        val kind = sz.s("kind").ifBlank { "portrait" }
        if (kind == "custom") return clamp64(sz.d("w") ?: 832.0) to clamp64(sz.d("h") ?: 1216.0)
        val p = NaiModels.sizes[kind] ?: NaiModels.sizes["portrait"]!!
        return p.first to p.second
    }

    fun clamp64(v: Double): Int = (64 * Math.round(v / 64.0)).toInt().coerceIn(64, 2048)

    fun sizeLabel(sz: JsonObject?): String {
        val (w, h) = sizeDims(sz)
        return NaiModels.sizes[sz.s("kind")]?.third ?: "${w}×${h}"
    }

    fun block(name: String, type: String, folderId: String? = null): JsonObject = buildJsonObject {
        put("id", JsonPrimitive(newId("gb"))); put("name", JsonPrimitive(name)); put("type", JsonPrimitive(type)); put("text", JsonPrimitive(""))
        if (folderId != null) put("folderId", JsonPrimitive(folderId))
    }

    fun newCharacter(name: String): JsonObject = buildJsonObject {
        put("id", JsonPrimitive(newId("gc"))); put("name", JsonPrimitive(name)); put("size", size())
        put("freePrompt", JsonPrimitive("")); put("freeNegative", JsonPrimitive(""))
        put("blocks", JsonArray(listOf("성별", "헤어스타일", "얼굴", "상의", "하의").map { block(it, "prompt") } + block("배제 태그", "negative")))
    }

    fun newPreset(name: String, character: JsonObject?): JsonObject = buildJsonObject {
        put("id", JsonPrimitive(newId("gp"))); put("name", JsonPrimitive(name)); put("images", JsonArray(emptyList()))
        put("imageName", JsonPrimitive("")); put("imageType", JsonPrimitive("asset")); put("size", size())
        put("assetPrompt", JsonPrimitive(DEFAULT_ASSET_PROMPT)); put("assetNegative", JsonPrimitive(""))
        put("chars", JsonArray(if (character == null) emptyList() else listOf(presetChar(character))))
    }

    fun presetChar(character: JsonObject): JsonObject = buildJsonObject {
        put("id", JsonPrimitive(newId("pc"))); put("characterId", JsonPrimitive(character.s("id")))
        put("blockIds", JsonArray(character.arr("blocks").map { JsonPrimitive(it.s("id")) }))
        put("prompt", JsonPrimitive("")); put("negative", JsonPrimitive(""))
        put("pos", buildJsonObject { put("mode", JsonPrimitive("auto")) })
    }

    /** An empty imageGen with one character and no presets (original defaultImageGen). */
    fun blankState(): JsonObject = defaults()

    private fun defaults(): JsonObject {
        val c = newCharacter("캐릭터 1")
        return buildJsonObject {
            put("style", buildJsonObject {
                put("steps", JsonPrimitive(28)); put("guidance", JsonPrimitive(5)); put("rescale", JsonPrimitive(0))
                put("sampler", JsonPrimitive("k_euler_ancestral")); put("prompt", JsonPrimitive("")); put("negative", JsonPrimitive(""))
                put("providerId", JsonNull); put("model", JsonPrimitive(NaiModels.list[0].first))
            })
            put("characters", JsonArray(listOf(c)))
            put("selectedCharacterId", JsonPrimitive(c.s("id")))
            put("preview", JsonNull)
            put("presets", JsonArray(emptyList()))
        }
    }

    private val cache = mutableMapOf<String, JsonObject>()

    fun load(cid: String): JsonObject = cache.getOrPut(cid) {
        runCatching { CardIO.json.parseToJsonElement(file(cid).readText()).jsonObject }.getOrNull()?.let { t ->
            val d = defaults()
            JsonObject(d + t + ("style" to JsonObject(d.o("style").orEmpty() + t.o("style").orEmpty())))
        } ?: newProject(cid)
    }

    /** First open of a character: ★ template (or the built-in 기본 템플릿) and ★ art style, like the original. */
    private fun newProject(cid: String): JsonObject {
        var g = runCatching { TemplateLibrary.newProjectState(defaults()) }.getOrElse { defaults() }
        StyleLibrary.patch(runCatching { StyleLibrary.favorite()?.o("data") }.getOrNull())?.let { pairs ->
            g = g.with("style" to JsonObject(g.o("style").orEmpty() + pairs))
        }
        if (CharacterStore.items.any { it.id == cid }) {
            val text = CardIO.json.encodeToString(JsonElement.serializer(), g)
            CharacterStore.io.launch { runCatching { file(cid).writeText(text) } }
        }
        return g
    }

    fun save(cid: String, o: JsonObject) {
        cache[cid] = o
        val text = CardIO.json.encodeToString(JsonElement.serializer(), o)
        CharacterStore.io.launch { file(cid).writeText(text) }
        version++
    }

    fun update(cid: String, change: (JsonObject) -> JsonObject) = save(cid, change(load(cid)))

    // ---------- list helpers ----------

    private fun JsonObject.mapList(key: String, f: (MutableList<JsonObject>) -> Unit): JsonObject {
        val l = arr(key).toMutableList(); f(l); return with(key to JsonArray(l))
    }

    fun patchStyle(cid: String, vararg pairs: Pair<String, JsonElement>) = update(cid) { it.with("style" to it.o("style").orEmpty().let { s -> JsonObject(s + pairs) }) }

    fun character(g: JsonObject, id: String?) = g.arr("characters").firstOrNull { it.s("id") == id }

    fun patchCharacter(cid: String, charId: String, change: (JsonObject) -> JsonObject) = update(cid) { g ->
        g.mapList("characters") { l -> val i = l.indexOfFirst { it.s("id") == charId }; if (i >= 0) l[i] = change(l[i]) }
    }

    fun addCharacter(cid: String): String {
        var newId = ""
        update(cid) { g ->
            val c = newCharacter("캐릭터 ${g.arr("characters").size + 1}"); newId = c.s("id")
            g.mapList("characters") { it.add(c) }.with("selectedCharacterId" to JsonPrimitive(newId))
        }
        return newId
    }

    fun removeCharacter(cid: String, charId: String) = update(cid) { g ->
        val rest = g.arr("characters").filter { it.s("id") != charId }
        val presets = g.arr("presets").map { p -> p.with("chars" to JsonArray(p.arr("chars").filter { it.s("characterId") != charId })) }
        g.with("characters" to JsonArray(rest), "presets" to JsonArray(presets),
            "selectedCharacterId" to str(rest.firstOrNull()?.s("id")))
    }

    fun patchBlocks(cid: String, charId: String, f: (MutableList<JsonObject>) -> Unit) = patchCharacter(cid, charId) { c -> c.mapList("blocks", f) }

    fun preset(g: JsonObject, id: String?) = g.arr("presets").firstOrNull { it.s("id") == id }

    fun patchPreset(cid: String, presetId: String, change: (JsonObject) -> JsonObject) = update(cid) { g ->
        g.mapList("presets") { l -> val i = l.indexOfFirst { it.s("id") == presetId }; if (i >= 0) l[i] = change(l[i]) }
    }

    fun addPreset(cid: String, name: String): String {
        var id = ""
        update(cid) { g ->
            val p = newPreset(name.ifBlank { "새 프리셋" }, character(g, g.sOrNull("selectedCharacterId")) ?: g.arr("characters").firstOrNull())
            id = p.s("id"); g.mapList("presets") { it.add(p) }
        }
        return id
    }

    fun removePreset(cid: String, presetId: String) = removePresetFull(cid, presetId)

    /** Toggles one id (block or folder) in a preset character's blockIds. */
    fun toggleBlock(cid: String, presetId: String, pcharId: String, blockId: String) = patchPreset(cid, presetId) { p ->
        p.mapList("chars") { l ->
            val i = l.indexOfFirst { it.s("id") == pcharId }
            if (i >= 0) {
                val ids = (l[i]["blockIds"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content }?.toMutableList() ?: mutableListOf()
                if (blockId in ids) ids.remove(blockId) else ids.add(blockId)
                l[i] = l[i].with("blockIds" to JsonArray(ids.map { JsonPrimitive(it) }))
            }
        }
    }

    fun blockIdList(pc: JsonObject?): List<String> = (pc?.get("blockIds") as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content } ?: emptyList()

    fun normalizeSize(o: JsonObject?): JsonObject {
        if (o == null) return size()
        val kind = o.s("kind").takeIf { it == "landscape" || it == "square" || it == "custom" } ?: "portrait"
        return size(kind, clamp64(o.d("w") ?: 832.0), clamp64(o.d("h") ?: 1216.0))
    }

    fun normalizePos(o: JsonObject?): JsonObject = if (o.s("mode") == "custom") buildJsonObject {
        put("mode", JsonPrimitive("custom"))
        put("col", JsonPrimitive((o.d("col") ?: 2.0).toInt().coerceIn(0, 4))); put("row", JsonPrimitive((o.d("row") ?: 2.0).toInt().coerceIn(0, 4)))
    } else buildJsonObject { put("mode", JsonPrimitive("auto")) }

    /** Cells nearest the centre first (module 904 P). */
    private val cellsByCentre: List<Pair<Int, Int>> = (0 until 5).flatMap { r -> (0 until 5).map { c -> c to r } }
        .sortedBy { (c, r) -> Math.hypot(c - 2.0, r - 2.0) }

    /** Module 904 normalizePositions: all auto, or all custom on distinct cells ([first] keeps its cell). */
    fun normalizePositions(chars: List<JsonObject>, first: List<Int> = emptyList()): List<JsonObject> {
        if (chars.none { it.o("pos").s("mode") == "custom" }) return chars.map { if (it.o("pos").s("mode") == "auto") it else it.with("pos" to normalizePos(null)) }
        val order = first.filter { it in chars.indices } + chars.indices.filter { it !in first }
        val used = mutableSetOf<String>(); val out = chars.toMutableList()
        for (i in order) {
            val pos = chars[i].o("pos")
            if (pos.s("mode") == "custom") {
                val key = "${pos.d("col")?.toInt()},${pos.d("row")?.toInt()}"
                if (key !in used) { used.add(key); continue }
            }
            val cell = cellsByCentre.firstOrNull { "${it.first},${it.second}" !in used } ?: cellsByCentre[0]
            used.add("${cell.first},${cell.second}")
            out[i] = chars[i].with("pos" to buildJsonObject { put("mode", JsonPrimitive("custom")); put("col", JsonPrimitive(cell.first)); put("row", JsonPrimitive(cell.second)) })
        }
        return out
    }

    /** Module 904 setCharPos. */
    fun setCharPos(cid: String, presetId: String, pcharId: String, pos: JsonObject) = patchPreset(cid, presetId) { p ->
        val chars = p.arr("chars"); val i = chars.indexOfFirst { it.s("id") == pcharId }
        if (i < 0) p
        else if (pos.s("mode") == "auto") p.with("chars" to JsonArray(chars.map { it.with("pos" to normalizePos(null)) }))
        else p.with("chars" to JsonArray(normalizePositions(chars.mapIndexed { j, c -> if (j == i) c.with("pos" to pos) else c }, listOf(i))))
    }

    fun blockIds(pc: JsonObject?): Set<String> = (pc?.get("blockIds") as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content }?.toSet() ?: emptySet()

    // ---------- texts edited in the full-screen editor ("gen:…" keys) ----------

    private fun parts(key: String) = key.removePrefix("gen:").split(":")

    fun label(cid: String, key: String): String {
        val g = load(cid); val p = parts(key)
        return when (p[0]) {
            "style" -> if (p.getOrNull(1) == "negative") "그림체 네거티브" else "그림체 프롬프트"
            "block" -> character(g, p[1])?.arr("blocks")?.firstOrNull { it.s("id") == p[2] }?.s("name")?.ifBlank { "블록" } ?: "블록"
            "free" -> if (p.getOrNull(2) == "freeNegative") "자유 네거티브" else "자유 프롬프트"
            "preset" -> if (p.getOrNull(2) == "assetNegative") "에셋 네거티브" else "에셋 프롬프트"
            "pchar" -> if (p.getOrNull(3) == "negative") "캐릭터 추가 네거티브" else "캐릭터 추가 프롬프트"
            else -> key
        }
    }

    fun getText(cid: String, key: String): String {
        val g = load(cid); val p = parts(key)
        return when (p[0]) {
            "style" -> g.o("style").s(p[1])
            "block" -> character(g, p[1])?.arr("blocks")?.firstOrNull { it.s("id") == p[2] }.s("text")
            "free" -> character(g, p[1]).s(p[2])
            "preset" -> preset(g, p[1]).s(p[2])
            "pchar" -> preset(g, p[1])?.arr("chars")?.firstOrNull { it.s("id") == p[2] }.s(p[3])
            else -> ""
        }
    }

    fun setText(cid: String, key: String, value: String) {
        val p = parts(key); val v = JsonPrimitive(value)
        when (p[0]) {
            "style" -> patchStyle(cid, p[1] to v)
            "block" -> patchBlocks(cid, p[1]) { l -> val i = l.indexOfFirst { it.s("id") == p[2] }; if (i >= 0) l[i] = l[i].with("text" to v) }
            "free" -> patchCharacter(cid, p[1]) { it.with(p[2] to v) }
            "preset" -> patchPreset(cid, p[1]) { it.with(p[2] to v) }
            "pchar" -> patchPreset(cid, p[1]) { pr -> pr.mapList("chars") { l -> val i = l.indexOfFirst { it.s("id") == p[2] }; if (i >= 0) l[i] = l[i].with(p[3] to v) } }
        }
    }

    // ---------- prompt building (module 904 joinTags / characterPrompts / jobForPreset) ----------

    fun joinTags(vararg parts: String?): String =
        parts.map { (it ?: "").trim().replace(Regex("[,\\s]+$"), "") }.filter { it.isNotEmpty() }.joinToString(", ")

    /** Blocks that count for a preset character: block checked and, if it is in a folder, the folder checked too. */
    fun activeBlocks(character: JsonObject, ids: Set<String>?): List<JsonObject> {
        val blocks = character.arr("blocks")
        val folderIds = blocks.filter { it.s("type") == "folder" }.map { it.s("id") }.toSet()
        return blocks.filter { b ->
            val folder = b.sOrNull("folderId")?.takeIf { it in folderIds }
            b.s("type") != "folder" && (ids == null || (b.s("id") in ids && (folder == null || folder in ids)))
        }
    }

    data class CharJob(val prompt: String, val negative: String, val center: Pair<Double, Double>?)
    data class Job(val basePrompt: String, val baseNegative: String, val w: Int, val h: Int, val chars: List<CharJob>)

    private fun charPrompts(character: JsonObject, ids: Set<String>?, extraP: String, extraN: String): Pair<String, String> {
        val active = activeBlocks(character, ids)
        return joinTags(*active.filter { it.s("type") == "prompt" }.map { it.s("text") }.toTypedArray(), extraP) to
            joinTags(*active.filter { it.s("type") == "negative" }.map { it.s("text") }.toTypedArray(), extraN)
    }

    fun jobForPreset(g: JsonObject, preset: JsonObject): Job {
        val style = g.o("style")
        val chars = preset.arr("chars").mapNotNull { pc ->
            val c = character(g, pc.s("characterId")) ?: return@mapNotNull null
            val (pp, nn) = charPrompts(c, blockIds(pc), pc.s("prompt"), pc.s("negative"))
            val pos = pc.o("pos")
            val center = if (pos.s("mode") == "custom") ((pos.d("col") ?: 2.0) + 0.5) / 5 to ((pos.d("row") ?: 2.0) + 0.5) / 5 else null
            CharJob(pp, nn, center)
        }
        val (w, h) = sizeDims(preset.o("size"))
        return Job(joinTags(style.s("prompt"), preset.s("assetPrompt")), joinTags(style.s("negative"), preset.s("assetNegative")), w, h, chars)
    }

    fun jobForCharacter(g: JsonObject, c: JsonObject): Job {
        val style = g.o("style")
        val freeIds = c.arr("blocks").filter { !(it["free"]?.toString() == "false") }.map { it.s("id") }.toSet()
        val (pp, nn) = charPrompts(c, freeIds, c.s("freePrompt"), c.s("freeNegative"))
        val (w, h) = sizeDims(c.o("size"))
        return Job(joinTags(style.s("prompt")), joinTags(style.s("negative")), w, h, listOf(CharJob(pp, nn, null)))
    }

    // ---------- NovelAI request (module 951 buildNaiBody) ----------

    fun buildBody(job: Job, style: JsonObject?, model: String, seed: Long): JsonObject {
        val medium = model == NaiModels.V5_MEDIUM
        // V5 Full Medium is a distilled model with fixed settings: 14 steps, Euler Ancestral, heavy UC preset, no negatives / CFG rescale.
        val steps = if (medium) 14 else (style.d("steps") ?: 28.0).toInt()
        val sampler = if (medium) "k_euler_ancestral" else style.s("sampler").ifBlank { "k_euler_ancestral" }
        val baseNeg = if (medium) "" else job.baseNegative
        val chars = job.chars.map { if (medium) it.copy(negative = "") else it }
        val useCoords = chars.any { it.center != null }
        fun center(c: CharJob) = buildJsonObject {
            put("x", JsonPrimitive(c.center?.first ?: 0.5)); put("y", JsonPrimitive(c.center?.second ?: 0.5))
        }
        return buildJsonObject {
            put("input", JsonPrimitive(job.basePrompt)); put("model", JsonPrimitive(model)); put("action", JsonPrimitive("generate"))
            put("parameters", buildJsonObject {
                put("params_version", JsonPrimitive(3)); put("width", JsonPrimitive(job.w)); put("height", JsonPrimitive(job.h))
                put("scale", JsonPrimitive(style.d("guidance") ?: 5.0))
                if (!medium) put("cfg_rescale", JsonPrimitive(style.d("rescale") ?: 0.0))
                put("sampler", JsonPrimitive(sampler)); put("steps", JsonPrimitive(steps))
                put("seed", JsonPrimitive(seed)); put("extra_noise_seed", JsonPrimitive(seed)); put("n_samples", JsonPrimitive(1))
                put("ucPreset", JsonPrimitive(if (medium) 0 else 3)); put("qualityToggle", JsonPrimitive(false)); put("autoSmea", JsonPrimitive(false))
                put("deliberate_euler_ancestral_bug", JsonPrimitive(false)); put("prefer_brownian", JsonPrimitive(true))
                put("normalize_reference_strength_multiple", JsonPrimitive(true)); put("inpaintImg2ImgStrength", JsonPrimitive(1))
                put("skip_cfg_above_sigma", JsonNull); put("image_format", JsonPrimitive("png")); put("dynamic_thresholding", JsonPrimitive(false))
                put("controlnet_strength", JsonPrimitive(1)); put("legacy", JsonPrimitive(false)); put("legacy_uc", JsonPrimitive(false))
                put("add_original_image", JsonPrimitive(false)); put("noise_schedule", JsonPrimitive("karras")); put("legacy_v3_extend", JsonPrimitive(false))
                put("uncond_scale", JsonPrimitive(1)); put("prompt", JsonPrimitive(job.basePrompt)); put("negative_prompt", JsonPrimitive(baseNeg))
                put("reference_image_multiple", JsonArray(emptyList())); put("reference_information_extracted_multiple", JsonArray(emptyList()))
                put("reference_strength_multiple", JsonArray(emptyList()))
                put("characterPrompts", buildJsonArray {
                    chars.forEach { c -> add(buildJsonObject { put("prompt", JsonPrimitive(c.prompt)); put("uc", JsonPrimitive(c.negative)); put("center", center(c)); put("enabled", JsonPrimitive(true)) }) }
                })
                put("v4_prompt", buildJsonObject {
                    put("use_coords", JsonPrimitive(useCoords)); put("use_order", JsonPrimitive(true))
                    put("caption", buildJsonObject {
                        put("base_caption", JsonPrimitive(job.basePrompt))
                        put("char_captions", buildJsonArray { chars.forEach { c -> add(buildJsonObject { put("char_caption", JsonPrimitive(c.prompt)); put("centers", buildJsonArray { add(center(c)) }) }) } })
                    })
                })
                put("v4_negative_prompt", buildJsonObject {
                    put("legacy_uc", JsonPrimitive(false))
                    put("caption", buildJsonObject {
                        put("base_caption", JsonPrimitive(baseNeg))
                        put("char_captions", buildJsonArray { chars.forEach { c -> add(buildJsonObject { put("char_caption", JsonPrimitive(c.negative)); put("centers", buildJsonArray { add(center(c)) }) }) } })
                    })
                })
            })
        }
    }

    /** Finds the NovelAI image provider for this character (style.providerId, else the first one). */
    fun provider(g: JsonObject): JsonObject? =
        SettingsStore.provider(g.o("style").sOrNull("providerId"))?.takeIf { it.s("format") == "novelai-image" }
            ?: SettingsStore.providers.firstOrNull { it.s("format") == "novelai-image" }

    /** Calls NovelAI and returns PNG bytes. */
    suspend fun generate(g: JsonObject, job: Job): ByteArray = withContext(Dispatchers.IO) {
        val p = provider(g) ?: throw ProviderError("NovelAI(image) 프로바이더가 없습니다. 설정 › 프로바이더에서 추가하세요.")
        val r = Llm.resolve(p)
        if (r.apiKey.isNullOrBlank()) throw ProviderError("NovelAI Persistent API Token이 설정되지 않았습니다. 설정 › 프로바이더에서 키를 연결하세요.")
        if (job.basePrompt.isBlank() && job.chars.all { it.prompt.isBlank() }) throw ProviderError("프롬프트가 비어 있습니다. 그림체 프롬프트나 캐릭터 블록을 작성하세요.")
        val model = g.o("style").s("model").ifBlank { NaiModels.list[0].first }
        val body = CardIO.json.encodeToString(JsonElement.serializer(), buildBody(job, g.o("style"), model, (Math.random() * 4294967295.0).toLong()))
        var attempt = 0
        while (true) {
            val c = URL(Llm.joinUrl(r.baseUrl, "ai/generate-image")).openConnection() as HttpURLConnection
            try {
                c.requestMethod = "POST"; c.doOutput = true; c.connectTimeout = 20_000; c.readTimeout = 120_000
                c.setRequestProperty("Content-Type", "application/json")
                c.setRequestProperty("Accept", "application/zip, application/x-zip-compressed, image/png, */*")
                Llm.authHeaders(r).forEach { (k, v) -> c.setRequestProperty(k, v) }
                c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                val code = c.responseCode
                if (code !in 200..299) {
                    val text = c.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
                    throw ProviderError(when (code) {
                        401 -> "NovelAI 인증 실패: Persistent API Token(pst-…)이 올바른지 확인하세요."
                        402 -> "Anlas가 부족하거나 구독 등급에서 허용되지 않는 설정입니다. 크기·스텝 수를 줄이거나 Anlas를 충전하세요."
                        429 -> "NovelAI가 동시 요청을 거부했습니다(429). 한 번에 한 장씩만 생성할 수 있습니다. 잠시 후 다시 시도하세요."
                        500 -> "NovelAI 서버 오류(500). 프롬프트가 비어 있거나 모델 ID가 올바르지 않은지 확인하세요."
                        else -> "NovelAI 오류 ($code): ${text.take(200)}"
                    }, code)
                }
                val bytes = c.inputStream.use { it.readBytes() }
                return@withContext extractImage(bytes)
            } catch (e: java.io.IOException) {
                if (attempt >= 3) throw ProviderError("네트워크 오류: ${e.message}")
                attempt++
                kotlinx.coroutines.delay(1500L * attempt)
            } finally {
                c.disconnect()
            }
        }
        @Suppress("UNREACHABLE_CODE") ByteArray(0)
    }

    private fun extractImage(bytes: ByteArray): ByteArray {
        if (bytes.size > 1 && bytes[0] == 0x89.toByte() && bytes[1] == 'P'.code.toByte()) return bytes
        if (bytes.size < 2 || bytes[0] != 'P'.code.toByte() || bytes[1] != 'K'.code.toByte()) throw ProviderError("NovelAI 응답을 해석할 수 없습니다(ZIP/PNG가 아님).")
        ZipInputStream(ByteArrayInputStream(bytes)).use { z ->
            while (true) {
                val e = z.nextEntry ?: break
                if (Regex("\\.(png|webp|jpe?g)$", RegexOption.IGNORE_CASE).containsMatchIn(e.name)) return z.readBytes()
            }
        }
        throw ProviderError("NovelAI 응답 ZIP에 이미지가 없습니다.")
    }

    /** Generates one image for a preset and stores it (becomes the selected image). */
    suspend fun generateForPreset(cid: String, presetId: String) {
        val g = load(cid)
        val preset = preset(g, presetId) ?: return
        val png = generate(g, jobForPreset(g, preset))
        val name = "${presetId}_${System.currentTimeMillis()}.png"
        withContext(Dispatchers.IO) { imageFile(cid, name).writeBytes(png) }
        withContext(Dispatchers.Main) {
            patchPreset(cid, presetId) { p ->
                val imgs = p.arr("images") +
                    buildJsonObject { put("id", JsonPrimitive(newId("gi"))); put("file", JsonPrimitive(name)); put("createdAt", JsonPrimitive(System.currentTimeMillis())); put("selected", JsonPrimitive(false)) }
                p.with("images" to JsonArray(imgs))
            }
        }
    }

    suspend fun generateForCharacter(cid: String, charId: String): String {
        val g = load(cid)
        val c = character(g, charId) ?: throw ProviderError("캐릭터가 없습니다.")
        val png = generate(g, jobForCharacter(g, c))
        val name = "free_${charId}_${System.currentTimeMillis()}.png"
        withContext(Dispatchers.IO) { imageFile(cid, name).writeBytes(png) }
        withContext(Dispatchers.Main) { update(cid) { it.with("preview" to JsonPrimitive(name)) } }
        return name
    }

    // ---------- 봇에 반영 (modules 967 reflectPreset / 968 syncPresetToBot) ----------

    private fun xDir(cid: String) = File(CharacterStore.dir(cid), "x")
    /** 1장째는 이름 그대로(neutral), 2장째부터 neutral.2, neutral.3 … (RisuAI 감정 이름이 neutral.1 로 깨지지 않게). */
    fun reflectedName(imageName: String, name: String, n: Int): String {
        val base = imageName.trim().ifBlank { name.trim() }.ifBlank { "image" }
        return if (n <= 1) base else "$base.$n"
    }
    private fun ext(file: String) = file.substringAfterLast('.', "png").lowercase().let { if (it == "jpeg") "jpg" else it }

    /** Checked images (selected) become card assets named imageName, imageName.2 … (emotion or x-risu-asset); unchecked ones are removed. */
    fun syncPresetToBot(cid: String, presetId: String) {
        val p = preset(load(cid), presetId) ?: return
        val emotion = p.s("imageType") == "emotion"
        val list = if (emotion) "emotion" else "asset"
        val folder = if (emotion) "emotion" else "other"
        val x = xDir(cid).apply { mkdirs() }
        val remove = mutableSetOf<String>(); val add = mutableListOf<JsonObject>()
        val images = p.arr("images").toMutableList()
        images.forEachIndexed { i, im ->
            val r = im.o("reflected")
            if (!im.b("selected") && r != null) {
                remove.add("embeded://" + r.s("path")); File(x, r.s("path")).delete()
                images[i] = JsonObject(im - "reflected")
            }
        }
        var n = 0
        images.forEachIndexed { i, im ->
            if (!im.b("selected")) return@forEachIndexed
            val src = imageFile(cid, im.s("file"))
            val r = im.o("reflected")
            var path: String
            val rid: String
            if (r != null && File(x, r.s("path")).exists()) {
                rid = r.s("id"); path = r.s("path"); remove.add("embeded://$path")
                if (r.s("list") != list) {
                    val np = "assets/$folder/image/$rid.${ext(path)}"
                    File(x, np).parentFile?.mkdirs(); File(x, path).renameTo(File(x, np)); path = np
                }
            } else {
                if (!src.exists()) return@forEachIndexed
                rid = r?.s("id")?.ifBlank { null } ?: newId("ga")
                path = "assets/$folder/image/$rid.${ext(im.s("file"))}"
                File(x, path).parentFile?.mkdirs(); src.copyTo(File(x, path), overwrite = true)
            }
            n++
            images[i] = im.with("reflected" to buildJsonObject { put("id", JsonPrimitive(rid)); put("path", JsonPrimitive(path)); put("list", JsonPrimitive(list)) })
            add.add(buildJsonObject {
                put("type", JsonPrimitive(if (emotion) "emotion" else "x-risu-asset")); put("uri", JsonPrimitive("embeded://$path"))
                put("name", JsonPrimitive(reflectedName(p.s("imageName"), p.s("name"), n))); put("ext", JsonPrimitive(ext(path)))
            })
        }
        if (remove.isNotEmpty() || add.isNotEmpty()) CharacterStore.updateData(cid) { d -> withAssets(d, remove, add) }
        patchPreset(cid, presetId) { it.with("images" to JsonArray(images)) }
    }

    /** Replaces asset entries; viewScreen follows the original export (emotion images → "emotion"). */
    private fun withAssets(d: JsonObject, remove: Set<String>, add: List<JsonObject>): JsonObject {
        val assets = ((d["assets"] as? JsonArray) ?: JsonArray(emptyList())).filter { a -> ((a as? JsonObject)?.s("uri") ?: "") !in remove } + add
        var out = d.with("assets" to JsonArray(assets))
        val emotions = assets.count { (it as? JsonObject)?.s("type") == "emotion" }
        val risu = out.o("extensions")?.o("risuai")
        val view = risu.sOrNull("viewScreen")
        val nextView = if (emotions > 0) "emotion" else if (view == "emotion") "none" else view
        if (nextView != view && nextView != null) out = Fields.set(out, listOf("extensions", "risuai", "viewScreen"), JsonPrimitive(nextView))
        return out
    }

    /** Module 967 unreflectPreset: removes every asset this preset put in the bot. */
    private fun unreflect(cid: String, p: JsonObject): List<JsonObject> {
        val x = xDir(cid); val remove = mutableSetOf<String>()
        val images = p.arr("images").map { im ->
            val r = im.o("reflected") ?: return@map im
            remove.add("embeded://" + r.s("path")); File(x, r.s("path")).delete(); JsonObject(im - "reflected")
        }
        if (remove.isNotEmpty()) CharacterStore.updateData(cid) { d -> withAssets(d, remove, emptyList()) }
        return images
    }

    fun toggleUse(cid: String, presetId: String, imageId: String) {
        patchPreset(cid, presetId) { p -> p.with("images" to JsonArray(p.arr("images").map { if (it.s("id") == imageId) it.with("selected" to JsonPrimitive(!it.b("selected"))) else it })) }
        syncPresetToBot(cid, presetId)
    }

    /** Several images at once (이미지 선별): sets selected and syncs every changed preset. */
    fun setUses(cid: String, changes: Map<String, Boolean>) {
        val touched = load(cid).arr("presets").filter { p -> p.arr("images").any { im -> changes[im.s("id")]?.let { it != im.b("selected") } == true } }.map { it.s("id") }
        touched.forEach { pid ->
            patchPreset(cid, pid) { p -> p.with("images" to JsonArray(p.arr("images").map { im -> changes[im.s("id")]?.let { im.with("selected" to JsonPrimitive(it)) } ?: im })) }
            syncPresetToBot(cid, pid)
        }
    }

    /** Re-reflects every preset that has images in the bot (renames old imageName.1 entries). Returns the number of images in the bot. */
    fun resyncAll(cid: String): Int {
        load(cid).arr("presets").filter { p -> p.arr("images").any { it.b("selected") || it.o("reflected") != null } }.forEach { syncPresetToBot(cid, it.s("id")) }
        return selectedCount(load(cid))
    }

    fun deleteImage(cid: String, presetId: String, imageId: String) {
        val im = preset(load(cid), presetId)?.arr("images")?.firstOrNull { it.s("id") == imageId } ?: return
        if (im.o("reflected") != null) {
            patchPreset(cid, presetId) { p -> p.with("images" to JsonArray(p.arr("images").map { if (it.s("id") == imageId) it.with("selected" to JsonPrimitive(false)) else it })) }
            syncPresetToBot(cid, presetId)
        }
        imageFile(cid, im.s("file")).delete()
        patchPreset(cid, presetId) { p -> p.with("images" to JsonArray(p.arr("images").filter { it.s("id") != imageId })) }
    }

    /** Module 968 removePresetFull / clearPresetImages. */
    fun removePresetFull(cid: String, presetId: String) = clear(cid, presetId, true)
    fun clearPresetImages(cid: String, presetId: String) = clear(cid, presetId, false)
    private fun clear(cid: String, presetId: String, removePreset: Boolean) {
        val p = preset(load(cid), presetId) ?: return
        unreflect(cid, p)
        p.arr("images").forEach { imageFile(cid, it.s("file")).delete() }
        update(cid) { g -> g.mapList("presets") { l ->
            if (removePreset) l.removeAll { it.s("id") == presetId }
            else { val i = l.indexOfFirst { it.s("id") == presetId }; if (i >= 0) l[i] = l[i].with("images" to JsonArray(emptyList())) }
        } }
    }

    // ---------- 이미지 압축 (module 966) ----------

    private val light = setOf("webp", "jpg", "jpeg")

    fun countCompressTargets(cid: String, onlyHeavy: Boolean) =
        load(cid).arr("presets").sumOf { p -> p.arr("images").count { !onlyHeavy || ext(it.s("file")) !in light } }

    data class CompressResult(val converted: Int, val saved: Long)

    /** Re-saves generated images as WebP/JPEG; reflected images are re-reflected with the new files. */
    suspend fun compress(cid: String, webp: Boolean, quality: Int, onlyHeavy: Boolean, progress: (Int, Int) -> Unit): CompressResult {
        val total = countCompressTargets(cid, onlyHeavy)
        var done = 0; var converted = 0; var saved = 0L
        withContext(Dispatchers.Main) { progress(0, total) }
        for (p0 in load(cid).arr("presets")) {
            val p = preset(load(cid), p0.s("id")) ?: continue
            val targets = p.arr("images").filter { !onlyHeavy || ext(it.s("file")) !in light }
            if (targets.isEmpty()) continue
            val hadReflected = p.arr("images").any { it.o("reflected") != null }
            val images = withContext(Dispatchers.Main) { if (hadReflected) unreflect(cid, p) else p.arr("images") }
            val renamed = mutableMapOf<String, String>()
            for (im in targets) {
                runCatching {
                    withContext(Dispatchers.IO) {
                        val src = imageFile(cid, im.s("file"))
                        val bmp = android.graphics.BitmapFactory.decodeFile(src.path) ?: return@withContext
                        val bos = java.io.ByteArrayOutputStream()
                        @Suppress("DEPRECATION")
                        bmp.compress(if (webp) android.graphics.Bitmap.CompressFormat.WEBP_LOSSY else android.graphics.Bitmap.CompressFormat.JPEG, quality.coerceIn(5, 100), bos)
                        bmp.recycle()
                        val bytes = bos.toByteArray()
                        val newExt = if (webp) "webp" else "jpg"
                        if (bytes.size < src.length() || ext(src.name) != newExt) {
                            val name = src.nameWithoutExtension.substringBefore("_c") + "_c" + System.currentTimeMillis().toString(36) + "." + newExt
                            imageFile(cid, name).writeBytes(bytes)
                            saved += src.length() - bytes.size
                            src.delete(); renamed[im.s("id")] = name; converted++
                        }
                    }
                }
                done++
                withContext(Dispatchers.Main) { progress(done, total) }
            }
            withContext(Dispatchers.Main) {
                patchPreset(cid, p.s("id")) { it.with("images" to JsonArray(images.map { im -> renamed[im.s("id")]?.let { f -> im.with("file" to JsonPrimitive(f)) } ?: im })) }
                if (hadReflected) syncPresetToBot(cid, p.s("id"))
            }
        }
        return CompressResult(converted, saved)
    }

    /** Module 1687 exportSelectedZip: every image checked for the bot, named imageName.N.ext. */
    fun writeSelectedZip(cid: String, out: java.io.OutputStream): Int {
        var count = 0; val used = mutableSetOf<String>()
        java.util.zip.ZipOutputStream(out).use { zip ->
            load(cid).arr("presets").forEach { p ->
                p.arr("images").filter { it.b("selected") }.forEachIndexed { i, im ->
                    val f = imageFile(cid, im.s("file")); if (!f.exists()) return@forEachIndexed
                    val base = safeName(p.s("imageName").ifBlank { p.s("name") }.ifBlank { "image" })
                    var name = "$base.${i + 1}.${ext(f.name)}"; var k = 2
                    while (name in used) { name = "$base.${i + 1}_$k.${ext(f.name)}"; k++ }
                    used.add(name)
                    zip.putNextEntry(java.util.zip.ZipEntry(name)); f.inputStream().use { it.copyTo(zip) }; zip.closeEntry(); count++
                }
            }
        }
        return count
    }

    fun selectedCount(g: JsonObject) = g.arr("presets").sumOf { p -> p.arr("images").count { it.b("selected") } }

    fun safeName(s: String) = s.replace(Regex("[\\/:*?\"<>|\u0000-\u001f]+"), "_").trim().ifBlank { "image" }.take(100)

    // ---------- 아이콘으로 추가 (module 1681) ----------

    /** No icon yet → becomes the main icon; otherwise added as an extra icon asset named after the image character. */
    fun addAsIcon(cid: String, file: File, name: String): Boolean = CharIcons.add(cid, file, name)

    private fun writePng(src: File, dst: File) {
        if (ext(src.name) == "png") { src.copyTo(dst, overwrite = true); return }
        val bmp = android.graphics.BitmapFactory.decodeFile(src.path) ?: run { src.copyTo(dst, overwrite = true); return }
        dst.outputStream().use { bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        bmp.recycle()
    }

    /** Copy of an image character (original 1681 "복사"). */
    fun duplicateCharacter(cid: String, charId: String) = update(cid) { g ->
        val c = character(g, charId) ?: return@update g
        val map = c.arr("blocks").associate { it.s("id") to newId("gb") }
        val copy = c.with("id" to JsonPrimitive(newId("gc")), "name" to JsonPrimitive(c.s("name") + " 복사"), "blocks" to JsonArray(c.arr("blocks").map { b ->
            val nb = b.with("id" to JsonPrimitive(map[b.s("id")]!!))
            b.sOrNull("folderId")?.let { f -> map[f]?.let { nb.with("folderId" to JsonPrimitive(it)) } } ?: nb
        }))
        g.mapList("characters") { it.add(copy) }.with("selectedCharacterId" to JsonPrimitive(copy.s("id")))
    }

    fun thumb(preset: JsonObject?): JsonObject? = preset?.arr("images")?.let { l -> l.firstOrNull { it.b("selected") } ?: l.lastOrNull() }
}
