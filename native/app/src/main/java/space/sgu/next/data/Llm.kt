package space.sgu.next.data

import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec

class ProviderError(message: String, val status: Int? = null) : Exception(message)

/** Everything needed to call a provider (ported from resolveProvider, module 945). */
data class Resolved(
    val baseUrl: String,
    val apiKey: String?,
    val auth: String,
    val tokenParam: String,
    val extraHeaders: Map<String, String>,
    val extraBody: JsonObject,
)

/** Provider calls (non-streaming). Streaming + the processor pipeline come with the 작업실 step. */
object Llm {
    private fun parseObj(text: String?): JsonObject =
        if (text.isNullOrBlank()) JsonObject(emptyMap())
        else runCatching { CardIO.json.parseToJsonElement(text) as? JsonObject }.getOrNull() ?: JsonObject(emptyMap())

    fun defaultTokenParam(p: JsonObject): String =
        p.sOrNull("tokenParam") ?: if (p.s("preset") == "openai") "max_completion_tokens" else "max_tokens"

    fun joinUrl(a: String, b: String) = a.trimEnd('/') + "/" + b.trimStart('/')

    fun authHeaders(r: Resolved): Map<String, String> {
        val h = r.extraHeaders.toMutableMap()
        val k = r.apiKey ?: return h
        when (r.auth) {
            "bearer" -> h["Authorization"] = "Bearer $k"
            "x-api-key" -> h["x-api-key"] = k
            "x-goog-api-key" -> h["x-goog-api-key"] = k
        }
        return h
    }

    fun withQueryKey(url: String, r: Resolved): String {
        if (r.auth != "query-key" || r.apiKey == null) return url
        return url + (if ("?" in url) "&" else "?") + "key=" + URLEncoder.encode(r.apiKey, "UTF-8")
    }

    suspend fun resolve(p: JsonObject): Resolved = withContext(Dispatchers.IO) {
        val preset = p.s("preset")
        var key = Secrets.get(p.sOrNull("keyId"))?.let { raw ->
            var c = raw.replace(Regex("[\\r\\n\\t]"), "").trim().trim('"', '\'').replace(Regex("^Bearer\\s+", RegexOption.IGNORE_CASE), "").trim()
            if (preset != "vertex" && c != raw && Regex("\\s").containsMatchIn(c)) c = c.replace(Regex("\\s+"), "")
            if (preset == "vertex") raw else c.ifEmpty { null }
        }
        var auth = p.s("auth")
        var base = p.s("baseUrl").trim()
        val keyless = preset == "vertex" || preset == "copilot" || auth == "none"
        if (key == null && !keyless) {
            throw ProviderError("${p.s("name").ifBlank { "프로바이더" }}: API 키를 찾을 수 없습니다. 프로바이더 설정에서 키를 다시 입력하고 저장하세요.")
        }
        if (preset == "vertex" || auth == "vertex-sa") {
            val v = p.o("vertex")
            if (v.s("projectId").isBlank()) throw ProviderError("Vertex AI: Project ID가 필요합니다.")
            key = Vertex.accessToken(v.s("clientEmail"), key ?: "")
            auth = "bearer"
            base = Vertex.baseUrl(v.s("projectId"), v.s("location"))
        }
        if (preset == "copilot" || auth == "copilot-oauth") {
            throw ProviderError("GitHub Copilot은 SGU Next에서 아직 준비 중이에요. 다음 업데이트에서 열려요.")
        }
        val headers = parseObj(p.s("headersJson")).mapValues { (_, v) -> (v as? JsonPrimitive)?.contentOrNull ?: v.toString() }
        Resolved(base, key, auth, defaultTokenParam(p), headers, parseObj(p.s("bodyJson")))
    }

    /** Plain HTTP with retries on 429/5xx. Returns the response body text. */
    suspend fun http(method: String, url: String, headers: Map<String, String>, body: String?, timeoutMs: Int = 60_000, retries: Int = 0): String =
        withContext(Dispatchers.IO) {
            var attempt = 0
            while (true) {
                val c = (URL(url).openConnection() as HttpURLConnection)
                try {
                    c.requestMethod = method
                    c.connectTimeout = 20_000
                    c.readTimeout = timeoutMs
                    c.setRequestProperty("Accept", "application/json")
                    if (body != null) c.setRequestProperty("Content-Type", "application/json")
                    headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
                    if (body != null) {
                        c.doOutput = true
                        c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                    }
                    val code = c.responseCode
                    val text = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() } ?: ""
                    if (code in 200..299) return@withContext text
                    if ((code == 429 || code >= 500) && attempt < retries) {
                        attempt++; kotlinx.coroutines.delay(1500L * attempt); continue
                    }
                    throw ProviderError(explain(code, text), code)
                } catch (e: ProviderError) {
                    throw e
                } catch (e: java.net.SocketTimeoutException) {
                    throw ProviderError("응답 시간이 초과됐어요 (${timeoutMs / 1000}초)")
                } catch (e: java.io.IOException) {
                    if (attempt < retries) { attempt++; kotlinx.coroutines.delay(1500L * attempt); continue }
                    throw ProviderError("네트워크 오류: ${e.message ?: e.javaClass.simpleName}")
                } finally {
                    c.disconnect()
                }
            }
            @Suppress("UNREACHABLE_CODE") ""
        }

    private fun explain(code: Int, text: String): String {
        val msg = runCatching {
            val o = CardIO.json.parseToJsonElement(text)
            val err = (o as? JsonObject)?.get("error")
            when (err) {
                is JsonObject -> err.s("message").ifBlank { err.toString() }
                is JsonPrimitive -> err.contentOrNull
                else -> (o as? JsonObject).s("message").ifBlank { null }
            }
        }.getOrNull() ?: text.take(300)
        val hint = when (code) {
            401, 403 -> " (API 키나 권한을 확인하세요)"
            404 -> " (주소나 모델 ID를 확인하세요)"
            429 -> " (요청이 너무 많거나 한도를 넘었어요)"
            else -> ""
        }
        return "HTTP $code: ${msg.trim()}$hint"
    }

    private fun stringsAt(o: JsonObject, key: String, field: String): List<String> =
        ((o[key] as? JsonArray) ?: JsonArray(emptyList())).mapNotNull { (it as? JsonObject).sOrNull(field) }

    suspend fun listModels(p: JsonObject): List<String> {
        val r = resolve(p)
        return when (p.s("format")) {
            "openai-chat", "openai-responses", "jev" -> {
                val o = CardIO.json.parseToJsonElement(http("GET", withQueryKey(joinUrl(r.baseUrl, "models"), r), authHeaders(r), null, 20_000)).jsonObject
                (stringsAt(o, "data", "id") + stringsAt(o, "models", "id") + stringsAt(o, "models", "name")).filter { it.isNotBlank() }.distinct().sorted()
            }
            "anthropic" -> {
                val h = authHeaders(r) + ("anthropic-version" to "2023-06-01")
                val o = CardIO.json.parseToJsonElement(http("GET", joinUrl(r.baseUrl, "models?limit=100"), h, null, 20_000)).jsonObject
                stringsAt(o, "data", "id").sorted()
            }
            "gemini" -> {
                if (p.s("preset") == "vertex") return Presets.byId["vertex"]!!.models
                val o = CardIO.json.parseToJsonElement(http("GET", withQueryKey(joinUrl(r.baseUrl, "models?pageSize=200"), r), authHeaders(r), null, 20_000)).jsonObject
                o.arr("models").filter { m ->
                    val methods = (m["supportedGenerationMethods"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
                    methods == null || "generateContent" in methods
                }.map { it.s("name").removePrefix("models/") }.filter { it.isNotBlank() }.sorted()
            }
            else -> emptyList()
        }
    }

    suspend fun checkConnection(p: JsonObject): Pair<Boolean, String> = try {
        when (p.s("format")) {
            "novelai-image" -> {
                // Same check as the original (module 951 checkNaiConnection): subscription + Anlas.
                val r = resolve(p)
                val o = CardIO.json.parseToJsonElement(http("GET", joinUrl(r.baseUrl, "user/subscription"), authHeaders(r), null, 20_000)).jsonObject
                val steps = o.o("trainingStepsLeft")
                val anlas = if (steps == null) null else ((steps.d("fixedTrainingStepsLeft") ?: 0.0) + (steps.d("purchasedTrainingSteps") ?: 0.0)).toLong()
                true to "연결됨 · 구독 등급 ${o.sOrNull("tier") ?: "?"}" + (anlas?.let { " · Anlas $it" } ?: "")
            }
            "openai-chat", "openai-responses", "anthropic", "gemini", "jev" -> {
                val n = listModels(p).size
                true to if (n > 0) "연결됨 · 서버 모델 ${n}개" else "연결됨"
            }
            else -> false to "이 포맷은 모델 목록을 지원하지 않아 자동 확인할 수 없습니다. 모델 화면의 '연결 테스트'를 사용하세요."
        }
    } catch (e: Throwable) {
        false to (e.message ?: e.javaClass.simpleName)
    }

    /** "Reply with the single word: OK" round trip (testModel, module 945). Returns the reply. */
    suspend fun testModel(model: JsonObject): String {
        val p = SettingsStore.provider(model.s("providerId")) ?: throw ProviderError("모델에 연결된 프로바이더가 없습니다.")
        val r = resolve(p)
        val id = model.s("modelId")
        val maxTokens = (model.o("params").d("maxTokens")?.toInt() ?: 64).coerceIn(16, 256)
        val prompt = BuiltinPrompts.get("test.prompt")
        fun enc(o: JsonObject) = CardIO.json.encodeToString(JsonElement.serializer(), JsonObject(r.extraBody + o))
        return when (p.s("format")) {
            "openai-chat" -> {
                val body = buildJsonObject {
                    put("model", JsonPrimitive(id))
                    put("messages", buildJsonArray { add(buildJsonObject { put("role", JsonPrimitive("user")); put("content", JsonPrimitive(prompt)) }) })
                    put(r.tokenParam, JsonPrimitive(maxTokens))
                }
                val o = CardIO.json.parseToJsonElement(http("POST", withQueryKey(joinUrl(r.baseUrl, "chat/completions"), r), authHeaders(r), enc(body), 45_000)).jsonObject
                ((o["choices"] as? JsonArray)?.firstOrNull() as? JsonObject).o("message").s("content")
            }
            "openai-responses" -> {
                val body = buildJsonObject {
                    put("model", JsonPrimitive(id)); put("input", JsonPrimitive(prompt)); put("max_output_tokens", JsonPrimitive(maxTokens))
                }
                val o = CardIO.json.parseToJsonElement(http("POST", withQueryKey(joinUrl(r.baseUrl, "responses"), r), authHeaders(r), enc(body), 45_000)).jsonObject
                o.sOrNull("output_text") ?: o.arr("output").flatMap { it.arr("content") }.joinToString("") { it.s("text") }
            }
            "anthropic" -> {
                val body = buildJsonObject {
                    put("model", JsonPrimitive(id)); put("max_tokens", JsonPrimitive(maxTokens))
                    put("messages", buildJsonArray { add(buildJsonObject { put("role", JsonPrimitive("user")); put("content", JsonPrimitive(prompt)) }) })
                }
                val h = authHeaders(r) + ("anthropic-version" to "2023-06-01")
                val o = CardIO.json.parseToJsonElement(http("POST", joinUrl(r.baseUrl, "messages"), h, enc(body), 45_000)).jsonObject
                o.arr("content").filter { it.s("type") == "text" }.joinToString("") { it.s("text") }
            }
            "gemini" -> {
                val body = buildJsonObject {
                    put("contents", buildJsonArray {
                        add(buildJsonObject {
                            put("role", JsonPrimitive("user"))
                            put("parts", buildJsonArray { add(buildJsonObject { put("text", JsonPrimitive(prompt)) }) })
                        })
                    })
                    put("generationConfig", buildJsonObject { put("maxOutputTokens", JsonPrimitive(maxTokens)) })
                }
                val url = withQueryKey(joinUrl(r.baseUrl, "models/${URLEncoder.encode(id, "UTF-8")}:generateContent"), r)
                val o = CardIO.json.parseToJsonElement(http("POST", url, authHeaders(r), enc(body), 45_000)).jsonObject
                o.arr("candidates").firstOrNull().o("content").arr("parts").filter { !it.b("thought") }.joinToString("") { it.s("text") }
            }
            "novelai-image" -> throw ProviderError("NovelAI(image)는 이미지 생성 전용입니다. 이미지 탭 › 이미지 생성에서 사용하세요.")
            "jev" -> throw ProviderError("Jev는 채팅 모델이 아닙니다. 서브 프로세서 전략을 'Jev'로 설정해 사용하세요.")
            else -> throw ProviderError("지원하지 않는 포맷: ${p.s("format")}")
        }.trim().take(80)
    }
}

/** Vertex AI service-account auth (module 949): RS256 JWT → OAuth access token, cached ~1h. */
object Vertex {
    private val cache = mutableMapOf<String, Pair<String, Long>>()

    fun baseUrl(projectId: String, location: String): String {
        val loc = location.trim().ifEmpty { "global" }
        val host = if (loc == "global") "aiplatform.googleapis.com" else "$loc-aiplatform.googleapis.com"
        return "https://$host/v1/projects/${URLEncoder.encode(projectId.trim(), "UTF-8")}/locations/$loc/publishers/google"
    }

    fun normalizeKey(k: String) = k.replace("\\n", "\n").trim()

    /** Parses a pasted service-account JSON → (projectId, clientEmail, privateKey). */
    fun parseServiceAccount(text: String): Triple<String, String, String>? = runCatching {
        val o = CardIO.json.parseToJsonElement(text).jsonObject
        val pid = o.s("project_id"); val mail = o.s("client_email"); val pk = o.s("private_key")
        if (pid.isBlank() || mail.isBlank() || pk.isBlank()) null else Triple(pid, mail, normalizeKey(pk))
    }.getOrNull()

    private fun b64url(b: ByteArray) = Base64.encodeToString(b, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)

    suspend fun accessToken(clientEmail: String, privateKey: String): String {
        val cacheKey = "$clientEmail|${privateKey.length}"
        cache[cacheKey]?.let { (t, exp) -> if (exp - 60_000 > System.currentTimeMillis()) return t }
        if (clientEmail.isBlank() || privateKey.isBlank()) throw ProviderError("Vertex AI: Client Email과 Private Key가 필요합니다.")
        val now = System.currentTimeMillis() / 1000
        val header = """{"alg":"RS256","typ":"JWT"}"""
        val claims = buildJsonObject {
            put("iss", JsonPrimitive(clientEmail)); put("scope", JsonPrimitive("https://www.googleapis.com/auth/cloud-platform"))
            put("aud", JsonPrimitive("https://oauth2.googleapis.com/token")); put("iat", JsonPrimitive(now)); put("exp", JsonPrimitive(now + 3600))
        }.toString()
        val jwt = try {
            val unsigned = b64url(header.toByteArray()) + "." + b64url(claims.toByteArray())
            val pem = normalizeKey(privateKey).replace(Regex("-----[A-Z ]+-----"), "").replace(Regex("\\s"), "")
            val key = KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(Base64.decode(pem, Base64.DEFAULT)))
            val sig = Signature.getInstance("SHA256withRSA").apply { initSign(key); update(unsigned.toByteArray()) }.sign()
            unsigned + "." + b64url(sig)
        } catch (e: Throwable) {
            throw ProviderError("Vertex AI: Private Key로 서명하지 못했습니다. PEM 형식(-----BEGIN PRIVATE KEY-----)인지 확인하세요. (${e.message})")
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
                    val o = runCatching { CardIO.json.parseToJsonElement(body).jsonObject }.getOrNull()
                    val m = "${o.s("error")} ${o.s("error_description")}".trim().ifEmpty { body.take(300) }
                    val hint = if ("invalid_grant" in m) " (Client Email/Private Key가 서로 맞는지, 기기 시간이 정확한지 확인하세요)" else ""
                    throw ProviderError("Vertex AI 토큰 발급 실패 ($code): $m$hint", code)
                }
                body
            } finally { c.disconnect() }
        }
        val o = CardIO.json.parseToJsonElement(text).jsonObject
        val token = o.s("access_token")
        val expires = (o.d("expires_in") ?: 3600.0).toLong()
        cache[cacheKey] = token to (System.currentTimeMillis() + expires * 1000)
        return token
    }
}
