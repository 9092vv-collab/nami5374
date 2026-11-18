package space.nami.navdot

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.SharedPreferences
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.VibratorManager
import android.view.Display
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityWindowInfo
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.roundToInt

class NavDotService : AccessibilityService(), SharedPreferences.OnSharedPreferenceChangeListener {
    companion object {
        @Volatile var running = false
    }

    private val h = Handler(Looper.getMainLooper())
    private lateinit var sp: SharedPreferences
    private var wm: WindowManager? = null
    private var dot: DotView? = null
    private var lp: WindowManager.LayoutParams? = null
    private var imeTop = -1          // top edge of the keyboard on screen, -1 = no keyboard
    private var dragX = 0
    private var dragY = 0

    override fun onServiceConnected() {
        running = true
        sp = Prefs.sp(this)
        sp.registerOnSharedPreferenceChangeListener(this)
        wm = getSystemService(WindowManager::class.java)
        refresh()
    }

    override fun onAccessibilityEvent(e: AccessibilityEvent?) {
        if (e?.eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED) checkIme()
    }

    override fun onInterrupt() {}

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // Rotation or fold/unfold: the screen size changed, put the dot back at its spot.
        h.postDelayed({ checkIme(); place() }, 60)
    }

    override fun onSharedPreferenceChanged(p: SharedPreferences?, key: String?) {
        refresh() // after our own drag save this puts the dot at the same place, harmless
    }

    override fun onDestroy() {
        running = false
        runCatching { sp.unregisterOnSharedPreferenceChangeListener(this) }
        remove()
        super.onDestroy()
    }

    // ---- the dot window ----

    private fun refresh() {
        if (!Prefs.enabled(sp)) { remove(); return }
        if (dot == null) add() else { look(); place() }
    }

    private fun add() {
        val v = DotView(this)
        v.onGesture = { perform(it) }
        v.hasDouble = { Prefs.act(sp, Gest.DOUBLE) != Act.NONE }
        v.onDragStart = {
            buzz(VibrationEffect.EFFECT_HEAVY_CLICK)
            dragX = lp?.x ?: 0; dragY = lp?.y ?: 0
        }
        v.onDragTo = { dx, dy -> moveTo(dragX + dx, dragY + dy) }
        v.onDragEnd = { saveSpot() }
        val s = px(Prefs.size(sp))
        val p = WindowManager.LayoutParams(
            s, s,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        )
        p.gravity = Gravity.TOP or Gravity.START
        p.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        p.fitInsetsTypes = 0
        dot = v; lp = p
        look()
        spot(p)
        runCatching { wm?.addView(v, p) }.onFailure { dot = null; lp = null }
    }

    private fun remove() {
        h.removeCallbacksAndMessages(null)
        dot?.let { v -> runCatching { wm?.removeView(v) } }
        dot = null; lp = null
    }

    private fun look() {
        val v = dot ?: return
        val p = lp ?: return
        val s = px(Prefs.size(sp))
        v.alphaPct = Prefs.alpha(sp)
        v.invalidate()
        if (p.width != s) { p.width = s; p.height = s }
    }

    private fun screen(): Rect =
        runCatching { wm!!.currentWindowMetrics.bounds }.getOrNull()?.takeIf { it.width() > 0 }
            ?: run {
                val d = getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)
                val m = android.util.DisplayMetrics()
                @Suppress("DEPRECATION") d.getRealMetrics(m)
                Rect(0, 0, m.widthPixels, m.heightPixels)
            }

    /** Works out x/y from the saved spot (fractions of the screen) and the keyboard. */
    private fun spot(p: WindowManager.LayoutParams) {
        val r = screen()
        val s = p.width
        p.x = (Prefs.x(sp) * (r.width() - s)).roundToInt().coerceIn(0, maxOf(0, r.width() - s))
        var y = (Prefs.y(sp) * (r.height() - s)).roundToInt().coerceIn(0, maxOf(0, r.height() - s))
        if (Prefs.avoidIme(sp) && imeTop > 0 && y + s > imeTop - px(10)) y = maxOf(0, imeTop - s - px(10))
        p.y = y
    }

    private fun place() {
        val v = dot ?: return
        val p = lp ?: return
        if (v.dragging) return
        spot(p)
        runCatching { wm?.updateViewLayout(v, p) }
    }

    private fun moveTo(x: Int, y: Int) {
        val v = dot ?: return
        val p = lp ?: return
        val r = screen()
        p.x = x.coerceIn(0, maxOf(0, r.width() - p.width))
        p.y = y.coerceIn(0, maxOf(0, r.height() - p.height))
        runCatching { wm?.updateViewLayout(v, p) }
    }

    private fun saveSpot() {
        val p = lp ?: return
        val r = screen()
        val fx = p.x.toFloat() / maxOf(1, r.width() - p.width)
        val fy = p.y.toFloat() / maxOf(1, r.height() - p.height)
        // Dropped while pushed up by the keyboard: keep where it was dropped anyway.
        Prefs.putSpot(this, fx.coerceIn(0f, 1f), fy.coerceIn(0f, 1f))
    }

    // ---- keyboard ----

    private fun checkIme() {
        val top = runCatching {
            val w = windows.firstOrNull { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }
            if (w == null) -1 else Rect().also { w.getBoundsInScreen(it) }.let { if (it.height() > 0) it.top else -1 }
        }.getOrDefault(-1)
        if (top != imeTop) { imeTop = top; place() }
    }

    // ---- actions ----

    private fun perform(g: Gest) {
        val a = Prefs.act(sp, g)
        if (a == Act.NONE) return
        buzz(VibrationEffect.EFFECT_CLICK)
        if (a == Act.SHOT) {
            // Keep the dot out of the screenshot.
            val v = dot
            v?.visibility = View.INVISIBLE
            h.postDelayed({ performGlobalAction(a.global) }, 120)
            h.postDelayed({ v?.visibility = View.VISIBLE }, 1500)
            return
        }
        performGlobalAction(a.global)
    }

    private fun buzz(effect: Int) {
        if (!Prefs.haptic(sp)) return
        runCatching {
            getSystemService(VibratorManager::class.java).defaultVibrator.vibrate(VibrationEffect.createPredefined(effect))
        }
    }

    private fun px(dp: Int) = (dp * resources.displayMetrics.density).roundToInt()
}

/** The dot itself: tap, double tap, four swipes, and hold-then-drag to move it. */
class DotView(c: Context) : View(c) {
    var onGesture: (Gest) -> Unit = {}
    var hasDouble: () -> Boolean = { false }
    var onDragStart: () -> Unit = {}
    var onDragTo: (Int, Int) -> Unit = { _, _ -> }
    var onDragEnd: () -> Unit = {}
    var alphaPct = 55
    var dragging = false
        private set

    private val d = resources.displayMetrics.density
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private var down = false
    private var moved = false
    private var downX = 0f
    private var downY = 0f
    private var waitingSecond = false

    private val longPress = Runnable {
        dragging = true
        invalidate()
        onDragStart()
    }
    private val single = Runnable {
        waitingSecond = false
        onGesture(Gest.TAP)
    }

    override fun onDraw(canvas: Canvas) {
        val a = (alphaPct.coerceIn(10, 100) * 255 / 100)
        val pressedA = if (down || dragging) minOf(255, a + 70) else a
        fill.color = if (dragging) Color.argb(pressedA, 0xD9, 0x77, 0x57) else Color.argb(pressedA, 0x14, 0x14, 0x13)
        ring.color = Color.argb(minOf(255, pressedA + 40), 255, 255, 255)
        ring.strokeWidth = 1.5f * d
        val cx = width / 2f
        val cy = height / 2f
        val r = minOf(width, height) / 2f - ring.strokeWidth
        val rr = if (down && !dragging) r * 0.9f else r
        canvas.drawCircle(cx, cy, rr, fill)
        canvas.drawCircle(cx, cy, rr, ring)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                down = true; moved = false; dragging = false
                downX = e.rawX; downY = e.rawY
                invalidate()
                postDelayed(longPress, 420)
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = e.rawX - downX
                val dy = e.rawY - downY
                if (dragging) onDragTo(dx.toInt(), dy.toInt())
                else if (!moved && hypot(dx, dy) > 10 * d) { moved = true; removeCallbacks(longPress) }
            }
            MotionEvent.ACTION_UP -> {
                removeCallbacks(longPress)
                down = false
                if (dragging) {
                    dragging = false
                    onDragEnd()
                } else {
                    val dx = e.rawX - downX
                    val dy = e.rawY - downY
                    if (hypot(dx, dy) >= 22 * d) {
                        cancelPendingTap()
                        onGesture(
                            if (abs(dx) > abs(dy)) { if (dx > 0) Gest.RIGHT else Gest.LEFT }
                            else { if (dy > 0) Gest.DOWN else Gest.UP }
                        )
                    } else tap()
                }
                invalidate()
            }
            MotionEvent.ACTION_CANCEL -> {
                removeCallbacks(longPress)
                down = false
                if (dragging) { dragging = false; onDragEnd() }
                invalidate()
            }
        }
        return true
    }

    private fun tap() {
        if (!hasDouble()) { onGesture(Gest.TAP); return }
        if (waitingSecond) {
            cancelPendingTap()
            onGesture(Gest.DOUBLE)
        } else {
            waitingSecond = true
            postDelayed(single, 260)
        }
    }

    private fun cancelPendingTap() {
        if (waitingSecond) { removeCallbacks(single); waitingSecond = false }
    }
}
