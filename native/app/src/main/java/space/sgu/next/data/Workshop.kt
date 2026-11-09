package space.sgu.next.data

import android.content.Context
import android.widget.Toast
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

private fun js(s: String) = JsonPrimitive(s)
private fun cardData(cid: String): JsonObject = (CharacterStore.readCard(cid)["data"] as? JsonObject) ?: JsonObject(emptyMap())

// =====================================================================================
// 되돌리기 · 다시 실행 (module 901), over the stored card data, direct edits included
// =====================================================================================

object EditHistory {
    private class State(var data: JsonObject, val turnId: String? = null)
    private class H(val states: MutableList<State>, var idx: Int, var lastManualAt: Long)
    private val map = HashMap<String, H>()
    private var silent = 0
    private var tag: Pair<String, String>? = null
    /** cid → (canUndo, canRedo) */
    val flags = mutableStateMapOf<String, Pair<Boolean, Boolean>>()

    fun <T> silently(block: () -> T): T { silent++; try { return block() } finally { silent-- } }
    fun <T> tagged(cid: String, turnId: String, block: () -> T): T { tag = cid to turnId; try { return block() } finally { tag = null } }

    fun record(cid: String, before: JsonObject, after: JsonObject) {
        if (silent > 0 || before == after) return
        val turnId = tag?.takeIf { it.first == cid }?.second
        val h = map.getOrPut(cid) { H(mutableListOf(State(before)), 0, 0) }
        if (h.states[h.idx].data != before) h.states[h.idx].data = before
        while (h.states.size > h.idx + 1) h.states.removeAt(h.states.size - 1)
        val now = System.currentTimeMillis(); val cur = h.states[h.idx]
        if (turnId == null && cur.turnId == null && h.idx > 0 && now - h.lastManualAt < 1200) {
            cur.data = after
            if (h.states[h.idx - 1].data == after) { h.states.removeAt(h.idx); h.idx-- }
        } else {
            h.states.add(State(after, turnId)); h.idx++
            if (h.states.size > 200) { h.states.removeAt(0); h.idx-- }
        }
        h.lastManualAt = if (turnId != null) 0 else now
        publish(cid)
    }

    data class Step(val data: JsonObject, val turnId: String?)

    fun undo(cid: String): Step? {
        val h = map[cid] ?: return null
        while (h.idx > 0) {
            val e = h.states[h.idx]; val s = h.states[h.idx - 1]
            if (s.data != e.data) { h.idx--; h.lastManualAt = 0; publish(cid); return Step(s.data, e.turnId) }
            h.states.removeAt(h.idx); h.idx--
        }
        publish(cid); return null
    }

    fun redo(cid: String): Step? {
        val h = map[cid] ?: return null
        while (h.idx < h.states.size - 1) {
            val e = h.states[h.idx + 1]
            if (h.states[h.idx].data != e.data) { h.idx++; h.lastManualAt = 0; publish(cid); return Step(e.data, e.turnId) }
            h.states.removeAt(h.idx + 1)
        }
        publish(cid); return null
    }

    /** Drops a turn's states and rolls its fields back in the later ones (removeTurnEdits). */
    fun removeTurnEdits(cid: String, turnId: String, snap: JsonObject?) {
        val h = map[cid] ?: return
        val tagged = h.states.indices.filter { it > 0 && h.states[it].turnId == turnId }
        val from = tagged.firstOrNull() ?: 0
        if (snap != null) for (i in from until h.states.size) h.states[i].data = SguData.restore(h.states[i].data, snap)
        for (i in tagged.reversed()) { h.states.removeAt(i); if (h.idx >= i) h.idx-- }
        for (i in h.states.size - 1 downTo 1) if (h.states[i - 1].data == h.states[i].data) { h.states.removeAt(i); if (h.idx >= i) h.idx-- }
        if (h.idx < 0) h.idx = 0
        publish(cid)
    }

    fun retargetTurn(cid: String, turnId: String, snap: JsonObject) {
        val h = map[cid] ?: return
        val r = h.states.indices.firstOrNull { it > 0 && h.states[it].turnId == turnId } ?: return
        for (i in r until h.states.size) h.states[i].data = SguData.restore(h.states[i].data, snap)
        publish(cid)
    }

    fun clear(cid: String) { map.remove(cid); publish(cid) }

    private fun publish(cid: String) {
        val h = map[cid]
        val f = if (h == null) false to false else {
            var u = false; var i = h.idx; while (i >= 1 && !u) { u = h.states[i - 1].data != h.states[i].data; i-- }
            var r = false; i = h.idx + 1; while (i < h.states.size && !r) { r = h.states[i - 1].data != h.states[i].data; i++ }
            u to r
        }
        if (flags[cid] != f) flags[cid] = f
    }
}

// =====================================================================================
// 요청 로그 (module 958)
// =====================================================================================

object Logs {
    private lateinit var file: File
    val entries = mutableStateListOf<JsonObject>()
    private var saveJob: Job? = null

    fun init(ctx: Context) {
        if (::file.isInitialized) return
        file = File(ctx.filesDir, "logs.json")
        runCatching { (CardIO.json.parseToJsonElement(file.readText()) as JsonArray).mapNotNull { it as? JsonObject } }.getOrNull()?.let { entries.addAll(it) }
    }

    private fun redact(e: JsonElement?): JsonElement {
        if (e == null) return JsonNull
        val t = CardIO.json.encodeToString(JsonElement.serializer(), e)
            .replace(Regex("(sk-[A-Za-z0-9_-]{6})[A-Za-z0-9_-]{8,}"), "$1…")
            .replace(Regex("(\"(?:Authorization|x-api-key|x-goog-api-key)\":\\s*\")[^\"]+", RegexOption.IGNORE_CASE), "$1***")
        return runCatching { CardIO.json.parseToJsonElement(t) }.getOrDefault(e)
    }

    fun add(kind: String, model: String? = null, provider: String? = null, request: JsonElement? = null, response: JsonElement? = null,
            ms: Long? = null, usage: Usage? = null, error: String? = null) {
        val max = maxOf(5, SettingsStore.root.o("logging").d("maxEntries")?.toInt() ?: 50)
        val e = buildJsonObject {
            put("id", Scripts.newId("log")); put("at", System.currentTimeMillis()); put("kind", kind)
            model?.let { put("model", it) }; provider?.let { put("provider", it) }
            request?.let { put("request", redact(it)) }; response?.let { put("response", redact(it)) }
            ms?.let { put("ms", it) }; error?.let { put("error", it) }
            usage?.let { u -> put("usage", buildJsonObject { u.inputTokens?.let { put("inputTokens", it) }; u.outputTokens?.let { put("outputTokens", it) } }) }
        }
        CoroutineScope(Dispatchers.Main).launch {
            entries.add(0, e)
            while (entries.size > max) entries.removeAt(entries.size - 1)
            save()
        }
    }

    fun clear() { entries.clear(); save() }

    private fun save() {
        if (!::file.isInitialized) return
        saveJob?.cancel()
        val snap = entries.toList()
        saveJob = CharacterStore.io.launch { delay(500); runCatching { file.writeText(CardIO.json.encodeToString(JsonElement.serializer(), JsonArray(snap))) } }
    }

    fun messagesJson(msgs: List<Msg>): JsonArray = JsonArray(msgs.map { m ->
        buildJsonObject { put("role", m.role); put("content", m.text ?: m.parts.orEmpty().joinToString("\n") { if (it.isText) it.text ?: "" else "[image ${it.mime}]" }) }
    })
}

// =====================================================================================
// 웹 검색 (module 960)
// =====================================================================================

object WebSearch {
    const val KEY_ID = "search-tavily"
    private const val UA = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36"
    data class Hit(val title: String, val url: String, val snippet: String, var page: String? = null)

    fun hasNative(p: JsonObject?, modelId: String): Boolean = when (p.s("format")) {
        "anthropic", "gemini" -> true
        "openai-responses" -> Regex("api\\.openai\\.com|openai\\.azure\\.com", RegexOption.IGNORE_CASE).containsMatchIn(p.s("baseUrl"))
        "openai-chat" -> Regex("openrouter", RegexOption.IGNORE_CASE).containsMatchIn(p.s("baseUrl")) ||
            (Regex("api\\.openai\\.com", RegexOption.IGNORE_CASE).containsMatchIn(p.s("baseUrl")) && Regex("search", RegexOption.IGNORE_CASE).containsMatchIn(modelId))
        else -> false
    }

    private fun decode(t: String) = t.replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
        .replace(Regex("&#x27;|&#39;"), "'").replace("&nbsp;", " ").replace(Regex("&#(\\d+);")) { it.groupValues[1].toIntOrNull()?.toChar()?.toString() ?: "" }
    private fun plain(t: String) = decode(t.replace(Regex("<[^>]+>"), " ")).replace(Regex("\\s+"), " ").trim()

    private fun get(url: String, method: String = "GET", body: String? = null, headers: Map<String, String> = emptyMap(), timeout: Int = 12000): Pair<Int, String> {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.requestMethod = method; c.connectTimeout = timeout; c.readTimeout = timeout
            c.setRequestProperty("User-Agent", UA); headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
            if (body != null) { c.doOutput = true; c.outputStream.use { it.write(body.toByteArray()) } }
            val code = c.responseCode
            val ct = c.contentType ?: ""
            val text = if (code in 200..299 && (ct.isEmpty() || Regex("html|text|json", RegexOption.IGNORE_CASE).containsMatchIn(ct))) c.inputStream.bufferedReader().use { it.readText().take(400_000) } else ""
            return code to text
        } finally { c.disconnect() }
    }

    private fun ddg(html: String): List<Hit> {
        val out = mutableListOf<Hit>()
        val re = Regex("<a[^>]+class=\"[^\"]*result__a[^\"]*\"[^>]*href=\"([^\"]+)\"[^>]*>([\\s\\S]*?)</a>([\\s\\S]*?)(?=<a[^>]+class=\"[^\"]*result__a|$)")
        for (m in re.findAll(html)) {
            if (out.size >= 8) break
            var u = decode(m.groupValues[1])
            Regex("[?&]uddg=([^&]+)").find(u)?.let { u = java.net.URLDecoder.decode(it.groupValues[1], "UTF-8") } ?: run { if (u.startsWith("//")) u = "https:$u" }
            if (!Regex("^https?://", RegexOption.IGNORE_CASE).containsMatchIn(u) || u.contains("duckduckgo.com/y.js")) continue
            val sn = Regex("class=\"[^\"]*result__snippet[^\"]*\"[^>]*>([\\s\\S]*?)</(?:a|td|div)>").find(m.groupValues[3])
            out.add(Hit(plain(m.groupValues[2]), u, sn?.let { plain(it.groupValues[1]) } ?: ""))
        }
        return out
    }

    private fun bing(html: String): List<Hit> {
        val out = mutableListOf<Hit>()
        for (m in Regex("<li class=\"b_algo\"[\\s\\S]*?<h2[^>]*><a[^>]+href=\"([^\"]+)\"[^>]*>([\\s\\S]*?)</a></h2>([\\s\\S]*?)</li>").findAll(html)) {
            if (out.size >= 8) break
            var u = decode(m.groupValues[1])
            Regex("[?&]u=a1([A-Za-z0-9_-]+)").find(u)?.let { runCatching { u = String(android.util.Base64.decode(it.groupValues[1].replace('-', '+').replace('_', '/'), android.util.Base64.DEFAULT)) } }
            if (!Regex("^https?://", RegexOption.IGNORE_CASE).containsMatchIn(u)) continue
            val p = Regex("<p[^>]*>([\\s\\S]*?)</p>").find(m.groupValues[3])
            out.add(Hit(plain(m.groupValues[2]), u, p?.let { plain(it.groupValues[1]) } ?: ""))
        }
        return out
    }

    private fun page(url: String): String? = runCatching {
        val (code, t) = get(url, timeout = 8000, headers = mapOf("Accept" to "text/html,*/*"))
        if (code !in 200..299) return null
        val c = plain(t.replace(Regex("<(script|style|noscript|svg|nav|footer|header|aside)[\\s\\S]*?</\\1>", RegexOption.IGNORE_CASE), " ").replace(Regex("<!--[\\s\\S]*?-->"), " "))
        if (c.length > 200) c.take(2500) else null
    }.getOrNull()

    suspend fun search(q0: String): List<Hit> = withContext(Dispatchers.IO) {
        val q = q0.replace(Regex("\\s+"), " ").trim().take(300)
        if (q.isEmpty()) return@withContext emptyList()
        Secrets.get(KEY_ID)?.let { key ->
            runCatching {
                val (code, t) = get("https://api.tavily.com/search", "POST", CardIO.json.encodeToString(JsonElement.serializer(), buildJsonObject { put("query", q); put("max_results", 6); put("search_depth", "basic") }),
                    mapOf("Content-Type" to "application/json", "Authorization" to "Bearer $key"), 15000)
                if (code in 200..299) (CardIO.json.parseToJsonElement(t).jsonObject).arr("results").map { Hit(it.s("title"), it.s("url"), it.s("content").take(700)) } else null
            }.getOrNull()?.takeIf { it.isNotEmpty() }?.let { return@withContext it }
        }
        val hits = runCatching { ddg(get("https://html.duckduckgo.com/html/", "POST", "q=" + URLEncoder.encode(q, "UTF-8"), mapOf("Content-Type" to "application/x-www-form-urlencoded", "Accept" to "text/html")).second) }.getOrDefault(emptyList())
            .ifEmpty { runCatching { bing(get("https://www.bing.com/search?q=" + URLEncoder.encode(q, "UTF-8"), headers = mapOf("Accept" to "text/html", "Accept-Language" to "en,ko;q=0.8")).second) }.getOrDefault(emptyList()) }
        hits.take(3).forEach { it.page = page(it.url) }
        hits.take(6)
    }

    suspend fun context(q: String): String = try {
        val r = search(q)
        if (r.isEmpty()) "Web search for \"$q\" returned no results (the search service may be blocked or unavailable). Answer from your own knowledge and say that search found nothing."
        else "Web search results for \"$q\" (retrieved just now). Use them when relevant and mention the source URL when you rely on one.\n\n" +
            r.mapIndexed { i, h -> "[${i + 1}] ${h.title}\n${h.url}\n${h.snippet}${h.page?.let { "\nPage excerpt: $it" } ?: ""}" }.joinToString("\n\n")
    } catch (e: CancellationException) { throw e } catch (e: Exception) {
        "Web search for \"$q\" failed (${e.message}). Tell the user that web search is unavailable right now and answer from your own knowledge."
    }
}

// =====================================================================================
// Processors (934 sub, 959 main)
// =====================================================================================

object Processors {
    fun subAssigned(): Boolean {
        val o = SettingsStore.root.o("processors").o("sub")
        return if (o.s("strategy") == "jev") o.sOrNull("jevProviderId") != null || SettingsStore.providers.any { it.s("format") == "jev" }
        else o.sOrNull("modelId")?.let { id -> SettingsStore.models.any { it.s("id") == id } } == true
    }
    fun isSubOff(): Boolean = SettingsStore.root.o("processors").o("sub").b("disabled") || !subAssigned()

    fun mainModelId(doc: JsonObject): String? {
        val id = doc.sOrNull("mainModelId")
        return if (id != null && SettingsStore.models.any { it.s("id") == id }) id else SettingsStore.root.o("processors").o("main").sOrNull("modelId")
    }
    fun mainModels(doc: JsonObject): Pair<JsonObject?, JsonObject?> =
        SettingsStore.model(mainModelId(doc)) to SettingsStore.model(SettingsStore.root.o("processors").o("main").sOrNull("fallbackModelId"))

    private fun subInput(doc: JsonObject, command: String): String {
        val t = maxOf(1, SettingsStore.root.o("processors").o("sub").d("chatRange")?.toInt() ?: 2)
        if (t <= 1) return command
        val lines = mutableListOf<String>()
        for (turn in doc.o("session").arr("turns")) if (turn.s("status") == "done") {
            lines.add("User: ${turn.s("command")}"); if (turn.s("response").isNotEmpty()) lines.add("Sgu: ${turn.s("response")}")
        }
        val n = lines.takeLast(t - 1)
        return if (n.isEmpty()) command else (n + "User: $command").joinToString("\n")
    }

    private fun describe(data: JsonObject, id: String) = "${WFields.map[id]?.aiLabel} ($id): ${WFields.description(id)} Currently ${if (SguData.isEmpty(data, id)) "EMPTY" else "has content"}."

    private val SUB_SCHEMA = JsonSchema("sgu_sub_decision", buildJsonObject {
        put("type", "object")
        put("properties", buildJsonObject {
            put("instruction", buildJsonObject { put("type", "boolean"); put("description", "true if the user's intent is an instruction/command for the assistant to work on the character; false if it is ordinary conversation.") })
            put("task", buildJsonObject { put("type", "string"); put("description", "One-sentence restatement of what the main processor must do.") })
            put("view", buildJsonObject { put("type", "array"); put("items", buildJsonObject { put("type", "string") }); put("description", "Field ids whose current content must be read.") })
            put("edit", buildJsonObject { put("type", "array"); put("items", buildJsonObject { put("type", "string") }); put("description", "Field ids that must be modified.") })
            put("reasons", buildJsonObject { put("type", "object"); put("additionalProperties", buildJsonObject { put("type", "string") }); put("description", "Optional short reason per field id.") })
        })
        put("required", buildJsonArray { listOf("instruction", "task", "view", "edit").forEach { add(js(it)) } })
    })

    suspend fun runSub(doc: JsonObject, data: JsonObject, command: String): Decision =
        if (SettingsStore.root.o("processors").o("sub").s("strategy") == "jev") jev(doc, data, command) else llmSub(doc, data, command)

    private suspend fun jev(doc: JsonObject, data: JsonObject, command: String): Decision {
        val o = SettingsStore.root.o("processors").o("sub")
        val p = SettingsStore.provider(o.sOrNull("jevProviderId")) ?: SettingsStore.providers.firstOrNull { it.s("format") == "jev" }
            ?: throw ProviderError("Jev 프로바이더가 설정되지 않았습니다. 설정 › 프로세서에서 Jev를 지정하세요.")
        val (view, edit) = Perms.candidates(doc.o("permissions").orEmptyObj())
        val refCands = References.subCandidates(doc)
        val state = (listOf("The user is editing a RisuAI character card with an AI assistant.",
            "User command (the last message is the one to act on; earlier lines are context only): \"\"\"${subInput(doc, command)}\"\"\"", "", "Character fields:") +
            WFields.all.filter { it.viewable }.map { "- " + describe(data, it.id) } +
            refCands.let { c -> if (c.isEmpty()) emptyList() else listOf("", "Reference materials:") + c.map { "- " + References.describe(it) } }).joinToString("\n")
        val q = mutableMapOf<String, JsonElement>()
        q["intent:instruction"] = buildJsonObject { put("type", "noul"); put("instructions", "Is the user's intent an instruction or command to create, change or look up the character (not just ordinary conversation, a greeting, a question about the assistant, or small talk)? Answer yes only if the user wants the assistant to do work on the character.") }
        for (e in view) q["view:$e"] = buildJsonObject { put("type", "noul"); put("instructions", "To carry out the user command well, does the assistant need to READ the current content of the field \"$e\"? Answer yes only if the command depends on or should stay consistent with that field.") }
        for (r in refCands) q["view:ref:${r.s("id")}"] = buildJsonObject { put("type", "noul"); put("instructions", "Does the assistant need to READ the reference material \"${r.s("name")}\" to carry out the user command well? Answer yes only if the command clearly benefits from it.") }
        for (e in edit) q["edit:$e"] = buildJsonObject { put("type", "noul"); put("instructions", "To carry out the user command, should the assistant MODIFY the field \"$e\" (write, change, add to or delete from it)? Answer yes only if the command explicitly or clearly implies changing this field.") }
        val t0 = System.currentTimeMillis()
        val timeout = minOf(SettingsStore.root.d("requestTimeoutMs")?.toInt() ?: 180000, 90000)
        val res = Chat.jevSystemOne(p, o.s("jevModel").ifBlank { "jev-latest" }, state, JsonObject(q), timeout, SettingsStore.root.d("retries")?.toInt() ?: 2)
        Logs.add("sub", o.s("jevModel"), p.s("name"), buildJsonObject { put("state", state); put("questionCount", q.size) }, res["answers"], System.currentTimeMillis() - t0)
        val th = o.d("jevThreshold") ?: 0.5
        val v = mutableListOf<String>(); val ed = mutableListOf<String>(); val reasons = mutableMapOf<String, String>(); var instruction = true
        for ((k, a) in res.o("answers").orEmptyObj()) {
            val ao = a as? JsonObject ?: continue
            if (ao.s("type") != "noul") continue
            val score = ao.d("noul") ?: 0.0
            val i = k.indexOf(':'); val kind = k.substring(0, i); val f = k.substring(i + 1)
            reasons[k] = "Jev ${(score * 100).toInt()}%"
            if (kind == "intent") instruction = score >= th
            else if (score >= th) { if (kind == "view") v.add(f) else if (kind == "edit") ed.add(f) }
        }
        return Decision(command, v.filter { !it.startsWith("ref:") }, if (instruction) ed else emptyList(), v.filter { it.startsWith("ref:") }.map { it.removePrefix("ref:") }, instruction, null, reasons)
    }

    private suspend fun llmSub(doc: JsonObject, data: JsonObject, command: String): Decision {
        val o = SettingsStore.root.o("processors").o("sub")
        val model = SettingsStore.model(o.sOrNull("modelId")) ?: throw ProviderError("서브 프로세서 모델이 설정되지 않았습니다. 설정 › 프로세서에서 모델을 지정하세요.")
        val fallback = SettingsStore.model(o.sOrNull("fallbackModelId"))
        val (view, edit) = Perms.candidates(doc.o("permissions").orEmptyObj())
        val refCands = References.subCandidates(doc)
        val system = (listOf(
            "You are the planning stage of a RisuAI character-card editing assistant.",
            "First judge the user's intent: is it an instruction/command to work on the character (instruction=true), or ordinary conversation rather than a command (instruction=false)? For ordinary conversation, return empty view and edit lists.",
            "Given the user's command and the list of character fields, decide which fields the writing stage must READ and which it must MODIFY.",
            "Be minimal: request reading only fields the command depends on, and modifying only fields the command asks to change.",
            "Fields that are always visible or never allowed are omitted from the lists below; do not invent field ids.",
            "", "Fields available to READ:") + view.map { "- " + describe(data, it) } +
            (if (refCands.isEmpty()) emptyList() else listOf("", "Reference materials available to READ (use the ref:<id> value in the 'view' array):") + refCands.map { "- " + References.describe(it) }) +
            listOf("", "Fields available to MODIFY:") + edit.map { "- $it" } +
            listOf("", "Respond with JSON: {\"instruction\": boolean, \"task\": string, \"view\": string[], \"edit\": string[], \"reasons\": {fieldId: string}}")).joinToString("\n")
        val user = "User command (the last message is the one to act on; earlier lines are context only): \"\"\"${subInput(doc, command)}\"\"\""

        suspend fun call(m: JsonObject, maxOverride: Int?): String {
            val base = maxOf(4096, minOf(m.o("params").d("maxTokens")?.toInt() ?: 4096, 16384))
            val th = m.o("thinking")
            val thinking = if (th.b("enabled") || !Regex("^(gpt-5|o[1-9])", RegexOption.IGNORE_CASE).containsMatchIn(m.s("modelId"))) th
                else buildJsonObject { put("enabled", true); put("budget", JsonNull); put("effort", "low") }
            val so = m.s("structuredOutput").ifBlank { "auto" }
            val params = JsonObject(m.o("params").orEmptyObj() + ("maxTokens" to JsonPrimitive(maxOverride ?: base)))
            val req = Chat.buildRequest(m, listOf(Msg("system", system), Msg("user", user)), SUB_SCHEMA, stream = false, params = params, thinking = thinking,
                structuredOutput = if (so == "none") "none" else so, cacheOff = true)
            val t0 = System.currentTimeMillis()
            val r = Chat.chatWithModel(m, req)
            Logs.add("sub", m.s("modelId"), m.s("providerId"), Logs.messagesJson(req.messages), js(r.text), System.currentTimeMillis() - t0, r.usage)
            return if (r.text.isBlank() && r.finishReason in listOf("length", "max_tokens", "MAX_TOKENS") && maxOverride == null) call(m, minOf(2 * base, 32768)) else r.text
        }
        val text = try { call(model, null) } catch (e: CancellationException) { throw e } catch (e: Exception) { if (fallback == null) throw e; call(fallback, null) }
        val d = SguData.extractJson(text) ?: throw ProviderError("서브 프로세서 응답을 해석할 수 없습니다: " + text.take(200))
        fun list(k: String, allowed: Collection<String>) = ((d[k] as? JsonArray) ?: JsonArray(emptyList())).mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }.filter { it in allowed }
        val reasons = mutableMapOf<String, String>()
        d.o("reasons")?.forEach { (k, v) -> val t = (v as? JsonPrimitive)?.content ?: return@forEach; reasons["view:$k"] = t; reasons["edit:$k"] = t }
        val instruction = (d["instruction"] as? JsonPrimitive)?.booleanOrNull != false
        val v = list("view", view + refCands.map { "ref:" + it.s("id") })
        return Decision(d.sOrNull("task") ?: command, v.filter { !it.startsWith("ref:") }, if (instruction) list("edit", edit) else emptyList(),
            v.filter { it.startsWith("ref:") }.map { it.removePrefix("ref:") }, instruction, null, reasons)
    }

    /** Session summary: the sub processor's model when it is an LLM, otherwise the main model. */
    suspend fun summarize(previous: String?, turns: List<JsonObject>): String {
        val sub = SettingsStore.root.o("processors").o("sub")
        val subModel = if (!sub.b("disabled") && sub.s("strategy") != "jev") SettingsStore.model(sub.sOrNull("modelId")) else null
        val m = subModel ?: SettingsStore.model(SettingsStore.root.o("processors").o("main").sOrNull("modelId"))
            ?: throw ProviderError("요약에 쓸 모델이 없습니다. 설정 › 프로세서에서 모델을 지정하세요.")
        val system = listOf(
            "You keep a running summary of a session in which a user edits a RisuAI character card with an AI assistant.",
            "Merge the previous summary (if any) with the new turns into ONE short summary.",
            "Keep only what later turns need: the user's goals, decisions, preferences and constraints, and which fields were changed and how.",
            "Do not copy field contents (the card itself is always sent separately). Drop small talk.",
            "At most 10 short bullet points, under 1,200 characters. Write in the language the user writes in. Output only the summary.",
        ).joinToString("\n")
        val lines = turns.mapIndexed { i, t ->
            val ed = t.arr("edits").takeIf { it.isNotEmpty() }?.joinToString(", ") { "${it.s("field")}:${it.s("op")}" } ?: "no edits"
            "${i + 1}. User: ${t.s("command")}\n   Result: ${t.s("response").take(600)} [$ed]"
        }
        val user = (if (!previous.isNullOrBlank()) "Previous summary:\n$previous\n\n" else "") + "New turns:\n" + lines.joinToString("\n")
        val params = JsonObject(m.o("params").orEmptyObj() + ("maxTokens" to JsonPrimitive(maxOf(2048, minOf(m.o("params").d("maxTokens")?.toInt() ?: 2048, 8192)))))
        val req = Chat.buildRequest(m, listOf(Msg("system", system), Msg("user", user)), stream = false, params = params, cacheOff = true)
        val t0 = System.currentTimeMillis()
        val r = Chat.chatWithModel(m, req)
        Logs.add("sub", m.s("modelId"), m.s("providerId"), Logs.messagesJson(req.messages), js(r.text), System.currentTimeMillis() - t0, r.usage)
        val text = r.text.trim()
        if (text.isEmpty()) throw ProviderError("요약 응답이 비어 있습니다.")
        return text
    }

    data class MainOut(val message: String, val edits: List<JsonObject>, val raw: String, val usage: Usage?, val model: JsonObject, val prompt: Assembled)

    suspend fun runMain(doc: JsonObject, data: JsonObject, command: String, access: Access, instruction: Boolean, cb: Stream, onRestart: () -> Unit): MainOut {
        val (primary, fallback) = mainModels(doc)
        if (primary == null) throw ProviderError("메인 프로세서 모델이 설정되지 않았습니다. 설정 › 프로세서에서 모델을 지정하세요.")
        suspend fun run(m: JsonObject, appSearch: Boolean): MainOut {
            val web = doc.b("webSearch")
            val native = web && !appSearch && WebSearch.hasNative(SettingsStore.provider(m.s("providerId")), m.s("modelId"))
            val refs = References.loadForPrompt(doc, access.viewRefs, Chat.effectiveVision(m)).toMutableList()
            if (web && !native) refs.add(RefText("Web search results", null, "text", WebSearch.context(command)))
            val prompt = Prompt.assemble(Prompt.Input(data, PromptBlocks.blocksFor(doc), access.viewFields, access.editFields, command, refs,
                Workshop.historyTurns(doc), instruction, m.o("params").d("maxContext")?.toInt() ?: 150000, summary = Workshop.summaryText(doc)))
            val req0 = Chat.buildRequest(m, prompt.messages, SguData.MAIN_OUTPUT_SCHEMA, webSearch = native)
            val req = req0.copy(cache = req0.cache?.copy(key = doc.s("id").ifBlank { null }))
            val t0 = System.currentTimeMillis()
            val r = try { Chat.chatWithModel(m, req, cb) } catch (e: CancellationException) { throw e } catch (e: Exception) {
                if (native && Regex("tool|search|unsupported|invalid|400", RegexOption.IGNORE_CASE).containsMatchIn(e.message ?: "")) {
                    Logs.add("error", m.s("modelId"), error = "네이티브 검색 실패, 앱 검색으로 재시도: ${e.message}")
                    onRestart(); return run(m, true)
                }
                throw e
            }
            Logs.add("main", m.s("modelId"), m.s("providerId"), Logs.messagesJson(req.messages), js(r.text), System.currentTimeMillis() - t0, r.usage)
            if (r.finishReason in listOf("length", "max_tokens", "MAX_TOKENS")) {
                val msg = "최대 출력 토큰(${m.o("params").d("maxTokens")?.toInt() ?: "기본값"})에 도달해 응답이 잘렸습니다. 설정 › 모델에서 최대 토큰을 늘리거나 명령 범위를 줄이세요."
                if (r.text.isBlank() || SguData.extractJson(r.text) == null) throw ProviderError(msg)
            }
            val j = SguData.extractJson(r.text) ?: return MainOut(r.text.trim(), emptyList(), r.text, r.usage, m, prompt)
            val edits = ((j["edits"] as? JsonArray) ?: JsonArray(emptyList())).mapNotNull { it as? JsonObject }
                .filter { (it["field"] as? JsonPrimitive)?.isString == true && (it["op"] as? JsonPrimitive)?.isString == true }
            return MainOut((j["message"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: "", edits, r.text, r.usage, m, prompt)
        }
        return try { run(primary, false) } catch (e: CancellationException) { throw e } catch (e: Exception) {
            if (fallback == null) throw e
            Logs.add("error", primary.s("modelId"), error = "메인 모델 실패, 폴백 시도: ${e.message}")
            onRestart(); run(fallback, false)
        }
    }

    /** What the main processor would receive right now (프롬프트 미리보기, module 1694). */
    fun preview(doc: JsonObject, data: JsonObject, command: String): Assembled {
        val perms = doc.o("permissions").orEmptyObj()
        val view = WFields.all.filter { it.viewable && Perms.view(perms.o(it.id)) == 3 }.map { it.id }
        return Prompt.assemble(Prompt.Input(data, PromptBlocks.blocksFor(doc), view, emptyList(), command.ifBlank { "(명령)" }, emptyList(),
            Workshop.historyTurns(doc), true, Processors.mainModels(doc).first?.o("params")?.d("maxContext")?.toInt(), summary = Workshop.summaryText(doc)))
    }
}

fun JsonObject?.orEmptyObj(): JsonObject = this ?: JsonObject(emptyMap())

// =====================================================================================
// Workshop docs (per character: permissions, sessions, turns) and jobs (module 910)
// =====================================================================================

data class JobState(
    val charId: String, val turnId: String, val command: String, val phase: String,
    val stream: String = "", val reasoning: String = "", val startedAt: Long = System.currentTimeMillis(),
    val decision: Decision? = null, val access: Access? = null, val error: String? = null,
) {
    val active get() = phase in setOf("analyzing", "awaiting", "generating", "applying")
}

object Workshop {
    val PHASES = mapOf("idle" to "", "analyzing" to "서브 프로세서 분석 중", "awaiting" to "권한 확인 대기", "generating" to "메인 프로세서 작성 중",
        "applying" to "변경 적용 중", "done" to "완료", "error" to "오류")

    private lateinit var appCtx: Context
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val docs = HashMap<String, JsonObject>()
    var version by mutableIntStateOf(0)
        private set
    val jobs = mutableStateMapOf<String, JobState>()
    private val running = HashMap<String, Job>()
    private val approvals = HashMap<String, CompletableDeferred<List<Elevation>>>()
    private val saves = HashMap<String, Job>()
    private var toldSubOff = false

    fun init(ctx: Context) { appCtx = ctx.applicationContext; Logs.init(ctx) }

    private fun file(cid: String) = File(CharacterStore.dir(cid), "workshop.json")

    private fun fresh(cid: String): JsonObject = buildJsonObject {
        put("id", cid); put("permissions", Perms.fromDefaults()); put("promptPresetId", JsonNull); put("mainModelId", JsonNull)
        put("activeReferences", JsonArray(emptyList())); put("refOverrides", JsonObject(emptyMap())); put("refChars", JsonArray(emptyList()))
        put("session", buildJsonObject { put("turns", JsonArray(emptyList())); put("startedAt", System.currentTimeMillis()) })
    }

    fun doc(cid: String): JsonObject = docs.getOrPut(cid) {
        val loaded = runCatching { CardIO.json.parseToJsonElement(file(cid).readText()).jsonObject }.getOrNull()
        if (loaded == null) fresh(cid) else {
            val defaults = Perms.fromDefaults()
            // Interrupted turns (app closed while running) become errors, like a cancelled request.
            val turns = loaded.o("session").arr("turns").map { if (it.s("status") == "running") JsonObject(it + ("status" to js("error")) + ("error" to js("앱이 종료되어 작업이 중단되었습니다"))) else it }
            JsonObject(loaded + ("id" to js(cid)) + ("permissions" to JsonObject(defaults + loaded.o("permissions").orEmptyObj())) +
                ("session" to JsonObject(loaded.o("session").orEmptyObj() + ("turns" to JsonArray(turns)))))
        }
    }

    fun updateDoc(cid: String, change: (JsonObject) -> JsonObject) {
        val next = change(doc(cid))
        docs[cid] = next; version++
        saves[cid]?.cancel()
        saves[cid] = CharacterStore.io.launch { delay(300); runCatching { file(cid).writeText(CardIO.json.encodeToString(JsonElement.serializer(), next)) } }
    }

    fun forget(cid: String) { docs.remove(cid); EditHistory.clear(cid) }

    fun turns(cid: String): List<JsonObject> = doc(cid).o("session").arr("turns")

    private fun patch(o: JsonObject, p: Map<String, JsonElement?>): JsonObject {
        val m = o.toMutableMap(); p.forEach { (k, v) -> if (v == null) m.remove(k) else m[k] = v }; return JsonObject(m)
    }

    fun updateTurn(cid: String, turnId: String, p: Map<String, JsonElement?>) = updateDoc(cid) { d ->
        val s = d.o("session").orEmptyObj()
        if (s.arr("turns").any { it.s("id") == turnId })
            JsonObject(d + ("session" to JsonObject(s + ("turns" to JsonArray(s.arr("turns").map { if (it.s("id") == turnId) patch(it, p) else it })))))
        else {
            // patchTurnAnywhere: a turn in another session slot.
            val slots = d.o("sessionSlots") ?: return@updateDoc d
            JsonObject(d + ("sessionSlots" to JsonObject(slots.mapValues { (_, v) ->
                val slot = v as? JsonObject ?: return@mapValues v
                val ss = slot.o("session").orEmptyObj()
                if (ss.arr("turns").none { it.s("id") == turnId }) slot
                else JsonObject(slot + ("session" to JsonObject(ss + ("turns" to JsonArray(ss.arr("turns").map { if (it.s("id") == turnId) patch(it, p) else it })))))
            })))
        }
    }

    private fun pushTurn(cid: String, t: JsonObject) = updateDoc(cid) { d ->
        val s = d.o("session").orEmptyObj()
        val old = s.arr("turns").map { if (it.containsKey("variants")) JsonObject(it - "variants" - "vi" - "vid") else it }
        JsonObject(d + ("session" to JsonObject(s + ("turns" to JsonArray(old + t)))))
    }

    private fun applyData(cid: String, data: JsonObject) = CharacterStore.updateData(cid) { data }

    private fun toast(t: String) = runCatching { Toast.makeText(appCtx, t, Toast.LENGTH_LONG).show() }

    private fun setJob(cid: String, f: (JobState) -> JobState) { jobs[cid]?.let { jobs[cid] = f(it) } }

    private fun decisionJson(r: Decision): String = jsonText(buildJsonObject {
        put("task", r.task); put("view", JsonArray(r.view.map { js(it) })); put("refs", JsonArray(r.refs.map { js(it) })); put("edit", JsonArray(r.edit.map { js(it) }))
        r.reason?.let { put("reason", it) }; put("instruction", r.instruction)
        put("fieldReasons", JsonObject(r.fieldReasons.mapValues { js(it.value) }))
    })

    fun isActive(cid: String) = jobs[cid]?.active == true

    /** One workshop turn: sub → permission check → main → apply edits (useJobs.start). */
    fun start(cid: String, command: String, rerunTurnId: String? = null) {
        if (isActive(cid)) return
        val doc0 = doc(cid)
        val existing = rerunTurnId?.let { id -> turns(cid).firstOrNull { it.s("id") == id } }
        val turnId = existing?.s("id") ?: Scripts.newId("turn")
        if (existing != null) updateTurn(cid, turnId, mapOf("command" to js(command), "status" to js("running")) +
            listOf("analysis", "elevation", "response", "edits", "snapshot", "error", "usage", "model", "noResponse", "raw", "undone", "redoSnapshot").associateWith { null })
        else pushTurn(cid, buildJsonObject { put("id", turnId); put("createdAt", System.currentTimeMillis()); put("command", command); put("status", "running") })
        val subOff = Processors.isSubOff()
        jobs[cid] = JobState(cid, turnId, command, if (subOff) "generating" else "analyzing")
        running[cid] = scope.launch {
            try {
                if (subOff && !SettingsStore.root.o("processors").o("sub").b("disabled") && !toldSubOff) { toldSubOff = true; toast("서브 프로세서 없이 세션을 진행합니다") }
                val data0 = SguData.from(cardData(cid))
                val r = if (subOff) Decision(command, emptyList(), emptyList(), emptyList(), true, "서브 프로세서 비활성화")
                    else Processors.runSub(doc(cid), data0, command)
                val rawSub = decisionJson(r)
                if (!subOff) updateTurn(cid, turnId, mapOf("raw" to buildJsonObject { put("sub", rawSub) }))
                var access = Perms.resolve(doc(cid).o("permissions").orEmptyObj(), r, subOff)
                References.resolveAccess(doc(cid), r.refs, r.fieldReasons, subOff, command, turnId).let { ra ->
                    access = access.copy(viewRefs = ra.on, elevation = access.elevation + ra.elevation)
                }
                if (!subOff) updateTurn(cid, turnId, mapOf("analysis" to buildJsonObject {
                    put("task", r.task); put("view", JsonArray((r.view + r.refs.map { "ref:$it" }).map { js(it) })); put("edit", JsonArray(r.edit.map { js(it) })); r.reason?.let { put("reason", it) }
                }))
                setJob(cid) { it.copy(decision = if (subOff) null else r, access = access) }
                var approved = emptyList<Elevation>()
                if (access.elevation.isNotEmpty()) {
                    setJob(cid) { it.copy(phase = "awaiting") }
                    val wait = CompletableDeferred<List<Elevation>>()
                    approvals[cid] = wait
                    approved = wait.await()
                    approvals.remove(cid)
                    if (approved.isNotEmpty()) updateDoc(cid) { d -> JsonObject(d + ("permissions" to Perms.applyElevation(d.o("permissions").orEmptyObj(), approved))) }
                    val ok = approved.map { "${it.kind}:${it.field}" }.toSet()
                    updateTurn(cid, turnId, mapOf("elevation" to buildJsonObject {
                        put("approved", JsonArray(ok.map { js(it) }))
                        put("denied", JsonArray(access.elevation.map { "${it.kind}:${it.field}" }.filter { it !in ok }.map { js(it) }))
                    }))
                }
                access = Perms.finalize(access, approved)
                setJob(cid) { it.copy(access = access, phase = "generating", stream = "", reasoning = "") }
                val out = Processors.runMain(doc(cid), SguData.from(cardData(cid)), command, access, r.instruction,
                    Stream(onDelta = { d -> setJob(cid) { it.copy(stream = it.stream + d) } }, onReasoning = { d -> setJob(cid) { it.copy(reasoning = it.reasoning + d) } }),
                    onRestart = { setJob(cid) { it.copy(stream = "", reasoning = "") } })
                setJob(cid) { it.copy(phase = "applying") }
                val before = cardData(cid)
                val res = Edits.apply(SguData.from(before), out.edits, access.editFields)
                val snapshot = SguData.snapshotOf(before, res.changed)
                if (res.changed.isNotEmpty()) EditHistory.tagged(cid, turnId) { applyData(cid, SguData.apply(before, res.data, res.changed)) }
                updateTurn(cid, turnId, mapOf(
                    "status" to js("done"), "response" to js(out.message),
                    "edits" to JsonArray(res.applied.map { buildJsonObject { put("field", it.field); put("op", it.op); put("summary", it.summary) } }),
                    "snapshot" to snapshot,
                    "usage" to out.usage?.let { u -> buildJsonObject { u.inputTokens?.let { put("inputTokens", it) }; u.outputTokens?.let { put("outputTokens", it) } } },
                    "model" to js(out.model.s("name").ifBlank { out.model.s("modelId") }),
                    "raw" to buildJsonObject { if (!subOff) put("sub", rawSub); put("main", out.raw) },
                ))
                if (res.rejected.isNotEmpty()) Logs.add("error", error = "거부된 편집 ${res.rejected.size}건: " + res.rejected.joinToString(", ") { "${it.first.s("field")}/${it.first.s("op")} (${it.second})" })
                setJob(cid) { it.copy(phase = "done") }
                maybeSummarize(cid)
            } catch (e: Throwable) {
                val cancelled = e is CancellationException
                val msg = if (cancelled) "취소됨" else e.message ?: e.toString()
                val partial = if (cancelled) SguData.partialMessage(jobs[cid]?.stream ?: "").trim() else ""
                updateTurn(cid, turnId, mapOf("status" to js(if (cancelled) "cancelled" else "error"), "error" to js(msg)) + (if (partial.isNotEmpty()) mapOf("response" to js(partial)) else emptyMap()))
                setJob(cid) { it.copy(phase = if (cancelled) "idle" else "error", error = msg) }
                if (!cancelled) Logs.add("error", error = msg)
            } finally {
                running.remove(cid); approvals.remove(cid)
            }
        }
    }

    // ---------- 세션 요약 (SGU Next) ----------
    // Done turns that fell out of the recent window are folded into a short running summary (session.summary =
    // {text, covered:[turnIds], at}); the history block then sends the summary + the recent turns.

    val summarizing = androidx.compose.runtime.mutableStateMapOf<String, Boolean>()
    private fun summarySettings() = SettingsStore.root.o("sessionSummary").orEmptyObj()
    fun summaryEnabled() = summarySettings().b("enabled", true)
    fun summaryEvery() = (summarySettings().d("everyTurns")?.toInt() ?: 6).coerceIn(2, 50)
    fun summaryKeep() = (summarySettings().d("keepRecent")?.toInt() ?: 6).coerceIn(1, 50)

    fun summary(doc: JsonObject): JsonObject? = doc.o("session").o("summary")
    fun summaryText(doc: JsonObject): String? = summary(doc)?.s("text")?.takeIf { it.isNotBlank() }
    private fun covered(doc: JsonObject) = (summary(doc)?.get("covered") as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content }?.toSet() ?: emptySet()
    fun historyTurns(doc: JsonObject): List<JsonObject> { val c = covered(doc); return doc.o("session").arr("turns").filter { it.s("id") !in c } }
    private fun foldable(doc: JsonObject, keep: Int) = historyTurns(doc).filter { it.s("status") == "done" && !it.b("noResponse") }.dropLast(keep)

    private fun setSummary(cid: String, sum: JsonObject?) = updateDoc(cid) { d ->
        val sess = d.o("session").orEmptyObj()
        JsonObject(d + ("session" to if (sum == null) JsonObject(sess - "summary") else JsonObject(sess + ("summary" to sum))))
    }

    fun setSummaryText(cid: String, text: String) {
        val cur = summary(doc(cid))
        if (text.isBlank() && cur == null) return
        setSummary(cid, buildJsonObject { put("text", text.trim()); put("covered", cur?.get("covered") ?: JsonArray(emptyList())); put("at", System.currentTimeMillis()); put("edited", true) })
    }

    /** Forget the summary: every turn goes back to the normal history. */
    fun clearSummary(cid: String) = setSummary(cid, null)

    private fun maybeSummarize(cid: String) {
        if (!summaryEnabled() || summarizing[cid] == true) return
        if (foldable(doc(cid), summaryKeep()).size >= summaryEvery()) summarizeNow(cid)
    }

    /** Folds every done turn except the recent ones into the summary (also the "지금 요약" button). */
    fun summarizeNow(cid: String, keep: Int = summaryKeep()) {
        if (summarizing[cid] == true) return
        val turns = foldable(doc(cid), keep)
        if (turns.isEmpty()) { toast("요약할 이전 턴이 없어요"); return }
        summarizing[cid] = true
        scope.launch {
            try {
                val text = Processors.summarize(summaryText(doc(cid)), turns)
                val ids = covered(doc(cid)) + turns.map { it.s("id") }
                setSummary(cid, buildJsonObject { put("text", text.trim()); put("covered", JsonArray(ids.map { JsonPrimitive(it) })); put("at", System.currentTimeMillis()) })
            } catch (e: CancellationException) { throw e } catch (e: Throwable) {
                Logs.add("error", error = "세션 요약 실패: ${e.message}")
                toast("세션 요약에 실패했어요 · 요청 로그 확인")
            } finally { summarizing.remove(cid) }
        }
    }

    fun approve(cid: String, list: List<Elevation>) { approvals[cid]?.complete(list) }
    fun cancel(cid: String) { approvals[cid]?.cancel(); running[cid]?.cancel() }
    fun dismiss(cid: String) { jobs.remove(cid) }

    // ---------- turn-level undo / variants ----------

    private fun hasResult(t: JsonObject) = t.s("status") == "done" && !t.b("noResponse") && (t.s("response").isNotEmpty() || t.arr("edits").isNotEmpty())
    private fun hasSnapshot(t: JsonObject) = t.s("status") == "done" && (t.o("snapshot")?.isNotEmpty() == true)

    fun undoCandidate(cid: String): JsonObject? = turns(cid).lastOrNull { hasSnapshot(it) && !it.b("undone") }

    private fun mergedSnapshot(snap: JsonObject?, variants: List<JsonObject>): JsonObject {
        val m = LinkedHashMap<String, JsonElement>()
        for (v in variants) v.o("snapshot")?.forEach { (k, x) -> if (k !in m) m[k] = x }
        snap?.forEach { (k, x) -> m[k] = x }
        return JsonObject(m)
    }

    private fun variantList(t: JsonObject): Pair<MutableList<JsonObject>, String> {
        val list = t.arr("variants").map { if (it.s("id").isEmpty()) JsonObject(it + ("id" to js(Scripts.newId("var")))) else it }.toMutableList()
        val vid = t.sOrNull("vid") ?: t.d("vi")?.toInt()?.let { list.getOrNull(it)?.s("id") } ?: Scripts.newId("var")
        return list to vid
    }

    private val VARIANT_KEYS = listOf("model", "analysis", "elevation", "response", "edits", "raw", "snapshot", "undone", "redoSnapshot", "status", "error", "usage")
    private fun asVariant(t: JsonObject, id: String) = JsonObject(mapOf("id" to js(id)) + VARIANT_KEYS.mapNotNull { k -> t[k]?.let { k to it } })

    fun reroll(cid: String, turnId: String) {
        if (isActive(cid)) return
        val t = turns(cid).firstOrNull { it.s("id") == turnId } ?: return
        val data = cardData(cid)
        val (list, vid) = variantList(t)
        var redo = t.o("redoSnapshot")
        val snap = t.o("snapshot")
        if (snap != null && !t.b("undone")) redo = SguData.snapshotOf(data, snap.keys)
        val u = list.indexOfFirst { it.s("id") == vid }
        if (hasResult(t)) { val v = JsonObject(asVariant(t, vid) + ("redoSnapshot" to (redo ?: JsonNull))); if (u >= 0) list[u] = v else list.add(v) }
        else if (u >= 0) list.removeAt(u)
        val nv = Scripts.newId("var")
        list.add(buildJsonObject { put("id", nv) })
        updateTurn(cid, turnId, mapOf("variants" to JsonArray(list), "vi" to JsonPrimitive(list.size - 1), "vid" to js(nv)))
        val merged = mergedSnapshot(snap, list)
        if (merged.isNotEmpty()) EditHistory.removeTurnEdits(cid, turnId, merged)
        if (snap != null && !t.b("undone")) EditHistory.silently { applyData(cid, SguData.restore(data, snap)) }
        start(cid, t.s("command"), turnId)
    }

    fun switchVariant(cid: String, turnId: String, index: Int) {
        if (isActive(cid)) return
        val t = turns(cid).firstOrNull { it.s("id") == turnId } ?: return
        if (t.arr("variants").isEmpty()) return
        val (list, vid) = variantList(t)
        val target = list.getOrNull(index)?.s("id") ?: return
        if (target == vid) return
        var data = cardData(cid)
        var redo = t.o("redoSnapshot")
        val applied = !t.b("undone")
        val snap = t.o("snapshot")
        if (snap != null && applied) { redo = SguData.snapshotOf(data, snap.keys); data = SguData.restore(data, snap) }
        val d = list.indexOfFirst { it.s("id") == vid }
        if (hasResult(t)) { val v = JsonObject(asVariant(t, vid) + ("redoSnapshot" to (redo ?: JsonNull))); if (d >= 0) list[d] = v else list.add(v) }
        else if (d >= 0) list.removeAt(d)
        val u = list.firstOrNull { it.s("id") == target } ?: return
        if (applied && u.o("snapshot") != null && u.o("redoSnapshot") != null) data = SguData.restore(data, u.o("redoSnapshot")!!)
        EditHistory.silently { applyData(cid, data) }
        updateTurn(cid, turnId, mapOf(
            "analysis" to u["analysis"], "elevation" to u["elevation"], "response" to u["response"], "edits" to u["edits"], "raw" to u["raw"],
            "snapshot" to u["snapshot"], "undone" to JsonPrimitive(!applied), "redoSnapshot" to if (!applied) u["redoSnapshot"] else null,
            "status" to (u["status"] ?: js("done")), "error" to u["error"], "usage" to u["usage"], "model" to u["model"], "noResponse" to null,
            "variants" to JsonArray(list), "vi" to JsonPrimitive(maxOf(0, list.indexOfFirst { it.s("id") == target })), "vid" to js(target),
        ))
        EditHistory.retargetTurn(cid, turnId, JsonObject(mergedSnapshot(u.o("snapshot"), list) + u.o("redoSnapshot").orEmptyObj()))
    }

    fun undoTurn(cid: String, turnId: String) {
        val t = turns(cid).firstOrNull { it.s("id") == turnId } ?: return
        val snap = t.o("snapshot") ?: return
        val data = cardData(cid)
        val redo = SguData.snapshotOf(data, snap.keys)
        applyData(cid, SguData.restore(data, snap))
        updateTurn(cid, turnId, mapOf("undone" to JsonPrimitive(true), "redoSnapshot" to redo))
    }

    /** Top-bar ← (direct edits included). */
    fun undoLast(cid: String) {
        val before = cardData(cid)
        val step = EditHistory.undo(cid) ?: return
        EditHistory.silently { applyData(cid, step.data) }
        val tid = step.turnId ?: return
        val t = turns(cid).firstOrNull { it.s("id") == tid }
        val redo = t?.o("snapshot")?.let { SguData.snapshotOf(before, it.keys) }
        updateTurn(cid, tid, mapOf("undone" to JsonPrimitive(true)) + (if (redo != null) mapOf("redoSnapshot" to redo) else emptyMap()))
    }

    fun redoNext(cid: String) {
        val step = EditHistory.redo(cid) ?: return
        EditHistory.silently { applyData(cid, step.data) }
        step.turnId?.let { updateTurn(cid, it, mapOf("undone" to JsonPrimitive(false))) }
    }

    /** 메시지 삭제: "only" or "after", from the command or only the response (deleteTurns). */
    fun deleteTurns(cid: String, turnId: String, mode: String, what: String = "command") {
        val list = turns(cid)
        val c = list.indexOfFirst { it.s("id") == turnId }; if (c < 0) return
        val respOnly = what == "response"
        val targets = if (mode == "after") list.subList(c, list.size) else listOf(list[c])
        var data = cardData(cid)
        for (t in targets.reversed()) {
            val merged = mergedSnapshot(t.o("snapshot"), t.arr("variants"))
            if (merged.isNotEmpty()) EditHistory.removeTurnEdits(cid, t.s("id"), merged)
            val snap = t.o("snapshot")
            if (snap != null && !t.b("undone")) data = SguData.restore(data, snap)
        }
        EditHistory.silently { applyData(cid, data) }
        updateDoc(cid) { d ->
            val s = d.o("session").orEmptyObj(); val ts = s.arr("turns")
            val next = if (respOnly) ts.take(if (mode == "after") c + 1 else ts.size).map {
                if (it.s("id") == turnId) patch(it, mapOf("noResponse" to JsonPrimitive(true), "status" to js("done")) +
                    listOf("response", "error", "analysis", "elevation", "edits", "raw", "snapshot", "undone", "redoSnapshot", "usage", "variants", "vi").associateWith { null }) else it
            } else if (mode == "after") ts.take(c) else ts.filter { it.s("id") != turnId }
            JsonObject(d + ("session" to JsonObject(s + ("turns" to JsonArray(next)))))
        }
    }

    // ---------- sessions (module 908) ----------

    private val SLOT_KEYS = listOf("session", "permissions", "activeReferences", "refOverrides", "refChars", "attachedUploads", "webSearch", "mainModelId", "promptPresetId")
    fun sessionList(d: JsonObject): List<JsonObject> = d.arr("sessionList").ifEmpty { listOf(buildJsonObject { put("id", "s1"); put("name", "세션 1") }) }
    fun currentSessionId(d: JsonObject) = d.sOrNull("sessionId") ?: sessionList(d)[0].s("id")
    fun currentSessionName(d: JsonObject) = sessionList(d).firstOrNull { it.s("id") == currentSessionId(d) }?.s("name") ?: "세션"
    private fun slotOf(d: JsonObject) = JsonObject(SLOT_KEYS.mapNotNull { k -> d[k]?.let { k to it } }.toMap())
    private fun withSlot(d: JsonObject, slot: JsonObject): JsonObject {
        val m = d.toMutableMap(); SLOT_KEYS.forEach { m.remove(it) }; m.putAll(slot)
        if ("activeReferences" !in m) m["activeReferences"] = JsonArray(emptyList())
        return JsonObject(m)
    }
    fun sessionTurnCount(d: JsonObject, sid: String) = if (sid == currentSessionId(d)) d.o("session").arr("turns").size else d.o("sessionSlots").o(sid).o("session").arr("turns").size

    fun addSession(cid: String) = updateDoc(cid) { n ->
        val list = sessionList(n); val cur = currentSessionId(n)
        val slots = JsonObject(n.o("sessionSlots").orEmptyObj() + (cur to slotOf(n)))
        var i = list.size + 1; while (list.any { it.s("name") == "세션 $i" }) i++
        val id = Scripts.newId("ses")
        val perms = JsonObject(n.o("permissions").orEmptyObj().mapValues { (_, v) ->
            val o = v as? JsonObject ?: return@mapValues v
            buildJsonObject { put("view", buildJsonObject { put("default", o.o("view")?.get("default") ?: JsonPrimitive(0)) }); put("edit", buildJsonObject { put("default", o.o("edit")?.get("default") ?: JsonPrimitive(0)) }) }
        })
        val slot = buildJsonObject {
            put("session", buildJsonObject { put("turns", JsonArray(emptyList())); put("startedAt", System.currentTimeMillis()) })
            put("permissions", perms); put("activeReferences", JsonArray(emptyList()))
            put("mainModelId", n["mainModelId"] ?: JsonNull); put("promptPresetId", n["promptPresetId"] ?: JsonNull)
        }
        JsonObject(withSlot(n, slot) + ("sessionList" to JsonArray(list + buildJsonObject { put("id", id); put("name", "세션 $i") })) + ("sessionId" to js(id)) + ("sessionSlots" to slots))
    }

    fun switchSession(cid: String, sid: String) = updateDoc(cid) { s ->
        val cur = currentSessionId(s); if (sid == cur) return@updateDoc s
        val target = s.o("sessionSlots").o(sid) ?: return@updateDoc s
        val slots = (s.o("sessionSlots").orEmptyObj() + (cur to slotOf(s))).toMutableMap(); slots.remove(sid)
        JsonObject(withSlot(s, target) + ("sessionList" to JsonArray(sessionList(s))) + ("sessionId" to js(sid)) + ("sessionSlots" to JsonObject(slots)))
    }

    fun renameSession(cid: String, sid: String, name: String) = updateDoc(cid) { s ->
        val n = name.trim(); if (n.isEmpty()) s
        else JsonObject(s + ("sessionList" to JsonArray(sessionList(s).map { if (it.s("id") == sid) JsonObject(it + ("name" to js(n))) else it })) + ("sessionId" to js(currentSessionId(s))))
    }

    fun removeSession(cid: String, sid: String) = updateDoc(cid) { s ->
        val list = sessionList(s); if (list.size <= 1) return@updateDoc s
        val cur = currentSessionId(s); val rest = list.filter { it.s("id") != sid }
        if (sid != cur) {
            JsonObject(s + ("sessionList" to JsonArray(rest)) + ("sessionId" to js(cur)) + ("sessionSlots" to JsonObject(s.o("sessionSlots").orEmptyObj() - sid)))
        } else {
            val p = list.indexOfFirst { it.s("id") == sid }; val next = rest[minOf(p, rest.size - 1)]
            val slots = s.o("sessionSlots").orEmptyObj().toMutableMap(); val h = slots.remove(next.s("id")) as? JsonObject
            JsonObject((if (h != null) withSlot(s, h) else s) + ("sessionList" to JsonArray(rest)) + ("sessionId" to js(next.s("id"))) + ("sessionSlots" to JsonObject(slots)))
        }
    }

    fun setPermission(cid: String, field: String, kind: String, level: Int, session: Boolean) = updateDoc(cid) { d ->
        val perms = d.o("permissions").orEmptyObj(); val p = perms.o(field).orEmptyObj(); val k = p.o(kind).orEmptyObj()
        val nk = if (session) JsonObject(k + ("session" to JsonPrimitive(level))) else JsonObject(k + ("default" to JsonPrimitive(level)) - "session")
        JsonObject(d + ("permissions" to JsonObject(perms + (field to JsonObject(p + (kind to nk))))))
    }

    fun clearSessionPermission(cid: String, field: String, kind: String) = updateDoc(cid) { d ->
        val perms = d.o("permissions").orEmptyObj(); val p = perms.o(field).orEmptyObj()
        JsonObject(d + ("permissions" to JsonObject(perms + (field to JsonObject(p + (kind to JsonObject(p.o(kind).orEmptyObj() - "session")))))))
    }

    fun set(cid: String, key: String, value: JsonElement?) = updateDoc(cid) { d -> if (value == null) JsonObject(d - key) else JsonObject(d + (key to value)) }

    @Suppress("unused") private fun unused(x: Any?) = (x as? JsonPrimitive)?.doubleOrNull
}
