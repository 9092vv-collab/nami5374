package space.nami.rotate

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.SharedPreferences
import android.content.res.Configuration
import android.database.ContentObserver
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.content.pm.ActivityInfo
import android.graphics.PixelFormat
import android.provider.Settings
import android.view.View
import android.view.WindowManager
import android.util.DisplayMetrics
import android.view.Display
import android.view.Surface
import android.view.accessibility.AccessibilityEvent
import kotlin.math.min

/** Saved choices. */
object Prefs {
    const val FILE = "rotate"
    const val ENABLED = "enabled"
    const val ROTATION = "rotation"
    const val FORCE = "force"
    private const val PREV_ACCEL = "prevAccel"
    private const val PREV_USER = "prevUser"

    fun of(c: Context): SharedPreferences = c.getSharedPreferences(FILE, Context.MODE_PRIVATE)
    fun enabled(c: Context) = of(c).getBoolean(ENABLED, true)
    fun rotation(c: Context) = of(c).getInt(ROTATION, Surface.ROTATION_90)
    fun setEnabled(c: Context, v: Boolean) = of(c).edit().putBoolean(ENABLED, v).apply()
    fun setRotation(c: Context, v: Int) = of(c).edit().putInt(ROTATION, v).apply()
    /** Also override apps that fix their own orientation (invisible overlay). */
    fun force(c: Context) = of(c).getBoolean(FORCE, true)
    fun setForce(c: Context, v: Boolean) = of(c).edit().putBoolean(FORCE, v).apply()

    fun savePrev(c: Context, accel: Int, user: Int) =
        of(c).edit().putInt(PREV_ACCEL, accel).putInt(PREV_USER, user).apply()
    fun prevAccel(c: Context) = of(c).getInt(PREV_ACCEL, 1)
    fun prevUser(c: Context) = of(c).getInt(PREV_USER, Surface.ROTATION_0)
}

/** True when the default display is the big inner screen (smallest side 600dp or more). */
fun isUnfolded(c: Context): Boolean {
    val dm = c.getSystemService(DisplayManager::class.java) ?: return false
    val d = dm.getDisplay(Display.DEFAULT_DISPLAY) ?: return false
    val m = DisplayMetrics()
    @Suppress("DEPRECATION") d.getRealMetrics(m)
    if (m.density <= 0f) return false
    return min(m.widthPixels, m.heightPixels) / m.density >= 600f
}

/**
 * Holds the screen rotation while the phone is unfolded:
 * auto-rotate off + user_rotation = chosen direction. Re-applies when the system
 * (e.g. One UI's per-screen rotation memory) writes something else.
 * When folded it does nothing, so the cover screen keeps its own setting.
 */
class RotateService : AccessibilityService() {
    private val h = Handler(Looper.getMainLooper())
    private var enforcing = false

    private val check = Runnable { apply() }
    private fun schedule(delay: Long = 400) {
        h.removeCallbacks(check)
        h.postDelayed(check, delay)
    }

    private val displays = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(id: Int) {}
        override fun onDisplayRemoved(id: Int) {}
        override fun onDisplayChanged(id: Int) {
            if (id != Display.DEFAULT_DISPLAY) return
            // Folding: drop the overlay right away so the cover screen never turns sideways.
            if (!isUnfolded(this@RotateService)) hideOverlay()
            schedule()
        }
    }
    private val settings = object : ContentObserver(h) {
        override fun onChange(selfChange: Boolean) { schedule() }
    }
    private var disabledByUser = false
    private val prefs = SharedPreferences.OnSharedPreferenceChangeListener { p, key ->
        if (key == Prefs.ENABLED && !p.getBoolean(Prefs.ENABLED, true)) disabledByUser = true
        schedule(50)
    }

    override fun onServiceConnected() {
        running = true
        getSystemService(DisplayManager::class.java)?.registerDisplayListener(displays, h)
        val r = contentResolver
        r.registerContentObserver(Settings.System.getUriFor(Settings.System.ACCELEROMETER_ROTATION), false, settings)
        r.registerContentObserver(Settings.System.getUriFor(Settings.System.USER_ROTATION), false, settings)
        Prefs.of(this).registerOnSharedPreferenceChangeListener(prefs)
        schedule(50)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        schedule()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    override fun onDestroy() {
        running = false
        h.removeCallbacks(check)
        hideOverlay()
        getSystemService(DisplayManager::class.java)?.unregisterDisplayListener(displays)
        contentResolver.unregisterContentObserver(settings)
        Prefs.of(this).unregisterOnSharedPreferenceChangeListener(prefs)
        super.onDestroy()
    }

    // An invisible 1px window that asks for the orientation. Windows above apps win over
    // what the app itself asks for, so apps that lock portrait follow it too.
    private var overlay: View? = null
    private var overlayOrientation = Int.MIN_VALUE

    /** ActivityInfo orientation that lands on [rotation] for the default display. */
    private fun orientationFor(rotation: Int): Int {
        val d = getSystemService(DisplayManager::class.java)?.getDisplay(Display.DEFAULT_DISPLAY)
            ?: return ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        val m = DisplayMetrics()
        @Suppress("DEPRECATION") d.getRealMetrics(m)
        val cur = d.rotation
        val wide = m.widthPixels > m.heightPixels
        val sideways = cur == Surface.ROTATION_90 || cur == Surface.ROTATION_270
        val naturalLandscape = wide != sideways
        return if (!naturalLandscape) when (rotation) {
            Surface.ROTATION_90 -> ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            Surface.ROTATION_270 -> ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE
            Surface.ROTATION_180 -> ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT
            else -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        } else when (rotation) {
            Surface.ROTATION_0 -> ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            Surface.ROTATION_180 -> ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE
            Surface.ROTATION_90 -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            else -> ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT
        }
    }

    private fun showOverlay(rotation: Int) {
        val wm = getSystemService(WindowManager::class.java) ?: return
        val o = orientationFor(rotation)
        if (overlay != null && overlayOrientation == o) return
        val lp = WindowManager.LayoutParams(
            1, 1,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply { screenOrientation = o; title = "rotate-lock" }
        runCatching {
            val v = overlay
            if (v == null) { val nv = View(this); wm.addView(nv, lp); overlay = nv }
            else wm.updateViewLayout(v, lp)
            overlayOrientation = o
        }
    }

    private fun hideOverlay() {
        val v = overlay ?: return
        runCatching { getSystemService(WindowManager::class.java)?.removeView(v) }
        overlay = null
        overlayOrientation = Int.MIN_VALUE
    }

    private fun get(name: String, def: Int) = Settings.System.getInt(contentResolver, name, def)
    private fun put(name: String, v: Int) {
        if (get(name, -1) != v) runCatching { Settings.System.putInt(contentResolver, name, v) }
    }

    private fun apply() {
        if (!Settings.System.canWrite(this)) return
        val unfolded = isUnfolded(this)
        val on = Prefs.enabled(this)
        if (on && unfolded) {
            disabledByUser = false
            if (!enforcing) {
                Prefs.savePrev(this, get(Settings.System.ACCELEROMETER_ROTATION, 1),
                    get(Settings.System.USER_ROTATION, Surface.ROTATION_0))
                enforcing = true
            }
            put(Settings.System.ACCELEROMETER_ROTATION, 0)
            put(Settings.System.USER_ROTATION, Prefs.rotation(this))
            if (Prefs.force(this)) showOverlay(Prefs.rotation(this)) else hideOverlay()
        } else if (enforcing) {
            enforcing = false
            hideOverlay()
            // Turned off by the user while unfolded: give back what was there before.
            // Folded: leave it alone, One UI restores the cover screen's own setting.
            if (unfolded && disabledByUser) {
                put(Settings.System.USER_ROTATION, Prefs.prevUser(this))
                put(Settings.System.ACCELEROMETER_ROTATION, Prefs.prevAccel(this))
            }
            disabledByUser = false
        }
    }

    companion object {
        @Volatile var running = false
    }
}
