package space.nami.rotate

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.database.ContentObserver
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.provider.Settings
import android.util.DisplayMetrics
import android.view.Display
import android.view.Surface
import android.view.accessibility.AccessibilityEvent
import rikka.shizuku.Shizuku
import kotlin.math.min

/** Saved choices. */
object Prefs {
    const val FILE = "rotate"
    const val ENABLED = "enabled"
    const val ROTATION = "rotation"
    const val FORCE = "force"
    private const val PREV_ACCEL = "prevAccel"
    private const val PREV_USER = "prevUser"
    private const val PREV_IGNORE = "prevIgnore"

    fun of(c: Context): SharedPreferences = c.getSharedPreferences(FILE, Context.MODE_PRIVATE)
    fun enabled(c: Context) = of(c).getBoolean(ENABLED, true)
    fun rotation(c: Context) = of(c).getInt(ROTATION, Surface.ROTATION_90)
    fun setEnabled(c: Context, v: Boolean) = of(c).edit().putBoolean(ENABLED, v).apply()
    fun setRotation(c: Context, v: Int) = of(c).edit().putInt(ROTATION, v).apply()
    /** Make apps that fix their own orientation follow too (needs Shizuku). */
    fun force(c: Context) = of(c).getBoolean(FORCE, true)
    fun setForce(c: Context, v: Boolean) = of(c).edit().putBoolean(FORCE, v).apply()

    fun savePrev(c: Context, accel: Int, user: Int) =
        of(c).edit().putInt(PREV_ACCEL, accel).putInt(PREV_USER, user).apply()
    fun prevAccel(c: Context) = of(c).getInt(PREV_ACCEL, 1)
    fun prevUser(c: Context) = of(c).getInt(PREV_USER, Surface.ROTATION_0)
    fun prevIgnore(c: Context) = of(c).getBoolean(PREV_IGNORE, false)
    fun setPrevIgnore(c: Context, v: Boolean) = of(c).edit().putBoolean(PREV_IGNORE, v).apply()
}

/** Runs shell commands as the shell user through Shizuku. */
object Sh {
    fun ready(): Boolean = runCatching {
        Shizuku.pingBinder() && !Shizuku.isPreV11() &&
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    }.getOrDefault(false)

    private val newProcess by lazy {
        Shizuku::class.java.getDeclaredMethod(
            "newProcess", Array<String>::class.java, Array<String>::class.java, String::class.java,
        ).apply { isAccessible = true }
    }

    /** Output, or null when it could not run. */
    fun run(vararg cmd: String): String? {
        if (!ready()) return null
        return runCatching {
            val p = newProcess.invoke(null, arrayOf(*cmd), null, null) as Process
            val out = p.inputStream.bufferedReader().readText()
            p.waitFor()
            if (p.exitValue() == 0) out else null
        }.getOrNull()
    }
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
 * Holds the screen rotation while the phone is unfolded.
 * With Shizuku: `cmd window set-user-rotation lock <r>` (exact rotation, no guessing) and
 * `cmd window set-ignore-orientation-request true` so apps can't turn it.
 * Without Shizuku: auto-rotate off + user_rotation in system settings.
 * When folded it gives the cover screen back.
 */
class RotateService : AccessibilityService() {
    private val main = Handler(Looper.getMainLooper())
    private val thread = HandlerThread("rotate").apply { start() }
    private val bg = Handler(thread.looper)
    private var enforcing = false
    // Persisted: if the service dies while apps are told to be ignored, the next start undoes it.
    private var ignoring: Boolean
        get() = Prefs.of(this).getBoolean("ignoring", false)
        set(v) { Prefs.of(this).edit().putBoolean("ignoring", v).commit() }
    private var disabledByUser = false

    private val check = Runnable { apply() }
    private fun schedule(delay: Long = 400) {
        bg.removeCallbacks(check)
        bg.postDelayed(check, delay)
    }

    private val displays = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(id: Int) {}
        override fun onDisplayRemoved(id: Int) {}
        override fun onDisplayChanged(id: Int) {
            if (id != Display.DEFAULT_DISPLAY) return
            // Folding: let apps rotate again right away so the cover screen is never stuck sideways.
            if (ignoring && !isUnfolded(this@RotateService)) bg.post { releaseIgnore() }
            schedule()
        }
    }
    private val settings = object : ContentObserver(main) {
        override fun onChange(selfChange: Boolean) { schedule() }
    }
    private val prefs = SharedPreferences.OnSharedPreferenceChangeListener { p, key ->
        if (key == Prefs.ENABLED && !p.getBoolean(Prefs.ENABLED, true)) disabledByUser = true
        schedule(30)
    }
    private val shizukuUp = Shizuku.OnBinderReceivedListener { schedule(30) }
    private val shizukuPerm = Shizuku.OnRequestPermissionResultListener { _, _ -> schedule(30) }

    override fun onServiceConnected() {
        running = true
        getSystemService(DisplayManager::class.java)?.registerDisplayListener(displays, main)
        val r = contentResolver
        r.registerContentObserver(Settings.System.getUriFor(Settings.System.ACCELEROMETER_ROTATION), false, settings)
        r.registerContentObserver(Settings.System.getUriFor(Settings.System.USER_ROTATION), false, settings)
        Prefs.of(this).registerOnSharedPreferenceChangeListener(prefs)
        runCatching {
            Shizuku.addBinderReceivedListenerSticky(shizukuUp)
            Shizuku.addRequestPermissionResultListener(shizukuPerm)
        }
        schedule(30)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        schedule()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    override fun onDestroy() {
        running = false
        bg.removeCallbacks(check)
        bg.post { releaseIgnore(); thread.quitSafely() }
        getSystemService(DisplayManager::class.java)?.unregisterDisplayListener(displays)
        contentResolver.unregisterContentObserver(settings)
        Prefs.of(this).unregisterOnSharedPreferenceChangeListener(prefs)
        runCatching {
            Shizuku.removeBinderReceivedListener(shizukuUp)
            Shizuku.removeRequestPermissionResultListener(shizukuPerm)
        }
        super.onDestroy()
    }

    private fun get(name: String, def: Int) = Settings.System.getInt(contentResolver, name, def)
    private fun put(name: String, v: Int) {
        if (get(name, -1) != v) runCatching { Settings.System.putInt(contentResolver, name, v) }
    }

    private fun currentRotation(): Int =
        getSystemService(DisplayManager::class.java)?.getDisplay(Display.DEFAULT_DISPLAY)?.rotation ?: -1

    private fun lock(r: Int) {
        val locked = get(Settings.System.ACCELEROMETER_ROTATION, 1) == 0 &&
            get(Settings.System.USER_ROTATION, -1) == r
        if (locked && currentRotation() == r) return
        if (Sh.run("cmd", "window", "set-user-rotation", "lock", r.toString()) == null) {
            put(Settings.System.ACCELEROMETER_ROTATION, 0)
            put(Settings.System.USER_ROTATION, r)
        }
    }

    private fun holdIgnore() {
        if (ignoring) return
        val now = Sh.run("cmd", "window", "get-ignore-orientation-request") ?: return
        Prefs.setPrevIgnore(this, now.contains("true"))
        if (Sh.run("cmd", "window", "set-ignore-orientation-request", "true") != null) ignoring = true
    }

    private fun releaseIgnore() {
        if (!ignoring) return
        ignoring = false
        Sh.run("cmd", "window", "set-ignore-orientation-request", Prefs.prevIgnore(this).toString())
    }

    private fun apply() {
        val shizuku = Sh.ready()
        if (!shizuku && !Settings.System.canWrite(this)) return
        val unfolded = isUnfolded(this)
        val on = Prefs.enabled(this)
        if (on && unfolded) {
            disabledByUser = false
            if (!enforcing) {
                Prefs.savePrev(this, get(Settings.System.ACCELEROMETER_ROTATION, 1),
                    get(Settings.System.USER_ROTATION, Surface.ROTATION_0))
                enforcing = true
            }
            if (shizuku && Prefs.force(this)) holdIgnore() else releaseIgnore()
            lock(Prefs.rotation(this))
        } else if (enforcing) {
            enforcing = false
            releaseIgnore()
            // Turned off by the user while unfolded: give back what was there before.
            // Folded: leave it alone, One UI restores the cover screen's own setting.
            if (unfolded && disabledByUser) {
                val accel = Prefs.prevAccel(this)
                val user = Prefs.prevUser(this)
                val cmd = if (accel == 1) arrayOf("cmd", "window", "set-user-rotation", "free")
                else arrayOf("cmd", "window", "set-user-rotation", "lock", user.toString())
                if (Sh.run(*cmd) == null) {
                    put(Settings.System.USER_ROTATION, user)
                    put(Settings.System.ACCELEROMETER_ROTATION, accel)
                }
            }
            disabledByUser = false
        } else releaseIgnore()
    }

    companion object {
        @Volatile var running = false
    }
}
