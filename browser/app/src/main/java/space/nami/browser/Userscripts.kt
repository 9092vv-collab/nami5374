package space.nami.browser

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.mutableStateListOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream

/** One installed userscript (metadata parsed from its ==UserScript== block). */
class UScript(
    val id: String,
    val source: String,
    var enabled: Boolean,
    val installUrl: String?,
) {
    val meta: Map<String, List<String>> = parseMeta(source)
    fun one(k: String) = meta[k]?.firstOrNull() ?: ""
    val name get() = one("name:ko").ifEmpty { one("name") }.ifEmpty { "이름 없는 스크립트" }
    val version get() = one("version")
    val namespace get() = one("namespace")
    val description get() = one("description:ko").ifEmpty { one("description") }
    val runAt get() = one("run-at").ifEmpty { "document-end" }
    val matches get() = (meta["match"] ?: emptyList()) + (meta["include"] ?: emptyList())
    val updateUrl get() = one("downloadURL").ifEmpty { one("updateURL") }.ifEmpty { installUrl ?: "" }

    companion object {
        fun parseMeta(src: String): Map<String, List<String>> {
            val m = LinkedHashMap<String, MutableList<String>>()
            val block = Regex("//\\s*==UserScript==([\\s\\S]*?)//\\s*==/UserScript==").find(src)?.groupValues?.get(1) ?: return m
            for (line in block.lines()) {
                val r = Regex("^\\s*//\\s*@([\\w:.\\-]+)\\s*(.*?)\\s*$").find(line) ?: continue
                val key = r.groupValues[1].substringBefore(':').let { if (it == r.groupValues[1]) it else r.groupValues[1] }
                m.getOrPut(key) { ArrayList() }.add(r.groupValues[2])
            }
            return m
        }
    }
}

/**
 * Tampermonkey-compatible userscript manager.
 * Scripts are injected at document start into every frame by a small wrapper that checks
 * @match/@include/@exclude, waits for @run-at and provides the common GM_* / GM.* APIs
 * (values, addStyle, addElement, xmlhttpRequest, openInTab, setClipboard, notification,
 * registerMenuCommand, getResourceText/URL, download, info, log). @require and @resource are fetched at install time.
 */
class Userscripts(private val ctx: Context) {
    val list = mutableStateListOf<UScript>()
    var onChange: () -> Unit = {}
    private val dir = File(ctx.filesDir, "userscripts").apply { mkdirs() }
    private val index = File(dir, "index.json")

    init { load() }

    private fun load() {
        list.clear()
        val arr = runCatching { JSONArray(index.readText()) }.getOrDefault(JSONArray())
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val id = o.getString("id")
            val f = File(dir, "$id.user.js")
            if (f.exists()) list.add(UScript(id, f.readText(), o.optBoolean("enabled", true), o.optString("url").ifEmpty { null }))
        }
    }

    private fun saveIndex() {
        val arr = JSONArray()
        list.forEach { arr.put(JSONObject().put("id", it.id).put("enabled", it.enabled).put("url", it.installUrl ?: "")) }
        index.writeText(arr.toString())
        onChange()
    }

    fun setEnabled(s: UScript, on: Boolean) {
        val i = list.indexOf(s); if (i < 0) return
        list[i] = UScript(s.id, s.source, on, s.installUrl); saveIndex()
    }

    fun remove(s: UScript) {
        list.remove(s)
        File(dir, "${s.id}.user.js").delete(); File(dir, "${s.id}.req.js").delete(); File(dir, "${s.id}.res.json").delete()
        ctx.getSharedPreferences("gm_${s.id}", Context.MODE_PRIVATE).edit().clear().apply()
        saveIndex()
    }

    /** Installs (or replaces a script with the same name + namespace). Fetches @require / @resource. */
    suspend fun install(source: String, url: String?): UScript = withContext(Dispatchers.IO) {
        val probe = UScript("probe", source, true, url)
        if (probe.meta.isEmpty()) throw IllegalArgumentException("==UserScript== 머리말이 없어요")
        val old = list.firstOrNull { it.name == probe.name && it.namespace == probe.namespace }
        val id = old?.id ?: ("s" + System.currentTimeMillis().toString(36) + (100..999).random())
        val base = url
        val req = StringBuilder()
        for (r in probe.meta["require"] ?: emptyList()) {
            runCatching { req.append(fetch(resolve(base, r))).append("\n;\n") }
        }
        val res = JSONObject()
        for (r in probe.meta["resource"] ?: emptyList()) {
            val parts = r.split(Regex("\\s+"), 2)
            if (parts.size == 2) runCatching { res.put(parts[0], fetch(resolve(base, parts[1]))) }
        }
        File(dir, "$id.user.js").writeText(source)
        File(dir, "$id.req.js").writeText(req.toString())
        File(dir, "$id.res.json").writeText(res.toString())
        val s = UScript(id, source, old?.enabled ?: true, url ?: old?.installUrl)
        withContext(Dispatchers.Main) {
            val i = list.indexOfFirst { it.id == id }
            if (i >= 0) list[i] = s else list.add(s)
            saveIndex()
        }
        s
    }

    suspend fun installFromUrl(url: String) = install(fetch(url), url)

    /** .user.js / .js, a Tampermonkey zip backup, or a Tampermonkey JSON backup. Returns how many were added. */
    suspend fun importFile(uri: Uri): Int = withContext(Dispatchers.IO) {
        val bytes = ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: throw IllegalStateException("파일을 열 수 없어요")
        var n = 0
        if (bytes.size > 2 && bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte()) {
            ZipInputStream(bytes.inputStream()).use { z ->
                while (true) {
                    val e = z.nextEntry ?: break
                    if (!e.isDirectory && e.name.endsWith(".user.js")) { install(z.readBytes().toString(Charsets.UTF_8), null); n++ }
                }
            }
        } else {
            val text = bytes.toString(Charsets.UTF_8)
            if (text.contains("==UserScript==") && !text.trimStart().startsWith("{")) { install(text, null); n++ }
            else {
                val o = JSONObject(text)
                val arr = o.optJSONArray("scripts") ?: JSONArray()
                for (i in 0 until arr.length()) {
                    val sc = arr.getJSONObject(i)
                    val src = sc.optString("source").ifEmpty { sc.optString("code") }
                    if (src.contains("==UserScript==")) { install(src, null); n++ }
                }
            }
        }
        n
    }

    suspend fun update(s: UScript): String {
        val url = s.updateUrl.ifEmpty { return "업데이트 주소가 없어요" }
        val src = fetch(url)
        val v = UScript("probe", src, true, url).version
        if (v == s.version && src == s.source) return "이미 최신이에요 ($v)"
        install(src, s.installUrl ?: url)
        return "업데이트했어요 (${s.version} → $v)"
    }

    private fun resolve(base: String?, u: String): String =
        if (base == null || Regex("^[a-z]+:").containsMatchIn(u)) u else URL(URL(base), u).toString()

    suspend fun fetch(url: String): String = withContext(Dispatchers.IO) {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 15_000; c.readTimeout = 30_000; c.instanceFollowRedirects = true
            if (c.responseCode !in 200..299) throw IllegalStateException("HTTP ${c.responseCode}")
            c.inputStream.bufferedReader().use { it.readText() }
        } finally { c.disconnect() }
    }

    // ---------- values (GM_getValue / GM_setValue) ----------

    fun values(id: String): String {
        val sp = ctx.getSharedPreferences("gm_$id", Context.MODE_PRIVATE)
        val o = JSONObject()
        sp.all.forEach { (k, v) -> runCatching { o.put(k, JSONObject("{\"v\":$v}").get("v")) } }
        return o.toString()
    }
    fun setValue(id: String, key: String, json: String) = ctx.getSharedPreferences("gm_$id", Context.MODE_PRIVATE).edit().putString(key, json).apply()
    fun deleteValue(id: String, key: String) = ctx.getSharedPreferences("gm_$id", Context.MODE_PRIVATE).edit().remove(key).apply()

    // ---------- injection ----------

    /** Wrapper JS for every enabled script, ready for addDocumentStartJavaScript. */
    fun wrappers(): List<String> = list.filter { it.enabled }.map { wrap(it) }

    private fun matchToRegex(p: String): String {
        val t = p.trim()
        if (t == "<all_urls>" || t == "*") return "^(https?|file|ftp)://"
        if (t.length > 2 && t.startsWith("/") && t.endsWith("/")) return t.substring(1, t.length - 1)
        val m = Regex("^(\\*|https?|file|ftp)://([^/]*)(/.*)?$").find(t)
        if (m != null) {
            val scheme = if (m.groupValues[1] == "*") "https?" else Regex.escape(m.groupValues[1]).let { m.groupValues[1] }
            val host = m.groupValues[2]
            val hostRe = when {
                host == "*" -> "[^/]*"
                host.startsWith("*.") -> "(?:[^/]*\\.)?" + glob(host.substring(2))
                else -> glob(host)
            }
            val path = m.groupValues[3].ifEmpty { "/" }
            return "^$scheme://$hostRe(?::\\d+)?" + glob(path) + if (path.endsWith("*")) "" else "$"
        }
        return "^" + glob(t) + "$"
    }

    private fun glob(s: String): String {
        val sb = StringBuilder()
        for (c in s) when (c) {
            '*' -> sb.append(".*")
            in "\\.+?()[]{}^$|/" -> sb.append('\\').append(c)
            else -> sb.append(c)
        }
        return sb.toString()
    }

    private fun q(s: String) = JSONObject.quote(s)

    private fun wrap(s: UScript): String {
        val inc = s.matches.ifEmpty { listOf("*") }.map { matchToRegex(it) }
        val exc = ((s.meta["exclude"] ?: emptyList()) + (s.meta["exclude-match"] ?: emptyList())).map { matchToRegex(it) }
        val req = runCatching { File(dir, "${s.id}.req.js").readText() }.getOrDefault("")
        val res = runCatching { File(dir, "${s.id}.res.json").readText() }.getOrDefault("{}")
        val info = JSONObject()
            .put("script", JSONObject().put("name", s.name).put("namespace", s.namespace).put("version", s.version)
                .put("description", s.description).put("runAt", s.runAt))
            .put("scriptHandler", "Tampermonkey").put("version", "5.0 (Nami)").put("scriptMetaStr", "")
        return """
(function(){
  var U = location.href;
  if (!/^(https?|file|ftp):/.test(U)) return;
  ${if (s.meta.containsKey("noframes")) "if (window.top !== window) return;" else ""}
  var INC = [${inc.joinToString(",") { "new RegExp(${q(it)})" }}];
  var EXC = [${exc.joinToString(",") { "new RegExp(${q(it)})" }}];
  try { if (!INC.some(function(r){return r.test(U);}) || EXC.some(function(r){return r.test(U);})) return; } catch(e) { return; }
  var B = window.NamiBridge; if (!B) return;
  var SID = ${q(s.id)};
  var RES = $res;
  var VALS = {}; try { VALS = JSON.parse(B.gmValues(SID) || '{}'); } catch(e) {}
  var GM_info = $info;
  function GM_getValue(k, d) { return Object.prototype.hasOwnProperty.call(VALS, k) ? VALS[k] : d; }
  function GM_setValue(k, v) { VALS[k] = v; try { B.gmSet(SID, String(k), JSON.stringify(v)); } catch(e) {} }
  function GM_deleteValue(k) { delete VALS[k]; try { B.gmDel(SID, String(k)); } catch(e) {} }
  function GM_listValues() { return Object.keys(VALS); }
  function GM_addStyle(css) { var st = document.createElement('style'); st.textContent = css; (document.head || document.documentElement).appendChild(st); return st; }
  function GM_addElement(a, b, c) { var parent, tag, attrs; if (typeof a === 'string') { parent = (a === 'script' || a === 'style' || a === 'link') ? (document.head || document.documentElement) : (document.body || document.documentElement); tag = a; attrs = b; } else { parent = a; tag = b; attrs = c; }
    var el = document.createElement(tag); if (attrs) for (var k in attrs) { if (k === 'textContent') el.textContent = attrs[k]; else el.setAttribute(k, attrs[k]); } parent.appendChild(el); return el; }
  function GM_openInTab(url, o) { var bg = (typeof o === 'boolean') ? o : !!(o && (o.active === false || o.loadInBackground)); B.gmOpen(String(url), bg); return { close: function(){}, closed: false }; }
  function GM_setClipboard(t) { B.gmClip(String(t)); }
  function GM_notification(d, title) { var text = (typeof d === 'object' && d) ? (d.text || d.title || '') : String(d); B.gmToast(text); }
  function GM_log() { console.log.apply(console, arguments); }
  function GM_getResourceText(n) { return RES[n] || null; }
  function GM_getResourceURL(n) { var t = RES[n]; return t == null ? null : 'data:text/plain;charset=utf-8,' + encodeURIComponent(t); }
  function GM_download(d, name) { var url = (typeof d === 'object') ? d.url : d; var nm = (typeof d === 'object') ? d.name : name; B.gmDownload(String(url), nm ? String(nm) : ''); }
  var MENU = window.__namiMenu = window.__namiMenu || [];
  function GM_registerMenuCommand(name, fn) { var id = SID + ':' + MENU.length; MENU.push({ id: id, name: String(name), fn: fn }); return id; }
  function GM_unregisterMenuCommand(id) { for (var i = 0; i < MENU.length; i++) if (MENU[i].id === id) { MENU.splice(i, 1); break; } }
  var CB = window.__namiGmCb = window.__namiGmCb || {};
  function GM_xmlhttpRequest(d) {
    var rid = 'x' + Math.random().toString(36).slice(2);
    var aborted = false;
    CB[rid] = function(r) {
      delete CB[rid]; if (aborted) return;
      if (r.error) { if (d.ontimeout && r.timeout) d.ontimeout(r); else if (d.onerror) d.onerror(r); if (d.onloadend) d.onloadend(r); return; }
      var rt = d.responseType || '';
      if (r.b64 != null) { var bin = atob(r.b64), u8 = new Uint8Array(bin.length); for (var i = 0; i < bin.length; i++) u8[i] = bin.charCodeAt(i);
        r.response = rt === 'blob' ? new Blob([u8], { type: r.contentType || '' }) : u8.buffer; }
      else if (rt === 'json') { try { r.response = JSON.parse(r.responseText); } catch(e) { r.response = null; } }
      else if (rt === 'document') { try { r.response = new DOMParser().parseFromString(r.responseText, 'text/html'); } catch(e) {} }
      else r.response = r.responseText;
      r.readyState = 4; r.context = d.context;
      if (d.onreadystatechange) d.onreadystatechange(r);
      if (d.onload) d.onload(r);
      if (d.onloadend) d.onloadend(r);
    };
    var data = d.data; if (data != null && typeof data !== 'string') { try { data = (data instanceof URLSearchParams) ? data.toString() : JSON.stringify(data); } catch(e) { data = String(data); } }
    B.gmXhr(JSON.stringify({ id: rid, method: d.method || 'GET', url: String(new URL(d.url, location.href)), headers: d.headers || {}, data: data == null ? null : data,
      binary: d.responseType === 'arraybuffer' || d.responseType === 'blob', timeout: d.timeout || 0, anonymous: !!d.anonymous, frame: window.top === window }));
    return { abort: function(){ aborted = true; if (d.onabort) d.onabort({}); } };
  }
  function P(f) { return function() { var a = arguments; return new Promise(function(ok, no) { try { ok(f.apply(null, a)); } catch(e) { no(e); } }); }; }
  var GM = { info: GM_info, getValue: P(GM_getValue), setValue: P(GM_setValue), deleteValue: P(GM_deleteValue), listValues: P(GM_listValues),
    addStyle: P(GM_addStyle), addElement: P(GM_addElement), openInTab: P(GM_openInTab), setClipboard: P(GM_setClipboard), notification: P(GM_notification),
    getResourceUrl: P(GM_getResourceURL), getResourceText: P(GM_getResourceText), registerMenuCommand: P(GM_registerMenuCommand), download: P(GM_download), log: GM_log,
    xmlHttpRequest: function(d) { return new Promise(function(ok, no) { var o = Object.assign({}, d); o.onload = function(r){ if (d.onload) d.onload(r); ok(r); }; o.onerror = function(r){ if (d.onerror) d.onerror(r); no(r); }; GM_xmlhttpRequest(o); }); } };
  var unsafeWindow = window;
  function run() {
    try {
      (function(unsafeWindow, GM, GM_info, GM_getValue, GM_setValue, GM_deleteValue, GM_listValues, GM_addStyle, GM_addElement, GM_openInTab,
               GM_setClipboard, GM_notification, GM_log, GM_getResourceText, GM_getResourceURL, GM_download, GM_registerMenuCommand,
               GM_unregisterMenuCommand, GM_xmlhttpRequest) {
$req
${s.source}
      }).call(window, unsafeWindow, GM, GM_info, GM_getValue, GM_setValue, GM_deleteValue, GM_listValues, GM_addStyle, GM_addElement, GM_openInTab,
              GM_setClipboard, GM_notification, GM_log, GM_getResourceText, GM_getResourceURL, GM_download, GM_registerMenuCommand,
              GM_unregisterMenuCommand, GM_xmlhttpRequest);
    } catch (e) { console.error('[Nami userscript] ' + GM_info.script.name, e); }
  }
  var at = ${q(s.runAt)};
  if (at === 'document-start') run();
  else if (at === 'document-body') { if (document.body) run(); else new MutationObserver(function(m, o){ if (document.body) { o.disconnect(); run(); } }).observe(document.documentElement, { childList: true }); }
  else if (at === 'document-idle' || at === 'context-menu') { if (document.readyState === 'complete') setTimeout(run, 0); else window.addEventListener('load', function(){ setTimeout(run, 0); }, { once: true }); }
  else { if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', run, { once: true }); else run(); }
})();
"""
    }
}
