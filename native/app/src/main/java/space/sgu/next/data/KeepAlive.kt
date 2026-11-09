package space.sgu.next.data

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/**
 * Background work (original settings.background + acquireJobPresence): while a 작업실 turn or an image batch runs,
 * a foreground service with a progress notification keeps Android from stopping the app; optional wake lock;
 * a notification when everything finished while the app was in the background.
 */
object KeepAlive {
    const val CHANNEL = "sgu_work"
    const val CHANNEL_DONE = "sgu_done"
    private const val NOTE_ID = 7101
    private const val DONE_ID = 7102
    var appVisible = true
    private lateinit var app: Context
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var running = false

    data class Status(val busy: Boolean, val title: String, val text: String, val done: Int, val total: Int)

    private fun status(): Status {
        val ws = Workshop.jobs.values.filter { it.active }
        val gen = GenRun.progress.entries.toList()
        val comp = GenRun.compressing.entries.toList()
        if (ws.isEmpty() && gen.isEmpty() && comp.isEmpty()) return Status(false, "", "", 0, 0)
        val name = { id: String -> CharacterStore.items.firstOrNull { it.id == id }?.name ?: "캐릭터" }
        val parts = mutableListOf<String>()
        ws.forEach { parts.add("${name(it.charId)} · ${Workshop.PHASES[it.phase] ?: it.phase}") }
        var done = 0; var total = 0
        gen.forEach { (cid, p) -> parts.add("${name(cid)} · 이미지 ${p.done}/${p.total} · ETA ${GenRun.eta(p)}"); done += p.done; total += p.total }
        comp.forEach { (cid, p) -> parts.add("${name(cid)} · 압축 ${p.first}/${p.second}") }
        val title = if (gen.isNotEmpty()) "에셋 작업(ETA ${GenRun.eta(gen.first().value)})" else "SGU 작업 중"
        return Status(true, title, parts.joinToString("\n"), done, total)
    }

    private var watching = false

    fun watch(ctx: Context) {
        if (watching) return
        watching = true
        app = ctx.applicationContext
        channels()
        scope.launch {
            snapshotFlow { status() }.distinctUntilChanged().collect { s ->
                val bg = SettingsStore.root.o("background")
                if (s.busy && bg.b("foregroundService", true)) {
                    WorkService.status = s
                    if (!running) { running = true; runCatching { app.startForegroundService(Intent(app, WorkService::class.java)) } }
                    else WorkService.refresh(app)
                } else if (running && (!s.busy || !bg.b("foregroundService", true))) {
                    running = false
                    app.stopService(Intent(app, WorkService::class.java))
                }
                if (WorkService.wasBusy && !s.busy && !appVisible && bg.b("notifyOnComplete", true)) notifyDone()
                WorkService.wasBusy = s.busy
            }
        }
    }

    private fun channels() {
        val nm = app.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "작업 진행", NotificationManager.IMPORTANCE_LOW))
        nm.createNotificationChannel(NotificationChannel(CHANNEL_DONE, "작업 완료", NotificationManager.IMPORTANCE_DEFAULT))
    }

    fun openApp(ctx: Context): PendingIntent = PendingIntent.getActivity(ctx, 0,
        ctx.packageManager.getLaunchIntentForPackage(ctx.packageName)?.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP) ?: Intent(),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    private fun notifyDone() {
        runCatching {
            val n = Notification.Builder(app, CHANNEL_DONE).setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentTitle("SGU 작업이 끝났어요").setContentText("눌러서 결과를 확인하세요").setContentIntent(openApp(app)).setAutoCancel(true).build()
            app.getSystemService(NotificationManager::class.java).notify(DONE_ID, n)
        }
    }

    fun build(ctx: Context, s: Status): Notification = Notification.Builder(ctx, CHANNEL)
        .setSmallIcon(android.R.drawable.stat_notify_sync).setContentTitle(s.title.ifBlank { "SGU 작업 중" })
        .setContentText(s.text.lineSequence().firstOrNull() ?: "").setStyle(Notification.BigTextStyle().bigText(s.text))
        .setOngoing(true).setOnlyAlertOnce(true).setContentIntent(openApp(ctx))
        .apply { if (s.total > 0) setProgress(s.total, s.done, false) }
        .build()

    const val ID = NOTE_ID
}

class WorkService : Service() {
    companion object {
        @Volatile var status = KeepAlive.Status(true, "SGU 작업 중", "", 0, 0)
        @Volatile var wasBusy = false
        fun refresh(ctx: Context) {
            runCatching { ctx.getSystemService(NotificationManager::class.java).notify(KeepAlive.ID, KeepAlive.build(ctx, status)) }
        }
    }
    private var lock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val n = KeepAlive.build(this, status)
        if (Build.VERSION.SDK_INT >= 34) startForeground(KeepAlive.ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else startForeground(KeepAlive.ID, n)
        if (SettingsStore.root.o("background").b("keepAwake") && lock == null) {
            lock = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "sgu:work").apply { setReferenceCounted(false); acquire(6 * 60 * 60 * 1000L) }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        lock?.let { if (it.isHeld) it.release() }; lock = null
        super.onDestroy()
    }
}
