package space.nami.browser

import android.util.Base64
import android.util.LruCache
import com.google.android.gms.tasks.Task
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Result of one batch: the translations plus which engine actually did it. */
class Translated(val texts: List<String>, val engine: String)

/**
 * Chrome-style page translation backend. Always into Korean.
 * GOOGLE: Google's free endpoint (what Chrome-like extensions use); on failure → ML Kit on the phone.
 * VERTEX: Gemini through the user's service account; on failure → Google → ML Kit.
 */
class TranslatorHub(private val prefs: Prefs) {
    private val cache = LruCache<String, String>(4000)
    private val net = Semaphore(4)

    suspend fun translate(texts: List<String>): Translated {
        val out = arrayOfNulls<String>(texts.size)
        val todo = ArrayList<Int>()
        texts.forEachIndexed { i, t -> val c = cache.get(t); if (c != null) out[i] = c else todo.add(i) }
        if (todo.isEmpty()) return Translated(out.map { it!! }, "cache")
        val src = todo.map { texts[it] }
        var engine = prefs.engine
        var res: List<String>? = null
        var used = ""
        if (engine == Engine.VERTEX) {
            res = runCatching { vertex(src) }.getOrNull(); used = "vertex"
            if (res == null) engine = Engine.GOOGLE
        }
        if (res == null && engine == Engine.GOOGLE) { res = runCatching { google(src) }.getOrNull(); used = "google" }
        val done: List<String> = res ?: device(src).also { used = "device" }
        todo.forEachIndexed { k, i -> out[i] = done[k]; if (done[k] != texts[i]) cache.put(texts[i], done[k]) }
        return Translated(out.map { it ?: "" }, used)
    }

    // ---------- Google (free endpoint) ----------

    private suspend fun google(texts: List<String>): List<String> {
        // Batch endpoint first (many q= in one request); if its shape surprises us, one request per text.
        runCatching { googleBatch(texts) }.getOrNull()?.let { if (it.size == texts.size) return it }
        return coroutineScope { texts.map { t -> async { net.withPermit { googleOne(t) } } }.awaitAll() }
    }

    private suspend fun googleBatch(texts: List<String>): List<String> = net.withPermit {
        val body = texts.joinToString("&") { "q=" + URLEncoder.encode(it, "UTF-8") }
        val raw = post("https://translate.googleapis.com/translate_a/t?client=gtx&sl=auto&tl=ko&dt=t&format=text", body,
            "application/x-www-form-urlencoded;charset=UTF-8")
        val arr = JSONArray(raw)
        // Shapes seen in the wild: ["t1","t2"], [["t1","en"],["t2","en"]], or for one q: "t" / ["t","en"].
        val list = (0 until arr.length()).map { i ->
            when (val e = arr.get(i)) {
                is String -> e
                is JSONArray -> e.getString(0)
                else -> throw IllegalStateException("shape")
            }
        }
        if (texts.size == 1 && list.size == 2 && arr.get(0) is String && arr.get(1) is String && list[1].length <= 6) list.take(1).map(::unescape)
        else list.map(::unescape)
    }

    private suspend fun googleOne(text: String): String {
        val raw = post("https://translate.googleapis.com/translate_a/single?client=gtx&sl=auto&tl=ko&dt=t",
            "q=" + URLEncoder.encode(text, "UTF-8"), "application/x-www-form-urlencoded;charset=UTF-8")
        val parts = JSONArray(raw).getJSONArray(0)
        val sb = StringBuilder()
        for (i in 0 until parts.length()) {
            val p = parts.optJSONArray(i) ?: continue
            if (!p.isNull(0)) sb.append(p.optString(0))
        }
        return sb.toString()
    }

    private fun unescape(s: String): String {
        if ('&' !in s) return s
        return s.replace(Regex("&#(\\d+);")) { it.groupValues[1].toIntOrNull()?.let { c -> String(Character.toChars(c)) } ?: it.value }
            .replace("&quot;", "\"").replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&")
    }

    // ---------- Vertex (Gemini) ----------

    private var token: Pair<String, Long>? = null

    private suspend fun vertex(texts: List<String>): List<String> {
        val sa = JSONObject(prefs.vertexJson)
        val project = sa.getString("project_id")
        val loc = prefs.vertexLocation.trim().ifEmpty { "global" }
        val host = if (loc == "global") "aiplatform.googleapis.com" else "$loc-aiplatform.googleapis.com"
        val model = prefs.vertexModel.trim().ifEmpty { "gemini-2.5-flash" }
        val url = "https://$host/v1/projects/$project/locations/$loc/publishers/google/models/$model:generateContent"
        val instr = "You translate web page text into natural Korean. The user message is a JSON array of text fragments " +
            "from one page, in reading order. Reply with ONLY a JSON array of strings of exactly the same length and order, " +
            "each item the Korean translation of the matching fragment. Keep numbers, URLs, usernames and symbols as they are. " +
            "Tags like long_hair or 1girl: translate their meaning briefly. If a fragment is already Korean or has nothing to translate, return it unchanged."
        val body = JSONObject()
            .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", instr))))
            .put("contents", JSONArray().put(JSONObject().put("role", "user")
                .put("parts", JSONArray().put(JSONObject().put("text", JSONArray(texts).toString())))))
            .put("generationConfig", JSONObject().put("responseMimeType", "application/json"))
        val raw = net.withPermit {
            post(url, body.toString(), "application/json", "Bearer " + vertexToken(sa))
        }
        val parts = JSONObject(raw).getJSONArray("candidates").getJSONObject(0).getJSONObject("content").getJSONArray("parts")
        val sb = StringBuilder()
        for (i in 0 until parts.length()) {
            val p = parts.getJSONObject(i)
            if (!p.optBoolean("thought")) sb.append(p.optString("text"))
        }
        val txt = sb.toString().trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        val arr = JSONArray(txt)
        if (arr.length() != texts.size) throw IllegalStateException("length")
        return (0 until arr.length()).map { arr.optString(it) }
    }

    private suspend fun vertexToken(sa: JSONObject): String {
        token?.let { (t, exp) -> if (exp - 60_000 > System.currentTimeMillis()) return t }
        val email = sa.getString("client_email")
        val pk = sa.getString("private_key").replace("\\n", "\n")
        val now = System.currentTimeMillis() / 1000
        fun b64(b: ByteArray) = Base64.encodeToString(b, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        val claims = JSONObject().put("iss", email).put("scope", "https://www.googleapis.com/auth/cloud-platform")
            .put("aud", "https://oauth2.googleapis.com/token").put("iat", now).put("exp", now + 3600)
        val unsigned = b64("""{"alg":"RS256","typ":"JWT"}""".toByteArray()) + "." + b64(claims.toString().toByteArray())
        val pem = pk.replace(Regex("-----[A-Z ]+-----"), "").replace(Regex("\\s"), "")
        val key = KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(Base64.decode(pem, Base64.DEFAULT)))
        val sig = Signature.getInstance("SHA256withRSA").apply { initSign(key); update(unsigned.toByteArray()) }.sign()
        val form = "grant_type=" + URLEncoder.encode("urn:ietf:params:oauth:grant-type:jwt-bearer", "UTF-8") +
            "&assertion=" + URLEncoder.encode(unsigned + "." + b64(sig), "UTF-8")
        val o = JSONObject(post("https://oauth2.googleapis.com/token", form, "application/x-www-form-urlencoded"))
        val t = o.getString("access_token")
        token = t to (System.currentTimeMillis() + o.optLong("expires_in", 3600) * 1000)
        return t
    }

    /** Quick check from the settings screen. Returns a short Korean status line. */
    suspend fun test(): String = runCatching {
        val r = when (prefs.engine) {
            Engine.VERTEX -> vertex(listOf("Hello, world!"))
            Engine.GOOGLE -> google(listOf("Hello, world!"))
            Engine.DEVICE -> device(listOf("Hello, world!"))
        }
        "잘 돼요: Hello, world! → ${r.first()}"
    }.getOrElse { "실패: ${it.message ?: it.javaClass.simpleName}" }

    // ---------- ML Kit (on the phone) ----------

    private val translators = HashMap<String, Translator>()
    private val langId by lazy { LanguageIdentification.getClient() }

    private suspend fun device(texts: List<String>): List<String> {
        val joined = texts.joinToString(" ").take(1000)
        val tag = runCatching { langId.identifyLanguage(joined).await() }.getOrNull() ?: "und"
        val src = TranslateLanguage.fromLanguageTag(tag) ?: TranslateLanguage.ENGLISH
        if (src == TranslateLanguage.KOREAN) return texts
        val tr = synchronized(translators) {
            translators.getOrPut(src) {
                Translation.getClient(TranslatorOptions.Builder().setSourceLanguage(src).setTargetLanguage(TranslateLanguage.KOREAN).build())
            }
        }
        tr.downloadModelIfNeeded().await()
        return texts.map { t -> runCatching { tr.translate(t).await() }.getOrDefault(t) }
    }

    // ---------- http ----------

    private suspend fun post(url: String, body: String, type: String, auth: String? = null): String = withContext(Dispatchers.IO) {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.requestMethod = "POST"; c.doOutput = true; c.connectTimeout = 15_000; c.readTimeout = 60_000
            c.setRequestProperty("Content-Type", type)
            c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 16) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/141.0 Mobile Safari/537.36")
            if (auth != null) c.setRequestProperty("Authorization", auth)
            c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = c.responseCode
            val text = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() } ?: ""
            if (code !in 200..299) throw IllegalStateException("HTTP $code ${text.take(200)}")
            text
        } finally { c.disconnect() }
    }
}

suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { cont ->
    addOnSuccessListener { cont.resume(it) }
    addOnFailureListener { cont.resumeWithException(it) }
    addOnCanceledListener { cont.cancel() }
}
