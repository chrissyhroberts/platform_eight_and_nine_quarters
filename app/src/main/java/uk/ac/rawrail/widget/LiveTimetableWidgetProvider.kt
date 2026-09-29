package uk.ac.rawrail.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.view.View
import android.widget.RemoteViews
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import uk.ac.rawrail.R
import kotlin.math.ceil

/** Compact pinned departure board: the next three matching services, using the watch-summary format. */
class LiveTimetableWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, appWidgetIds: IntArray) {
        val pending = goAsync()
        providerScope.launch {
            try { updateWidgets(context.applicationContext, appWidgetIds) } finally { pending.finish() }
        }
    }

    override fun onAppWidgetOptionsChanged(
        context: Context,
        manager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: android.os.Bundle,
    ) {
        val pending = goAsync()
        providerScope.launch {
            try { updateWidgets(context.applicationContext, intArrayOf(appWidgetId)) } finally { pending.finish() }
        }
    }

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        appWidgetIds.forEach { WidgetRouteStore.delete(context, it) }
        super.onDeleted(context, appWidgetIds)
    }

    companion object {
        private val providerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        internal suspend fun updateWidgets(context: Context, ids: IntArray) {
            val manager = AppWidgetManager.getInstance(context)
            ids.forEach { id ->
                val route = WidgetRouteStore.load(context, id)
                if (route == null) {
                    manager.updateAppWidget(id, unconfiguredViews(context, id))
                    return@forEach
                }
                val data = loadRailWidgetData(context, route)
                manager.updateAppWidget(id, views(context, id, data))
            }
        }

        private fun views(context: Context, id: Int, data: RailWidgetData): RemoteViews {
            val views = RemoteViews(context.packageName, R.layout.live_timetable_widget)
            views.setTextViewText(R.id.widget_title, data.route.title)

            val statusParts = data.summary.status.split(" · ")
                .filterNot { it.startsWith("updated ") }
            val railState = statusParts.firstOrNull().orEmpty().ifBlank { "LIVE" }
            val now = System.currentTimeMillis()
            val leaseMinutes = data.leaseExpiry
                ?.takeIf { data.watchActive && it > now }
                ?.let { ceil((it - now) / 60_000.0).toInt().coerceAtLeast(1) }

            val headerState = if (data.watchActive) {
                if (leaseMinutes != null) "$railState · ${leaseMinutes}m" else railState
            } else {
                "PAUSED"
            }
            views.setTextViewText(R.id.widget_state, headerState)

            setSummaryLines(views, data.summary.lines)

            val detailParts = statusParts.drop(1)
            val footerParts = buildList {
                if (!data.watchActive) add("Cached")
                addAll(detailParts)
                if (detailParts.none { it == "${data.cadenceSeconds}s" }) add("${data.cadenceSeconds}s")
            }
            views.setTextViewText(R.id.widget_status, footerParts.joinToString(" · "))

            if (data.lastSuccess != null) {
                val ageMs = (now - data.lastSuccess).coerceAtLeast(0L)
                val base = SystemClock.elapsedRealtime() - ageMs
                views.setViewVisibility(R.id.widget_age, View.VISIBLE)
                // Chronometer is rendered/ticked by Android itself: no background worker and no API traffic.
                views.setChronometer(R.id.widget_age, base, "updated %s ago", true)
            } else {
                views.setViewVisibility(R.id.widget_age, View.GONE)
            }

            views.setImageViewResource(
                R.id.widget_toggle,
                if (data.watchActive) R.drawable.widget_toggle_on else R.drawable.widget_toggle_off
            )
            views.setContentDescription(
                R.id.widget_toggle,
                if (data.watchActive) "Pause live updates" else "Start 30 minute live monitor"
            )

            val toggle = toggleWatchPendingIntent(context, data.route, id)
            views.setOnClickPendingIntent(R.id.widget_toggle, toggle)
            // The whole departure-board surface is the fast interaction: tap anywhere to toggle.
            // Opening the full app is deliberately reserved for the small Open affordance.
            views.setOnClickPendingIntent(R.id.widget_root, toggle)
            views.setOnClickPendingIntent(R.id.widget_open, openRoutePendingIntent(context, data.route, id, 12))
            return views
        }

        private fun unconfiguredViews(context: Context, id: Int): RemoteViews {
            val views = RemoteViews(context.packageName, R.layout.live_timetable_widget)
            views.setTextViewText(R.id.widget_title, "Platform 8 9/4")
            views.setTextViewText(R.id.widget_state, "TIMETABLE")
            views.setTextViewText(R.id.widget_line_1, "Choose a route to pin")
            views.setViewVisibility(R.id.widget_line_2, View.GONE)
            views.setViewVisibility(R.id.widget_line_3, View.GONE)
            views.setTextViewText(R.id.widget_status, "Tap to configure")
            views.setViewVisibility(R.id.widget_age, View.GONE)
            views.setViewVisibility(R.id.widget_toggle, View.GONE)
            views.setTextViewText(R.id.widget_open, "Choose")
            val configure = Intent(context, LiveTimetableWidgetConfigureActivity::class.java)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
            val pending = android.app.PendingIntent.getActivity(
                context,
                id + 17,
                configure,
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.widget_open, pending)
            views.setOnClickPendingIntent(R.id.widget_root, pending)
            return views
        }

        private fun setSummaryLines(views: RemoteViews, lines: List<String>) {
            val ids = intArrayOf(R.id.widget_line_1, R.id.widget_line_2, R.id.widget_line_3)
            ids.forEachIndexed { index, viewId ->
                val line = lines.getOrNull(index)
                views.setViewVisibility(viewId, if (line == null) View.GONE else View.VISIBLE)
                if (line != null) views.setTextViewText(viewId, line)
            }
        }
    }
}
