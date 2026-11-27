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
import kotlinx.serialization.json.contentOrNull
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
        put("blockIds", JsonArray(character.arr("blocks").map { JsonPrimitive(it.s("id")) }))  // 예전 방식: 모든 블록 켜짐 (사용자 요청으로 되돌림)
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
    /** 백업 복원 뒤 다시 읽게 함. */
    fun forgetAll() { cache.clear(); version++ }

    /** 그림체 설정은 모든 캐릭터 공용 (설정의 "imageStyle"). 없으면 처음 연 캐릭터의 그림체로 시작. */
    private fun globalStyle(): JsonObject? = SettingsStore.root.o("imageStyle")?.takeIf { it.isNotEmpty() }

    fun load(cid: String): JsonObject {
        val g = loadOwn(cid)
        val gs = globalStyle()
        if (gs == null) { g.o("style")?.let { st -> SettingsStore.update { it["imageStyle"] = st } }; return g }
        return if (g.o("style") == gs) g else g.with("style" to gs)
    }

    private fun loadOwn(cid: String): JsonObject = cache.getOrPut(cid) {
        runCatching { CardIO.json.parseToJsonElement(file(cid).readText()).jsonObject }.getOrNull()?.let { t ->
            val d = defaults()
            // 켜진 블록이 없는데 켜져 있는 폴더는 끔 (생성 결과는 같음, 화면에서만 헷갈렸음)
            tidyAll(JsonObject(d + t + ("style" to JsonObject(d.o("style").orEmpty() + t.o("style").orEmpty()))))
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
        o.o("style")?.let { st -> if (st != globalStyle()) SettingsStore.update { it["imageStyle"] = st } }
        cache[cid] = o
        val text = CardIO.json.encodeToString(JsonElement.serializer(), o)
        CharacterStore.io.launch { file(cid).writeText(text) }
        version++
    }

    fun update(cid: String, change: (JsonObject) -> JsonObject) = save(cid, change(load(cid)))

    /** Import (IO thread, before the character is listed): writes the restored template directly. */
    fun writeImported(cid: String, o: JsonObject) {
        file(cid).writeText(CardIO.json.encodeToString(JsonElement.serializer(), o)); cache.remove(cid)
    }

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
    /** 폴더를 누르면 폴더와 안의 블록을 한꺼번에 켜고 끔. 폴더 안 블록을 켜면 폴더도 켜짐. */
    fun toggleBlock(cid: String, presetId: String, pcharId: String, blockId: String) = patchPreset(cid, presetId) { p ->
        val g = load(cid)
        p.mapList("chars") { l ->
            val i = l.indexOfFirst { it.s("id") == pcharId }
            if (i >= 0) {
                val c = character(g, l[i].s("characterId")) ?: return@mapList
                val blocks = c.arr("blocks")
                val ids = blockIdList(l[i]).toMutableList()
                val b = blocks.firstOrNull { it.s("id") == blockId } ?: return@mapList
                if (b.s("type") == "folder") {
                    val kids = blocks.filter { it.sOrNull("folderId") == blockId }.map { it.s("id") }
                    val on = blockId in ids && kids.any { it in ids }
                    if (on) ids.removeAll(kids + blockId) else ids.addAll(kids + blockId)
                } else {
                    if (blockId in ids) ids.remove(blockId) else { ids.add(blockId); b.sOrNull("folderId")?.let { ids.add(it) } }
                }
                l[i] = l[i].with("blockIds" to JsonArray(tidyIds(c, ids).map { JsonPrimitive(it) }))
            }
        }
    }

    /**
     * 프리셋에 캐릭터를 새로 붙일 때 켜 둘 블록 = 캐릭터 설정 화면에서 체크된 블록(free != false).
     * 전에는 모든 블록·폴더를 켜서, 의상 폴더가 여러 개면 전부 켜진 채로 시작했음.
     */
    fun defaultBlockIds(character: JsonObject): List<String> {
        val blocks = character.arr("blocks")
        fun on(b: JsonObject) = b["free"]?.toString() != "false"
        val folders = blocks.filter { it.s("type") == "folder" }
        // 의상 폴더 안 블록은 슬롯(상반신·하반신)으로만 들어가니 직접 켜지 않음
        val picked = blocks.filter { b -> b.s("type") != "folder" && on(b) && (b.sOrNull("folderId")?.let { f -> folders.none { it.s("id") == f } } ?: true) }.map { it.s("id") }
        return tidyIds(character, picked)
    }

    /** 폴더 id는 그 안의 블록이 하나라도 켜져 있을 때만 남기고, 켜진 블록이 든 폴더는 켬 (폴더만 켜지고 안은 비어 보이던 문제). */
    fun tidyIds(character: JsonObject, ids: List<String>): List<String> {
        val blocks = character.arr("blocks")
        val folderIds = blocks.filter { it.s("type") == "folder" }.map { it.s("id") }.toSet()
        val kids = ids.filter { it !in folderIds }
        val keepFolders = folderIds.filter { f -> f in ids && blocks.any { it.sOrNull("folderId") == f && it.s("id") in kids } }
        return (kids + keepFolders).distinct()
    }

    private fun tidyAll(g0: JsonObject): JsonObject {
        val g = outfitize(g0)
        val chars = g.arr("characters").associateBy { it.s("id") }
        var changed = false
        val presets = g.arr("presets").map { p ->
            p.with("chars" to JsonArray(p.arr("chars").map { pc ->
                val c = chars[pc.s("characterId")]
                val ids = (pc["blockIds"] as? JsonArray)?.let { blockIdList(pc) }
                if (c == null || ids == null) pc else tidyIds(c, ids).let { t -> if (t.toSet() != ids.toSet()) { changed = true; pc.with("blockIds" to JsonArray(t.map { JsonPrimitive(it) })) } else pc }
            }))
        }
        return if (changed) g.with("presets" to JsonArray(presets)) else g
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
            "style" -> when (p.getOrNull(1)) { "negative" -> "그림체 네거티브"; "quality" -> "퀄리티 태그"; else -> "그림체 프롬프트" }
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

    /** 외형(블록·그림체)과 행동(에셋·자유·캐릭터 프롬프트) 사이를 한 줄 띄워 보냄. 쉼표는 남겨서 태그가 붙지 않게. */
    fun joinSections(vararg parts: String?): String =
        parts.map { (it ?: "").trim().replace(Regex("[,\\s]+$"), "") }.filter { it.isNotEmpty() }.joinToString(",\n\n")

    fun joinTags(vararg parts: String?): String =
        parts.map { (it ?: "").trim().replace(Regex("[,\\s]+$"), "") }.filter { it.isNotEmpty() }.joinToString(", ")

    /** Blocks that count for a preset character: block checked and, if it is in a folder, the folder checked too. */
    /**
     * 켜진 블록 (0.9.62 의상 폴더): 폴더 = 의상, 캐릭터마다 하나(outfit)만 입음. 폴더 안 블록은 직접 켜지 않고,
     * 슬롯 블록(상반신·하반신)이 켜져 있으면 입은 의상의 상의·하의가 그 자리에 들어감.
     */
    fun activeBlocks(character: JsonObject, ids: Set<String>?): List<JsonObject> {
        val blocks = character.arr("blocks")
        val folderIds = blocks.filter { it.s("type") == "folder" }.map { it.s("id") }.toSet()
        val fit = outfit(character)
        return blocks.mapNotNull { b ->
            when {
                b.s("type") == "folder" -> null
                b.sOrNull("folderId")?.takeIf { it in folderIds } != null -> null
                ids != null && b.s("id") !in ids -> null
                isSlot(b) -> fit?.let { f -> outfitPart(character, f, b.s("slot") != "bottom") }?.takeIf { it.s("type") != "negative" }
                else -> b
            }
        }
    }

    // ---------- 의상 폴더 (0.9.62) ----------
    fun isSlot(b: JsonObject) = b.s("type") == "slot"
    fun outfitFolders(c: JsonObject): List<JsonObject> = c.arr("blocks").filter { it.s("type") == "folder" }
    /** 입은 의상 = outfit에 적힌 폴더, 없으면 첫 폴더. */
    fun outfit(c: JsonObject): JsonObject? = outfitFolders(c).let { fs -> fs.firstOrNull { it.s("id") == c.s("outfit") } ?: fs.firstOrNull() }
    /** 의상 폴더의 상의(이름에 "상")·하의(이름에 "하"), 없으면 첫째·둘째 블록. */
    fun outfitPart(c: JsonObject, folder: JsonObject, top: Boolean): JsonObject? {
        val kids = c.arr("blocks").filter { it.sOrNull("folderId") == folder.s("id") && it.s("type") != "folder" }
        val k = if (top) "상" else "하"
        return kids.firstOrNull { it.s("name").contains(k) } ?: kids.getOrNull(if (top) 0 else 1)
    }
    fun setOutfit(cid: String, charId: String, folderId: String) = patchCharacter(cid, charId) { it.with("outfit" to JsonPrimitive(folderId)) }

    /**
     * 예전 구조를 의상 폴더 구조로 (불러올 때마다 확인, 이미 바뀌었으면 그대로):
     * - SGU식 "상반신"·"하반신" 블록 → 슬롯(id 그대로라 프리셋 연결 유지), 적혀 있던 글은 "기본의상" 폴더의 상의·하의로
     * - "탈의" 폴더 → 폴더 밖 블록 "상반신(탈의)"·"하반신(탈의)" (고정 태그, 프리셋이 직접 켬)
     * - 의상 폴더 안 블록을 켜던 프리셋 → 같은 자리의 슬롯을 켬, 폴더 id는 뺌
     */
    fun outfitize(g: JsonObject): JsonObject {
        var changed = false
        val remaps = mutableMapOf<String, Map<String, String?>>()
        val chars = g.arr("characters").map { c ->
            val (nc, remap) = outfitChar(c)
            if (nc !== c) { changed = true; if (remap.isNotEmpty()) remaps[c.s("id")] = remap }
            nc
        }
        if (!changed) return g
        val presets = g.arr("presets").map { p ->
            p.with("chars" to JsonArray(p.arr("chars").map { pc ->
                val r = remaps[pc.s("characterId")] ?: return@map pc
                val ids = blockIdList(pc).mapNotNull { id -> if (r.containsKey(id)) r[id] else id }.distinct()
                pc.with("blockIds" to JsonArray(ids.map { JsonPrimitive(it) }))
            }))
        }
        return g.with("characters" to JsonArray(chars), "presets" to JsonArray(presets))
    }

    private fun outfitChar(c: JsonObject): Pair<JsonObject, Map<String, String?>> {
        val orig = c.arr("blocks")
        if (orig.any { isSlot(it) }) return c to emptyMap()
        val blocks = orig.toMutableList()
        fun loose(b: JsonObject) = b.s("type") != "folder" && b.sOrNull("folderId") == null
        val oldTop = blocks.firstOrNull { loose(it) && it.s("name").trim() == "상반신" }
        val oldBottom = blocks.firstOrNull { loose(it) && it.s("name").trim() == "하반신" }
        if (oldTop == null && oldBottom == null && blocks.none { it.s("type") == "folder" }) return c to emptyMap()
        val remap = mutableMapOf<String, String?>()
        // 탈의 폴더 → 고정 블록
        blocks.firstOrNull { it.s("type") == "folder" && it.s("name").trim() == "탈의" }?.let { nude ->
            for (i in blocks.indices) {
                val k = blocks[i]
                if (k.sOrNull("folderId") != nude.s("id")) continue
                val nm = if (k.s("name").contains("하")) "하반신(탈의)" else "상반신(탈의)"
                blocks[i] = JsonObject(k - "folderId" + ("name" to JsonPrimitive(nm)))
            }
            blocks.removeAll { it.s("id") == nude.s("id") }; remap[nude.s("id")] = null
        }
        val firstFolderAt = blocks.indexOfFirst { it.s("type") == "folder" }.let { if (it < 0) blocks.size else it }
        fun slot(old: JsonObject?, name: String, kind: String): String {
            if (old != null) {
                val i = blocks.indexOfFirst { it.s("id") == old.s("id") }
                blocks[i] = old.with("type" to JsonPrimitive("slot"), "slot" to JsonPrimitive(kind), "text" to JsonPrimitive(""))
                return old.s("id")
            }
            val nb = block(name, "prompt").with("type" to JsonPrimitive("slot"), "slot" to JsonPrimitive(kind))
            blocks.add(minOf(firstFolderAt + (if (kind == "bottom") 1 else 0), blocks.size), nb)
            return nb.s("id")
        }
        val topId = slot(oldTop, "상반신", "top")
        val bottomId = slot(oldBottom, "하반신", "bottom")
        val outfits = blocks.filter { it.s("type") == "folder" }
        // 예전 상반신·하반신에 적혀 있던 글 → 기본의상 폴더
        val tText = oldTop?.s("text").orEmpty(); val bText = oldBottom?.s("text").orEmpty()
        var created: String? = null
        if (outfits.isEmpty() || tText.isNotBlank() || bText.isNotBlank()) {
            val f = block(if (outfits.any { it.s("name").trim() == "기본의상" }) "기존 의상" else "기본의상", "folder")
            blocks.add(f)
            blocks.add(block("상의", "prompt", f.s("id")).with("text" to JsonPrimitive(tText)))
            blocks.add(block("하의", "prompt", f.s("id")).with("text" to JsonPrimitive(bText)))
            created = f.s("id")
        }
        for (f in outfits) {
            remap[f.s("id")] = null
            val kids = blocks.filter { it.sOrNull("folderId") == f.s("id") && it.s("type") != "folder" }
            val top = kids.firstOrNull { it.s("name").contains("상") } ?: kids.getOrNull(0)
            kids.forEach { k -> remap[k.s("id")] = if (k === top || (!k.s("name").contains("하") && k.s("name").contains("상"))) topId else bottomId }
        }
        val folderIds = blocks.filter { it.s("type") == "folder" }.map { it.s("id") }
        val fit = c.s("outfit").takeIf { it in folderIds } ?: outfits.firstOrNull { it.s("name").trim() == "기본의상" }?.s("id") ?: outfits.firstOrNull()?.s("id") ?: created
        return c.with("blocks" to JsonArray(blocks), "outfit" to (fit?.let { JsonPrimitive(it) } ?: JsonNull)) to remap
    }

    data class CharJob(val prompt: String, val negative: String, val center: Pair<Double, Double>?)
    data class Job(val basePrompt: String, val baseNegative: String, val w: Int, val h: Int, val chars: List<CharJob>)

    private fun charPrompts(character: JsonObject, ids: Set<String>?, extraP: String, extraN: String): Pair<String, String> {
        val active = activeBlocks(character, ids)
        return joinSections(joinTags(*active.filter { it.s("type") == "prompt" }.map { it.s("text") }.toTypedArray()), extraP) to
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
        return Job(joinSections(style.s("prompt"), preset.s("assetPrompt"), style.s("quality")),  // 그림체 → 동작(에셋) → 퀄리티 태그
            joinTags(style.s("negative"), preset.s("assetNegative")), w, h, chars)
    }

    fun jobForCharacter(g: JsonObject, c: JsonObject): Job {
        val style = g.o("style")
        val freeIds = c.arr("blocks").filter { !(it["free"]?.toString() == "false") }.map { it.s("id") }.toSet()
        val (pp, nn) = charPrompts(c, freeIds, c.s("freePrompt"), c.s("freeNegative"))
        val (w, h) = sizeDims(c.o("size"))
        return Job(joinSections(style.s("prompt"), style.s("quality")), joinTags(style.s("negative")), w, h, listOf(CharJob(pp, nn, null)))
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
    /**
     * 시드: 그림체 설정(style)의 seedFixed가 켜져 있으면 마지막에 쓴 시드(style.seed)를 계속 씀.
     * 랜덤일 때도 쓴 시드를 style.seed에 남겨서, 마음에 드는 그림이 나온 뒤 "고정"을 누르면 그 시드로 고정됨.
     */
    private suspend fun seedFor(cid: String): Long {
        val st = load(cid).o("style")
        val last = (st?.get("seed") as? JsonPrimitive)?.content?.toLongOrNull()
        val seed = if (st?.b("seedFixed") == true && last != null) last else (Math.random() * 4294967295.0).toLong()
        if (seed != last) withContext(Dispatchers.Main) { patchStyle(cid, "seed" to JsonPrimitive(seed)) }
        return seed
    }

    fun seedFixed(g: JsonObject) = g.o("style")?.b("seedFixed") == true
    fun toggleSeed(cid: String) = patchStyle(cid, "seedFixed" to JsonPrimitive(!seedFixed(load(cid))))

    suspend fun generate(g: JsonObject, job: Job, seed: Long = (Math.random() * 4294967295.0).toLong()): ByteArray = withContext(Dispatchers.IO) {
        val p = provider(g) ?: throw ProviderError("NovelAI(image) 프로바이더가 없습니다. 설정 › 프로바이더에서 추가하세요.")
        val r = Llm.resolve(p)
        if (r.apiKey.isNullOrBlank()) throw ProviderError("NovelAI Persistent API Token이 설정되지 않았습니다. 설정 › 프로바이더에서 키를 연결하세요.")
        if (job.basePrompt.isBlank() && job.chars.all { it.prompt.isBlank() }) throw ProviderError("프롬프트가 비어 있습니다. 그림체 프롬프트나 캐릭터 블록을 작성하세요.")
        val model = g.o("style").s("model").ifBlank { NaiModels.list[0].first }
        val body = CardIO.json.encodeToString(JsonElement.serializer(), buildBody(job, g.o("style"), model, seed))
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
        val png = generate(g, jobForPreset(g, preset), seedFor(cid))
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
        val png = generate(g, jobForCharacter(g, c), seedFor(cid))
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

    /** 다른 봇의 이미지 캐릭터(외형 블록·폴더·크기·자유 프롬프트)를 새 캐릭터로 복사해 넣음. 돌려주는 값 = 새 캐릭터 이름. */
    fun importCharacter(cid: String, src: JsonObject, fromBot: String): String {
        var name = ""
        update(cid) { g ->
            val map = src.arr("blocks").associate { it.s("id") to newId("gb") }
            val taken = g.arr("characters").map { it.s("name") }.toSet()
            val base = src.s("name").ifBlank { fromBot.ifBlank { "가져온 캐릭터" } }
            name = if (base !in taken) base else "$base ($fromBot)".let { n -> if (n !in taken) n else "$n ${g.arr("characters").size + 1}" }
            val copy = src.with("id" to JsonPrimitive(newId("gc")), "name" to JsonPrimitive(name), "blocks" to JsonArray(src.arr("blocks").map { b ->
                val nb = b.with("id" to JsonPrimitive(map[b.s("id")]!!))
                b.sOrNull("folderId")?.let { f -> map[f]?.let { nb.with("folderId" to JsonPrimitive(it)) } } ?: nb
            })).let { c -> (c["activeBlockIds"] as? JsonArray)?.let { a -> c.with("activeBlockIds" to JsonArray(a.mapNotNull { (it as? JsonPrimitive)?.content?.let { id -> map[id]?.let { n -> JsonPrimitive(n) } } })) } ?: c }
            g.mapList("characters") { it.add(copy) }.with("selectedCharacterId" to JsonPrimitive(copy.s("id")))
        }
        return name
    }

    /**
     * 외형 덮어쓰기: target 캐릭터 자리(프리셋에 붙은 그대로)에 src의 외형 블록을 씀. 블록은 이름으로 맞춤(id 유지 → 프리셋의 켜기·끄기 그대로).
     * 맞는 이름이 없는 src 블록은 새로 추가(프리셋에선 꺼진 채), src에 없는 target 블록은 지움(프리셋 연결에서도 빠짐). 이름도 src로(겹치면 그대로).
     */
    fun overwriteLook(cid: String, targetId: String, src: JsonObject): String {
        var name = ""
        update(cid) { g ->
            val t = g.arr("characters").firstOrNull { it.s("id") == targetId } ?: return@update g
            val tb = t.arr("blocks")
            // 가져올 쪽도 의상 구조로 맞춘 뒤 비교 (탈의 폴더 → 상반신(탈의)·하반신(탈의) 고정 블록)
            val src2 = outfitChar(src).first
            val sb = src2.arr("blocks")
            // 이름 + 들어 있는 폴더 이름으로 맞춤 (기본의상/상의 ≠ 탈의/상의).
            // 띄어쓰기·대소문자와 ":" 뒤는 무시 (성별 = 성별:여, 가슴크기 = 가슴 크기) → 프리셋 연결 유지
            fun norm(n: String) = n.substringBefore(':').filterNot { it.isWhitespace() }.lowercase()
            fun keyIn(list: List<JsonObject>, b: JsonObject): String {
                val folder = b.sOrNull("folderId")?.let { f -> list.firstOrNull { it.s("id") == f && it.s("type") == "folder" }?.s("name") } ?: ""
                return norm(folder) + "/" + norm(b.s("name"))
            }
            fun key(b: JsonObject) = keyIn(if (tb.any { it === b }) tb else sb, b)
            val out = mutableListOf<JsonObject>()
            val used = mutableSetOf<String>()
            // 폴더 id 대응: src 폴더 → target 폴더(이름 같음) 또는 새 폴더
            val folderMap = mutableMapOf<String, String>()
            sb.filter { it.s("type") == "folder" }.forEach { f ->
                val m = tb.firstOrNull { it.s("type") == "folder" && key(it) == key(f) }
                folderMap[f.s("id")] = m?.s("id") ?: newId("gb")
            }
            for (b in tb) {
                val m = sb.firstOrNull { key(it) == key(b) && (it.s("type") == "folder") == (b.s("type") == "folder") && it.s("id") !in used }
                if (m != null) { used.add(m.s("id")); out.add(if (b.s("type") == "folder") b else b.with("name" to JsonPrimitive(m.s("name")), "text" to JsonPrimitive(m.s("text")), "type" to JsonPrimitive(if (isSlot(b)) "slot" else m.s("type").ifBlank { "prompt" }))) }
                // src에 없는 블록·폴더는 지움
            }
            for (b in sb) {
                if (b.s("id") in used) continue
                if (b.s("type") == "folder") { if (out.none { it.s("id") == folderMap[b.s("id")] }) out.add(b.with("id" to JsonPrimitive(folderMap[b.s("id")]!!))); continue }
                var nb = b.with("id" to JsonPrimitive(newId("gb")))
                nb = b.sOrNull("folderId")?.let { f -> folderMap[f]?.let { nb.with("folderId" to JsonPrimitive(it)) } } ?: JsonObject(nb - "folderId")
                out.add(nb)
            }
            val taken = g.arr("characters").filter { it.s("id") != targetId }.map { it.s("name") }.toSet()
            val newName = src.s("name").takeIf { it.isNotBlank() && it !in taken } ?: t.s("name")
            name = newName
            // 지워진 폴더를 가리키던 블록은 폴더 밖으로
            val ids = out.map { it.s("id") }.toSet()
            val fixed = out.map { b -> b.sOrNull("folderId")?.takeIf { it !in ids }?.let { JsonObject(b - "folderId") } ?: b }
            val srcFit = outfit(src2)?.s("id")?.let { folderMap[it] }?.takeIf { it in ids }
            val nt = t.with("name" to JsonPrimitive(newName), "blocks" to JsonArray(fixed)).let { x -> if (srcFit != null) x.with("outfit" to JsonPrimitive(srcFit)) else x }
            g.mapList("characters") { l -> val i = l.indexOfFirst { it.s("id") == targetId }; if (i >= 0) l[i] = nt }
                .mapList("presets") { l -> for (k in l.indices) {
                    val p = l[k]
                    l[k] = p.with("chars" to JsonArray(p.arr("chars").map { pc ->
                        if (pc.s("characterId") != targetId) pc
                        else pc.with("blockIds" to JsonArray(((pc["blockIds"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull } ?: emptyList()).filter { it in ids }.map { JsonPrimitive(it) }))
                    }))
                } }
        }
        return name
    }

    fun thumb(preset: JsonObject?): JsonObject? = preset?.arr("images")?.let { l -> l.firstOrNull { it.b("selected") } ?: l.lastOrNull() }
}
