package space.sgu.next.data

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import java.io.File

// ---------- small JSON helpers ----------

fun JsonObject?.s(key: String): String = ((this?.get(key)) as? JsonPrimitive)?.contentOrNull ?: ""
fun JsonObject?.sOrNull(key: String): String? = ((this?.get(key)) as? JsonPrimitive)?.contentOrNull
fun JsonObject?.b(key: String, def: Boolean = false): Boolean = ((this?.get(key)) as? JsonPrimitive)?.booleanOrNull ?: def
fun JsonObject?.d(key: String): Double? = ((this?.get(key)) as? JsonPrimitive)?.doubleOrNull
fun JsonObject?.o(key: String): JsonObject? = this?.get(key) as? JsonObject
fun JsonObject?.arr(key: String): List<JsonObject> = ((this?.get(key)) as? JsonArray)?.mapNotNull { it as? JsonObject } ?: emptyList()
fun JsonObject.with(vararg pairs: Pair<String, JsonElement>): JsonObject = JsonObject(this + pairs)
fun str(v: String?): JsonElement = if (v == null) JsonNull else JsonPrimitive(v)
fun num(v: Number?): JsonElement = if (v == null) JsonNull else JsonPrimitive(v)

/**
 * App settings in exactly the original app's JSON shape (module 890/882), so a "설정 JSON" backup
 * from the original (optionally with `_secrets`) imports as-is and exports back the same way.
 */
object SettingsStore {
    private lateinit var file: File
    var root by mutableStateOf(defaults())
        private set
    private var saveJob: Job? = null

    fun defaultModelParams(): JsonObject = buildJsonObject {
        put("maxContext", JsonPrimitive(150000)); put("maxTokens", JsonPrimitive(50000))
        for (k in listOf("temperature", "topP", "topK", "frequencyPenalty", "presencePenalty")) put(k, JsonNull)
    }

    private fun defaults(): JsonObject = buildJsonObject {
        put("version", JsonPrimitive(1))
        put("theme", JsonPrimitive("light"))
        put("onboardingDone", JsonPrimitive(false))
        put("providers", JsonArray(emptyList()))
        put("keys", JsonArray(emptyList()))
        put("models", JsonArray(emptyList()))
        put("processors", buildJsonObject {
            put("main", buildJsonObject { put("modelId", JsonNull); put("fallbackModelId", JsonNull) })
            put("sub", buildJsonObject {
                put("disabled", JsonPrimitive(false)); put("strategy", JsonPrimitive("llm"))
                put("modelId", JsonNull); put("fallbackModelId", JsonNull); put("jevProviderId", JsonNull)
                put("jevModel", JsonPrimitive("jev-latest")); put("jevThreshold", JsonPrimitive(0.5)); put("chatRange", JsonPrimitive(2))
            })
        })
        put("background", buildJsonObject {
            put("foregroundService", JsonPrimitive(true)); put("keepAwake", JsonPrimitive(false)); put("notifyOnComplete", JsonPrimitive(true))
        })
        put("logging", buildJsonObject { put("enabled", JsonPrimitive(true)); put("maxEntries", JsonPrimitive(50)) })
        put("requestTimeoutMs", JsonPrimitive(180000))
        put("retries", JsonPrimitive(2))
        put("permissionDefaults", Perms.builtinDefaults())
        put("permissionDefaultsVersion", JsonPrimitive(2))
        put("promptPresetsVersion", JsonPrimitive(3))
        put("promptPresets", buildJsonArray { add(defaultPreset()) })
        put("activePromptPresetId", JsonPrimitive("preset_default"))
        put("fieldDescriptions", JsonObject(emptyMap()))
    }

    fun defaultPreset(): JsonObject = buildJsonObject {
        put("id", JsonPrimitive("preset_default")); put("name", JsonPrimitive("기본"))
        put("blocks", JsonArray(PromptBlocks.defaults())); put("createdAt", JsonPrimitive(System.currentTimeMillis()))
    }

    /** Fills missing keys from defaults (shallow, plus processors.main/sub) — like migrateSettings. */
    private fun migrate(t: JsonObject): JsonObject {
        val d = defaults()
        val merged = (d + t).toMutableMap()
        val dp = d.o("processors"); val tp = t.o("processors")
        merged["processors"] = buildJsonObject {
            put("main", JsonObject(dp.o("main").orEmpty() + tp.o("main").orEmpty()))
            put("sub", JsonObject(dp.o("sub").orEmpty() + tp.o("sub").orEmpty()))
        }
        merged["background"] = JsonObject(d.o("background").orEmpty() + t.o("background").orEmpty())
        merged["logging"] = JsonObject(d.o("logging").orEmpty() + t.o("logging").orEmpty())
        merged.remove("_secrets")
        if (t.arr("promptPresets").isEmpty()) {
            merged["promptPresets"] = JsonArray(listOf(defaultPreset()))
            merged["activePromptPresetId"] = JsonPrimitive("preset_default")
        }
        if ((t.d("permissionDefaultsVersion") ?: 0.0) < 2) merged["permissionDefaults"] = d["permissionDefaults"]!!
        else merged["permissionDefaults"] = JsonObject(d.o("permissionDefaults").orEmpty() + t.o("permissionDefaults").orEmpty())
        merged["permissionDefaultsVersion"] = JsonPrimitive(2)
        // NovelAI image providers must call image.novelai.net (the text endpoint answers 401).
        (merged["providers"] as? JsonArray)?.let { list ->
            merged["providers"] = JsonArray(list.map { e ->
                val p = e as? JsonObject ?: return@map e
                val url = p.s("baseUrl")
                if (p.s("format") == "novelai-image" && (url.isBlank() || "text.novelai.net" in url)) p.with("baseUrl" to JsonPrimitive(Presets.NAI_IMAGE_URL)) else p
            })
        }
        return JsonObject(merged)
    }

    fun init(ctx: Context) {
        if (::file.isInitialized) return
        Secrets.init(ctx)
        file = File(ctx.filesDir, "settings.json")
        root = runCatching { migrate(CardIO.json.parseToJsonElement(file.readText()).jsonObject) }.getOrDefault(defaults())
    }

    private fun scheduleSave() {
        saveJob?.cancel()
        val snapshot = root
        saveJob = CharacterStore.io.launch {
            delay(300)
            file.writeText(CardIO.json.encodeToString(JsonElement.serializer(), snapshot))
        }
    }

    fun update(change: (MutableMap<String, JsonElement>) -> Unit) {
        val m = root.toMutableMap(); change(m); root = JsonObject(m); scheduleSave()
    }

    // ---------- lists ----------

    val providers: List<JsonObject> get() = root.arr("providers")
    val models: List<JsonObject> get() = root.arr("models")
    val keys: List<JsonObject> get() = root.arr("keys")
    fun provider(id: String?) = providers.firstOrNull { it.s("id") == id }
    fun model(id: String?) = models.firstOrNull { it.s("id") == id }

    private fun newId(prefix: String) = prefix + "_" + System.currentTimeMillis().toString(36) + (1000..9999).random().toString(36)

    private fun editList(key: String, change: (MutableList<JsonObject>) -> Unit) = update { m ->
        val list = root.arr(key).toMutableList(); change(list); m[key] = JsonArray(list)
    }

    fun patch(listKey: String, id: String, vararg pairs: Pair<String, JsonElement>) = editList(listKey) { list ->
        val i = list.indexOfFirst { it.s("id") == id }
        if (i >= 0) list[i] = list[i].with(*pairs)
    }

    fun addProvider(p: Preset): String {
        val id = newId("pv")
        val sameCount = providers.count { it.s("preset") == p.id }
        val obj = buildJsonObject {
            put("id", JsonPrimitive(id))
            put("name", JsonPrimitive(if (sameCount > 0) "${p.name} ${sameCount + 1}" else p.name))
            put("preset", JsonPrimitive(p.id)); put("format", JsonPrimitive(p.format))
            put("baseUrl", JsonPrimitive(p.baseUrl)); put("auth", JsonPrimitive(p.auth))
            put("keyId", JsonNull); put("headersJson", JsonPrimitive("")); put("bodyJson", JsonPrimitive(""))
            if (p.id == "copilot") put("copilot", buildJsonObject { put("family", JsonPrimitive("vscode")) })
            if (p.id == "vertex") put("vertex", buildJsonObject {
                put("projectId", JsonPrimitive("")); put("clientEmail", JsonPrimitive("")); put("location", JsonPrimitive("global"))
            })
            if (p.defaultCache != "off") put("cache", buildJsonObject { put("mode", JsonPrimitive(p.defaultCache)); put("ttl", JsonPrimitive("5m")) })
            put("createdAt", JsonPrimitive(System.currentTimeMillis()))
        }
        editList("providers") { it.add(obj) }
        return id
    }

    fun removeProvider(id: String) = update { m ->
        val p = provider(id)
        val keyId = p.sOrNull("keyId")
        if (keyId != null && providers.none { it.s("id") != id && it.sOrNull("keyId") == keyId }) {
            Secrets.remove(keyId)
            m["keys"] = JsonArray(keys.filter { it.s("id") != keyId })
        }
        val gone = models.filter { it.s("providerId") == id }.map { it.s("id") }.toSet()
        m["providers"] = JsonArray(providers.filter { it.s("id") != id })
        m["models"] = JsonArray(models.filter { it.s("providerId") != id })
        m["processors"] = clearedProcessors(gone)
    }

    /** Saves the provider's API key (encrypted) and links it. */
    fun setProviderKey(providerId: String, value: String) {
        val p = provider(providerId) ?: return
        val clean = value.trim()
        if (clean.isEmpty()) return
        val existing = p.sOrNull("keyId")?.takeIf { k -> keys.any { it.s("id") == k } }
        val hint = clean.takeLast(4)
        if (existing != null) {
            Secrets.put(existing, clean)
            patch("keys", existing, "hint" to JsonPrimitive(hint))
        } else {
            val keyId = newId("key")
            Secrets.put(keyId, clean)
            editList("keys") {
                it.add(buildJsonObject {
                    put("id", JsonPrimitive(keyId)); put("label", JsonPrimitive("${p.s("name")} 키"))
                    put("hint", JsonPrimitive(hint)); put("createdAt", JsonPrimitive(System.currentTimeMillis()))
                })
            }
            patch("providers", providerId, "keyId" to JsonPrimitive(keyId))
        }
    }

    fun removeProviderKey(providerId: String) {
        val p = provider(providerId) ?: return
        val keyId = p.sOrNull("keyId") ?: return
        if (providers.none { it.s("id") != providerId && it.sOrNull("keyId") == keyId }) {
            Secrets.remove(keyId)
            editList("keys") { l -> l.removeAll { it.s("id") == keyId } }
        }
        patch("providers", providerId, "keyId" to JsonNull)
    }

    fun keyHint(p: JsonObject?): String? {
        val keyId = p.sOrNull("keyId") ?: return null
        return keys.firstOrNull { it.s("id") == keyId }?.s("hint")?.ifBlank { "····" } ?: if (Secrets.get(keyId) != null) "····" else null
    }

    fun addModel(providerId: String, modelId: String): String {
        val id = newId("md")
        val obj = buildJsonObject {
            put("id", JsonPrimitive(id)); put("providerId", JsonPrimitive(providerId))
            put("modelId", JsonPrimitive(modelId)); put("name", JsonPrimitive(modelId))
            put("params", defaultModelParams()); put("stream", JsonPrimitive(true))
            put("thinking", buildJsonObject { put("enabled", JsonPrimitive(false)); put("budget", JsonNull); put("effort", JsonNull) })
            put("structuredOutput", JsonPrimitive("auto"))
            put("vision", JsonPrimitive(Presets.guessVision(modelId)))
            put("createdAt", JsonPrimitive(System.currentTimeMillis()))
        }
        editList("models") { it.add(obj) }
        return id
    }

    fun removeModel(id: String) = update { m ->
        m["models"] = JsonArray(models.filter { it.s("id") != id })
        m["processors"] = clearedProcessors(setOf(id))
    }

    private fun clearedProcessors(gone: Set<String>): JsonObject {
        val p = root.o("processors")
        fun clean(o: JsonObject?): JsonObject = JsonObject(o.orEmpty().mapValues { (k, v) ->
            if ((k == "modelId" || k == "fallbackModelId") && (v as? JsonPrimitive)?.contentOrNull?.let { it in gone } == true) JsonNull else v
        })
        return buildJsonObject { put("main", clean(p.o("main"))); put("sub", clean(p.o("sub"))) }
    }

    fun setProcessor(role: String, key: String, value: JsonElement) = update { m ->
        val p = root.o("processors")
        val r = p.o(role).orEmpty().toMutableMap(); r[key] = value
        m["processors"] = JsonObject(p.orEmpty() + (role to JsonObject(r)))
    }

    fun favoriteCount(): Int = models.count { it.b("favorite") && it.s("id") != root.o("processors").o("main").sOrNull("modelId") }

    // ---------- backup (same file as the original's "설정 JSON 내보내기/가져오기") ----------

    data class ImportResult(val providers: Int, val models: Int, val keys: Int)

    fun importBackup(text: String): ImportResult {
        val obj = CardIO.json.parseToJsonElement(text).jsonObject
        if (obj["providers"] !is JsonArray && obj["models"] !is JsonArray) error("SGU 설정 파일이 아니에요")
        var keyCount = 0
        obj.o("_secrets")?.forEach { (id, v) ->
            (v as? JsonPrimitive)?.contentOrNull?.let { Secrets.put(id, it); keyCount++ }
        }
        root = migrate(obj)
        scheduleSave()
        return ImportResult(providers.size, models.size, keyCount)
    }

    fun exportBackup(includeKeys: Boolean): String {
        val out = if (includeKeys) {
            root.with("_secrets" to JsonObject(keys.mapNotNull { k -> Secrets.get(k.s("id"))?.let { k.s("id") to JsonPrimitive(it) } }.toMap()))
        } else {
            root.with(
                "keys" to JsonArray(emptyList()),
                "providers" to buildJsonArray { providers.forEach { add(it.with("keyId" to JsonNull)) } },
            )
        }
        return kotlinx.serialization.json.Json { prettyPrint = true }.encodeToString(JsonElement.serializer(), out)
    }
}
