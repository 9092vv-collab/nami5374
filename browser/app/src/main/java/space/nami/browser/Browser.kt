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
    var title by mutableStateOf("")
    var url by mutableStateOf("")
    var progress by mutableIntStateOf(100)
    var canBack by mutableStateOf(false)
    var canForward by mutableStateOf(false)
    /** Translation wanted on this tab (stays on while moving around the same site). */
    var translateOn by mutableStateOf(false)
    var translating by mutableStateOf(false)
    var host = ""
    var fellBackNoticed = false
}

class LongPress(val link: String?, val image: String?)

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
    var pull by mutableFloatStateOf(0f)
    var pill by mutableStateOf<String?>(null)
    var longPress by mutableStateOf<LongPress?>(null)
    var customView by mutableStateOf<View?>(null)
    private var customCb: WebChromeClient.CustomViewCallback? = null
    /** Set by the UI: runs an Action (used by gestures). */
    var runAction: (Action) -> Unit = {}

    val translator = TranslatorHub(prefs)
    private var nextId = 1L
    private val density = act.resources.displayMetrics.density
    private var pillJob: Job? = null

    /** The one container the UI shows; holds the current tab's frame. */
    val container = FrameLayout(act)

    fun showPill(text: String?, ms: Long = 2200) {
        pillJob?.cancel(); pill = text
        if (text != null && ms > 0) pillJob = scope.launch { delay(ms); pill = null }
    }

    fun attachCurrent() {
        val t = tab ?: return
        if (container.childCount == 1 && container.getChildAt(0) === t.frame) return
        container.removeAllViews()
        (t.frame.parent as? ViewGroup)?.removeView(t.frame)
        container.addView(t.frame, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        tabs.forEach { if (it === t) it.web.onResume() else it.web.onPause() }
    }

    fun select(i: Int) { if (i in tabs.indices) { current = i; attachCurrent() } }

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
        if (i < current) current -= 1
        if (current >= tabs.size) current = tabs.size - 1
        attachCurrent()
    }

    fun save() = prefs.saveTabs(tabs.map { it.web.url ?: it.url }.filter { it.startsWith("http") }, current)

    fun restore(): Boolean {
        val (urls, idx) = prefs.loadTabs()
        if (urls.isEmpty()) return false
        urls.forEach { newTab(it, select = false, append = true) }
        select(idx.coerceIn(0, tabs.size - 1))
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
        t.url = url
        t.web.loadUrl(url)
    }

    // ---------- translation ----------

    fun toggleTranslate(t: BrowserTab) {
        t.translateOn = !t.translateOn
        if (t.translateOn) startTranslate(t)
        else {
            t.translating = false
            t.web.evaluateJavascript("window.__nami&&__nami.stop()", null)
            showPill("원문으로 돌아왔어요")
        }
    }

    private fun startTranslate(t: BrowserTab) {
        t.translating = true
        if (t === tab) showPill("번역 중…", 0)
        t.web.evaluateJavascript(PageScript.JS, null)
        t.web.evaluateJavascript("window.__nami&&__nami.start()", null)
    }

    inner class Bridge(private val t: BrowserTab) {
        @JavascriptInterface
        fun translate(id: Int, json: String) {
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
        fun idle() {
            scope.launch {
                if (t.translating) {
                    t.translating = false
                    if (t === tab && t.translateOn) showPill("한국어로 번역했어요")
                }
            }
        }

        @JavascriptInterface
        fun doubleTap() { scope.launch { if (t === tab) runAction(prefs.doubleTapAction) } }
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
            setSupportMultipleWindows(true)
            javaScriptCanOpenWindowsAutomatically = false
            mediaPlaybackRequiresUserGesture = true
            mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            // Look like plain Chrome (drop the WebView "; wv" marker).
            userAgentString = userAgentString.replace("; wv", "").replace(Regex("Version/\\d+(\\.\\d+)* "), "")
        }
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true)
        web.addJavascriptInterface(Bridge(t), "NamiBridge")
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            runCatching { WebViewCompat.addDocumentStartJavaScript(web, PageScript.JS, setOf("*")) }
        }

        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, req: WebResourceRequest): Boolean {
                val u = req.url
                val s = u.scheme ?: return false
                if (s == "http" || s == "https" || s == "about" || s == "data" || s == "blob" || s == "javascript") return false
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
            }

            override fun onPageFinished(view: WebView, url: String) {
                t.url = url; t.progress = 100
                t.canBack = view.canGoBack(); t.canForward = view.canGoForward()
                view.evaluateJavascript(PageScript.JS, null)
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

        web.setDownloadListener { url, ua, cd, mime, _ -> download(url, ua, cd, mime) }

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

    fun exitCustomView() { customCb?.onCustomViewHidden(); customView = null; customCb = null }

    fun download(url: String, ua: String? = null, cd: String? = null, mime: String? = null) {
        if (!url.startsWith("http")) { Toast.makeText(act, "이 파일은 받을 수 없어요", Toast.LENGTH_SHORT).show(); return }
        if (prefs.splitParts > 0) runCatching {
            val name = URLUtil.guessFileName(url, cd, mime)
            if (act.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED)
                act.requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 11)
            DownloadService.start(act, url, name, mime, CookieManager.getInstance().getCookie(url),
                ua ?: tab?.web?.settings?.userAgentString, tab?.url, prefs.splitParts)
            Toast.makeText(act, "받는 중: $name", Toast.LENGTH_SHORT).show()
            return
        }.onFailure { Toast.makeText(act, "분할 다운로드를 못 써서 기본 방식으로 받아요", Toast.LENGTH_SHORT).show() }
        runCatching {
            val name = URLUtil.guessFileName(url, cd, mime)
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
