package space.nami.finder

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews

/** Home-screen search bar. Tapping anywhere opens the search window. */
class FinderWidget : AppWidgetProvider() {
    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) {
        for (id in ids) mgr.updateAppWidget(id, views(ctx))
    }

    private fun views(ctx: Context): RemoteViews {
        val open = Intent(ctx, SearchActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        val pi = PendingIntent.getActivity(ctx, 0, open,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return RemoteViews(ctx.packageName, R.layout.widget).apply {
            setOnClickPendingIntent(R.id.bar, pi)
            setOnClickPendingIntent(android.R.id.background, pi)
        }
    }
}
