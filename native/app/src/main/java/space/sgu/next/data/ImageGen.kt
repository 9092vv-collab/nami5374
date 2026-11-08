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
        } ?: defaults()
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

    fun removePreset(cid: String, presetId: String) {
        preset(load(cid), presetId)?.arr("images")?.forEach { imageFile(cid, it.s("file")).delete() }
        update(cid) { g -> g.mapList("presets") { l -> l.removeAll { it.s("id") == presetId } } }
    }

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
        return blocks.filter { b ->
            b.s("type") != "folder" && (ids == null || (b.s("id") in ids && (b.sOrNull("folderId") == null || b.s("folderId") in ids)))
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
                val imgs = p.arr("images").map { it.with("selected" to JsonPrimitive(false)) } +
                    buildJsonObject { put("id", JsonPrimitive(newId("gi"))); put("file", JsonPrimitive(name)); put("selected", JsonPrimitive(true)) }
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

    fun selectImage(cid: String, presetId: String, imageId: String) = patchPreset(cid, presetId) { p ->
        p.with("images" to JsonArray(p.arr("images").map { it.with("selected" to JsonPrimitive(it.s("id") == imageId)) }))
    }

    fun deleteImage(cid: String, presetId: String, imageId: String) {
        preset(load(cid), presetId)?.arr("images")?.firstOrNull { it.s("id") == imageId }?.let { imageFile(cid, it.s("file")).delete() }
        patchPreset(cid, presetId) { p -> p.with("images" to JsonArray(p.arr("images").filter { it.s("id") != imageId })) }
    }

    fun thumb(preset: JsonObject?): JsonObject? = preset?.arr("images")?.let { l -> l.firstOrNull { it.b("selected") } ?: l.lastOrNull() }
}
