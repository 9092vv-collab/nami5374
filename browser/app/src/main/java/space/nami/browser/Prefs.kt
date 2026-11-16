package space.nami.browser

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Add
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
    SETTINGS("설정", Icons.Filled.Settings);

    companion object {
        fun of(s: String?): Action? = entries.firstOrNull { it.name == s }
        /** Buttons that can live on the toolbar. */
        val toolbarChoices = listOf(ADDRESS, TABS, MENU, BACK, FORWARD, RELOAD, TRANSLATE, NEW_TAB, HOME, SHARE, TOP, SETTINGS)
        /** Things a gesture can be bound to. */
        val gestureChoices = listOf(NONE, RELOAD, TRANSLATE, BACK, FORWARD, TOOLBAR, NEW_TAB, TABS, TOP, HOME)
    }
}

/** Pull distance (dp) for "세게 끌어내리기". */
enum class PullStrength(val label: String, val dp: Int) { LIGHT("약하게", 120), NORMAL("보통", 190), STRONG("세게", 280) }

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
    var dotOnLeft by mutableStateOf(sp.getBoolean("dotOnLeft", false))
        private set
    var hideStatusBar by mutableStateOf(sp.getBoolean("hideStatusBar", false))
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

    fun setToolbar(v: List<Action>) {
        val list = v.distinct().let { if (Action.MENU in it) it else it + Action.MENU }
        toolbar = list; sp.edit().putString("toolbar", list.joinToString(",") { it.name }).apply()
    }
    fun setPullAction(v: Action) { pullAction = v; sp.edit().putString("pullAction", v.name).apply() }
    fun setDoubleTapAction(v: Action) { doubleTapAction = v; sp.edit().putString("doubleTapAction", v.name).apply() }
    fun setPullStrength(v: PullStrength) { pullStrength = v; sp.edit().putString("pullStrength", v.name).apply() }
    fun setDotOnLeft(v: Boolean) { dotOnLeft = v; sp.edit().putBoolean("dotOnLeft", v).apply() }
    fun setHideStatusBar(v: Boolean) { hideStatusBar = v; sp.edit().putBoolean("hideStatusBar", v).apply() }
    fun setEngine(v: Engine) { engine = v; sp.edit().putString("engine", v.name).apply() }
    fun setVertexJson(v: String) { vertexJson = v; sp.edit().putString("vertexJson", v).apply() }
    fun setVertexModel(v: String) { vertexModel = v; sp.edit().putString("vertexModel", v).apply() }
    fun setVertexLocation(v: String) { vertexLocation = v; sp.edit().putString("vertexLocation", v).apply() }
    fun setHomeUrl(v: String) { homeUrl = v; sp.edit().putString("homeUrl", v).apply() }
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
