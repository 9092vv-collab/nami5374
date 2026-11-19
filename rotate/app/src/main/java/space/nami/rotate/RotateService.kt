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

/** Saved choices. */
object Prefs {
    const val FILE = "rotate"
    const val ENABLED = "enabled"
    const val ROTATION = "rotation"
    const val FORCE = "force"
    private const val PREV_ACCEL = "prevAccel"
    private const val PREV_USER = "prevUser"
    private const val PREV_IGNORE = "prevIgnore"

    const val EXCLUDED = "excluded"

    fun of(c: Context): SharedPreferences = c.getSharedPreferences(FILE, Context.MODE_PRIVATE)
    /** Apps that get the screen back (no lock) while they're in front. */
    fun excluded(c: Context): Set<String> = of(c).getStringSet(EXCLUDED, emptySet()) ?: emptySet()
    fun setExcluded(c: Context, v: Set<String>) = of(c).edit().putStringSet(EXCLUDED, HashSet(v)).apply()
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
    // Cover screen: portrait lock until we've seen its real setting.
    fun saveCover(c: Context, accel: Int, user: Int) =
        of(c).edit().putInt("coverAccel", accel).putInt("coverUser", user).apply()
    fun coverAccel(c: Context) = of(c).getInt("coverAccel", 0)
    fun coverUser(c: Context) = of(c).getInt("coverUser", Surface.ROTATION_0)
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
    /** What the last command did, shown on the app screen to find problems. */
    @Volatile var last = ""

    fun run(vararg cmd: String): String? {
        val name = cmd.drop(2).joinToString(" ")
        if (!ready()) { last = "$name → Shizuku 안 됨"; return null }
        return runCatching {
            val p = newProcess.invoke(null, arrayOf(*cmd), null, null) as Process
            val out = p.inputStream.bufferedReader().readText()
            val err = p.errorStream.bufferedReader().readText()
            p.waitFor()
            val code = p.exitValue()
            last = "$name → " + (if (code == 0) "성공" else "실패($code) ${err.ifBlank { out }.trim().take(160)}")
            if (code == 0) out else null
        }.getOrElse {
            val e = (it as? java.lang.reflect.InvocationTargetException)?.targetException ?: it
            last = "$name → 오류 ${e.javaClass.simpleName}: ${e.message?.take(160)}"
            null
        }
    }
}

/** Native pixel size of the panel the default display is on now ("2184×1968"), for the status line. */
fun panelSize(c: Context): String {
    val d = c.getSystemService(DisplayManager::class.java)?.getDisplay(Display.DEFAULT_DISPLAY) ?: return "?"
    val m = d.mode
    return "${m.physicalWidth}×${m.physicalHeight}"
}

/**
 * True when the default display is on the big inner panel.
 * The cover and inner panels share display 0; the inner panel simply has far more pixels.
 * Uses the panel's native resolution (not affected by display zoom or rotation) and compares it
 * with the biggest panel seen so far, so it doesn't depend on the exact model.
 */
fun isUnfolded(c: Context): Boolean {
    val d = c.getSystemService(DisplayManager::class.java)?.getDisplay(Display.DEFAULT_DISPLAY) ?: return false
    val m = d.mode
    val area = m.physicalWidth.toLong() * m.physicalHeight
    if (area <= 0) return false
    val p = Prefs.of(c)
    var biggest = p.getLong("biggestPanel", 0L)
    if (area > biggest) { biggest = area; p.edit().putLong("biggestPanel", area).apply() }
    // Until the inner panel has been seen once, only call it unfolded when it's clearly large.
    if (area < 3_300_000L && biggest == area) return false
    return area * 100 >= biggest * 85
}

/**
 * Holds the screen rotation while the phone is unfolded.
 * With Shizuku: `cmd window user-rotation lock <r>` (exact rotation, no guessing) and
 * `cmd window set-ignore-orientation-request true` so apps can't turn it.
 * Without Shizuku: auto-rotate off + user_rotation in system settings.
 * When folded it gives the cover screen back.
 */
class RotateService : AccessibilityService() {
    private val main = Handler(Looper.getMainLooper())
    private val thread = HandlerThread("rotate").apply { start() }
    private val bg = Handler(thread.looper)
    // Persisted so a restart while folded never mistakes our lock for the cover screen's own setting.
    private var enforcing: Boolean
        get() = Prefs.of(this).getBoolean("enforcing", false)
        set(v) { Prefs.of(this).edit().putBoolean("enforcing", v).commit() }
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
            if (!isUnfolded(this@RotateService)) {
                if (ignoring) bg.post { releaseIgnore() }
                schedule(30) // give the cover screen its own setting back right away
            } else schedule()
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

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString() ?: return
        // The notification shade and the keyboard pop over apps; they don't change which app is in front.
        if (pkg == "com.android.systemui" || pkg == imePackage()) return
        if (pkg != front) {
            front = pkg
            schedule(60)
        }
    }

    private fun imePackage(): String? =
        Settings.Secure.getString(contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)?.substringBefore('/')
    override fun onInterrupt() {}

    override fun onDestroy() {
        running = false
        pausedFor = null
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
        if (Sh.run("cmd", "window", "user-rotation", "lock", r.toString()) == null) {
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

    /** Let the app in front turn the screen as it likes (auto-rotate on). */
    private fun free() {
        if (get(Settings.System.ACCELEROMETER_ROTATION, 0) == 1) return
        if (Sh.run("cmd", "window", "user-rotation", "free") == null) put(Settings.System.ACCELEROMETER_ROTATION, 1)
    }

    private fun restore(accel: Int, user: Int) {
        val cmd = if (accel == 1) arrayOf("cmd", "window", "user-rotation", "free")
        else arrayOf("cmd", "window", "user-rotation", "lock", user.toString())
        if (Sh.run(*cmd) == null) {
            put(Settings.System.USER_ROTATION, user)
            put(Settings.System.ACCELEROMETER_ROTATION, accel)
        }
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
            val f = front
            if (f != null && f in Prefs.excluded(this)) {
                // An excluded app is in front: hand the screen back until another app comes up.
                pausedFor = f
                releaseIgnore()
                free()
                return
            }
            pausedFor = null
            if (shizuku && Prefs.force(this)) holdIgnore() else releaseIgnore()
            lock(Prefs.rotation(this))
            return
        }
        releaseIgnore()
        if (enforcing) {
            enforcing = false
            if (!unfolded) {
                // Folded: the lock lives on the same display, so put the cover screen's own setting back.
                restore(Prefs.coverAccel(this), Prefs.coverUser(this))
            } else if (disabledByUser) {
                // Turned off by the user while unfolded: give back what was there before.
                restore(Prefs.prevAccel(this), Prefs.prevUser(this))
            }
        } else if (!unfolded) {
            // Remember how the cover screen is set while we're not touching it.
            Prefs.saveCover(this, get(Settings.System.ACCELEROMETER_ROTATION, 0),
                get(Settings.System.USER_ROTATION, Surface.ROTATION_0))
        }
        disabledByUser = false
    }

    companion object {
        @Volatile var running = false
        /** App in front (from window changes), shown on the status line. */
        @Volatile var front: String? = null
        /** Set while the lock is let go for an excluded app. */
        @Volatile var pausedFor: String? = null
    }
}
