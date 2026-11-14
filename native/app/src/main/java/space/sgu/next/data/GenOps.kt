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
            "update" to "with id (preset id); partial preset (same keys as add). \"chars\" replaces the whole attached-character list",
            "remove" to "none, with id (deletes the preset and its images)",
            "remove_images" to "none, with id (deletes every generated image of the preset)",
            "image" to "with id (preset id); {imageId, action:\"remove\"|\"use\"|\"unuse\"} — use = upload the image to the bot",
            "generate" to "with id (preset id); optional {n: 1-4}. Generates images with the NovelAI provider after the edits are applied (costs Anlas)")
        else -> listOf(
            "add_character" to "{name, size?, freePrompt?, freeNegative?, blocks?:[{name,type:\"prompt\"|\"negative\",text}]}",
            "update_character" to "with id (character id); partial {name, size, freePrompt, freeNegative}",
            "remove_character" to "none, with id (also detaches it from presets)",
            "add_block" to "with id (character id); {name, type:\"prompt\"|\"negative\"|\"folder\", text, folderId?}. type \"folder\" makes a folder that groups blocks (no text). folderId puts the new block inside a folder: give the folder's id, or the exact name of a folder (use the name for a folder created earlier in the same response)",
            "update_block" to "with id (character id); {blockId, name?, type?, text?, folderId?}. folderId moves the block into that folder (folder id or exact folder name); folderId: null or \"\" takes it out of its folder. A folder cannot be put inside a folder, and a block cannot change to or from type \"folder\"",
            "remove_block" to "with id (character id); {blockId}. Removing a folder keeps its blocks and takes them out of the folder")
    }

    private val pretty = kotlinx.serialization.json.Json { prettyPrint = true }
    private fun j(e: JsonElement) = pretty.encodeToString(JsonElement.serializer(), e)
    private fun str(v: JsonElement?): String = when (v) { null, is JsonNull -> ""; is JsonPrimitive -> v.contentOrNull ?: ""; else -> v.toString() }
    private fun num(v: JsonElement?): Double? = (v as? JsonPrimitive)?.let { it.doubleOrNull ?: it.contentOrNull?.toDoubleOrNull() }

    fun serialize(g: JsonObject?, id: String): String {
        val g0 = g ?: return ""
        val chars = g0.arr("characters"); val presets = g0.arr("presets")
        return j(when (id) {
            "genFree" -> JsonArray(chars.map { c -> buildJsonObject {
                put("id", c.s("id")); put("name", c.s("name")); put("freePrompt", c.s("freePrompt")); put("freeNegative", c.s("freeNegative"))
                put("activeBlockIds", JsonArray(c.arr("blocks").filter { it["free"]?.toString() != "false" }.map { JsonPrimitive(it.s("id")) }))
            } })
            "genFreeRun" -> JsonArray(chars.map { c -> buildJsonObject { put("id", c.s("id")); put("name", c.s("name")); put("size", c.o("size") ?: ImageGenStore.size()) } })
            "genCompress" -> buildJsonObject {
                put("presets", presets.size); put("generatedImages", presets.sumOf { it.arr("images").size })
                put("defaults", buildJsonObject { put("format", "webp"); put("quality", 80) })
            }
            "genCharacters" -> JsonArray(chars.map { c -> buildJsonObject {
                put("id", c.s("id")); put("name", c.s("name")); put("size", c.o("size") ?: ImageGenStore.size())
                put("blocks", JsonArray(c.arr("blocks").map { b -> buildJsonObject {
                    put("id", b.s("id")); put("name", b.s("name")); put("type", b.s("type"))
                    if (b.s("type") != "folder") { put("text", b.s("text")); b.sOrNull("folderId")?.let { put("folderId", it) } }
                } }))
            } })
            else -> buildJsonObject {
                put("availableCharacters", JsonArray(chars.map { c -> buildJsonObject {
                    put("id", c.s("id")); put("name", c.s("name"))
                    put("blocks", JsonArray(c.arr("blocks").map { b -> buildJsonObject { put("id", b.s("id")); put("name", b.s("name")); put("type", b.s("type")); b.sOrNull("folderId")?.let { put("folderId", it) } } }))
                } }))
                put("presets", JsonArray(presets.map { p -> buildJsonObject {
                    put("id", p.s("id")); put("name", p.s("name")); put("imageName", p.s("imageName")); put("imageType", p.s("imageType").ifBlank { "asset" })
                    put("size", p.o("size") ?: ImageGenStore.size()); put("assetPrompt", p.s("assetPrompt")); put("assetNegative", p.s("assetNegative"))
                    put("images", JsonArray(p.arr("images").mapIndexed { i, im -> buildJsonObject { put("imageId", im.s("id")); put("order", i + 1); put("usedInBot", im.b("selected")) } }))
                    put("chars", JsonArray(p.arr("chars").map { pc -> buildJsonObject {
                        put("characterId", pc.s("characterId")); put("blockIds", pc["blockIds"] ?: JsonArray(emptyList()))
                        put("prompt", pc.s("prompt")); put("negative", pc.s("negative")); put("pos", pc.o("pos") ?: buildJsonObject { put("mode", "auto") })
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
        for (o in (list as? JsonArray)?.mapNotNull { it as? JsonObject } ?: emptyList()) {
            val c = g.arr("characters").firstOrNull { it.s("id") == str(o["characterId"]) } ?: continue
            if (out.any { it.s("characterId") == c.s("id") }) continue
            val u = prev.firstOrNull { it.s("characterId") == c.s("id") }
            val blockIds = (o["blockIds"] as? JsonArray)?.map { str(it) }?.filter { b -> c.arr("blocks").any { it.s("id") == b } }
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

    private fun patchPreset(g: JsonObject, p: JsonObject, v: JsonObject): JsonObject {
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
    fun apply(g: JsonObject, field: String, op: String, id: String, value: JsonElement?): Result? {
        val h = value as? JsonObject ?: JsonObject(emptyMap())
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
                    val p = patchPreset(g, ImageGenStore.newPreset(str(h["name"]).ifBlank { "프리셋 ${presets.size + 1}" }, null).let { JsonObject(it + ("chars" to JsonArray(emptyList()))) }, h)
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
                        var out = with(c.withList("blocks", c.arr("blocks") + b))
                        // 새 폴더는 기존 프리셋에서 켜진 채로 시작 (꺼져 있으면 그 안으로 옮긴 블록이 프리셋에서 조용히 빠짐)
                        if (b.s("type") == "folder") out = out.withList("presets", presets.map { p -> p.withList("chars", p.arr("chars").map { pc ->
                            if (pc.s("characterId") == id && pc["blockIds"] is JsonArray) JsonObject(pc + ("blockIds" to JsonArray((ImageGenStore.blockIdList(pc) + b.s("id")).map { JsonPrimitive(it) }))) else pc
                        }) })
                        Result(out, (if (b.s("type") == "folder") "캐릭터 블록 폴더 추가: " else "캐릭터 블록 추가: ") + "${c.s("name")} / ${b.s("name")}")
                    }
                    "update_block" -> {
                        val b = c.arr("blocks").firstOrNull { it.s("id") == str(h["blockId"]) } ?: error("블록 없음")
                        Result(with(c.withList("blocks", c.arr("blocks").map { if (it.s("id") == b.s("id")) block(h, b, c.arr("blocks")) else it })), "캐릭터 블록 수정: ${c.s("name")} / ${b.s("name")}")
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
