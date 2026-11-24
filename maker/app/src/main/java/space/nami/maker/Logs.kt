package space.nami.maker

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 요청 로그 (next의 요청 로그를 따라 함): AI에 보낸 요청과 받은 응답, 걸린 시간, 토큰(캐시 포함).
 * 그림 데이터(base64)는 너무 커서 줄여 적고, 키는 본문에 없어서 따로 가릴 것이 없음.
 */
object Logs {
    var rev by mutableIntStateOf(0)
    private var file: File? = null
    private val list = ArrayList<JSONObject>()
    private var settings = JSONObject()
    private var setFile: File? = null

    /** 요청 하나를 기록하는 동안 채우는 칸 */
    class Rec {
        var body: JSONObject? = null
        val usage = JSONObject()
        var finish = ""
    }

    fun init(ctx: Context) {
        if (file != null) return
        file = File(ctx.filesDir, "logs.json")
        setFile = File(ctx.filesDir, "logs_settings.json")
        settings = runCatching { JSONObject(setFile!!.readText()) }.getOrDefault(JSONObject())
        runCatching { JSONArray(file!!.readText()) }.getOrNull()?.let { a -> for (i in 0 until a.length()) a.optJSONObject(i)?.let { list.add(it) } }
        rev++
    }

    fun enabled() = settings.optBoolean("enabled", true)
    fun setEnabled(v: Boolean) { settings.put("enabled", v); saveSettings() }
    fun max() = settings.optInt("max", 50).coerceIn(5, 500)
    fun setMax(v: Int) { settings.put("max", v.coerceIn(5, 500)); saveSettings() }
    private fun saveSettings() { runCatching { setFile?.writeText(settings.toString()) }; rev++ }

    fun entries(): List<JSONObject> = synchronized(list) { list.toList() }

    fun clear() { synchronized(list) { list.clear() }; save() }

    fun add(kind: String, model: String, provider: String, request: JSONObject?, response: JSONObject?, ms: Long, usage: JSONObject?, error: String? = null) {
        if (!enabled()) return
        val e = JSONObject().put("id", "log" + System.nanoTime().toString(36)).put("at", System.currentTimeMillis())
            .put("kind", kind).put("model", model).put("provider", provider).put("ms", ms)
        request?.let { e.put("request", shrink(JSONObject(it.toString()))) }
        response?.let { e.put("response", shrink(JSONObject(it.toString()))) }
        if (usage != null && usage.length() > 0) e.put("usage", usage)
        error?.let { e.put("error", it) }
        synchronized(list) {
            list.add(0, e)
            while (list.size > max()) list.removeAt(list.size - 1)
        }
        save()
    }

    private fun save() {
        val snap = JSONArray(entries())
        rev++
        Thread { runCatching { file?.writeText(snap.toString()) } }.start()
    }

    /** 그림 데이터(긴 base64)는 "[그림 데이터 NKB 생략]"으로 */
    private fun shrink(v: Any?): Any? = when (v) {
        is JSONObject -> v.also { o ->
            o.keys().asSequence().toList().forEach { k ->
                val x = o.opt(k)
                if (k == "data" && x is String && x.length > 500) o.put(k, "[그림 데이터 ${x.length * 3 / 4 / 1024}KB 생략]")
                else shrink(x)
            }
        }
        is JSONArray -> v.also { a -> for (i in 0 until a.length()) shrink(a.opt(i)) }
        else -> v
    }

    // ---------- 복사용 글 ----------

    fun usageLine(u: JSONObject?): String {
        if (u == null || u.length() == 0) return ""
        val parts = ArrayList<String>()
        if (u.has("input")) parts.add("입력 ${u.optInt("input")}")
        if (u.has("cached")) parts.add("캐시 ${u.optInt("cached")}" + (if (u.optInt("input") > 0) " (${u.optInt("cached") * 100 / u.optInt("input")}%)" else ""))
        if (u.has("cacheWrite")) parts.add("캐시 쓰기 ${u.optInt("cacheWrite")}")
        if (u.has("output")) parts.add("출력 ${u.optInt("output")}")
        if (u.has("thinking")) parts.add("추론 ${u.optInt("thinking")}")
        return parts.joinToString(" · ") + " 토큰"
    }

    /** 요청을 사람이 읽기 좋은 글로: [시스템] / [유저] / [어시스턴트] 순서대로 */
    fun readable(req: JSONObject?): String {
        if (req == null) return ""
        val sb = StringBuilder()
        fun sec(t: String, body: String) { if (body.isBlank()) return; if (sb.isNotEmpty()) sb.append("\n\n"); sb.append("[").append(t).append("]\n").append(body.trim()) }
        // Anthropic
        if (req.has("messages")) {
            val sys = req.opt("system")
            sec("시스템", when (sys) { is String -> sys; is JSONArray -> (0 until sys.length()).joinToString("\n") { sys.optJSONObject(it)?.optString("text") ?: "" }; else -> "" })
            val ms = req.optJSONArray("messages") ?: JSONArray()
            for (i in 0 until ms.length()) {
                val m = ms.optJSONObject(i) ?: continue
                val c = m.opt("content")
                val t = when (c) {
                    is String -> c
                    is JSONArray -> (0 until c.length()).mapNotNull { k -> c.optJSONObject(k)?.let { b -> when (b.optString("type")) {
                        "text" -> b.optString("text"); "image" -> "[그림]"; "tool_result" -> b.optString("content"); else -> null } } }.joinToString("\n\n")
                    else -> ""
                }
                sec(if (m.optString("role") == "assistant") "어시스턴트" else "유저", t)
            }
            return sb.toString()
        }
        // Gemini
        req.optJSONObject("systemInstruction")?.optJSONArray("parts")?.let { ps -> sec("시스템", (0 until ps.length()).joinToString("\n") { ps.optJSONObject(it)?.optString("text") ?: "" }) }
        val cs = req.optJSONArray("contents") ?: JSONArray()
        for (i in 0 until cs.length()) {
            val m = cs.optJSONObject(i) ?: continue
            val ps = m.optJSONArray("parts") ?: JSONArray()
            val t = (0 until ps.length()).mapNotNull { k -> ps.optJSONObject(k)?.let { p -> when {
                p.has("text") -> p.optString("text"); p.has("inlineData") -> "[그림]"; p.has("functionCall") -> "[도구 호출] " + p.optJSONObject("functionCall"); else -> null } } }.joinToString("\n\n")
            sec(if (m.optString("role") == "model") "어시스턴트" else "유저", t)
        }
        return sb.toString()
    }

    /** 응답 본문 글만 */
    fun answer(res: JSONObject?): String {
        if (res == null) return ""
        res.optString("text").takeIf { it.isNotBlank() }?.let { return it }
        val a = res.optJSONArray("blocks") ?: return res.toString(2)
        return (0 until a.length()).mapNotNull { a.optJSONObject(it) }.joinToString("\n\n") { b ->
            if (b.optString("type") == "text") b.optString("text") else b.toString(2)
        }
    }

    fun full(e: JSONObject): String {
        val time = java.text.SimpleDateFormat("yyyy.MM.dd HH:mm:ss", java.util.Locale.KOREA).format(java.util.Date(e.optLong("at")))
        val head = listOf(time, e.optString("model"), "%.1f초".format(e.optLong("ms") / 1000.0), usageLine(e.optJSONObject("usage"))).filter { it.isNotBlank() }.joinToString(" · ")
        val sb = StringBuilder(head)
        e.optString("error").takeIf { it.isNotBlank() }?.let { sb.append("\n오류: ").append(it) }
        sb.append("\n\n===== 요청 =====\n").append(readable(e.optJSONObject("request")))
        e.optJSONObject("response")?.let { r ->
            r.optString("reasoning").takeIf { it.isNotBlank() }?.let { sb.append("\n\n===== 추론 =====\n").append(it) }
            sb.append("\n\n===== 응답 =====\n").append(answer(r))
        }
        return sb.toString()
    }
}
