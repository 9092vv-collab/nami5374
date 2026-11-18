package space.nami.navdot

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.SharedPreferences

/** What the dot can do. */
enum class Act(val label: String, val global: Int) {
    NONE("없음", 0),
    BACK("뒤로 가기", AccessibilityService.GLOBAL_ACTION_BACK),
    HOME("홈", AccessibilityService.GLOBAL_ACTION_HOME),
    RECENTS("최근 앱", AccessibilityService.GLOBAL_ACTION_RECENTS),
    NOTIFICATIONS("알림창", AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS),
    QUICK("빠른 설정", AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS),
    LOCK("화면 끄기", AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN),
    SHOT("스크린샷", AccessibilityService.GLOBAL_ACTION_TAKE_SCREENSHOT),
    SPLIT("분할 화면", AccessibilityService.GLOBAL_ACTION_TOGGLE_SPLIT_SCREEN),
    POWER("전원 메뉴", AccessibilityService.GLOBAL_ACTION_POWER_DIALOG),
}

/** The gestures on the dot. */
enum class Gest(val key: String, val label: String, val def: Act) {
    TAP("tap", "한 번 누르기", Act.BACK),
    DOUBLE("double", "두 번 누르기", Act.NONE),
    UP("up", "위로 밀기", Act.HOME),
    DOWN("down", "아래로 밀기", Act.NOTIFICATIONS),
    LEFT("left", "왼쪽으로 밀기", Act.RECENTS),
    RIGHT("right", "오른쪽으로 밀기", Act.QUICK),
}

object Prefs {
    const val DEF_X = 0.5f
    const val DEF_Y = 0.86f

    fun sp(c: Context): SharedPreferences = c.getSharedPreferences("navdot", Context.MODE_PRIVATE)

    fun act(sp: SharedPreferences, g: Gest): Act =
        sp.getString("act_" + g.key, null)?.let { n -> Act.entries.firstOrNull { it.name == n } } ?: g.def

    fun putAct(c: Context, g: Gest, a: Act) = sp(c).edit().putString("act_" + g.key, a.name).apply()

    fun enabled(sp: SharedPreferences) = sp.getBoolean("enabled", true)
    fun size(sp: SharedPreferences) = sp.getInt("size", 44)          // dp
    fun alpha(sp: SharedPreferences) = sp.getInt("alpha", 55)        // percent
    fun avoidIme(sp: SharedPreferences) = sp.getBoolean("avoidIme", true)
    fun haptic(sp: SharedPreferences) = sp.getBoolean("haptic", true)
    fun x(sp: SharedPreferences) = sp.getFloat("x", DEF_X)
    fun y(sp: SharedPreferences) = sp.getFloat("y", DEF_Y)

    fun putBool(c: Context, k: String, v: Boolean) = sp(c).edit().putBoolean(k, v).apply()
    fun putInt(c: Context, k: String, v: Int) = sp(c).edit().putInt(k, v).apply()
    fun putSpot(c: Context, x: Float, y: Float) = sp(c).edit().putFloat("x", x).putFloat("y", y).apply()
}
