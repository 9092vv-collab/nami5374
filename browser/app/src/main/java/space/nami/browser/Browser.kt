package space.nami.browser

import android.annotation.SuppressLint
import android.app.Activity
import android.app.DownloadManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.Toast
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONArray
import java.net.URLEncoder

/** One open tab. */
class BrowserTab(val id: Long, val web: WebView, val frame: PullFrame) {
    /** Files this tab's page is streaming to us right now (keep it running even when hidden). */
    @Volatile var relays = 0
    var title by mutableStateOf("")
    var url by mutableStateOf("")
    var progress by mutableIntStateOf(100)
    var canBack by mutableStateOf(false)
    var canForward by mutableStateOf(false)
    /** Translation wanted on this tab (stays on while moving around the same site). */
    var translateOn by mutableStateOf(false)
    var translating by mutableStateOf(false)
    /** Small screenshot for the tab list (taken when leaving the tab). */
    var thumb by mutableStateOf<Bitmap?>(null)
    @Volatile var host = ""
    var fellBackNoticed = false
    /** When the user last tapped a link (redirects right after a tap may also go to an app, like t.co → x.com). */
    var lastGesture = 0L
    val blocked = java.util.concurrent.atomic.AtomicInteger(0)
    val handlers = ArrayList<androidx.webkit.ScriptHandler>()
}

class LongPress(val link: String?, val image: String?)

class PageScriptInfo(val sid: String, val name: String, val commands: List<Pair<String, String>>)

/** Hides elements matched by the ad-block element-hiding rules; runs at document start in every frame. */
private const val BLOCK_CSS_JS = """
(function(){ try {
  var B = window.NamiBridge; if (!B || !B.css) return;
  var c = B.css(location.hostname); if (!c) return;
  var st = document.createElement('style'); st.textContent = c;
  var add = function(){ var p = document.head || document.documentElement; if (p) { p.appendChild(st); return true; } return false; };
  if (!add()) new MutationObserver(function(m, o){ if (add()) o.disconnect(); }).observe(document, { childList: true, subtree: true });
} catch(e) {} })();
"""

/**
 * Pull-down detector around the WebView: watches touches without stealing them.
 * Only arms when the page is already at the very top.
 */
@SuppressLint("ViewConstructor")
class PullFrame(ctx: Context, val web: WebView, private val threshold: () -> Float,
                private val onPull: (Float) -> Unit, private val onFire: () -> Unit, private val onTouch: () -> Unit) : FrameLayout(ctx) {
    init { addView(web, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)) }
    private var startY = 0f
    private var armed = false
    private var p = 0f
    override fun dispatchTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { onTouch(); startY = e.y; armed = !web.canScrollVertically(-1); set(0f) }
            MotionEvent.ACTION_POINTER_DOWN -> { armed = false; set(0f) }
            MotionEvent.ACTION_MOVE -> if (armed) {
                val dy = e.y - startY
                if (dy < -24f) { armed = false; set(0f) } else set((dy / threshold()).coerceIn(0f, 1.25f))
            }
            MotionEvent.ACTION_UP -> { if (armed && p >= 1f) onFire(); armed = false; set(0f) }
            MotionEvent.ACTION_CANCEL -> { armed = false; set(0f) }
        }
        return super.dispatchTouchEvent(e)
    }
    private fun set(v: Float) { if (v != p) { p = v; onPull(v) } }
}

/** Activity-side services the browser needs. */
interface Host {
    fun chooseFile(cb: ValueCallback<Array<Uri>>, params: WebChromeClient.FileChooserParams): Boolean
}

class Browser(private val act: Activity, val prefs: Prefs, private val scope: CoroutineScope, private val host: Host) {
    val tabs = mutableStateListOf<BrowserTab>()
    var current by mutableIntStateOf(0)
    val tab: BrowserTab? get() = tabs.getOrNull(current)

    // UI state that gestures and page events also change.
    var toolbarVisible by mutableStateOf(false)
    var editing by mutableStateOf(false)
    var showTabs by mutableStateOf(false)
    var showSettings by mutableStateOf(false)
    var showDownloads by mutableStateOf(false)
    /** Optional tappable word on the status pill (e.g. "열기"). */
    var pillAction by mutableStateOf<Pair<String, () -> Unit>?>(null)
    var pull by mutableFloatStateOf(0f)
    var pill by mutableStateOf<String?>(null)
    var longPress by mutableStateOf<LongPress?>(null)
    var customView by mutableStateOf<View?>(null)
    private var customCb: WebChromeClient.CustomViewCallback? = null
    /** Set by the UI: runs an Action (used by gestures). */
    var runAction: (Action) -> Unit = {}

    val translator = TranslatorHub(prefs)
    val blocker = Blocker(act, prefs)
    val scripts = Userscripts(act)
    /** A .user.js the user opened, waiting for "설치". */
    var pendingInstall by mutableStateOf<UScript?>(null)
    /** "이 페이지의 유저스크립트" panel: scripts that ran on this page and their menu commands. */
    var scriptPanel by mutableStateOf<List<PageScriptInfo>?>(null)
    private val docStart = WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)

    var netStatus by mutableStateOf("")
    /** Per-launch secret: only Nami's own injected scripts know it, so web pages can't use the privileged bridge calls. */
    val key: String = java.util.UUID.randomUUID().toString().replace("-", "") + java.util.UUID.randomUUID().toString().replace("-", "")
    private val pageJs: String by lazy { PageScript.js(key) }

    init {
        scripts.onChange = { tabs.forEach { applyScripts(it) } }
        Downloads.onDone = { uri, name, mime ->
            showPill("다 받았어요 · " + name.take(24), 4500, "열기" to { Downloads.open(act, uri, mime, name) })
        }
        applyNetwork()
        scope.launch {
            if (!prefs.blockerAppsChosen) {
                val found = blocker.findApps()
                if (found.isNotEmpty()) prefs.putBlockerApps(found.map { it.pkg }.toSet())
            }
            blocker.refresh(true)
        }
    }

    /** Points every WebView at LocalProxy when DNS / bypass / site proxy is on; otherwise goes direct. */
    fun applyNetwork() {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)) { netStatus = "이 폰의 웹뷰가 지원하지 않아요"; LocalProxy.stop(); return }
        val need = prefs.dnsMode != DnsMode.OFF || prefs.dpiBypass
        val pc = androidx.webkit.ProxyController.getInstance()
        if (need) {
            val port = runCatching { LocalProxy.start(prefs) }.getOrNull()
            if (port == null) { netStatus = "중계기를 켜지 못했어요"; return }
            val cfg = androidx.webkit.ProxyConfig.Builder().addProxyRule("127.0.0.1:$port").build()
            runCatching { pc.setProxyOverride(cfg, { it.run() }) { } }
            netStatus = "중계기 동작 중"
        } else {
            runCatching { pc.clearProxyOverride({ it.run() }) { } }
            LocalProxy.stop()
            netStatus = "꺼짐 (바로 연결)"
        }
    }

    fun applyScripts(t: BrowserTab) {
        if (!docStart) return
        t.handlers.forEach { runCatching { it.remove() } }
        t.handlers.clear()
        scripts.wrappers(key).forEach { js -> runCatching { t.handlers.add(WebViewCompat.addDocumentStartJavaScript(t.web, js, setOf("*"))) } }
    }

    fun offerInstall(url: String) {
        showPill("스크립트 불러오는 중…", 0)
        scope.launch {
            runCatching { scripts.fetch(url) }
                .onSuccess { src ->
                    showPill(null)
                    if (!src.contains("==UserScript==")) { Toast.makeText(act, "유저스크립트가 아니에요", Toast.LENGTH_SHORT).show(); tab?.web?.loadUrl(url) }
                    else pendingInstall = UScript("preview", src, true, url)
                }
                .onFailure { showPill("스크립트를 받지 못했어요: ${it.message}") }
        }
    }

    fun installPending() {
        val p = pendingInstall ?: return
        pendingInstall = null
        scope.launch {
            runCatching { scripts.install(p.source, p.installUrl) }
                .onSuccess { showPill("'${it.name}' 설치했어요 · 새로고침하면 적용돼요", 2600) }
                .onFailure { showPill("설치 실패: ${it.message}") }
        }
    }

    fun loadScriptPanel() {
        val t = tab ?: return
        val js = "JSON.stringify({ran:(window.__namiRan||[]),menu:(window.__namiMenu||[]).map(function(m){return {id:m.id,sid:m.sid,name:m.name}})})"
        t.web.evaluateJavascript(js) { r ->
            val panel = runCatching {
                val o = org.json.JSONObject(org.json.JSONTokener(r).nextValue() as? String ?: "{}")
                val ran = o.optJSONArray("ran") ?: JSONArray()
                val menu = o.optJSONArray("menu") ?: JSONArray()
                val cmds = (0 until menu.length()).map { menu.getJSONObject(it) }
                val sids = LinkedHashMap<String, String>()
                for (i in 0 until ran.length()) ran.getJSONObject(i).let { sids[it.optString("sid")] = it.optString("name") }
                cmds.forEach { c -> if (c.optString("sid") !in sids) sids[c.optString("sid")] = "" }
                sids.map { (sid, name) ->
                    val sc = scripts.list.firstOrNull { it.id == sid }
                    PageScriptInfo(sid, sc?.name ?: name, cmds.filter { it.optString("sid") == sid }.map { it.optString("id") to it.optString("name") })
                }
            }.getOrDefault(emptyList())
            scriptPanel = panel
        }
    }

    /** Runs a script's menu command, then refreshes the panel (scripts often relabel ON/OFF). */
    fun runScriptCommand(id: String) {
        val q = org.json.JSONObject.quote(id)
        tab?.web?.evaluateJavascript("try{var m=(window.__namiMenu||[]).filter(function(x){return x.id===$q})[0]; if(m) m.fn()}catch(e){console.error(e)}", null)
        scope.launch { delay(350); if (scriptPanel != null) loadScriptPanel() }
    }
    private var nextId = 1L
    private val density = act.resources.displayMetrics.density
    private var pillJob: Job? = null

    /** The one container the UI shows; holds the current tab's frame. */
    val container = FrameLayout(act)

    fun showPill(text: String?, ms: Long = 2200, action: Pair<String, () -> Unit>? = null) {
        pillJob?.cancel(); pill = text; pillAction = action
        if (text != null && ms > 0) pillJob = scope.launch { delay(ms); pill = null }
    }

    fun attachCurrent() {
        val t = tab ?: return
        if (container.childCount == 1 && container.getChildAt(0) === t.frame) return
        container.removeAllViews()
        (t.frame.parent as? ViewGroup)?.removeView(t.frame)
        container.addView(t.frame, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        tabs.forEach { if (it === t || it.relays > 0) it.web.onResume() else it.web.onPause() }
    }

    /** Disk writes for relayed downloads, one at a time and in order, off the UI thread. */
    private val pipeIo = java.util.concurrent.Executors.newSingleThreadExecutor()
    private val ui = android.os.Handler(android.os.Looper.getMainLooper())

    /**
     * window.NamiPipe: the page hands us raw bytes (ArrayBuffer) for a relayed download without base64 and
     * without blocking its main thread. "w:<id>" names the file the next buffer belongs to; we answer "ok"/"err"
     * after each write so the page never runs ahead of the disk.
     */
    private fun addPipe(web: WebView) {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER) ||
            !WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_ARRAY_BUFFER)) return
        var cur = ""
        runCatching {
            WebViewCompat.addWebMessageListener(web, "NamiPipe", setOf("*")) { _, msg, _, isMain, reply ->
                if (!isMain) return@addWebMessageListener
                if (msg.type == androidx.webkit.WebMessageCompat.TYPE_ARRAY_BUFFER) {
                    val id = cur; val bytes = msg.arrayBuffer
                    pipeIo.execute {
                        val ok = fs.writeBytes(id, bytes)
                        ui.post { runCatching { reply.postMessage(if (ok) "ok" else "err") } }
                    }
                } else {
                    val d = msg.data ?: return@addWebMessageListener
                    if (d.startsWith("w:")) cur = d.substring(2)
                }
            }
        }
    }

    /** A tab whose page is streaming a file to us keeps running at full priority even when hidden. */
    fun relayKeep(t: BrowserTab) {
        if (t.relays > 0) {
            t.web.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, false)
            t.web.onResume()
        } else {
            t.web.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, true)
            if (t !== tab) t.web.onPause()
        }
    }

    fun select(i: Int) {
        if (i !in tabs.indices) return
        if (i != current) tab?.let { capture(it) }
        current = i; attachCurrent()
    }

    /** Address-bar swipe: d = +1 next tab, -1 previous. */
    fun switchBy(d: Int) {
        val to = current + d
        if (to !in tabs.indices) { showPill(if (d > 0) "마지막 탭이에요" else "첫 번째 탭이에요", 1000); return }
        select(to)
        val t = tabs[to]
        showPill("${to + 1}/${tabs.size} · " + t.title.ifEmpty { Uri.parse(t.url).host ?: t.url }.take(28), 1300)
    }

    /** Long-press drag in the tab list. Keeps the selected tab selected. */
    fun moveTab(from: Int, to: Int) {
        if (from !in tabs.indices || to !in tabs.indices || from == to) return
        val cur = tab
        tabs.add(to, tabs.removeAt(from))
        current = tabs.indexOf(cur).coerceAtLeast(0)
    }

    fun openTabs() { tab?.let { capture(it) }; showTabs = true }

    /** Draws what the tab shows now into a small bitmap (40% size). */
    fun capture(t: BrowserTab) {
        val w = t.web
        if (w.width <= 0 || w.height <= 0 || !w.isAttachedToWindow) return
        runCatching {
            val s = 0.4f
            val bmp = Bitmap.createBitmap((w.width * s).toInt().coerceAtLeast(1), (w.height * s).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
            val c = android.graphics.Canvas(bmp)
            c.drawColor(android.graphics.Color.WHITE)
            c.scale(s, s)
            c.translate(-w.scrollX.toFloat(), -w.scrollY.toFloat())
            w.draw(c)
            t.thumb = bmp
        }
    }

    fun newTab(url: String?, select: Boolean = true, append: Boolean = false): BrowserTab {
        val t = createTab()
        val at = if (tabs.isEmpty() || append) tabs.size else current + 1
        tabs.add(at, t)
        if (select) { current = at; attachCurrent() } else if (at <= current) current++
        if (url != null) load(t, url)
        return t
    }

    fun closeTab(i: Int) {
        val t = tabs.getOrNull(i) ?: return
        tabs.removeAt(i)
        t.frame.removeAllViews(); t.web.destroy()
        if (tabs.isEmpty()) { current = 0; newTab(prefs.homeUrl); return }
        t.thumb = null
        if (i < current) current -= 1
        if (current >= tabs.size) current = tabs.size - 1
        attachCurrent()
    }

    /** In the app cache: the system may clear it when space is low (then tabs just show the site name). */
    private val thumbDir get() = java.io.File(act.cacheDir, "thumbs")

    /** Saves open tabs (urls + preview pictures) so they come back after the app is closed. */
    fun save() {
        tab?.let { capture(it) }
        val kept = tabs.filter { (it.web.url ?: it.url).startsWith("http") }
        prefs.saveTabs(kept.map { it.web.url ?: it.url }, kept.indexOf(tab).coerceAtLeast(0))
        val snaps = kept.map { it.thumb }
        val dir = thumbDir
        scope.launch(kotlinx.coroutines.Dispatchers.IO) {
            runCatching {
                dir.mkdirs()
                dir.listFiles()?.forEach { it.delete() }
                snaps.forEachIndexed { i, b ->
                    b?.let { bmp -> java.io.FileOutputStream(java.io.File(dir, "$i.webp")).use { o -> bmp.compress(Bitmap.CompressFormat.WEBP_LOSSY, 70, o) } }
                }
            }
        }
    }

    fun restore(): Boolean {
        val (urls, idx) = prefs.loadTabs()
        if (urls.isEmpty()) return false
        urls.forEach { newTab(it, select = false, append = true) }
        select(idx.coerceIn(0, tabs.size - 1))
        val restored = tabs.toList()
        val dir = thumbDir
        scope.launch {
            restored.forEachIndexed { i, t ->
                val bmp = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    runCatching { android.graphics.BitmapFactory.decodeFile(java.io.File(dir, "$i.webp").path) }.getOrNull()
                }
                if (bmp != null && t.thumb == null) t.thumb = bmp
            }
        }
        return true
    }

    /** Address-bar input → url or Google search. */
    fun load(t: BrowserTab, input: String) {
        val s = input.trim()
        val url = when {
            s.isEmpty() -> prefs.homeUrl
            Regex("^[a-zA-Z][a-zA-Z0-9+.-]*://").containsMatchIn(s) || s.startsWith("about:") -> s
            !s.contains(' ') && Regex("^[^/]+\\.[a-zA-Z]{2,}(:\\d+)?(/.*)?$").matches(s) -> "https://$s"
            !s.contains(' ') && s.startsWith("localhost") -> "http://$s"
            else -> "https://www.google.com/search?q=" + URLEncoder.encode(s, "UTF-8")
        }
        if (isUserJs(url)) { offerInstall(url); return }
        t.url = url
        t.web.loadUrl(url)
    }

    private fun isUserJs(url: String) = (url.startsWith("http://") || url.startsWith("https://")) &&
        Uri.parse(url).path?.endsWith(".user.js") == true

    // ---------- translation ----------

    fun toggleTranslate(t: BrowserTab) {
        t.translateOn = !t.translateOn
        if (t.translateOn) startTranslate(t)
        else {
            t.translating = false
            t.web.evaluateJavascript("window.__nami&&__nami.stop('$key')", null)
            showPill("원문으로 돌아왔어요")
        }
    }

    private fun startTranslate(t: BrowserTab) {
        t.translating = true
        if (t === tab) showPill("번역 중…", 0)
        t.web.evaluateJavascript(pageJs, null)
        t.web.evaluateJavascript("window.__nami&&__nami.start('$key')", null)
    }

    inner class Bridge(private val t: BrowserTab) {
        @JavascriptInterface
        fun translate(k: String, id: Int, json: String) {
            if (k != key) return
            scope.launch {
                val texts = runCatching { JSONArray(json).let { a -> (0 until a.length()).map { a.getString(it) } } }.getOrNull()
                if (texts == null) { t.web.evaluateJavascript("window.__nami&&__nami.fail($id)", null); return@launch }
                val r = runCatching { translator.translate(texts) }.getOrNull()
                if (r == null) {
                    t.web.evaluateJavascript("window.__nami&&__nami.fail($id)", null)
                    if (t === tab) showPill("번역하지 못했어요")
                    return@launch
                }
                if (r.engine == "device" && prefs.engine != Engine.DEVICE && !t.fellBackNoticed) {
                    t.fellBackNoticed = true
                    Toast.makeText(act, "구글 번역이 안 돼서 폰 번역으로 바꿨어요", Toast.LENGTH_SHORT).show()
                }
                t.web.evaluateJavascript("window.__nami&&__nami.done($id," + JSONArray(r.texts) + ")", null)
            }
        }

        @JavascriptInterface
        fun idle(k: String) {
            if (k != key) return
            scope.launch {
                if (t.translating) {
                    t.translating = false
                    if (t === tab && t.translateOn) showPill("한국어로 번역했어요")
                }
            }
        }

        @JavascriptInterface fun css(host: String): String = runCatching { blocker.css(host) }.getOrDefault("")
        @JavascriptInterface fun downloadHint(k: String, href: String, name: String) { if (k != key) return; if (href.isNotEmpty() && name.isNotBlank()) downloadHints[href] = name }
        @JavascriptInterface
        fun blobBegin(k: String, id: String, href: String, hint: String, type: String, size: Double): Boolean {
            if (k != key) return false
            val (name, guessed) = FileName.build("", null, type.ifEmpty { null }, hint.ifEmpty { null })
            val ok = blobs.start(id, name, type.ifEmpty { null }, guessed)
            scope.launch { Toast.makeText(act, if (ok) "받는 중: $name" else "저장하지 못했어요", Toast.LENGTH_SHORT).show() }
            return ok
        }
        /** Called synchronously on the click, before the WebView's own download callback for the same blob. */
        @JavascriptInterface fun claimBlob(k: String, href: String) { if (k != key) return; blobs.taken.add(href) }
        @JavascriptInterface fun blobChunk(k: String, id: String, b64: String): Boolean = k == key && blobs.chunk(id, b64)
        @JavascriptInterface fun blobEnd(k: String, id: String) { if (k == key) blobs.end(id) }
        @JavascriptInterface fun blobFail(k: String, id: String) { if (k != key) return; blobs.fail(id); scope.launch { Toast.makeText(act, "파일을 읽지 못했어요", Toast.LENGTH_SHORT).show() } }
        @JavascriptInterface fun relayOpen(k: String, id: String, name: String, mime: String): Boolean {
            if (k != key) return false
            val (n, _) = FileName.build("", null, mime.ifEmpty { null }, name.ifEmpty { null })
            val ok = fs.open(id, "", n, mime)
            if (ok) { t.relays++; scope.launch { relayKeep(t) } }
            scope.launch { if (ok) Toast.makeText(act, "받는 중: $n", Toast.LENGTH_SHORT).show() else Toast.makeText(act, "받기 실패", Toast.LENGTH_SHORT).show() }
            return ok
        }
        @JavascriptInterface fun relayFail(k: String, msg: String, url: String, got: Double, opened: Boolean) {
            if (k != key) return
            if (opened) { t.relays = (t.relays - 1).coerceAtLeast(0); scope.launch { relayKeep(t) } }
            val fb = relayFallback.remove(url)
            scope.launch {
                when {
                    got > 0 && msg == "stalled" -> Toast.makeText(act, "사이트가 ${DownloadService.size(got.toLong())}에서 90초 넘게 멈춰서 그만뒀어요", Toast.LENGTH_LONG).show()
                    got > 0 -> Toast.makeText(act, "받다가 끊겼어요 (${DownloadService.size(got.toLong())}에서) · 다시 받아 주세요", Toast.LENGTH_LONG).show()
                    fb != null -> fb()
                    else -> Toast.makeText(act, "받기 실패: $msg", Toast.LENGTH_SHORT).show()
                }
            }
        }
        @JavascriptInterface fun relayDone(k: String, url: String) {
            if (k != key) return
            relayFallback.remove(url)
            t.relays = (t.relays - 1).coerceAtLeast(0); scope.launch { relayKeep(t) }
        }
        @JavascriptInterface fun fsWrite(k: String, id: String, b64: String, pos: Double): Boolean = k == key && fs.write(id, b64, pos)
        @JavascriptInterface fun fsClose(k: String, id: String): Boolean = k == key && fs.close(id)
        @JavascriptInterface fun fsAbort(k: String, id: String) { if (k == key) fs.abort(id) }
        @JavascriptInterface fun forceZoom(k: String): Boolean = k == key && prefs.forceZoom
        @JavascriptInterface fun scriptVersion(k: String, name: String, ns: String): String = if (k != key) "" else
            scripts.list.firstOrNull { (it.one("name") == name || it.name == name) && (ns.isEmpty() || it.namespace == ns) }?.version ?: ""
        @JavascriptInterface fun gmValues(k: String, id: String): String = if (k != key) "{}" else scripts.values(id)
        @JavascriptInterface fun gmSet(k: String, id: String, name: String, json: String) { if (k == key) scripts.setValue(id, name, json) }
        @JavascriptInterface fun gmDel(k: String, id: String, name: String) { if (k == key) scripts.deleteValue(id, name) }
        @JavascriptInterface fun gmOpen(k: String, url: String, background: Boolean) { if (k != key) return; scope.launch { newTab(url, select = !background); if (background) showPill("새 탭에서 열었어요", 1500) } }
        @JavascriptInterface fun gmClip(k: String, text: String) { if (k != key) return; scope.launch { copyText(act, text) } }
        @JavascriptInterface fun gmToast(k: String, text: String) { if (k != key) return; scope.launch { Toast.makeText(act, text, Toast.LENGTH_SHORT).show() } }
        @JavascriptInterface fun gmDownload(k: String, url: String, name: String) { if (k != key) return; scope.launch { download(url, cd = if (name.isNotEmpty()) "attachment; filename=\"$name\"" else null) } }
        @JavascriptInterface
        fun gmXhr(k: String, json: String) {
            if (k != key) return
            scope.launch {
                val o = runCatching { org.json.JSONObject(json) }.getOrNull() ?: return@launch
                val id = o.optString("id")
                val res = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { GmXhr.run(o, t.web.settings.userAgentString) }
                val q = org.json.JSONObject.quote(id)
                t.web.evaluateJavascript("window.__namiGmCb&&__namiGmCb($q,$res)", null)
            }
        }

        @JavascriptInterface
        fun doubleTap(k: String) {
            if (k != key) return
            scope.launch { if (t === tab) runAction(prefs.doubleTapAction) }
        }
    }

    // ---------- WebView ----------

    @SuppressLint("SetJavaScriptEnabled")
    private fun createTab(): BrowserTab {
        val web = WebView(act)
        val frame = PullFrame(act, web,
            threshold = { prefs.pullStrength.dp * density },
            onPull = { if (prefs.pullAction != Action.NONE && tab?.web === web) pull = it },
            onFire = { if (prefs.pullAction != Action.NONE) runAction(prefs.pullAction) },
            onTouch = { if (toolbarVisible && !editing) toolbarVisible = false })
        val t = BrowserTab(nextId++, web, frame)

        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            loadWithOverviewMode = true
            useWideViewPort = true
            builtInZoomControls = true
            displayZoomControls = false
            setSupportZoom(true)
            textZoom = prefs.textZoom
            setSupportMultipleWindows(true)
            javaScriptCanOpenWindowsAutomatically = false
            mediaPlaybackRequiresUserGesture = true
            mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            // Look like plain Chrome (drop the WebView "; wv" marker).
            userAgentString = userAgentString.replace("; wv", "").replace(Regex("Version/\\d+(\\.\\d+)* "), "")
        }
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true)
        web.addJavascriptInterface(Bridge(t), "NamiBridge")
        if (docStart) {
            runCatching { WebViewCompat.addDocumentStartJavaScript(web, BLOCK_CSS_JS, setOf("*")) }
            runCatching { WebViewCompat.addDocumentStartJavaScript(web, pageJs, setOf("*")) }
            addPipe(web)
            applyScripts(t)
        }

        web.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, req: WebResourceRequest): android.webkit.WebResourceResponse? {
                if (req.isForMainFrame) return null
                val r = blocker.intercept(req.url.toString(), t.host)
                if (r != null) t.blocked.incrementAndGet()
                return r
            }

            override fun shouldOverrideUrlLoading(view: WebView, req: WebResourceRequest): Boolean {
                val u = req.url
                if (req.isForMainFrame && isUserJs(u.toString())) { offerInstall(u.toString()); return true }
                val s = u.scheme ?: return false
                if (s == "http" || s == "https") {
                    // kio.ac-style service-worker download: stop the navigation before it uses up the one-time URL,
                    // and let a page of that site fetch it instead
                    if (isSwUrl(u.toString())) {
                        relay(u.toString(), null, view)
                        if (!view.canGoBack() && (view.url.isNullOrEmpty() || view.url == "about:blank")) {
                            val i = tabs.indexOfFirst { it.web === view }
                            if (i >= 0 && tabs.size > 1) closeTab(i)
                        }
                        return true
                    }
                    // Like Chrome: a tapped link to another site that an installed app handles (X, YouTube…) opens in that app.
                    if (req.isForMainFrame && prefs.openApps) {
                        val now = System.currentTimeMillis()
                        if (req.hasGesture()) t.lastGesture = now
                        val tapped = req.hasGesture() || (req.isRedirect && now - t.lastGesture < 4000)
                        val from = view.url?.let { Uri.parse(it).host } ?: ""
                        val to = u.host ?: ""
                        if (tapped && to.isNotEmpty() && Blocker.Engine.registrable(to) != Blocker.Engine.registrable(from) && openInApp(u)) {
                            // a fresh tab opened only for this link → close it again
                            if (!view.canGoBack() && (view.url.isNullOrEmpty() || view.url == "about:blank")) {
                                val i = tabs.indexOfFirst { it.web === view }
                                if (i >= 0 && tabs.size > 1) closeTab(i)
                            }
                            return true
                        }
                    }
                    return false
                }
                if (s == "about" || s == "data" || s == "blob" || s == "javascript") return false
                // intent:, market:, mailto: … → other apps
                return try {
                    val i = if (s == "intent") Intent.parseUri(u.toString(), Intent.URI_INTENT_SCHEME) else Intent(Intent.ACTION_VIEW, u)
                    i.addCategory(Intent.CATEGORY_BROWSABLE); i.component = null; i.selector = null
                    act.startActivity(i); true
                } catch (e: ActivityNotFoundException) {
                    val fb = if (s == "intent") runCatching { Intent.parseUri(u.toString(), Intent.URI_INTENT_SCHEME).getStringExtra("browser_fallback_url") }.getOrNull() else null
                    if (fb != null) view.loadUrl(fb)
                    true
                } catch (e: Exception) { true }
            }

            override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                t.url = url; t.progress = 5
                val h = Uri.parse(url).host ?: ""
                // Like Chrome: keep translating while staying on the same site; "always" sites start on.
                t.translateOn = (t.translateOn && h == t.host) || h in prefs.alwaysHosts
                t.translating = false
                t.host = h
                t.blocked.set(0)
            }

            override fun onPageFinished(view: WebView, url: String) {
                t.url = url; t.progress = 100
                t.canBack = view.canGoBack(); t.canForward = view.canGoForward()
                view.evaluateJavascript(pageJs, null)
                if (!docStart) {
                    view.evaluateJavascript(BLOCK_CSS_JS, null)
                    scripts.wrappers(key).forEach { view.evaluateJavascript(it, null) }
                }
                if (t.translateOn) startTranslate(t)
            }

            override fun doUpdateVisitedHistory(view: WebView, url: String, isReload: Boolean) {
                t.url = url; t.canBack = view.canGoBack(); t.canForward = view.canGoForward()
            }
        }

        web.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, p: Int) { t.progress = p }
            override fun onReceivedTitle(view: WebView, title: String?) { t.title = title ?: "" }
            override fun onCreateWindow(view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message): Boolean {
                if (!isUserGesture) return false
                val nt = newTab(null)
                (resultMsg.obj as WebView.WebViewTransport).webView = nt.web
                resultMsg.sendToTarget()
                return true
            }
            override fun onCloseWindow(window: WebView) {
                val i = tabs.indexOfFirst { it.web === window }
                if (i >= 0) closeTab(i)
            }
            override fun onShowCustomView(view: View, callback: CustomViewCallback) {
                customCb?.onCustomViewHidden(); customView = view; customCb = callback
            }
            override fun onHideCustomView() { customView = null; customCb = null }
            override fun onShowFileChooser(webView: WebView, cb: ValueCallback<Array<Uri>>, params: FileChooserParams): Boolean =
                host.chooseFile(cb, params)
        }

        web.setDownloadListener { url, ua, cd, mime, _ ->
            download(url, ua, cd, mime)
            // a blank tab that only existed to start this download → close it (like Chrome)
            if (!web.canGoBack() && (web.url.isNullOrEmpty() || web.url == "about:blank" || web.url == url) && tabs.size > 1) {
                val i = tabs.indexOfFirst { it.web === web }
                if (i >= 0) scope.launch { closeTab(i) }
            }
        }

        web.setOnLongClickListener {
            val h = web.hitTestResult
            when (h.type) {
                WebView.HitTestResult.IMAGE_TYPE -> { longPress = LongPress(null, h.extra); true }
                WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE -> {
                    val msg = Handler(Looper.getMainLooper()).obtainMessage()
                    web.requestFocusNodeHref(msg)
                    longPress = LongPress(msg.data.getString("url"), h.extra ?: msg.data.getString("src")); true
                }
                WebView.HitTestResult.SRC_ANCHOR_TYPE -> { longPress = LongPress(h.extra, null); true }
                else -> false
            }
        }
        return t
    }

    /** Opens the link in an installed app that handles it (not another browser). False if there is none. */
    fun openInApp(u: Uri): Boolean = try {
        act.startActivity(Intent(Intent.ACTION_VIEW, u).addCategory(Intent.CATEGORY_BROWSABLE)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REQUIRE_NON_BROWSER))
        true
    } catch (e: ActivityNotFoundException) { false } catch (e: Exception) { false }

    fun exitCustomView() { customCb?.onCustomViewHidden(); customView = null; customCb = null }

    /** Names a page gave its download links (`<a download="이름">`), keyed by href. */
    val downloadHints = java.util.concurrent.ConcurrentHashMap<String, String>()
    val blobs = BlobSaver(act)
    val fs = FsSaver(act)

    /** URLs a site's service worker answers (service-worker-responsify, used by kio.ac). Our downloader can't fetch them. */
    fun isSwUrl(url: String) = runCatching { Uri.parse(url).path?.endsWith("_service-worker-responsify") == true }.getOrDefault(false)

    /** Have a page of the same site fetch it (through its service worker) and stream it to a file. */
    /** Native download to try if the page couldn't get a relayed URL. */
    val relayFallback = java.util.concurrent.ConcurrentHashMap<String, () -> Unit>()

    fun relay(url: String, hint: String?, from: WebView? = null) {
        val h = Uri.parse(url).host ?: ""
        val t = tabs.filter { it.host == h && it.web.url != url && (from == null || it.web !== from || !it.web.url.isNullOrEmpty()) }.let { l -> l.firstOrNull { it === tab } ?: l.lastOrNull() }
        if (t == null) { Toast.makeText(act, "이 파일은 받을 수 없어요", Toast.LENGTH_SHORT).show(); return }
        val q = org.json.JSONObject.quote(url); val n = org.json.JSONObject.quote(hint ?: "")
        t.web.evaluateJavascript("(window.__nami&&__nami.relay)?(__nami.relay('$key',$q,$n),'ok'):'no'") { r ->
            if (r?.contains("ok") != true) relayFallback.remove(url)?.invoke() ?: Toast.makeText(act, "이 파일은 받을 수 없어요", Toast.LENGTH_SHORT).show()
        }
    }

    fun download(url: String, ua: String? = null, cd: String? = null, mime: String? = null, direct: Boolean = false) {
        val hint = downloadHints[url]
        // A download from a page its service worker controls (kio.ac zip etc.): our downloader can't see the
        // service worker, so let the page fetch it first; plain downloader if that fails.
        if (!direct && url.startsWith("http")) {
            val h = Uri.parse(url).host ?: ""
            val ok = tabs.filter { it.host == h && !it.web.url.isNullOrEmpty() && it.web.url != url && it.web.url != "about:blank" }
            val t = ok.firstOrNull { it === tab } ?: ok.lastOrNull()
            if (t != null) {
                t.web.evaluateJavascript("!!(navigator.serviceWorker&&navigator.serviceWorker.controller)") { r ->
                    if (r == "true") {
                        relayFallback[url] = { download(url, ua, cd, mime, true) }
                        relay(url, hint, null)
                    } else download(url, ua, cd, mime, true)
                }
                return
            }
        }
        downloadHints.remove(url)
        if (url.startsWith("data:")) {
            val n = blobs.saveDataUrl(url, hint)
            Toast.makeText(act, if (n != null) "저장했어요: $n" else "이 파일은 받을 수 없어요", Toast.LENGTH_SHORT).show(); return
        }
        if (url.startsWith("blob:")) {
            if (blobs.taken.remove(url)) return  // already being saved from the click
            val q = org.json.JSONObject.quote(url); val h = org.json.JSONObject.quote(hint ?: "")
            (tab?.web)?.evaluateJavascript("(window.__nami&&__nami.saveBlob)?(__nami.saveBlob('$key',$q,$h),'ok'):'no'") { r ->
                if (r?.contains("ok") != true) Toast.makeText(act, "이 파일은 받을 수 없어요", Toast.LENGTH_SHORT).show()
            }
            return
        }
        if (!url.startsWith("http")) { Toast.makeText(act, "이 파일은 받을 수 없어요", Toast.LENGTH_SHORT).show(); return }
        if (isSwUrl(url)) { relay(url, hint); return }
        val (name, guessed) = FileName.build(url, cd, mime, hint)
        if (prefs.splitParts > 0) runCatching {
            if (act.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED)
                act.requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 11)
            DownloadService.start(act, url, name, mime, CookieManager.getInstance().getCookie(url),
                ua ?: tab?.web?.settings?.userAgentString, tab?.url, prefs.splitParts, guessed, prefs.dualNetwork)
            Toast.makeText(act, "받는 중: $name", Toast.LENGTH_SHORT).show()
            return
        }.onFailure { Toast.makeText(act, "분할 다운로드를 못 써서 기본 방식으로 받아요", Toast.LENGTH_SHORT).show() }
        runCatching {
            val req = DownloadManager.Request(Uri.parse(url)).apply {
                CookieManager.getInstance().getCookie(url)?.let { addRequestHeader("Cookie", it) }
                addRequestHeader("User-Agent", ua ?: tab?.web?.settings?.userAgentString ?: "")
                tab?.url?.let { addRequestHeader("Referer", it) }
                if (mime != null) setMimeType(mime)
                setTitle(name)
                setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name)
            }
            (act.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager).enqueue(req)
            Toast.makeText(act, "받는 중: $name", Toast.LENGTH_SHORT).show()
        }.onFailure { Toast.makeText(act, "받기 실패: ${it.message}", Toast.LENGTH_SHORT).show() }
    }
}
