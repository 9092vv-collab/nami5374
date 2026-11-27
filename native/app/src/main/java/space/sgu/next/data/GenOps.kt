package space.sgu.next.data

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.put

/**
 * 작업실 image ops (original module 903): the AI can read/edit the image template, image characters, free prompts and
 * ask for generation / compression. Works on the character's imageGen (put into the workshop data as "imageGen").
 */
object GenOps {
    val IDS = setOf("genTemplate", "genCharacters", "genFree", "genFreeRun", "genCompress")
    private const val POS = "pos: {mode:\"auto\"} or {mode:\"custom\",col:0-4,row:0-4}. If any character of a preset is custom, all are (cells are unique)"

    val OP_KEYS = listOf("genFree", "genFreeRun", "genCompress", "genTemplate", "genCharacters")

    /** 작업실 AI가 보는 op 값 설명: 내장 프롬프트에서 고친 값이 있으면 그걸 씀. */
    fun ops(id: String): List<Pair<String, String>> {
        val k = if (id in OP_KEYS) id else "genCharacters"
        return defaultOps(id).map { (op, v) -> op to BuiltinPrompts.get("op.$k.$op").ifBlank { v } }
    }

    fun defaultOps(id: String): List<Pair<String, String>> = when (id) {
        "genFree" -> listOf("update" to "with id (character id); partial {freePrompt?: string, freeNegative?: string, blockIds?: string[] (blocks that take part when the free prompt runs; all other blocks are switched off. A block inside a folder takes part only while its folder is on: a folder is switched on when its id or any of its blocks is listed, and switched off otherwise — so list only what should be on and leave out folders the user turned off)}")
        "genFreeRun" -> listOf("run" to "with id (character id); optional {n: 1-2, addAsIcon: boolean (default false)}. Generates with the NovelAI provider after the edits are applied (costs Anlas)")
        "genCompress" -> listOf("compress" to "optional {format: \"webp\"|\"jpeg\" (default webp), quality: 5-100 (default 80)}. Compresses all generated images of the image template; use the defaults unless the user asks otherwise")
        "genTemplate" -> listOf(
            "add" to "new preset {name, imageName, imageType:\"asset\"|\"emotion\", size:{kind:\"portrait\"|\"landscape\"|\"square\"|\"custom\",w?,h?}, assetPrompt, assetNegative, chars:[{characterId, blockIds:[], prompt, negative, pos}]}; $POS",
            "update" to "with id (preset id); partial preset (same keys as add). \"chars\" replaces the whole attached-character list. characterId = the character's id (or exact name); blockIds = block ids (or exact block names) to switch on; a block inside a folder also switches its folder on",
            "remove" to "none, with id (deletes the preset and its images)",
            "remove_images" to "none, with id (deletes every generated image of the preset)",
            "image" to "with id (preset id); {imageId, action:\"remove\"|\"use\"|\"unuse\"} — use = upload the image to the bot",
            "generate" to "with id (preset id); optional {n: 1-4}. Generates images with the NovelAI provider after the edits are applied (costs Anlas)")
        else -> listOf(
            "add_character" to "{name, size?, freePrompt?, freeNegative?, blocks?:[{name,type:\"prompt\"|\"negative\",text}]}",
            "update_character" to "with id (character id); partial {name, size, freePrompt, freeNegative, outfit (folder id or exact folder name: the outfit the character wears)}",
            "remove_character" to "none, with id (also detaches it from presets)",
            "add_block" to "with id (character id); {name, type:\"prompt\"|\"negative\"|\"folder\", text, folderId?}. type \"folder\" makes a folder that groups blocks (no text). folderId puts the new block inside a folder: give the folder's id, or the exact name of a folder (use the name for a folder created earlier in the same response)",
            "update_block" to "with id (character id); {blockId, name?, type?, text?, folderId?}. folderId moves the block into that folder (folder id or exact folder name); folderId: null or \"\" takes it out of its folder. A folder cannot be put inside a folder, and a block cannot change to or from type \"folder\"",
            "remove_block" to "with id (character id); {blockId}. Removing a folder keeps its blocks and takes them out of the folder")
    }

    private val compact = kotlinx.serialization.json.Json { prettyPrint = false }
    private fun j(e: JsonElement) = compact.encodeToString(JsonElement.serializer(), e)
    private fun str(v: JsonElement?): String = when (v) { null, is JsonNull -> ""; is JsonPrimitive -> v.contentOrNull ?: ""; else -> v.toString() }
    private fun num(v: JsonElement?): Double? = (v as? JsonPrimitive)?.let { it.doubleOrNull ?: it.contentOrNull?.toDoubleOrNull() }

    // ---------- 짧은 id (0.9.54) ----------
    // AI에게는 gb_mwxpb47q2cf 같은 긴 id 대신 c·b·p·i + 짧은 글자를 보여 줌. id에서 바로 계산(순서와 무관)해서 같은 응답 안에서 지우고 고쳐도 안 꼬임.
    // 겹치면 그 id는 원래 id를 그대로 씀. AI가 원래 id나 이름을 써도 그대로 받음.
    private fun code(kind: Char, id: String) = kind + Integer.toString(id.hashCode() and 0xFFFFF, 36)
    private class Aliases(val toShort: Map<String, String>, val toReal: Map<String, String>)
    private fun aliases(g: JsonObject): Aliases {
        val all = mutableListOf<Pair<Char, String>>()
        g.arr("characters").forEach { c -> all.add('c' to c.s("id")); c.arr("blocks").forEach { all.add('b' to it.s("id")) } }
        g.arr("presets").forEach { p -> all.add('p' to p.s("id")); p.arr("images").forEach { all.add('i' to it.s("id")) } }
        val byCode = all.filter { it.second.isNotBlank() }.groupBy { code(it.first, it.second) }
        val toShort = mutableMapOf<String, String>(); val toReal = mutableMapOf<String, String>()
        byCode.forEach { (k, v) -> if (v.size == 1) { toShort[v[0].second] = k; toReal[k] = v[0].second } }
        return Aliases(toShort, toReal)
    }
    private fun Aliases.s(id: String) = toShort[id] ?: id
    private val ID_KEYS = setOf("characterId", "blockId", "folderId", "imageId", "id", "outfit")
    /** AI가 보낸 값 안의 짧은 id를 원래 id로. */
    private fun realize(e: JsonElement?, al: Aliases, key: String? = null): JsonElement? = when (e) {
        null -> null
        is JsonObject -> JsonObject(e.mapValues { (k, v) -> realize(v, al, k)!! })
        is JsonArray -> JsonArray(e.map { v -> if (key == "blockIds" || key == "activeBlockIds") (v as? JsonPrimitive)?.contentOrNull?.let { JsonPrimitive(al.toReal[it] ?: it) } ?: v else realize(v, al, null)!! })
        is JsonPrimitive -> if (key in ID_KEYS && e.isString) JsonPrimitive(al.toReal[e.content] ?: e.content) else e
        else -> e
    }

    /** 지금 그림체 모델이 V5M이면 네거티브를 아예 안 씀(생성 때도 안 보냄): AI에게 안 보여 주고, 써도 버림. */
    fun noNegatives(g: JsonObject?): Boolean =
        (SettingsStore.root.o("imageStyle")?.sOrNull("model") ?: g?.o("style")?.s("model")) == NaiModels.V5_MEDIUM
    private val NEG_KEYS = setOf("assetNegative", "asset_negative", "negative", "freeNegative")
    private fun dropNeg(e: JsonElement?): JsonElement? = when (e) {
        is JsonObject -> JsonObject(e.filterKeys { it !in NEG_KEYS }.mapValues { (k, v) ->
            if (k == "blocks" && v is JsonArray) JsonArray(v.filter { (it as? JsonObject)?.let { b -> str(b["type"]) == "negative" } != true }.map { dropNeg(it)!! }) else dropNeg(v)!! })
        is JsonArray -> JsonArray(e.map { dropNeg(it)!! })
        else -> e
    }

    /** 블록 목록: 폴더 안 블록은 폴더 아래 blocks로 묶어서 보여 줌(고칠 때는 지금처럼 folderId). */
    private fun blocksView(c: JsonObject, al: Aliases, withText: Boolean, noNeg: Boolean = false): JsonArray {
        val all = c.arr("blocks").filter { !noNeg || it.s("type") != "negative" }
        fun one(b: JsonObject) = buildJsonObject {
            put("id", al.s(b.s("id"))); put("name", b.s("name"))
            if (b.s("type") != "prompt") put("type", b.s("type"))
            if (withText && b.s("type") != "folder") put("text", b.s("text"))
        }
        val folderIds = all.filter { it.s("type") == "folder" }.map { it.s("id") }.toSet()
        return JsonArray(all.filter { it.sOrNull("folderId")?.takeIf { f -> f in folderIds } == null }.map { b ->
            if (b.s("type") == "folder") JsonObject(one(b) + ("blocks" to JsonArray(all.filter { it.sOrNull("folderId") == b.s("id") }.map { one(it) }))) else one(b)
        })
    }

    fun serialize(g: JsonObject?, id: String): String {
        val g0 = g ?: return ""
        val chars = g0.arr("characters"); val presets = g0.arr("presets")
        val al = aliases(g0)
        val defSize = ImageGenStore.size()
        fun sizeOf(o: JsonObject) = o.o("size") ?: defSize
        val nn = noNegatives(g0)
        val negNote = if (nn) " Negative prompts are not used with the current image model (V5M): they are hidden and must not be written." else ""
        return j(when (id) {
            "genFree" -> JsonArray(chars.map { c -> buildJsonObject {
                put("id", al.s(c.s("id"))); put("name", c.s("name")); put("freePrompt", c.s("freePrompt")); if (!nn) put("freeNegative", c.s("freeNegative"))
                put("activeBlockIds", JsonArray(c.arr("blocks").filter { it["free"]?.toString() != "false" }.map { JsonPrimitive(al.s(it.s("id"))) }))
            } })
            "genFreeRun" -> JsonArray(chars.map { c -> buildJsonObject { put("id", al.s(c.s("id"))); put("name", c.s("name")); if (sizeOf(c) != defSize) put("size", sizeOf(c)) } })
            "genCompress" -> buildJsonObject {
                put("presets", presets.size); put("generatedImages", presets.sumOf { it.arr("images").size })
                put("defaults", buildJsonObject { put("format", "webp"); put("quality", 80) })
            }
            "genCharacters" -> buildJsonObject {
                put("note", "Omitted when default: size = $defSize, block type = prompt. Blocks inside a folder are listed under the folder (edit with folderId). Folders are OUTFITS: each holds a top (name has 상) and a bottom (name has 하); the character wears one (outfit). Slot blocks 상반신/하반신 (type slot) take the worn outfit's top/bottom; 상반신(탈의)/하반신(탈의) are fixed blocks.$negNote")
                put("characters", JsonArray(chars.map { c -> buildJsonObject {
                    put("id", al.s(c.s("id"))); put("name", c.s("name")); if (sizeOf(c) != defSize) put("size", sizeOf(c))
                    ImageGenStore.outfit(c)?.let { put("outfit", al.s(it.s("id"))) }
                    put("blocks", blocksView(c, al, true, nn))
                } }))
            }
            else -> buildJsonObject {
       if (nn) put("note", negNote.trim())
                put("availableCharacters", JsonArray(chars.map { c -> buildJsonObject {
                    put("id", al.s(c.s("id"))); put("name", c.s("name")); put("blocks", blocksView(c, al, false, nn))
                } }))
                put("presets", JsonArray(presets.map { p -> buildJsonObject {
                    put("id", al.s(p.s("id"))); put("name", p.s("name"))
                    if (p.s("imageName") != p.s("name")) put("imageName", p.s("imageName"))
                    p.s("imageType").takeIf { it.isNotBlank() && it != "asset" }?.let { put("imageType", it) }
                    if (sizeOf(p) != defSize) put("size", sizeOf(p))
                    put("assetPrompt", p.s("assetPrompt")); if (!nn && p.s("assetNegative").isNotEmpty()) put("assetNegative", p.s("assetNegative"))
                    if (p.arr("images").isNotEmpty()) put("images", JsonArray(p.arr("images").mapIndexed { i, im -> buildJsonObject { put("imageId", al.s(im.s("id"))); put("order", i + 1); put("usedInBot", im.b("selected")) } }))
                    put("chars", JsonArray(p.arr("chars").map { pc -> buildJsonObject {
                        put("characterId", al.s(pc.s("characterId")))
                        put("blockIds", JsonArray(((pc["blockIds"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull } ?: emptyList()).map { JsonPrimitive(al.s(it)) }))
                        put("prompt", pc.s("prompt")); if (!nn && pc.s("negative").isNotEmpty()) put("negative", pc.s("negative"))
                        val pos = pc.o("pos"); if (pos != null && pos.s("mode") != "auto") put("pos", pos)
                    } }))
                } }))
            }
        })
    }

    /** Side effects run after the edits are saved: generation, compression, image removal / reflection. */
    data class Action(val kind: String, val id: String = "", val imageId: String = "", val n: Int = 1, val flag: Boolean = false, val format: String = "webp", val quality: Int = 80)
    data class Result(val g: JsonObject, val summary: String, val action: Action? = null)

    /** [siblings] = the character's current blocks (to resolve folderId by id or by folder name). */
    private fun block(v: JsonObject, old: JsonObject? = null, siblings: List<JsonObject> = emptyList()) = buildJsonObject {
        val isFolder = if (old != null) old.s("type") == "folder" else str(v["type"]) == "folder"
        put("id", old?.s("id")?.ifBlank { null } ?: Scripts.newId("gb"))
        put("name", if (v.containsKey("name")) str(v["name"]) else old?.s("name") ?: "")
        put("type", if (isFolder) "folder" else when (str(v["type"])) { "negative" -> "negative"; "prompt" -> "prompt"; else -> old?.s("type")?.ifBlank { null } ?: "prompt" })
        put("text", if (isFolder) "" else if (v.containsKey("text")) str(v["text"]) else old?.s("text") ?: "")
        old?.get("keep")?.let { put("keep", it) }; old?.get("free")?.let { put("free", it) }
        if (!isFolder) {
            if (v.containsKey("folderId")) {
                val want = str(v["folderId"]).trim()
                if (want.isNotEmpty() && want != "null") {
                    val folders = siblings.filter { it.s("type") == "folder" }
                    val f = folders.firstOrNull { it.s("id") == want } ?: folders.firstOrNull { it.s("name") == want } ?: folders.firstOrNull { it.s("name").trim().equals(want, ignoreCase = true) }
                        ?: error("폴더 '$want' 없음")
                    put("folderId", f.s("id"))
                }
            } else old?.get("folderId")?.let { put("folderId", it) }
        }
    }

    private fun presetChars(g: JsonObject, list: JsonElement?, prev: List<JsonObject>): List<JsonObject> {
        val out = mutableListOf<JsonObject>()
        val all = g.arr("characters")
        for (o in (list as? JsonArray)?.mapNotNull { it as? JsonObject } ?: emptyList()) {
            // characterId에 id 대신 이름을 쓰는 경우가 많아서 이름도 받음. 못 찾으면 조용히 빼지 않고 오류로 알림
            val want = str(o["characterId"] ?: o["characterName"] ?: o["character"] ?: o["name"] ?: o["id"]).trim()
            val c = all.firstOrNull { it.s("id") == want } ?: all.firstOrNull { it.s("name") == want } ?: all.firstOrNull { it.s("name").trim().equals(want, ignoreCase = true) }
                ?: error("캐릭터 '$want' 없음 (있는 캐릭터: ${all.joinToString { "${it.s("name")}=${it.s("id")}" }})")
            if (out.any { it.s("characterId") == c.s("id") }) continue
            val u = prev.firstOrNull { it.s("characterId") == c.s("id") }
            val blocks = c.arr("blocks")
            val blockIds = (o["blockIds"] as? JsonArray)?.map { str(it).trim() }?.mapNotNull { b ->
                (blocks.firstOrNull { it.s("id") == b } ?: blocks.firstOrNull { it.s("name") == b } ?: blocks.firstOrNull { it.s("name").trim().equals(b, ignoreCase = true) })?.s("id")
            }?.let { ids -> ids + ids.mapNotNull { id -> blocks.firstOrNull { it.s("id") == id }?.sOrNull("folderId") } }?.let { ImageGenStore.tidyIds(c, it) }
                ?: u?.let { ImageGenStore.blockIdList(it) } ?: c.arr("blocks").map { it.s("id") }
            out.add(buildJsonObject {
                put("id", u?.s("id") ?: Scripts.newId("pc")); put("characterId", c.s("id")); put("blockIds", JsonArray(blockIds.map { JsonPrimitive(it) }))
                put("prompt", if (o.containsKey("prompt")) str(o["prompt"]) else u?.s("prompt") ?: "")
                put("negative", if (o.containsKey("negative")) str(o["negative"]) else u?.s("negative") ?: "")
                put("pos", if (o.containsKey("pos")) ImageGenStore.normalizePos(o["pos"] as? JsonObject) else u?.o("pos") ?: ImageGenStore.normalizePos(null))
            })
        }
        return ImageGenStore.normalizePositions(out)
    }

    private fun patchPreset(g: JsonObject, p: JsonObject, v0: JsonObject): JsonObject {
        // AI가 쓰는 다른 모양도 받음: {preset:{…}}로 감싸기, prompt/negative, characters
        var v = (v0["preset"] as? JsonObject)?.let { JsonObject(v0 - "preset" + it) } ?: v0
        if (!v.containsKey("assetPrompt")) (v["prompt"] ?: v["asset_prompt"])?.let { v = JsonObject(v + ("assetPrompt" to it)) }
        if (!v.containsKey("assetNegative")) (v["negative"] ?: v["asset_negative"])?.let { v = JsonObject(v + ("assetNegative" to it)) }
        if (!v.containsKey("chars")) v["characters"]?.let { v = JsonObject(v + ("chars" to it)) }
        val m = p.toMutableMap()
        if (v.containsKey("name")) m["name"] = JsonPrimitive(str(v["name"]))
        if (v.containsKey("imageName")) m["imageName"] = JsonPrimitive(str(v["imageName"]))
        str(v["imageType"]).takeIf { it == "asset" || it == "emotion" }?.let { m["imageType"] = JsonPrimitive(it) }
        if (v.containsKey("size")) m["size"] = ImageGenStore.normalizeSize(v["size"] as? JsonObject)
        if (v.containsKey("assetPrompt")) m["assetPrompt"] = JsonPrimitive(str(v["assetPrompt"]))
        if (v.containsKey("assetNegative")) m["assetNegative"] = JsonPrimitive(str(v["assetNegative"]))
        if (v.containsKey("chars")) m["chars"] = JsonArray(presetChars(g, v["chars"], p.arr("chars")))
        return JsonObject(m)
    }

    private fun JsonObject.withList(k: String, list: List<JsonObject>) = JsonObject(this + (k to JsonArray(list)))

    /** Module 903 applyGenOp. Returns null for an op this field doesn't have. */
    fun apply(g: JsonObject, field: String, op: String, id0: String, value: JsonElement?): Result? {
        val al = aliases(g)
        val id = al.toReal[id0] ?: id0
        val nn = noNegatives(g)
        if (nn && (op == "add_block" || op == "update_block") && str((value as? JsonObject)?.get("type")) == "negative")
            error("지금 그림체 모델(V5M)은 네거티브를 쓰지 않아요")
        val h = (if (nn) dropNeg(realize(value, al)) else realize(value, al)) as? JsonObject ?: JsonObject(emptyMap())
        val chars = g.arr("characters"); val presets = g.arr("presets")
        when (field) {
            "genFree" -> {
                val c = chars.firstOrNull { it.s("id") == id } ?: error("캐릭터 $id 없음")
                if (op != "update") return null
                val ids = (h["blockIds"] as? JsonArray)?.map { str(it) }
                var nc = c
                if (h.containsKey("freePrompt")) nc = JsonObject(nc + ("freePrompt" to JsonPrimitive(str(h["freePrompt"]))))
                if (h.containsKey("freeNegative")) nc = JsonObject(nc + ("freeNegative" to JsonPrimitive(str(h["freeNegative"]))))
                if (ids != null) {
                    // 폴더는 무조건 켜지 않음: 목록에 폴더 id가 있거나 그 안의 블록이 하나라도 있으면 켜고, 아니면 끔 (전에는 항상 켜져서 꺼 둔 폴더가 전부 켜졌음)
                    val all = nc.arr("blocks")
                    nc = nc.withList("blocks", all.map { b ->
                        val on = if (b.s("type") == "folder") b.s("id") in ids || all.any { it.sOrNull("folderId") == b.s("id") && it.s("id") in ids } else b.s("id") in ids
                        JsonObject(b + ("free" to JsonPrimitive(on)))
                    })
                }
                return Result(g.withList("characters", chars.map { if (it.s("id") == id) nc else it }), "자유 프롬프트 수정: ${c.s("name")}")
            }
            "genFreeRun" -> {
                val c = chars.firstOrNull { it.s("id") == id } ?: error("캐릭터 $id 없음")
                if (op != "run") return null
                val n = (num(h["n"])?.toInt() ?: 1).coerceIn(1, 2)
                val icon = (h["addAsIcon"] as? JsonPrimitive)?.contentOrNull == "true"
                return Result(g, "자유 프롬프트 실행: ${c.s("name")} ×$n" + if (icon) " (아이콘 추가)" else "", Action("free", c.s("id"), n = n, flag = icon))
            }
            "genCompress" -> {
                if (op != "compress") return null
                val fmt = if (str(h["format"]) == "jpeg") "jpeg" else "webp"
                val q = (num(h["quality"])?.toInt() ?: 80).coerceIn(5, 100)
                return Result(g, "이미지 압축: $fmt $q%", Action("compress", format = fmt, quality = q))
            }
            "genTemplate" -> {
                if (op == "add") {
                    // 캐릭터를 안 붙였고 이미지 캐릭터가 하나뿐이면 그 캐릭터를 붙임 (체크된 블록으로)
                    val only = chars.singleOrNull()
                    val base = ImageGenStore.newPreset(str(h["name"] ?: (h["preset"] as? JsonObject)?.get("name")).ifBlank { "프리셋 ${presets.size + 1}" }, only)
                    val p = patchPreset(g, base, h)
                    return Result(g.withList("presets", presets + p), "이미지 프리셋 추가: ${p.s("name")}")
                }
                val p = presets.firstOrNull { it.s("id") == id } ?: error("프리셋 $id 없음")
                fun with(np: JsonObject) = g.withList("presets", presets.map { if (it.s("id") == id) np else it })
                return when (op) {
                    "update" -> Result(with(patchPreset(g, p, h)), "이미지 프리셋 수정: ${p.s("name")}")
                    "remove" -> Result(g, "이미지 프리셋 삭제: ${p.s("name")}", Action("removePreset", id))
                    "remove_images" -> Result(g, "프리셋 이미지 전체 삭제: ${p.s("name")}", Action("clearImages", id))
                    "image" -> {
                        val im = p.arr("images").firstOrNull { it.s("id") == str(h["imageId"]) } ?: error("이미지 없음")
                        when (str(h["action"])) {
                            "remove" -> Result(g, "프리셋 이미지 삭제: ${p.s("name")}", Action("imageRemove", id, im.s("id")))
                            "use", "unuse" -> { val use = str(h["action"]) == "use"
                                Result(g, "프리셋 이미지 ${if (use) "봇에 반영" else "반영 해제"}: ${p.s("name")}", Action("imageUse", id, im.s("id"), flag = use)) }
                            else -> null
                        }
                    }
                    "generate" -> Result(g, "이미지 생성 요청: ${p.s("name")} ×${(num(h["n"])?.toInt() ?: 1).coerceIn(1, 4)}", Action("generate", id, n = (num(h["n"])?.toInt() ?: 1).coerceIn(1, 4)))
                    else -> null
                }
            }
            else -> {
                if (op == "add_character") {
                    var c = ImageGenStore.newCharacter(str(h["name"]).ifBlank { "캐릭터 ${chars.size + 1}" })
                    if (h.containsKey("size")) c = JsonObject(c + ("size" to ImageGenStore.normalizeSize(h["size"] as? JsonObject)))
                    if (h.containsKey("freePrompt")) c = JsonObject(c + ("freePrompt" to JsonPrimitive(str(h["freePrompt"]))))
                    if (h.containsKey("freeNegative")) c = JsonObject(c + ("freeNegative" to JsonPrimitive(str(h["freeNegative"]))))
                    (h["blocks"] as? JsonArray)?.let { bl ->
                        // 폴더를 먼저 만들고, 나머지는 그 폴더들을 보고 folderId를 맞춤
                        val raw = bl.mapNotNull { it as? JsonObject }
                        val folders = raw.filter { str(it["type"]) == "folder" }.map { block(it) }
                        var fi = 0
                        c = c.withList("blocks", raw.map { if (str(it["type"]) == "folder") folders[fi++] else block(it, null, folders) })
                    }
                    val out = g.withList("characters", chars + c)
                    return Result(if (g.sOrNull("selectedCharacterId") == null) JsonObject(out + ("selectedCharacterId" to JsonPrimitive(c.s("id")))) else out, "이미지 캐릭터 추가: ${c.s("name")}")
                }
                val c = chars.firstOrNull { it.s("id") == id } ?: error("캐릭터 $id 없음")
                fun with(nc: JsonObject) = g.withList("characters", chars.map { if (it.s("id") == id) nc else it })
                return when (op) {
                    "update_character" -> {
                        var nc = c
                        if (h.containsKey("name")) nc = JsonObject(nc + ("name" to JsonPrimitive(str(h["name"]))))
                        if (h.containsKey("size")) nc = JsonObject(nc + ("size" to ImageGenStore.normalizeSize(h["size"] as? JsonObject)))
                        if (h.containsKey("freePrompt")) nc = JsonObject(nc + ("freePrompt" to JsonPrimitive(str(h["freePrompt"]))))
                        if (h.containsKey("freeNegative")) nc = JsonObject(nc + ("freeNegative" to JsonPrimitive(str(h["freeNegative"]))))
                        if (h.containsKey("outfit")) {
                            val o = str(h["outfit"]); val f = c.arr("blocks").firstOrNull { it.s("type") == "folder" && (it.s("id") == o || it.s("name") == o) } ?: error("없는 의상 폴더: $o")
                            nc = JsonObject(nc + ("outfit" to JsonPrimitive(f.s("id"))))
                        }
                        Result(with(nc), "이미지 캐릭터 수정: ${c.s("name")}")
                    }
                    "remove_character" -> {
                        val rest = chars.filter { it.s("id") != id }
                        var out = g.withList("characters", rest).withList("presets", presets.map { p -> p.withList("chars", ImageGenStore.normalizePositions(p.arr("chars").filter { it.s("characterId") != id })) })
                        if (g.sOrNull("selectedCharacterId") == id) out = JsonObject(out + ("selectedCharacterId" to (rest.firstOrNull()?.let { JsonPrimitive(it.s("id")) } ?: JsonNull)))
                        Result(out, "이미지 캐릭터 삭제: ${c.s("name")}")
                    }
                    "add_block" -> {
                        val b = block(h, null, c.arr("blocks"))
                        val out = with(c.withList("blocks", c.arr("blocks") + b))
                        Result(out, (if (b.s("type") == "folder") "캐릭터 블록 폴더 추가: " else "캐릭터 블록 추가: ") + "${c.s("name")} / ${b.s("name")}")
                    }
                    "update_block" -> {
                        val b = c.arr("blocks").firstOrNull { it.s("id") == str(h["blockId"]) } ?: error("블록 없음")
                        val nb = block(h, b, c.arr("blocks"))
                        var out = with(c.withList("blocks", c.arr("blocks").map { if (it.s("id") == b.s("id")) nb else it }))
                        // 프리셋에서 켜져 있던 블록을 폴더로 옮기면 그 폴더도 켬 (안 그러면 프리셋에서 조용히 빠짐)
                        nb.sOrNull("folderId")?.takeIf { it != b.sOrNull("folderId") }?.let { f ->
                            out = out.withList("presets", presets.map { p -> p.withList("chars", p.arr("chars").map { pc ->
                                val ids = ImageGenStore.blockIdList(pc)
                                if (pc.s("characterId") == id && b.s("id") in ids && f !in ids) JsonObject(pc + ("blockIds" to JsonArray((ids + f).map { JsonPrimitive(it) }))) else pc
                            }) })
                        }
                        Result(out, "캐릭터 블록 수정: ${c.s("name")} / ${b.s("name")}")
                    }
                    "remove_block" -> {
                        val b = c.arr("blocks").firstOrNull { it.s("id") == str(h["blockId"]) } ?: error("블록 없음")
                        // 폴더를 지우면 안의 블록은 남기고 폴더 밖으로 꺼냄
                        val out = with(c.withList("blocks", c.arr("blocks").filter { it.s("id") != b.s("id") }.map { if (it.sOrNull("folderId") == b.s("id")) JsonObject(it - "folderId") else it }))
                        Result(out.withList("presets", presets.map { p -> p.withList("chars", p.arr("chars").map { pc ->
                            if (pc.s("characterId") == id) JsonObject(pc + ("blockIds" to JsonArray(ImageGenStore.blockIdList(pc).filter { it != b.s("id") }.map { JsonPrimitive(it) }))) else pc
                        }) }), "캐릭터 블록 삭제: ${c.s("name")} / ${b.s("name")}")
                    }
                    else -> null
                }
            }
        }
    }

    /** After the turn's edits are saved (original 910: generate after apply). */
    fun runActions(cid: String, actions: List<Action>) {
        for (a in actions) when (a.kind) {
            "removePreset" -> ImageGenStore.removePresetFull(cid, a.id)
            "clearImages" -> ImageGenStore.clearPresetImages(cid, a.id)
            "imageRemove" -> ImageGenStore.deleteImage(cid, a.id, a.imageId)
            "imageUse" -> {
                val cur = ImageGenStore.preset(ImageGenStore.load(cid), a.id)?.arr("images")?.firstOrNull { it.s("id") == a.imageId }
                if (cur != null && cur.b("selected") != a.flag) ImageGenStore.toggleUse(cid, a.id, a.imageId)
            }
            "generate" -> GenRun.run(cid, listOf(a.id), a.n)
            "compress" -> GenRun.compress(cid, a.format != "jpeg", a.quality, false)
            "free" -> GenRun.runFree(cid, a.id, a.n, a.flag)
        }
    }
}
