package space.nami.maker

import android.content.Context
import android.util.Base64
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec

class ApiError(message: String) : Exception(message)

/** Providers → models, like Nami next. Keys live in [Secrets]; the rest in filesDir/api.json. */
object Api {
    class Preset(val id: String, val name: String, val desc: String, val models: List<String>)

    val PRESETS = listOf(
        Preset("vertex", "Vertex AI", "Google Cloud 서비스 계정(Project ID · Client Email · Private Key)으로 Gemini를 써요.",
            listOf("gemini-4-hydrogen", "gemini-3-pro-preview", "gemini-2.5-pro", "gemini-2.5-flash")),
        Preset("anthropic", "Anthropic", "Claude API 키(sk-ant-…)로 Claude를 써요.",
            listOf("claude-sonnet-5-5", "claude-opus-5-5", "claude-haiku-5-5")),
    )
    fun preset(id: String) = PRESETS.firstOrNull { it.id == id } ?: PRESETS[0]

    var rev by mutableIntStateOf(0)
    private var file: File? = null
    private var root = JSONObject()

    fun init(ctx: Context) {
        Secrets.init(ctx)
        if (file != null) return
        val f = File(ctx.filesDir, "api.json"); file = f
        root = runCatching { JSONObject(f.readText()) }.getOrDefault(JSONObject())
        if (!root.has("providers")) root.put("providers", JSONArray())
        if (!root.has("models")) root.put("models", JSONArray())
    }

    private fun save() { file?.writeText(root.toString()); rev++ }
    private fun list(k: String): List<JSONObject> { val a = root.getJSONArray(k); return List(a.length()) { a.getJSONObject(it) } }
    private fun setList(k: String, l: List<JSONObject>) { root.put(k, JSONArray(l)) }
    private fun newId(p: String) = p + System.currentTimeMillis().toString(36)

    fun providers() = list("providers")
    fun models() = list("models")
    fun provider(id: String?) = providers().firstOrNull { it.optString("id") == id }
    fun model(id: String?) = models().firstOrNull { it.optString("id") == id }
    fun modelsOf(pid: String) = models().filter { it.optString("providerId") == pid }

    val selectedId: String? get() = root.optString("selected").ifBlank { null }
    /** The model the AI chat uses (falls back to the first one). */
    fun current(): JSONObject? = model(selectedId) ?: models().firstOrNull()
    fun select(id: String) { root.put("selected", id); save() }

    fun label(m: JSONObject): String {
        val p = provider(m.optString("providerId"))
        return (p?.optString("name") ?: "?") + " · " + m.optString("modelId")
    }

    fun addProvider(preset: String): String {
        val id = newId("p")
        val same = providers().count { it.optString("preset") == preset }
        val o = JSONObject().put("id", id).put("preset", preset)
            .put("name", preset(preset).name + if (same > 0) " ${same + 1}" else "")
        if (preset == "vertex") o.put("projectId", "").put("clientEmail", "").put("location", "global")
        setList("providers", providers() + o); save()
        return id
    }

    fun patchProvider(id: String, k: String, v: String) {
        provider(id)?.put(k, v)  // same object inside root
        save()
    }

    fun removeProvider(id: String) {
        Secrets.remove("key_$id")
        setList("providers", providers().filter { it.optString("id") != id })
        setList("models", models().filter { it.optString("providerId") != id })
        save()
    }

    fun addModel(pid: String, modelId: String): String {
        modelsOf(pid).firstOrNull { it.optString("modelId") == modelId }?.let { return it.optString("id") }
        val id = newId("m")
        setList("models", models() + JSONObject().put("id", id).put("providerId", pid).put("modelId", modelId.trim()))
        if (selectedId == null || model(selectedId) == null) root.put("selected", id)
        save(); return id
    }

    fun removeModel(id: String) {
        setList("models", models().filter { it.optString("id") != id })
        if (selectedId == id) root.remove("selected")
        save()
    }

    fun hasKey(pid: String) = Secrets.has("key_$pid")
    fun setKey(pid: String, v: String) { Secrets.put("key_$pid", v); save() }
    fun removeKey(pid: String) { Secrets.remove("key_$pid"); save() }
    private fun key(pid: String) = Secrets.get("key_$pid")

    /** Cleans a pasted key the way Nami next does (quotes, "Bearer ", stray whitespace). */
    fun cleanKey(raw: String) = raw.replace(Regex("[\\r\\n\\t]"), "").trim().trim('"', '\'')
        .replace(Regex("^Bearer\\s+", RegexOption.IGNORE_CASE), "").replace(Regex("\\s+"), "")

    // ---------- HTTP ----------
    suspend fun http(method: String, url: String, headers: Map<String, String>, body: String?, timeoutMs: Int = 180_000): String =
        withContext(Dispatchers.IO) {
            val c = URL(url).openConnection() as HttpURLConnection
            try {
                c.requestMethod = method; c.connectTimeout = 20_000; c.readTimeout = timeoutMs
                c.setRequestProperty("Accept", "application/json")
                if (body != null) c.setRequestProperty("Content-Type", "application/json")
                headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
                if (body != null) { c.doOutput = true; c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) } }
                val code = c.responseCode
                val text = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() } ?: ""
                if (code !in 200..299) throw ApiError(explain(code, text))
                text
            } catch (e: ApiError) { throw e
            } catch (e: java.net.SocketTimeoutException) { throw ApiError("응답 시간이 초과됐어요 (${timeoutMs / 1000}초)")
            } catch (e: java.io.IOException) { throw ApiError("네트워크 오류: ${e.message ?: e.javaClass.simpleName}")
            } finally { c.disconnect() }
        }

    private fun explain(code: Int, text: String): String {
        val msg = runCatching {
            val o = JSONObject(text)
            when (val e = o.opt("error")) {
                is JSONObject -> e.optString("message").ifBlank { e.toString() }
                is String -> e
                else -> o.optString("message").ifBlank { null }
            }
        }.getOrNull() ?: text.take(300)
        val hint = when (code) {
            401, 403 -> " (키나 권한을 확인하세요)"
            404 -> " (모델 ID나 지역을 확인하세요)"
            429 -> " (요청이 너무 많거나 한도를 넘었어요)"
            else -> ""
        }
        return "HTTP $code: ${msg.trim()}$hint"
    }

    // ---------- Vertex AI (service account → OAuth token, same as Nami next) ----------
    object Vertex {
        private val cache = mutableMapOf<String, Pair<String, Long>>()

        fun baseUrl(projectId: String, location: String): String {
            val loc = location.trim().ifEmpty { "global" }
            val host = if (loc == "global") "aiplatform.googleapis.com" else "$loc-aiplatform.googleapis.com"
            return "https://$host/v1/projects/${URLEncoder.encode(projectId.trim(), "UTF-8")}/locations/$loc/publishers/google"
        }

        fun normalizeKey(k: String) = k.replace("\\n", "\n").trim()

        /** Pasted service-account JSON → (projectId, clientEmail, privateKey). */
        fun parseServiceAccount(text: String): Triple<String, String, String>? = runCatching {
            val o = JSONObject(text.trim())
            val pid = o.optString("project_id"); val mail = o.optString("client_email"); val pk = o.optString("private_key")
            if (pid.isBlank() || mail.isBlank() || pk.isBlank()) null else Triple(pid, mail, normalizeKey(pk))
        }.getOrNull()

        private fun b64url(b: ByteArray) = Base64.encodeToString(b, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)

        suspend fun accessToken(clientEmail: String, privateKey: String): String {
            val ck = "$clientEmail|${privateKey.length}"
            cache[ck]?.let { (t, exp) -> if (exp - 60_000 > System.currentTimeMillis()) return t }
            if (clientEmail.isBlank() || privateKey.isBlank()) throw ApiError("Vertex AI: Client Email과 Private Key가 필요해요.")
            val now = System.currentTimeMillis() / 1000
            val header = """{"alg":"RS256","typ":"JWT"}"""
            val claims = JSONObject().put("iss", clientEmail).put("scope", "https://www.googleapis.com/auth/cloud-platform")
                .put("aud", "https://oauth2.googleapis.com/token").put("iat", now).put("exp", now + 3600).toString()
            val jwt = try {
                val unsigned = b64url(header.toByteArray()) + "." + b64url(claims.toByteArray())
                val pem = normalizeKey(privateKey).replace(Regex("-----[A-Z ]+-----"), "").replace(Regex("\\s"), "")
                val key = KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(Base64.decode(pem, Base64.DEFAULT)))
                val sig = Signature.getInstance("SHA256withRSA").apply { initSign(key); update(unsigned.toByteArray()) }.sign()
                unsigned + "." + b64url(sig)
            } catch (e: Throwable) {
                throw ApiError("Vertex AI: Private Key로 서명하지 못했어요. PEM 형식(-----BEGIN PRIVATE KEY-----)인지 확인하세요. (${e.message})")
            }
            val form = "grant_type=" + URLEncoder.encode("urn:ietf:params:oauth:grant-type:jwt-bearer", "UTF-8") + "&assertion=" + URLEncoder.encode(jwt, "UTF-8")
            val text = withContext(Dispatchers.IO) {
                val c = URL("https://oauth2.googleapis.com/token").openConnection() as HttpURLConnection
                try {
                    c.requestMethod = "POST"; c.doOutput = true; c.connectTimeout = 20_000; c.readTimeout = 20_000
                    c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded"); c.setRequestProperty("Accept", "application/json")
                    c.outputStream.use { it.write(form.toByteArray()) }
                    val code = c.responseCode
                    val body = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() } ?: ""
                    if (code !in 200..299) {
                        val o = runCatching { JSONObject(body) }.getOrNull()
                        val m = "${o?.optString("error") ?: ""} ${o?.optString("error_description") ?: ""}".trim().ifEmpty { body.take(300) }
                        val hint = if ("invalid_grant" in m) " (Client Email/Private Key가 서로 맞는지, 폰 시간이 정확한지 확인하세요)" else ""
                        throw ApiError("Vertex AI 토큰 발급 실패 ($code): $m$hint")
                    }
                    body
                } catch (e: ApiError) { throw e
                } catch (e: java.io.IOException) { throw ApiError("네트워크 오류: ${e.message ?: e.javaClass.simpleName}")
                } finally { c.disconnect() }
            }
            val o = JSONObject(text)
            val token = o.getString("access_token")
            cache[ck] = token to (System.currentTimeMillis() + o.optLong("expires_in", 3600) * 1000)
            return token
        }
    }

    private class Conn(val format: String, val base: String, val headers: Map<String, String>)

    private suspend fun connect(p: JSONObject): Conn {
        val pid = p.optString("id")
        val k = key(pid)
        return when (p.optString("preset")) {
            "vertex" -> {
                if (p.optString("projectId").isBlank()) throw ApiError("Vertex AI: Project ID가 필요해요.")
                val token = Vertex.accessToken(p.optString("clientEmail"), k ?: "")
                Conn("gemini", Vertex.baseUrl(p.optString("projectId"), p.optString("location")), mapOf("Authorization" to "Bearer $token"))
            }
            else -> {
                if (k.isNullOrBlank()) throw ApiError("${p.optString("name")}: API 키를 넣고 저장해 주세요.")
                Conn("anthropic", "https://api.anthropic.com/v1", mapOf("x-api-key" to k, "anthropic-version" to "2023-06-01"))
            }
        }
    }

    // ---------- connection check / model test ----------
    suspend fun check(pid: String): String {
        val p = provider(pid) ?: throw ApiError("프로바이더가 없어요")
        val c = connect(p)
        val first = modelsOf(pid).firstOrNull()
        return when (c.format) {
            "gemini" -> if (first == null) "인증됨 · 모델을 추가하면 응답까지 확인해요" else "연결됨 · ${first.optString("modelId")}: " + testModel(first.optString("id"))
            else -> {
                val o = JSONObject(http("GET", c.base + "/models?limit=100", c.headers, null, 20_000))
                "연결됨 · 서버 모델 ${o.optJSONArray("data")?.length() ?: 0}개"
            }
        }
    }

    suspend fun testModel(mid: String): String {
        val m = model(mid) ?: throw ApiError("모델이 없어요")
        val p = provider(m.optString("providerId")) ?: throw ApiError("모델에 연결된 프로바이더가 없어요")
        val c = connect(p)
        val id = m.optString("modelId")
        val prompt = "Reply with the single word: OK"
        return when (c.format) {
            "gemini" -> {
                val body = JSONObject()
                    .put("contents", JSONArray().put(JSONObject().put("role", "user").put("parts", JSONArray().put(JSONObject().put("text", prompt)))))
                    .put("generationConfig", JSONObject().put("maxOutputTokens", 2048).also { g -> thinkLow(id)?.let { g.put("thinkingConfig", it) } })
                val o = JSONObject(http("POST", c.base + "/models/${URLEncoder.encode(id, "UTF-8")}:generateContent", c.headers, body.toString(), 60_000))
                geminiText(o).ifBlank { "(빈 응답 · ${o.optJSONArray("candidates")?.optJSONObject(0)?.optString("finishReason") ?: "?"})" }
            }
            else -> {
                val body = JSONObject().put("model", id).put("max_tokens", 32)
                    .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", prompt)))
                val o = JSONObject(http("POST", c.base + "/messages", c.headers, body.toString(), 60_000))
                val a = o.optJSONArray("content") ?: JSONArray()
                (0 until a.length()).map { a.getJSONObject(it) }.filter { it.optString("type") == "text" }.joinToString("") { it.optString("text") }
            }
        }.trim().take(60)
    }

    /** Quick tests shouldn't think long: Gemini 3+ takes thinkingLevel, 2.x flash takes a 0 budget. */
    private fun thinkLow(id: String): JSONObject? = when {
        Regex("gemini-2\\.5-flash", RegexOption.IGNORE_CASE).containsMatchIn(id) -> JSONObject().put("thinkingBudget", 0)
        Regex("gemini-[12](\\.|-|$)", RegexOption.IGNORE_CASE).containsMatchIn(id) -> null
        else -> JSONObject().put("thinkingLevel", "low")
    }

    private fun geminiText(o: JSONObject): String {
        val parts = o.optJSONArray("candidates")?.optJSONObject(0)?.optJSONObject("content")?.optJSONArray("parts") ?: return ""
        return (0 until parts.length()).map { parts.getJSONObject(it) }.filter { !it.optBoolean("thought") }.joinToString("") { it.optString("text") }
    }

    // ---------- model settings (like Nami next: stream, at once, thinking) ----------
    fun patchModel(id: String, k: String, v: Any?) { model(id)?.let { if (v == null) it.remove(k) else it.put(k, v) }; save() }
    fun stream(m: JSONObject) = m.optBoolean("stream", true)
    fun atOnce(m: JSONObject) = m.optBoolean("atOnce", false)
    fun thinking(m: JSONObject): JSONObject = m.optJSONObject("thinking") ?: JSONObject()
    fun patchThinking(id: String, k: String, v: Any?) {
        val m = model(id) ?: return
        val t = thinking(m); if (v == null) t.remove(k) else t.put(k, v)
        m.put("thinking", t); save()
    }
    val EFFORTS = listOf("minimal", "low", "medium", "high", "xhigh")
    private fun isOldGemini(id: String) = Regex("gemini-[12](\\.|-|$)", RegexOption.IGNORE_CASE).containsMatchIn(id)

    // ---------- one AI turn with tools ----------
    /** blocks: Anthropic-style content (thinking / text / tool_use). raw: Gemini parts to send back as-is (thought signatures). */
    class Turn(val blocks: JSONArray, val raw: JSONArray?, val wantsTools: Boolean, val reasoning: String)

    /** Live output while streaming (skipped when "한 번에 불러오기" is on). */
    class Live(val onText: (String) -> Unit = {}, val onThink: (String) -> Unit = {})

    /** [messages] use Anthropic-style blocks; assistant turns from Gemini also carry "gparts". */
    suspend fun turn(system: String, tools: JSONArray, messages: JSONArray, live: Live): Turn {
        val m = current() ?: throw ApiError("설정 › API에서 프로바이더와 모델을 먼저 추가해 주세요")
        val p = provider(m.optString("providerId")) ?: throw ApiError("모델에 연결된 프로바이더가 없어요")
        val c = connect(p)
        val cb = if (atOnce(m)) Live() else live
        return if (c.format == "gemini") geminiTurn(c, m, system, tools, messages, cb) else anthropicTurn(c, m, system, tools, messages, cb)
    }

    /** POST that reads a server-sent-event stream; onData gets each event's data. */
    private suspend fun sse(url: String, headers: Map<String, String>, body: String, onData: (String) -> Unit) = withContext(Dispatchers.IO) {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.requestMethod = "POST"; c.connectTimeout = 20_000; c.readTimeout = 180_000; c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json"); c.setRequestProperty("Accept", "text/event-stream")
            headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
            c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = c.responseCode
            if (code !in 200..299) throw ApiError(explain(code, c.errorStream?.bufferedReader()?.use { it.readText() } ?: ""))
            c.inputStream.bufferedReader(Charsets.UTF_8).use { rd ->
                val data = StringBuilder(); var has = false
                while (true) {
                    ensureActive()
                    val line = rd.readLine() ?: break
                    if (line.isEmpty()) { if (has) onData(data.toString()); data.clear(); has = false; continue }
                    if (line.startsWith("data:")) { if (has) data.append('\n'); data.append(line.substring(5).removePrefix(" ")); has = true }
                }
                if (has) onData(data.toString())
            }
        } catch (e: ApiError) { throw e
        } catch (e: java.net.SocketTimeoutException) { throw ApiError("응답 시간이 초과됐어요 (180초)")
        } catch (e: java.io.IOException) { throw ApiError("스트림이 끊겼어요: ${e.message ?: e.javaClass.simpleName}")
        } finally { c.disconnect() }
    }

    private suspend fun anthropicTurn(c: Conn, m: JSONObject, system: String, tools: JSONArray, messages: JSONArray, live: Live): Turn {
        val clean = JSONArray()
        for (i in 0 until messages.length()) {
            val o = messages.getJSONObject(i)
            clean.put(JSONObject().put("role", o.getString("role")).put("content", o.get("content")))
        }
        val th = thinking(m)
        var maxTokens = m.optInt("maxTokens", 0).takeIf { it > 0 } ?: 8000
        val body = JSONObject().put("model", m.optString("modelId")).put("system", system).put("tools", tools).put("messages", clean)
        if (th.optBoolean("enabled")) {
            val budget = maxOf(1024, th.optInt("budget", 0).takeIf { it > 0 } ?: 4000)
            body.put("thinking", JSONObject().put("type", "enabled").put("budget_tokens", budget))
            if (maxTokens <= budget) maxTokens = budget + 4000
        }
        body.put("max_tokens", maxTokens)
        if (!stream(m)) {
            val o = JSONObject(http("POST", c.base + "/messages", c.headers, body.toString()))
            val a = o.optJSONArray("content") ?: JSONArray()
            val r = StringBuilder()
            for (i in 0 until a.length()) a.getJSONObject(i).takeIf { it.optString("type") == "thinking" }?.let { r.append(it.optString("thinking")) }
            return Turn(a, null, o.optString("stop_reason") == "tool_use", r.toString())
        }
        body.put("stream", true)
        val blocks = sortedMapOf<Int, JSONObject>(); val json = mutableMapOf<Int, StringBuilder>()
        val reasoning = StringBuilder(); var stop = ""
        sse(c.base + "/messages", c.headers, body.toString()) { d ->
            val o = runCatching { JSONObject(d) }.getOrNull() ?: return@sse
            when (o.optString("type")) {
                "content_block_start" -> {
                    val i = o.optInt("index"); val cb = o.getJSONObject("content_block")
                    when (cb.optString("type")) {
                        "text" -> cb.put("text", "")
                        "thinking" -> { cb.put("thinking", ""); cb.put("signature", cb.optString("signature")) }
                        "tool_use" -> json[i] = StringBuilder()
                    }
                    blocks[i] = cb
                }
                "content_block_delta" -> {
                    val i = o.optInt("index"); val dl = o.getJSONObject("delta"); val b = blocks[i] ?: return@sse
                    when (dl.optString("type")) {
                        "text_delta" -> { val t = dl.optString("text"); b.put("text", b.optString("text") + t); live.onText(t) }
                        "thinking_delta" -> { val t = dl.optString("thinking"); b.put("thinking", b.optString("thinking") + t); reasoning.append(t); live.onThink(t) }
                        "signature_delta" -> b.put("signature", b.optString("signature") + dl.optString("signature"))
                        "input_json_delta" -> json[i]?.append(dl.optString("partial_json"))
                    }
                }
                "content_block_stop" -> {
                    val i = o.optInt("index")
                    json[i]?.let { sb -> blocks[i]?.put("input", runCatching { JSONObject(sb.toString().ifBlank { "{}" }) }.getOrDefault(JSONObject())) }
                }
                "message_delta" -> o.optJSONObject("delta")?.optString("stop_reason")?.takeIf { it.isNotBlank() }?.let { stop = it }
                "error" -> throw ApiError(o.optJSONObject("error")?.optString("message") ?: "Anthropic 스트림 오류")
            }
        }
        val out = JSONArray(); blocks.values.forEach { out.put(it) }
        return Turn(out, null, stop == "tool_use", reasoning.toString())
    }

    private val BLOCKS = setOf("SAFETY", "PROHIBITED_CONTENT", "BLOCKLIST", "SPII", "IMAGE_SAFETY", "RECITATION", "OTHER")

    private suspend fun geminiTurn(c: Conn, m: JSONObject, system: String, tools: JSONArray, messages: JSONArray, live: Live): Turn {
        val id = m.optString("modelId")
        val decl = JSONArray()
        for (i in 0 until tools.length()) {
            val t = tools.getJSONObject(i)
            decl.put(JSONObject().put("name", t.getString("name")).put("description", t.optString("description"))
                .put("parameters", gSchema(t.getJSONObject("input_schema"))))
        }
        val gc = JSONObject().put("maxOutputTokens", m.optInt("maxTokens", 0).takeIf { it > 0 } ?: 32768)
        val th = thinking(m)
        if (th.optBoolean("enabled")) gc.put("thinkingConfig", JSONObject().put("includeThoughts", true).also { t ->
            val budget = th.optInt("budget", 0)
            if (isOldGemini(id)) { if (budget > 0) t.put("thinkingBudget", budget) }
            else {
                val effort = th.optString("effort").takeIf { it in EFFORTS }
                    ?: budget.takeIf { it > 0 }?.let { b -> when { b <= 2048 -> "low"; b <= 8192 -> "medium"; else -> "high" } }
                effort?.let { t.put("thinkingLevel", it) }
            }
        })
        val body = JSONObject()
            .put("contents", toGemini(messages))
            .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", system))))
            .put("tools", JSONArray().put(JSONObject().put("functionDeclarations", decl)))
            .put("generationConfig", gc)
            .put("safetySettings", JSONArray().also { a ->
                listOf("HARM_CATEGORY_HARASSMENT", "HARM_CATEGORY_HATE_SPEECH", "HARM_CATEGORY_SEXUALLY_EXPLICIT", "HARM_CATEGORY_DANGEROUS_CONTENT")
                    .forEach { a.put(JSONObject().put("category", it).put("threshold", "BLOCK_NONE")) }
            })
        val model = URLEncoder.encode(id, "UTF-8")
        val parts = mutableListOf<JSONObject>()
        var finish = ""
        fun take(o: JSONObject) {
            o.optJSONObject("promptFeedback")?.optString("blockReason")?.takeIf { it.isNotBlank() }?.let { throw ApiError("구글 안전 필터에 막혔어요 (사유: $it)") }
            o.optJSONObject("error")?.let { throw ApiError(it.optString("message", "Gemini 오류")) }
            val cand = o.optJSONArray("candidates")?.optJSONObject(0) ?: return
            cand.optString("finishReason").takeIf { it.isNotBlank() }?.let { finish = it }
            val ps = cand.optJSONObject("content")?.optJSONArray("parts") ?: return
            for (i in 0 until ps.length()) {
                val pt = ps.getJSONObject(i)
                val txt = if (pt.has("text") && !pt.has("functionCall")) pt.optString("text") else null
                if (txt != null) { if (pt.optBoolean("thought")) live.onThink(txt) else live.onText(txt) }
                // glue streamed text pieces back together; a signature on a piece moves onto the joined part
                val last = parts.lastOrNull()
                if (txt != null && last != null && last.has("text") && !last.has("functionCall") && last.optBoolean("thought") == pt.optBoolean("thought")) {
                    last.put("text", last.optString("text") + txt)
                    pt.optString("thoughtSignature").takeIf { it.isNotBlank() }?.let { last.put("thoughtSignature", it) }
                } else parts.add(JSONObject(pt.toString()))
            }
        }
        if (stream(m)) sse(c.base + "/models/$model:streamGenerateContent?alt=sse", c.headers, body.toString()) { d ->
            runCatching { JSONObject(d) }.getOrNull()?.let { take(it) }
        } else take(JSONObject(http("POST", c.base + "/models/$model:generateContent", c.headers, body.toString())))

        val blocks = JSONArray(); var calls = false; val reasoning = StringBuilder()
        parts.forEachIndexed { i, pt ->
            val fc = pt.optJSONObject("functionCall")
            when {
                fc != null -> {
                    calls = true
                    blocks.put(JSONObject().put("type", "tool_use").put("id", "g${System.nanoTime().toString(36)}$i")
                        .put("name", fc.optString("name")).put("input", fc.optJSONObject("args") ?: JSONObject()))
                }
                pt.optBoolean("thought") -> reasoning.append(pt.optString("text"))
                pt.has("text") && pt.optString("text").isNotEmpty() -> blocks.put(JSONObject().put("type", "text").put("text", pt.optString("text")))
            }
        }
        if (blocks.length() == 0 && finish in BLOCKS) throw ApiError("구글 안전 필터에 막혔어요 (사유: $finish)")
        if (blocks.length() == 0 && finish == "MALFORMED_FUNCTION_CALL") throw ApiError("AI가 도구 호출을 잘못 만들었어요. 다시 시도해 주세요")
        return Turn(blocks, if (parts.isNotEmpty()) JSONArray(parts) else null, calls, reasoning.toString())
    }

    /** JSON schema → Gemini schema: upper-case types; arrays of free-form objects become a JSON string. */
    private fun gSchema(s: JSONObject): JSONObject {
        val type = s.optString("type")
        val items = s.optJSONObject("items")
        if (type == "array" && items != null && items.optString("type") == "object" && !items.has("properties")) {
            return JSONObject().put("type", "STRING").put("description", (s.optString("description") + " JSON 배열을 문자열로 넣어라.").trim())
        }
        val o = JSONObject()
        if (type.isNotBlank()) o.put("type", type.uppercase())
        s.optString("description").takeIf { it.isNotBlank() }?.let { o.put("description", it) }
        s.optJSONObject("properties")?.let { ps ->
            val out = JSONObject(); ps.keys().forEach { k -> out.put(k, gSchema(ps.getJSONObject(k))) }
            o.put("properties", out)
        }
        s.optJSONArray("required")?.let { o.put("required", it) }
        items?.let { o.put("items", gSchema(it)) }
        return o
    }

    private fun toGemini(messages: JSONArray): JSONArray {
        val names = mutableMapOf<String, String>()
        val out = mutableListOf<Pair<String, JSONArray>>()
        fun add(role: String, parts: JSONArray) {
            if (parts.length() == 0) return
            val last = out.lastOrNull()
            if (last != null && last.first == role) { for (i in 0 until parts.length()) last.second.put(parts.get(i)) } else out.add(role to parts)
        }
        for (i in 0 until messages.length()) {
            val m = messages.getJSONObject(i)
            val content = m.optJSONArray("content") ?: JSONArray().put(JSONObject().put("type", "text").put("text", m.optString("content")))
            val blocks = (0 until content.length()).map { content.getJSONObject(it) }
            blocks.filter { it.optString("type") == "tool_use" }.forEach { names[it.optString("id")] = it.optString("name") }
            if (m.optString("role") == "assistant") {
                val parts = m.optJSONArray("gparts") ?: JSONArray().also { a ->
                    blocks.forEach { b ->
                        when (b.optString("type")) {
                            "text" -> a.put(JSONObject().put("text", b.optString("text")))
                            "tool_use" -> a.put(JSONObject().put("functionCall", JSONObject().put("name", b.optString("name")).put("args", b.optJSONObject("input") ?: JSONObject())))
                        }
                    }
                }
                add("model", parts)
            } else {
                val parts = JSONArray(); val images = JSONArray()
                blocks.forEach { b ->
                    when (b.optString("type")) {
                        "text" -> parts.put(JSONObject().put("text", b.optString("text")))
                        "image" -> images.put(inline(b))
                        "tool_result" -> {
                            val rc = b.optJSONArray("content") ?: JSONArray()
                            val txt = StringBuilder()
                            for (k in 0 until rc.length()) {
                                val x = rc.getJSONObject(k)
                                if (x.optString("type") == "image") images.put(inline(x)) else txt.append(x.optString("text"))
                            }
                            val resp = JSONObject().put(if (b.optBoolean("is_error")) "error" else "result", txt.toString())
                            parts.put(JSONObject().put("functionResponse", JSONObject().put("name", names[b.optString("tool_use_id")] ?: "tool").put("response", resp)))
                        }
                    }
                }
                for (k in 0 until images.length()) parts.put(images.get(k))
                add("user", parts)
            }
        }
        return JSONArray().also { a -> out.forEach { (r, p) -> a.put(JSONObject().put("role", r).put("parts", p)) } }
    }

    private fun inline(img: JSONObject): JSONObject {
        val s = img.optJSONObject("source") ?: JSONObject()
        return JSONObject().put("inlineData", JSONObject().put("mimeType", s.optString("media_type", "image/png")).put("data", s.optString("data")))
    }
}
