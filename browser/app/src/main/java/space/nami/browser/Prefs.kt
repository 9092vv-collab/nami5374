package space.nami.browser

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material.icons.filled.VerticalAlignTop
import androidx.compose.material.icons.outlined.CropSquare
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.vector.ImageVector

/** Everything a toolbar button or a gesture can do. */
enum class Action(val label: String, val icon: ImageVector?) {
    NONE("없음", null),
    ADDRESS("주소창", Icons.Filled.Search),
    TABS("탭", Icons.Outlined.CropSquare),
    MENU("메뉴 (⋮)", Icons.Filled.MoreVert),
    BACK("뒤로", Icons.AutoMirrored.Filled.ArrowBack),
    FORWARD("앞으로", Icons.AutoMirrored.Filled.ArrowForward),
    RELOAD("새로고침", Icons.Filled.Refresh),
    TRANSLATE("번역 / 원문", Icons.Filled.Translate),
    NEW_TAB("새 탭", Icons.Filled.Add),
    HOME("홈", Icons.Filled.Home),
    SHARE("공유", Icons.Filled.Share),
    TOP("맨 위로", Icons.Filled.VerticalAlignTop),
    TOOLBAR("툴바 보이기/숨기기", Icons.Outlined.Visibility),
    NEXT_TAB("다음 탭", Icons.AutoMirrored.Filled.ArrowForward),
    PREV_TAB("이전 탭", Icons.AutoMirrored.Filled.ArrowBack),
    DOWNLOADS("받은 파일", Icons.Filled.Download),
    SETTINGS("설정", Icons.Filled.Settings);

    companion object {
        fun of(s: String?): Action? = entries.firstOrNull { it.name == s }
        /** Buttons that can live on the toolbar. */
        val toolbarChoices = listOf(ADDRESS, TABS, MENU, BACK, FORWARD, RELOAD, TRANSLATE, NEW_TAB, HOME, SHARE, TOP, DOWNLOADS, SETTINGS)
        /** Things a gesture can be bound to. */
        val gestureChoices = listOf(NONE, RELOAD, TRANSLATE, BACK, FORWARD, NEXT_TAB, PREV_TAB, TOOLBAR, NEW_TAB, TABS, TOP, HOME, DOWNLOADS)
    }
}

/** Pull distance (dp) for "세게 끌어내리기". */
enum class PullStrength(val label: String, val dp: Int) { LIGHT("약하게", 120), NORMAL("보통", 190), STRONG("세게", 280) }

enum class DnsMode(val label: String) { CLOUDFLARE("Cloudflare (1.1.1.1)"), GOOGLE("Google (8.8.8.8)"), OFF("끔 (통신사 DNS)") }

enum class Engine(val label: String) { GOOGLE("구글 (무료)"), VERTEX("Vertex (Gemini)"), DEVICE("폰 안에서 (ML Kit)") }

/** App settings, backed by SharedPreferences and exposed as Compose state. */
class Prefs(ctx: Context) {
    private val sp: SharedPreferences = ctx.getSharedPreferences("nami", Context.MODE_PRIVATE)

    var toolbar by mutableStateOf(
        sp.getString("toolbar", null)?.split(",")?.mapNotNull { Action.of(it) }?.takeIf { it.isNotEmpty() }
            ?: listOf(Action.ADDRESS, Action.TABS, Action.MENU)
    )
        private set

    var pullAction by mutableStateOf(Action.of(sp.getString("pullAction", null)) ?: Action.RELOAD)
        private set
    var doubleTapAction by mutableStateOf(Action.of(sp.getString("doubleTapAction", null)) ?: Action.TRANSLATE)
        private set
    var pullStrength by mutableStateOf(PullStrength.entries.firstOrNull { it.name == sp.getString("pullStrength", null) } ?: PullStrength.NORMAL)
        private set
    var dotLeftAction by mutableStateOf(Action.of(sp.getString("dotLeftAction", null)) ?: Action.NEXT_TAB)
        private set
    var dotRightAction by mutableStateOf(Action.of(sp.getString("dotRightAction", null)) ?: Action.PREV_TAB)
        private set
    var dotUpAction by mutableStateOf(Action.of(sp.getString("dotUpAction", null)) ?: Action.TABS)
        private set
    var dotOnLeft by mutableStateOf(sp.getBoolean("dotOnLeft", false))
        private set
    // Dot offset from its corner in dp. x: + = right, y: + = up
    var dotX by mutableStateOf(sp.getInt("dotX", 0))
        private set
    var dotY by mutableStateOf(sp.getInt("dotY", 0))
        private set
    var hideStatusBar by mutableStateOf(sp.getBoolean("hideStatusBar", false))
        private set
    var adblock by mutableStateOf(sp.getBoolean("adblock", true))
        private set
    /** Content-blocker apps the user turned on (null = never chosen → turn on whatever is found). */
    var blockerApps by mutableStateOf(sp.getStringSet("blockerApps", emptySet())?.toSet() ?: emptySet())
        private set
    val blockerAppsChosen get() = sp.contains("blockerApps")
    var filterUrls by mutableStateOf(sp.getString("filterUrls", "")!!.split("\n").filter { it.isNotBlank() })
        private set
    var adblockOff by mutableStateOf(sp.getStringSet("adblockOff", emptySet())?.toSet() ?: emptySet())
        private set
    var openApps by mutableStateOf(sp.getBoolean("openApps", true))
        private set
    /** "호환 모드": sites where the service worker is hidden so in-page downloads fall back to a saveable blob. */
    var swOffSites by mutableStateOf(sp.getStringSet("swOffSites", emptySet())?.toSet() ?: emptySet())
        private set
    var dualNetwork by mutableStateOf(sp.getBoolean("dualNetwork", false))
        private set
    var splitParts by mutableStateOf(sp.getInt("splitParts", 4))
        private set
    // ---- network (LocalProxy) ----
    var dnsMode by mutableStateOf(DnsMode.entries.firstOrNull { it.name == sp.getString("dnsMode", null) } ?: DnsMode.CLOUDFLARE)
        private set
    var dpiBypass by mutableStateOf(sp.getBoolean("dpiBypass", true))
        private set
    var bypassOff by mutableStateOf(sp.getStringSet("bypassOff", emptySet())?.toSet() ?: emptySet())
        private set
    var textZoom by mutableStateOf(sp.getInt("textZoom", 100))
        private set
    var forceZoom by mutableStateOf(sp.getBoolean("forceZoom", true))
        private set
    var highRefresh by mutableStateOf(sp.getBoolean("highRefresh", true))
        private set
    var engine by mutableStateOf(Engine.entries.firstOrNull { it.name == sp.getString("engine", null) } ?: Engine.GOOGLE)
        private set
    var vertexJson by mutableStateOf(sp.getString("vertexJson", "") ?: "")
        private set
    var vertexModel by mutableStateOf(sp.getString("vertexModel", "gemini-2.5-flash") ?: "gemini-2.5-flash")
        private set
    var vertexLocation by mutableStateOf(sp.getString("vertexLocation", "global") ?: "global")
        private set
    var alwaysHosts by mutableStateOf(sp.getStringSet("alwaysHosts", emptySet())?.toSet() ?: emptySet())
        private set
    var homeUrl by mutableStateOf(sp.getString("homeUrl", "https://www.google.com") ?: "https://www.google.com")
        private set

    fun putToolbar(v: List<Action>) {
        val list = v.distinct().let { if (Action.MENU in it) it else it + Action.MENU }
        toolbar = list; sp.edit().putString("toolbar", list.joinToString(",") { it.name }).apply()
    }
    fun putPullAction(v: Action) { pullAction = v; sp.edit().putString("pullAction", v.name).apply() }
    fun putDoubleTapAction(v: Action) { doubleTapAction = v; sp.edit().putString("doubleTapAction", v.name).apply() }
    fun putPullStrength(v: PullStrength) { pullStrength = v; sp.edit().putString("pullStrength", v.name).apply() }
    fun putDotLeftAction(v: Action) { dotLeftAction = v; sp.edit().putString("dotLeftAction", v.name).apply() }
    fun putDotRightAction(v: Action) { dotRightAction = v; sp.edit().putString("dotRightAction", v.name).apply() }
    fun putDotUpAction(v: Action) { dotUpAction = v; sp.edit().putString("dotUpAction", v.name).apply() }
    fun putDotOnLeft(v: Boolean) { dotOnLeft = v; dotX = 0; sp.edit().putBoolean("dotOnLeft", v).putInt("dotX", 0).apply() }
    fun putDotX(v: Int) { dotX = v; sp.edit().putInt("dotX", v).apply() }
    fun putDotY(v: Int) { dotY = v; sp.edit().putInt("dotY", v).apply() }
    fun putAdblock(v: Boolean) { adblock = v; sp.edit().putBoolean("adblock", v).apply() }
    fun putBlockerApps(v: Set<String>) { blockerApps = v; sp.edit().putStringSet("blockerApps", v).apply() }
    fun putFilterUrls(v: List<String>) { filterUrls = v.distinct(); sp.edit().putString("filterUrls", filterUrls.joinToString("\n")).apply() }
    /** True when ad blocking is turned off for this host or a parent domain (kio.ac also covers www.kio.ac). */
    fun adblockOffFor(host: String): Boolean {
        if (host.isEmpty()) return false
        var h = host.lowercase()
        while (true) {
            if (h in adblockOff) return true
            val i = h.indexOf('.'); if (i < 0) return false
            h = h.substring(i + 1)
        }
    }
    fun putAdblockOff(host: String, off: Boolean) {
        val h = host.lowercase().removePrefix("www.")
        val s = if (off) adblockOff + h
            else adblockOff.filterNot { e -> host.lowercase() == e || host.lowercase().endsWith(".$e") }.toSet()
        adblockOff = s; sp.edit().putStringSet("adblockOff", s).apply()
    }

    init {
        // Sites the user asked to keep ad blocking off for (added once).
        if (!sp.getBoolean("seed_adblockOff_kioac", false)) {
            putAdblockOff("kio.ac", true)
            sp.edit().putBoolean("seed_adblockOff_kioac", true).apply()
        }
        // kio.ac needs its service worker (downloads are relayed instead) → take it out of 호환 모드 once
        if (!sp.getBoolean("seed_swOn_kioac", false)) {
            putSwOff("kio.ac", false)
            sp.edit().putBoolean("seed_swOn_kioac", true).apply()
        }
    }
    fun putOpenApps(v: Boolean) { openApps = v; sp.edit().putBoolean("openApps", v).apply() }
    fun swOffFor(host: String): Boolean {
        if (host.isEmpty()) return false
        var h = host.lowercase()
        while (true) {
            if (h in swOffSites) return true
            val i = h.indexOf('.'); if (i < 0) return false
            h = h.substring(i + 1)
        }
    }
    fun putSwOff(host: String, on: Boolean) {
        val h = host.lowercase().removePrefix("www.")
        val s = if (on) swOffSites + h else swOffSites.filterNot { e -> host.lowercase() == e || host.lowercase().endsWith(".$e") }.toSet()
        swOffSites = s; sp.edit().putStringSet("swOffSites", s).apply()
    }
    fun putDualNetwork(v: Boolean) { dualNetwork = v; sp.edit().putBoolean("dualNetwork", v).apply() }
    fun putSplitParts(v: Int) { splitParts = v; sp.edit().putInt("splitParts", v).apply() }
    fun putDnsMode(v: DnsMode) { dnsMode = v; sp.edit().putString("dnsMode", v.name).apply() }
    fun putDpiBypass(v: Boolean) { dpiBypass = v; sp.edit().putBoolean("dpiBypass", v).apply() }
    fun putBypassOff(host: String, off: Boolean) {
        val h = host.lowercase().removePrefix("www.")
        bypassOff = if (off) bypassOff + h else bypassOff - h; sp.edit().putStringSet("bypassOff", bypassOff).apply()
    }
    fun putTextZoom(v: Int) { textZoom = v; sp.edit().putInt("textZoom", v).apply() }
    fun putForceZoom(v: Boolean) { forceZoom = v; sp.edit().putBoolean("forceZoom", v).apply() }
    fun putHighRefresh(v: Boolean) { highRefresh = v; sp.edit().putBoolean("highRefresh", v).apply() }
    fun putHideStatusBar(v: Boolean) { hideStatusBar = v; sp.edit().putBoolean("hideStatusBar", v).apply() }
    fun putEngine(v: Engine) { engine = v; sp.edit().putString("engine", v.name).apply() }
    fun putVertexJson(v: String) { vertexJson = v; sp.edit().putString("vertexJson", v).apply() }
    fun putVertexModel(v: String) { vertexModel = v; sp.edit().putString("vertexModel", v).apply() }
    fun putVertexLocation(v: String) { vertexLocation = v; sp.edit().putString("vertexLocation", v).apply() }
    fun putHomeUrl(v: String) { homeUrl = v; sp.edit().putString("homeUrl", v).apply() }
    fun setAlways(host: String, on: Boolean) {
        val s = if (on) alwaysHosts + host else alwaysHosts - host
        alwaysHosts = s; sp.edit().putStringSet("alwaysHosts", s).apply()
    }

    // Open tabs survive restarts (urls only).
    fun saveTabs(urls: List<String>, current: Int) {
        sp.edit().putString("tabs", urls.joinToString("\n")).putInt("tabIndex", current).apply()
    }
    fun loadTabs(): Pair<List<String>, Int> {
        val urls = sp.getString("tabs", "")!!.split("\n").filter { it.isNotBlank() }
        return urls to sp.getInt("tabIndex", 0)
    }
}
