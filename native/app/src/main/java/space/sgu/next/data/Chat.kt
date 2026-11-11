package space.sgu.next.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
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
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** One message for a chat request (original ChatMessage: string content or text/image parts). */
data class Part(val text: String? = null, val mime: String? = null, val base64: String? = null) {
    val isText get() = text != null
}

data class Msg(val role: String, val text: String? = null, val parts: List<Part>? = null, var cacheAfter: Boolean = false) {
    val plain: String get() = text ?: parts.orEmpty().filter { it.isText }.joinToString("\n") { it.text ?: "" }
    val partsOrText: List<Part> get() = parts ?: listOf(Part(text = text ?: ""))
}

data class JsonSchema(val name: String, val schema: JsonObject)
data class CacheOpt(val mode: String, val ttl: String, var target: String = "other", var key: String? = null)

data class ChatReq(
    val messages: List<Msg>,
    val params: JsonObject?,
    val stream: Boolean,
    val thinking: JsonObject?,
    val structuredOutput: String,
    val jsonSchema: JsonSchema? = null,
    val cache: CacheOpt? = null,
    val webSearch: Boolean = false,
    val timeoutMs: Int = 180_000,
)

data class Usage(val inputTokens: Int?, val outputTokens: Int?)
data class ChatResult(val text: String, val reasoning: String?, val usage: Usage?, val finishReason: String?)

class Stream(val onDelta: (String) -> Unit = {}, val onReasoning: (String) -> Unit = {})

/** Chat requests in the four original formats (modules 954 anthropic, 955 gemini, 956 openai-chat, 957 responses). */
object Chat {
    private fun js(o: JsonElement) = CardIO.json.encodeToString(JsonElement.serializer(), o)
    private fun JsonObject?.num(k: String): Double? = ((this?.get(k)) as? JsonPrimitive)?.doubleOrNull
    private fun JsonObject?.int(k: String): Int? = ((this?.get(k)) as? JsonPrimitive)?.let { it.intOrNull ?: it.doubleOrNull?.toInt() }
    private fun JsonObject?.bool(k: String): Boolean = ((this?.get(k)) as? JsonPrimitive)?.booleanOrNull == true
    private fun parse(text: String): JsonObject? = runCatching { CardIO.json.parseToJsonElement(text) as? JsonObject }.getOrNull()
    private fun putNum(m: MutableMap<String, JsonElement>, k: String, v: Double?) { if (v != null) m[k] = if (v == Math.floor(v) && kotlin.math.abs(v) < 1e12) JsonPrimitive(v.toLong()) else JsonPrimitive(v) }

    // ---------- cache (module 946) ----------

    fun cacheTargetFor(baseUrl: String, format: String): String {
        val host = runCatching { URL(baseUrl).host }.getOrDefault("")
        return when {
            format == "anthropic" -> "anthropic"
            format == "gemini" -> "gemini"
            host == "api.openai.com" -> "openai"
            host.endsWith("openrouter.ai") -> "openrouter"
            else -> "other"
        }
    }

    fun effectiveCache(model: JsonObject, provider: JsonObject?): Pair<String, String> {
        val c = model.o("cache")
        if (c != null && c.s("mode").isNotBlank() && c.s("mode") != "inherit") return c.s("mode") to c.s("ttl").ifBlank { "5m" }
        val p = provider.o("cache")
        return (p.s("mode").ifBlank { "off" }) to (p.s("ttl").ifBlank { "5m" })
    }

    fun planBreakpoints(msgs: List<Msg>, mode: String): Set<Int> {
        if (mode == "off") return emptySet()
        val o = sortedSetOf<Int>()
        msgs.forEachIndexed { i, m -> if (m.cacheAfter) o.add(i) }
        if (mode == "auto") {
            val lastSystem = msgs.indexOfLast { it.role == "system" }
            if (lastSystem >= 0) o.add(lastSystem)
            if (msgs.size >= 3) o.add(msgs.size - 2)
        }
        return o.toList().takeLast(4).toSet()
    }

    private fun ephemeral(ttl: String) = if (ttl == "1h") buildJsonObject { put("type", "ephemeral"); put("ttl", "1h") } else buildJsonObject { put("type", "ephemeral") }

    private fun openAiCacheParams(r: ChatReq): Map<String, JsonElement> {
        val c = r.cache ?: return emptyMap()
        if (c.mode == "off" || c.target != "openai") return emptyMap()
        val m = mutableMapOf<String, JsonElement>()
        c.key?.let { m["prompt_cache_key"] = JsonPrimitive(it) }
        if (c.ttl == "1h") m["prompt_cache_retention"] = JsonPrimitive("24h")
        return m
    }

    // ---------- request building (buildChatRequest, module 945) ----------

    fun buildRequest(
        model: JsonObject, messages: List<Msg>, jsonSchema: JsonSchema? = null, webSearch: Boolean = false,
        stream: Boolean? = null, params: JsonObject? = null, thinking: JsonObject? = null, structuredOutput: String? = null,
        cache: CacheOpt? = null, cacheOff: Boolean = false,
    ): ChatReq {
        val provider = SettingsStore.provider(model.s("providerId"))
        val (mode, ttl) = effectiveCache(model, provider)
        return ChatReq(
            messages = messages,
            params = params ?: model.o("params"),
            stream = stream ?: model.b("stream", true),
            thinking = thinking ?: model.o("thinking"),
            structuredOutput = structuredOutput ?: model.s("structuredOutput").ifBlank { "auto" },
            jsonSchema = jsonSchema,
            cache = if (cacheOff) null else cache ?: if (mode == "off") null else CacheOpt(mode, ttl),
            webSearch = webSearch,
            timeoutMs = SettingsStore.root.d("requestTimeoutMs")?.toInt() ?: 180_000,
        )
    }

    suspend fun chatWithModel(model: JsonObject, req0: ChatReq, cb: Stream = Stream()): ChatResult {
        val p = SettingsStore.provider(model.s("providerId")) ?: throw ProviderError("모델에 연결된 프로바이더가 없습니다.")
        val r = Llm.resolve(p)
        if (r.auth != "none" && r.apiKey == null) throw ProviderError("'${p.s("name")}'에 API 키가 설정되지 않았습니다.")
        val retries = SettingsStore.root.d("retries")?.toInt() ?: 2
        val req = req0.copy(cache = req0.cache?.copy(target = cacheTargetFor(r.baseUrl, p.s("format"))))
        val id = model.s("modelId")
        return when (p.s("format")) {
            "openai-chat" -> openAiChat(r, id, req, cb, retries)
            "openai-responses" -> responses(r, id, req, cb, retries)
            "anthropic" -> anthropic(r, id, req, cb, retries, null, emptySet())
            "gemini" -> gemini(r, id, req, cb, retries)
            "novelai-image" -> throw ProviderError("NovelAI(image)는 이미지 생성 전용입니다. 이미지 탭 › 이미지 생성에서 사용하세요.")
            "jev" -> throw ProviderError("Jev는 채팅 모델이 아닙니다. 서브 프로세서 전략을 'Jev'로 설정해 사용하세요.")
            else -> throw ProviderError("지원하지 않는 포맷: ${p.s("format")}")
        }
    }

    // ---------- HTTP + SSE (module 936) ----------

    class Response(val code: Int, val body: String?, val conn: HttpURLConnection?, val isStream: Boolean)

    /** Opens the request; on 2xx returns the open connection when the server streams, else the body. */
    private suspend fun open(url: String, headers: Map<String, String>, body: JsonObject, timeoutMs: Int, retries: Int): Response = withContext(Dispatchers.IO) {
        var attempt = 0
        while (true) {
            currentCoroutineContext().ensureActive()
            val c = URL(url).openConnection() as HttpURLConnection
            val job = currentCoroutineContext()[Job]
            val watch = job?.invokeOnCompletion { if (it != null) runCatching { c.disconnect() } }
            try {
                c.requestMethod = "POST"; c.connectTimeout = 20_000; c.readTimeout = timeoutMs
                c.setRequestProperty("Content-Type", "application/json")
                c.setRequestProperty("Accept", "application/json, text/event-stream")
                headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
                c.doOutput = true
                c.outputStream.use { it.write(js(body).toByteArray(Charsets.UTF_8)) }
                val code = c.responseCode
                if (code in 200..299) {
                    val stream = (c.contentType ?: "").lowercase().contains("text/event-stream")
                    if (stream) return@withContext Response(code, null, c, true)
                    val t = c.inputStream.bufferedReader().use { it.readText() }
                    c.disconnect(); watch?.dispose()
                    return@withContext Response(code, t, null, false)
                }
                val t = c.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
                c.disconnect(); watch?.dispose()
                val retryable = code == 429 || code == 408 || code >= 500
                if (retryable && attempt < retries) { attempt++; delay(minOf(8000L, 1000L * (1 shl attempt))); continue }
                throw ProviderError(explain(code, t, url), code).also { it.body = t }
            } catch (e: ProviderError) {
                throw e
            } catch (e: java.net.SocketTimeoutException) {
                c.disconnect(); watch?.dispose()
                currentCoroutineContext().ensureActive()
                if (attempt < retries) { attempt++; delay(minOf(8000L, 1000L * (1 shl attempt))); continue }
                throw ProviderError("응답 시간이 초과됐어요 (${timeoutMs / 1000}초)")
            } catch (e: java.io.IOException) {
                c.disconnect(); watch?.dispose()
                currentCoroutineContext().ensureActive()
                if (attempt < retries) { attempt++; delay(minOf(8000L, 1000L * (1 shl attempt))); continue }
                throw ProviderError("네트워크 오류: ${e.message ?: e.javaClass.simpleName}")
            }
        }
        @Suppress("UNREACHABLE_CODE") Response(0, null, null, false)
    }

    private var ProviderError.body: String?
        get() = bodies[this]
        set(v) { if (v != null) bodies[this] = v }
    private val bodies = java.util.WeakHashMap<ProviderError, String>()

    private fun explain(code: Int, text: String, url: String): String {
        val msg = runCatching {
            val o = CardIO.json.parseToJsonElement(text) as? JsonObject
            when (val e = o?.get("error") ?: (o?.get("errors") as? JsonArray)?.firstOrNull()) {
                is JsonObject -> e.s("message").ifBlank { e.toString() }
                is JsonPrimitive -> e.contentOrNull
                else -> o.s("message").ifBlank { null }
            }
        }.getOrNull() ?: text.take(300)
        val hint = when (code) {
            401, 403 -> " (API 키나 권한을 확인하세요)"
            404 -> " (주소나 모델 ID를 확인하세요)"
            429 -> " (요청이 너무 많거나 한도를 넘었어요)"
            529 -> " (서버가 과부하 상태예요)"
            else -> ""
        }
        return "HTTP $code: ${msg.trim()}$hint"
    }

    /** Reads `data:` events of an SSE stream until it ends. */
    private suspend fun readSse(c: HttpURLConnection, onData: (String, String?) -> Unit) = withContext(Dispatchers.IO) {
        try {
            c.inputStream.bufferedReader(Charsets.UTF_8).use { rd ->
                val data = StringBuilder(); var event: String? = null; var has = false
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val line = rd.readLine() ?: break
                    if (line.isEmpty()) {
                        if (has) onData(data.toString(), event)
                        data.clear(); event = null; has = false
                        continue
                    }
                    when {
                        line.startsWith(":") -> {}
                        line.startsWith("event:") -> event = line.substring(6).trim()
                        line.startsWith("data:") -> { if (has) data.append('\n'); data.append(line.substring(5).removePrefix(" ")); has = true }
                    }
                }
                if (has) onData(data.toString(), event)
            }
        } catch (e: java.io.IOException) {
            currentCoroutineContext().ensureActive()
            throw ProviderError("스트림이 끊겼어요: ${e.message ?: ""}")
        } finally {
            c.disconnect()
        }
    }

    private fun apiError(o: JsonObject?): String? {
        val e = o?.get("error") ?: (o?.get("errors") as? JsonArray)?.firstOrNull() ?: return null
        return when (e) { is JsonPrimitive -> e.contentOrNull; is JsonObject -> e.s("message").ifBlank { e.toString() }; else -> e.toString() }
    }

    // ---------- OpenAI chat completions (956) ----------

    private fun oaContent(m: Msg, cache: JsonObject?): JsonElement {
        if (m.parts == null && cache == null) return JsonPrimitive(m.text ?: "")
        val parts = m.partsOrText.map { p ->
            if (p.isText) buildJsonObject { put("type", "text"); put("text", p.text) }
            else buildJsonObject { put("type", "image_url"); put("image_url", buildJsonObject { put("url", "data:${p.mime};base64,${p.base64}") }) }
        }.toMutableList()
        if (cache != null) {
            val i = parts.indexOfLast { it.s("type") == "text" }
            if (i >= 0) parts[i] = JsonObject(parts[i] + ("cache_control" to cache))
        }
        return JsonArray(parts)
    }

    fun openAiBody(model: String, r: ChatReq, extra: JsonObject, tokenParam: String): JsonObject {
        val bp = if (r.cache != null && r.cache.mode != "off" && r.cache.target == "openrouter" && Regex("claude|anthropic|gemini", RegexOption.IGNORE_CASE).containsMatchIn(model))
            planBreakpoints(r.messages, r.cache.mode) else emptySet()
        val m = mutableMapOf<String, JsonElement>(
            "model" to JsonPrimitive(model),
            "messages" to JsonArray(r.messages.mapIndexed { i, msg ->
                buildJsonObject { put("role", msg.role); put("content", oaContent(msg, if (i in bp) ephemeral(r.cache!!.ttl) else null)) }
            }),
            "stream" to JsonPrimitive(r.stream),
        )
        val p = r.params
        p.num("maxTokens")?.let { putNum(m, tokenParam, it) }
        putNum(m, "temperature", p.num("temperature")); putNum(m, "top_p", p.num("topP"))
        putNum(m, "frequency_penalty", p.num("frequencyPenalty")); putNum(m, "presence_penalty", p.num("presencePenalty"))
        if (r.thinking.bool("enabled") && r.thinking.s("effort").isNotBlank()) m["reasoning_effort"] = JsonPrimitive(r.thinking.s("effort"))
        if (r.jsonSchema != null && (r.structuredOutput == "auto" || r.structuredOutput == "json_schema")) {
            m["response_format"] = buildJsonObject {
                put("type", "json_schema")
                put("json_schema", buildJsonObject { put("name", r.jsonSchema.name); put("schema", r.jsonSchema.schema); put("strict", false) })
            }
        } else if (r.structuredOutput == "json_object") m["response_format"] = buildJsonObject { put("type", "json_object") }
        if (r.stream) m["stream_options"] = buildJsonObject { put("include_usage", true) }
        m.putAll(openAiCacheParams(r))
        return JsonObject(m + extra)
    }

    private suspend fun openAiChat(rs: Resolved, model: String, r: ChatReq, cb: Stream, retries: Int): ChatResult {
        val url = Llm.withQueryKey(Llm.joinUrl(rs.baseUrl, "chat/completions"), rs)
        var body = openAiBody(model, r, rs.extraBody, rs.tokenParam)
        if (r.webSearch) {
            body = if (Regex("openrouter", RegexOption.IGNORE_CASE).containsMatchIn(rs.baseUrl)) JsonObject(body + ("plugins" to buildJsonArray { add(buildJsonObject { put("id", "web") }) }))
            else if (Regex("search", RegexOption.IGNORE_CASE).containsMatchIn(model)) JsonObject(body + ("web_search_options" to JsonObject(emptyMap()))) else body
        }
        val res = open(url, Llm.authHeaders(rs), body, r.timeoutMs, retries)
        if (!res.isStream) {
            val o = parse(res.body ?: "") ?: throw ProviderError("응답을 해석할 수 없어요: ${res.body?.take(200)}")
            if (r.stream) apiError(o)?.let { if (Regex("stream", RegexOption.IGNORE_CASE).containsMatchIn(it)) return openAiChat(rs, model, r.copy(stream = false), cb, retries) else throw ProviderError(it) }
            val ch = (o["choices"] as? JsonArray)?.firstOrNull() as? JsonObject
            val msg = ch.o("message")
            val text = when (val c = msg?.get("content")) { is JsonPrimitive -> c.contentOrNull ?: ""; is JsonArray -> c.joinToString("") { (it as? JsonObject).s("text") }; else -> "" }
            return ChatResult(text, msg.sOrNull("reasoning_content") ?: msg.sOrNull("reasoning"),
                Usage(o.o("usage").int("prompt_tokens"), o.o("usage").int("completion_tokens")), ch.sOrNull("finish_reason"))
        }
        val text = StringBuilder(); val reasoning = StringBuilder(); var usage: Usage? = null; var finish: String? = null
        readSse(res.conn!!) { d, _ ->
            if (d == "[DONE]") return@readSse
            val o = parse(d) ?: return@readSse
            apiError(o)?.let { throw ProviderError(it) }
            val ch = (o["choices"] as? JsonArray)?.firstOrNull() as? JsonObject
            val delta = ch.o("delta")
            delta.sOrNull("content")?.takeIf { it.isNotEmpty() }?.let { text.append(it); cb.onDelta(it) }
            (delta.sOrNull("reasoning_content") ?: delta.sOrNull("reasoning"))?.takeIf { it.isNotEmpty() }?.let { reasoning.append(it); cb.onReasoning(it) }
            ch.sOrNull("finish_reason")?.let { finish = it }
            o.o("usage")?.let { usage = Usage(it.int("prompt_tokens"), it.int("completion_tokens")) }
        }
        return ChatResult(text.toString(), reasoning.toString().ifEmpty { null }, usage, finish)
    }

    // ---------- OpenAI Responses (957) ----------

    fun responsesBody(model: String, r: ChatReq, extra: JsonObject): JsonObject {
        val instructions = mutableListOf<String>()
        val input = mutableListOf<JsonElement>()
        for (m in r.messages) {
            if (m.role == "system") { instructions.add(m.plain); continue }
            val tType = if (m.role == "assistant") "output_text" else "input_text"
            input.add(buildJsonObject {
                put("role", m.role)
                put("content", JsonArray(m.partsOrText.map { p ->
                    if (p.isText) buildJsonObject { put("type", tType); put("text", p.text) }
                    else buildJsonObject { put("type", "input_image"); put("image_url", "data:${p.mime};base64,${p.base64}") }
                }))
            })
        }
        val o = mutableMapOf<String, JsonElement>("model" to JsonPrimitive(model), "input" to JsonArray(input), "stream" to JsonPrimitive(r.stream))
        val ins = instructions.joinToString("\n\n"); if (ins.isNotEmpty()) o["instructions"] = JsonPrimitive(ins)
        putNum(o, "max_output_tokens", r.params.num("maxTokens")); putNum(o, "temperature", r.params.num("temperature")); putNum(o, "top_p", r.params.num("topP"))
        if (r.thinking.bool("enabled") && r.thinking.s("effort").isNotBlank()) o["reasoning"] = buildJsonObject { put("effort", r.thinking.s("effort")) }
        if (r.webSearch) o["tools"] = buildJsonArray { add(buildJsonObject { put("type", "web_search") }) }
        if (r.jsonSchema != null && (r.structuredOutput == "auto" || r.structuredOutput == "json_schema"))
            o["text"] = buildJsonObject { put("format", buildJsonObject { put("type", "json_schema"); put("name", r.jsonSchema.name); put("schema", r.jsonSchema.schema); put("strict", false) }) }
        else if (r.structuredOutput == "json_object") o["text"] = buildJsonObject { put("format", buildJsonObject { put("type", "json_object") }) }
        o.putAll(openAiCacheParams(r))
        return JsonObject(o + extra)
    }

    private suspend fun responses(rs: Resolved, model: String, r: ChatReq, cb: Stream, retries: Int): ChatResult {
        val res = open(Llm.joinUrl(rs.baseUrl, "responses"), Llm.authHeaders(rs), responsesBody(model, r, rs.extraBody), r.timeoutMs, retries)
        if (!res.isStream) {
            val o = parse(res.body ?: "") ?: throw ProviderError("응답을 해석할 수 없어요")
            if (r.stream) apiError(o)?.let { if (Regex("stream", RegexOption.IGNORE_CASE).containsMatchIn(it)) return responses(rs, model, r.copy(stream = false), cb, retries) else throw ProviderError(it) }
            val text = StringBuilder(); val rea = StringBuilder()
            for (item in o.arr("output")) {
                if (item.s("type") == "message") item.arr("content").filter { it.s("type") == "output_text" }.forEach { text.append(it.s("text")) }
                else if (item.s("type") == "reasoning") item.arr("summary").forEach { rea.append(it.s("text")) }
            }
            return ChatResult(text.toString(), rea.toString().ifEmpty { null }, Usage(o.o("usage").int("input_tokens"), o.o("usage").int("output_tokens")), null)
        }
        val text = StringBuilder(); val rea = StringBuilder(); var usage: Usage? = null
        readSse(res.conn!!) { d, _ ->
            val o = parse(d) ?: return@readSse
            when (o.s("type")) {
                "response.output_text.delta" -> { val t = o.s("delta"); text.append(t); cb.onDelta(t) }
                "response.reasoning_summary_text.delta" -> { val t = o.s("delta"); rea.append(t); cb.onReasoning(t) }
                "response.completed" -> usage = Usage(o.o("response").o("usage").int("input_tokens"), o.o("response").o("usage").int("output_tokens"))
                "error", "response.failed" -> throw ProviderError(o.o("error").sOrNull("message") ?: o.o("response").o("error").sOrNull("message") ?: "Responses API 오류")
            }
        }
        return ChatResult(text.toString(), rea.toString().ifEmpty { null }, usage, null)
    }

    // ---------- Anthropic (954) ----------

    fun anthropicBody(model: String, r: ChatReq, extra: JsonObject, cap: Int?, drop: Set<String>): JsonObject {
        val bp = r.cache?.let { planBreakpoints(r.messages, it.mode) } ?: emptySet()
        val eph = r.cache?.let { ephemeral(it.ttl) }
        val system = mutableListOf<JsonObject>()
        data class AM(val role: String, val content: MutableList<JsonObject>)
        val msgs = mutableListOf<AM>()
        r.messages.forEachIndexed { i, m ->
            if (m.role == "system") {
                val t = m.plain; if (t.isBlank()) return@forEachIndexed
                var o = buildJsonObject { put("type", "text"); put("text", t) }
                if (i in bp && eph != null) o = JsonObject(o + ("cache_control" to eph))
                system.add(o); return@forEachIndexed
            }
            val content = m.partsOrText.map { p ->
                if (p.isText) buildJsonObject { put("type", "text"); put("text", p.text) }
                else buildJsonObject { put("type", "image"); put("source", buildJsonObject { put("type", "base64"); put("media_type", p.mime); put("data", p.base64) }) }
            }.filter { it.s("type") != "text" || it.s("text").isNotBlank() }
            if (content.isEmpty()) return@forEachIndexed
            val last = msgs.lastOrNull()
            val target = if (last != null && last.role == m.role) last.content.also { it.addAll(content) } else AM(m.role, content.toMutableList()).also { msgs.add(it) }.content
            if (i in bp && eph != null && target.isNotEmpty()) target[target.size - 1] = JsonObject(target.last() + ("cache_control" to eph))
        }
        if (msgs.isEmpty() || msgs[0].role != "user") msgs.add(0, AM("user", mutableListOf(buildJsonObject { put("type", "text"); put("text", "(start)") })))
        if (r.jsonSchema != null && r.structuredOutput != "none") {
            val last = msgs.last()
            if (last.role == "user") last.content.add(buildJsonObject {
                put("type", "text"); put("text", BuiltinPrompts.t("chat.jsonOnly", "schema" to js(r.jsonSchema.schema)))
            })
        }
        val o = mutableMapOf<String, JsonElement>(
            "model" to JsonPrimitive(model),
            "messages" to JsonArray(msgs.map { buildJsonObject { put("role", it.role); put("content", JsonArray(it.content)) } }),
            "max_tokens" to JsonPrimitive(r.params.int("maxTokens") ?: 4096),
            "stream" to JsonPrimitive(r.stream),
        )
        if (system.isNotEmpty()) o["system"] = if (system.any { it.containsKey("cache_control") }) JsonArray(system) else JsonPrimitive(system.joinToString("\n\n") { it.s("text") })
        if (r.webSearch) o["tools"] = buildJsonArray { add(buildJsonObject { put("type", "web_search_20250305"); put("name", "web_search"); put("max_uses", 3) }) }
        r.params.num("temperature")?.let { putNum(o, "temperature", it.coerceIn(0.0, 1.0)) }
        if (r.params.num("temperature") == null) putNum(o, "top_p", r.params.num("topP"))
        putNum(o, "top_k", r.params.num("topK"))
        if (r.thinking.bool("enabled")) {
            val budget = maxOf(1024, r.thinking.int("budget") ?: 2048)
            o["thinking"] = buildJsonObject { put("type", "enabled"); put("budget_tokens", budget) }
            if ((o["max_tokens"] as JsonPrimitive).intOrNull!! <= budget) o["max_tokens"] = JsonPrimitive(budget + 2048)
            o.remove("temperature"); o.remove("top_p"); o.remove("top_k")
        }
        if (cap != null && ((o["max_tokens"] as JsonPrimitive).intOrNull ?: 0) > cap) o["max_tokens"] = JsonPrimitive(cap)
        drop.forEach { o.remove(it) }
        return JsonObject(o + extra)
    }

    private suspend fun anthropic(rs: Resolved, model: String, r: ChatReq, cb: Stream, retries: Int, cap: Int?, drop: Set<String>): ChatResult {
        val h = mapOf("anthropic-version" to "2023-06-01") + Llm.authHeaders(rs)
        val res = try {
            open(Llm.joinUrl(rs.baseUrl, "messages"), h, anthropicBody(model, r, rs.extraBody, cap, drop), r.timeoutMs, retries)
        } catch (e: ProviderError) {
            // Retry once with a smaller max_tokens or without sampling params the model rejects (original c()).
            val b = e.body
            if (e.status == 400 && b != null) {
                val msg = apiError(parse(b)) ?: b
                val m = Regex("max_tokens:\\s*\\d+\\s*>\\s*(\\d+)", RegexOption.IGNORE_CASE).find(msg) ?: Regex("maximum allowed number of output tokens.*?(\\d{3,6})", RegexOption.IGNORE_CASE).find(msg)
                if (m != null && cap == null) return anthropic(rs, model, r, cb, retries, m.groupValues[1].toInt(), drop)
                val nd = drop.toMutableSet(); var changed = false
                for (k in listOf("temperature", "top_p", "top_k")) if (Regex("\\b$k\\b", RegexOption.IGNORE_CASE).containsMatchIn(msg) && k !in nd) { nd.add(k); changed = true }
                if (changed) return anthropic(rs, model, r, cb, retries, cap, nd)
            }
            throw e
        }
        fun pause(stop: String?, text: String) { if (stop == "pause_turn" && text.isBlank()) throw ProviderError("웹 검색이 길어져 응답이 중간에 멈췄습니다(pause_turn). 다시 시도하거나 검색을 끄세요.") }
        if (!res.isStream) {
            val o = parse(res.body ?: "") ?: throw ProviderError("응답을 해석할 수 없어요")
            if (r.stream) apiError(o)?.let { if (Regex("stream", RegexOption.IGNORE_CASE).containsMatchIn(it)) return anthropic(rs, model, r.copy(stream = false), cb, retries, cap, drop) else throw ProviderError(it) }
            var text = StringBuilder(); val th = StringBuilder()
            for (c in o.arr("content")) {
                if (c.s("type") == "server_tool_use" || c.s("type") == "web_search_tool_result") text = StringBuilder()
                if (c.s("type") == "text") text.append(c.s("text"))
                if (c.s("type") == "thinking") th.append(c.s("thinking"))
            }
            pause(o.sOrNull("stop_reason"), text.toString())
            return ChatResult(text.toString(), th.toString().ifEmpty { null }, Usage(o.o("usage").int("input_tokens"), o.o("usage").int("output_tokens")), o.sOrNull("stop_reason"))
        }
        var text = StringBuilder(); val th = StringBuilder(); var inT: Int? = null; var outT: Int? = null; var stop: String? = null
        readSse(res.conn!!) { d, _ ->
            val o = parse(d) ?: return@readSse
            when (o.s("type")) {
                "content_block_delta" -> {
                    val dl = o.o("delta")
                    if (dl.s("type") == "text_delta" && dl.s("text").isNotEmpty()) { text.append(dl.s("text")); cb.onDelta(dl.s("text")) }
                    else if (dl.s("type") == "thinking_delta" && dl.s("thinking").isNotEmpty()) { th.append(dl.s("thinking")); cb.onReasoning(dl.s("thinking")) }
                }
                "content_block_start" -> { val t = o.o("content_block").s("type"); if (t == "server_tool_use" || t == "web_search_tool_result") text = StringBuilder() }
                "message_start" -> inT = o.o("message").o("usage").int("input_tokens")
                "message_delta" -> { o.o("usage").int("output_tokens")?.let { outT = it }; o.o("delta").sOrNull("stop_reason")?.let { stop = it } }
                "error" -> throw ProviderError(o.o("error").sOrNull("message") ?: "Anthropic 스트림 오류")
            }
        }
        pause(stop, text.toString())
        return ChatResult(text.toString(), th.toString().ifEmpty { null }, Usage(inT, outT), stop)
    }

    // ---------- Gemini (955) ----------

    private fun stripSchema(e: JsonElement): JsonElement = when (e) {
        is JsonArray -> JsonArray(e.map { stripSchema(it) })
        is JsonObject -> JsonObject(e.filterKeys { it != "additionalProperties" && it != "\$schema" && it != "default" }.mapValues { stripSchema(it.value) })
        else -> e
    }

    fun geminiBody(r: ChatReq, extra: JsonObject, modelId: String = ""): JsonObject {
        val system = mutableListOf<String>()
        data class GC(val role: String, val parts: MutableList<JsonObject>)
        val contents = mutableListOf<GC>()
        for (m in r.messages) {
            if (m.role == "system") { system.add(m.plain); continue }
            val parts = m.partsOrText.map { p ->
                if (p.isText) buildJsonObject { put("text", p.text) }
                else buildJsonObject { put("inlineData", buildJsonObject { put("mimeType", p.mime); put("data", p.base64) }) }
            }
            val role = if (m.role == "assistant") "model" else "user"
            val last = contents.lastOrNull()
            if (last != null && last.role == role) last.parts.addAll(parts) else contents.add(GC(role, parts.toMutableList()))
        }
        if (contents.isEmpty()) contents.add(GC("user", mutableListOf(buildJsonObject { put("text", "(start)") })))
        val gc = mutableMapOf<String, JsonElement>()
        val p = r.params
        putNum(gc, "maxOutputTokens", p.num("maxTokens")); putNum(gc, "temperature", p.num("temperature")); putNum(gc, "topP", p.num("topP"))
        putNum(gc, "topK", p.num("topK")); putNum(gc, "frequencyPenalty", p.num("frequencyPenalty")); putNum(gc, "presencePenalty", p.num("presencePenalty"))
        if (r.thinking.bool("enabled")) gc["thinkingConfig"] = buildJsonObject {
            put("includeThoughts", true)
            // Gemini 1.x/2.x take a token budget; Gemini 3 and newer only accept thinkingLevel (a budget → HTTP 400).
            if (Regex("gemini-[12](\\.|-|$)", RegexOption.IGNORE_CASE).containsMatchIn(modelId)) r.thinking.int("budget")?.let { put("thinkingBudget", it) }
            else {
                val effort = r.thinking.s("effort").lowercase().takeIf { it in setOf("minimal", "low", "medium", "high") }
                    ?: r.thinking.int("budget")?.let { b -> when { b <= 0 -> "minimal"; b <= 2048 -> "low"; b <= 8192 -> "medium"; else -> "high" } }
                effort?.let { put("thinkingLevel", it) }
            }
        }
        if (r.jsonSchema != null && (r.structuredOutput == "auto" || r.structuredOutput == "json_schema")) {
            gc["responseMimeType"] = JsonPrimitive("application/json"); gc["responseSchema"] = stripSchema(r.jsonSchema.schema)
        } else if (r.structuredOutput == "json_object") gc["responseMimeType"] = JsonPrimitive("application/json")
        val m = mutableMapOf<String, JsonElement>(
            "contents" to JsonArray(contents.map { buildJsonObject { put("role", it.role); put("parts", JsonArray(it.parts)) } }),
            "safetySettings" to JsonArray(listOf("HARM_CATEGORY_HARASSMENT", "HARM_CATEGORY_HATE_SPEECH", "HARM_CATEGORY_SEXUALLY_EXPLICIT", "HARM_CATEGORY_DANGEROUS_CONTENT")
                .map { buildJsonObject { put("category", it); put("threshold", "BLOCK_NONE") } }),
        )
        val sys = system.joinToString("\n\n")
        if (sys.isNotEmpty()) m["systemInstruction"] = buildJsonObject { put("parts", buildJsonArray { add(buildJsonObject { put("text", sys) }) }) }
        if (r.webSearch) {
            m["tools"] = buildJsonArray { add(buildJsonObject { put("google_search", JsonObject(emptyMap())) }) }
            gc.remove("responseMimeType"); gc.remove("responseSchema")
        }
        m["generationConfig"] = JsonObject(gc)
        return JsonObject(m + extra)
    }

    private suspend fun gemini(rs: Resolved, model: String, r: ChatReq, cb: Stream, retries: Int): ChatResult {
        val action = if (r.stream) "streamGenerateContent?alt=sse" else "generateContent"
        val url = Llm.withQueryKey(Llm.joinUrl(rs.baseUrl, "models/${URLEncoder.encode(model, "UTF-8")}:$action"), rs)
        val res = open(url, Llm.authHeaders(rs), geminiBody(r, rs.extraBody, model), r.timeoutMs, retries)
        fun split(o: JsonObject?): Pair<String, String> {
            val t = StringBuilder(); val th = StringBuilder()
            for (p in o.arr("candidates").firstOrNull().o("content").arr("parts")) {
                val s = p.sOrNull("text") ?: continue
                if (p.b("thought")) th.append(s) else t.append(s)
            }
            return t.toString() to th.toString()
        }
        if (!res.isStream) {
            val o = parse(res.body ?: "") ?: throw ProviderError("응답을 해석할 수 없어요")
            if (r.stream) apiError(o)?.let { if (Regex("stream", RegexOption.IGNORE_CASE).containsMatchIn(it)) return gemini(rs, model, r.copy(stream = false), cb, retries) else throw ProviderError(it) }
            o.o("promptFeedback").sOrNull("blockReason")?.let { throw ProviderError("Gemini 차단: $it") }
            val (t, th) = split(o)
            return ChatResult(t, th.ifEmpty { null }, Usage(o.o("usageMetadata").int("promptTokenCount"), o.o("usageMetadata").int("candidatesTokenCount")),
                o.arr("candidates").firstOrNull().sOrNull("finishReason"))
        }
        val text = StringBuilder(); val th = StringBuilder(); var usage: Usage? = null; var finish: String? = null
        readSse(res.conn!!) { d, _ ->
            val o = parse(d) ?: return@readSse
            apiError(o)?.let { throw ProviderError(it) }
            val (t, rr) = split(o)
            if (t.isNotEmpty()) { text.append(t); cb.onDelta(t) }
            if (rr.isNotEmpty()) { th.append(rr); cb.onReasoning(rr) }
            o.o("usageMetadata")?.let { usage = Usage(it.int("promptTokenCount"), it.int("candidatesTokenCount")) }
            o.arr("candidates").firstOrNull().sOrNull("finishReason")?.let { finish = it }
        }
        return ChatResult(text.toString(), th.toString().ifEmpty { null }, usage, finish)
    }

    // ---------- Jev (935) ----------

    suspend fun jevSystemOne(p: JsonObject, model: String, state: String, questions: JsonObject, timeoutMs: Int, retries: Int): JsonObject {
        val rs = Llm.resolve(p)
        val body = JsonObject(mapOf("state" to JsonPrimitive(state), "model" to JsonPrimitive(model), "questions" to questions) + rs.extraBody)
        val res = open(Llm.joinUrl(rs.baseUrl, "systemone"), Llm.authHeaders(rs), body, timeoutMs, retries)
        val text = res.body ?: res.conn?.inputStream?.bufferedReader()?.use { it.readText() }.also { res.conn?.disconnect() } ?: ""
        return parse(text) ?: throw ProviderError("Jev 응답을 해석할 수 없어요")
    }

    /** Vision guess (module 883 effectiveVision). */
    fun effectiveVision(model: JsonObject?): Boolean {
        if (model == null) return false
        if (model.b("visionSet")) return model.b("vision")
        if (model.b("vision")) return true
        val id = model.s("modelId")
        val yes = Regex("gpt-4o|gpt-4\\.1|gpt-4\\.5|gpt-4-turbo|gpt-4-vision|gpt-5|chatgpt-4o|\\bo[134](-|$)|claude|gemini|gemma-3|pixtral|llava|vision|\\bvl\\b|-vl|qwen.*vl|qwen3-(omni|vl)|internvl|minicpm-v|grok-(2-vision|4|vision)|llama-?(3\\.2-(11|90)b|4)|mistral-(medium|small)-(3|latest)|kimi-(k2\\.5|vl)|glm-4\\.?\\d*v|doubao.*vision|step-1v", RegexOption.IGNORE_CASE)
        val no = Regex("embed|whisper|tts|dall-e|moderation|rerank|gpt-3\\.5|instruct$|-coder-(?!.*vl)|codestral|deepseek-(chat|reasoner|r1|v3)(?!.*vl)", RegexOption.IGNORE_CASE)
        return yes.containsMatchIn(id) && !no.containsMatchIn(id)
    }

    @Suppress("unused") private val keepImports = listOf(JsonNull)
}
