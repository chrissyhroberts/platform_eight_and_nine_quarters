package uk.ac.rawrail.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import uk.ac.rawrail.MainActivity
import uk.ac.rawrail.data.AppPreferences
import uk.ac.rawrail.data.LiveRepository
import uk.ac.rawrail.data.LiveSnapshot
import uk.ac.rawrail.data.ObservationStore
import uk.ac.rawrail.data.Route
import uk.ac.rawrail.watch.WatchSummary
import uk.ac.rawrail.watch.buildWatchSummary

internal object WidgetRouteStore {
    private const val PREFS = "platform_934_widget_routes"
    private val json = Json { ignoreUnknownKeys = true }

    fun save(context: Context, appWidgetId: Int, route: Route) {
        context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(key(appWidgetId), json.encodeToString(route))
            .apply()
    }

    fun load(context: Context, appWidgetId: Int): Route? {
        val encoded = context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(key(appWidgetId), null)
            ?: return null
        return runCatching { json.decodeFromString<Route>(encoded) }.getOrNull()
    }

    fun delete(context: Context, appWidgetId: Int) {
        context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .remove(key(appWidgetId))
            .apply()
    }

    private fun key(appWidgetId: Int) = "timetable_$appWidgetId"
}

internal data class RailWidgetData(
    val route: Route,
    val summary: WatchSummary,
    val watchActive: Boolean,
    val lastSuccess: Long?,
    val leaseExpiry: Long?,
    val cadenceSeconds: Int,
)

/**
 * Loads the exact same compact train summary used by the lock-screen watch notification.
 * Active watches use the in-memory repository state. Paused widgets fall back to the durable
 * SQLite observation cache, so a widget remains useful without starting any hidden polling.
 */
internal suspend fun loadRailWidgetData(context: Context, route: Route): RailWidgetData {
    val app = context.applicationContext
    val repository = LiveRepository.get(app)
    val preferences = AppPreferences(app)
    val leaseExpiry = preferences.watchLeaseExpiry(route.key)
    val now = System.currentTimeMillis()
    val (repositoryActive, live) = withContext(Dispatchers.Main.immediate) {
        val watching = route.key in repository.watches.value
        watching to if (watching) {
            route.originCrsSet().map { repository.observe(it).value }
        } else {
            emptyList()
        }
    }
    val active = repositoryActive && (leaseExpiry == null || leaseExpiry > now)

    val snapshots = if (live.any { it.board != null }) {
        live
    } else {
        runCatching { loadCachedSnapshots(app, route) }.getOrElse {
            route.originCrsSet().map { crs -> LiveSnapshot(crs = crs, offline = true) }
        }
    }
    val cadence = repository.pollIntervalSeconds()
    val summary = buildWatchSummary(
        route = route,
        snapshots = snapshots,
        cadenceSeconds = cadence,
        now = now,
    )
    val lastSuccess = snapshots.mapNotNull { it.lastSuccess }.maxOrNull()
    return RailWidgetData(
        route = route,
        summary = summary,
        watchActive = active,
        lastSuccess = lastSuccess,
        leaseExpiry = leaseExpiry?.takeIf { it > now },
        cadenceSeconds = cadence,
    )
}

private fun loadCachedSnapshots(context: Context, route: Route): List<LiveSnapshot> {
    val store = ObservationStore(context)
    return try {
        route.originCrsSet().map { crs ->
            val latest = store.latestBoard(crs)
            if (latest == null) {
                LiveSnapshot(crs = crs, offline = true)
            } else {
                val (board, fetched) = latest
                val matching = board.services.filter(route::matches)
                LiveSnapshot(
                    crs = crs,
                    board = board,
                    lastSuccess = fetched,
                    timelines = matching.associate { service ->
                        service.identity to store.platformTimeline(crs, service)
                    }
                )
            }
        }
    } finally {
        store.close()
    }
}

internal fun openRoutePendingIntent(
    context: Context,
    route: Route,
    appWidgetId: Int,
    salt: Int = 0,
): PendingIntent {
    val intent = Intent(context, MainActivity::class.java)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        .putExtra("watchRoute", Json.encodeToString(route))
    return PendingIntent.getActivity(
        context,
        pendingRequestCode(appWidgetId, route.key, salt),
        intent,
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )
}

internal fun toggleWatchPendingIntent(
    context: Context,
    route: Route,
    appWidgetId: Int,
): PendingIntent {
    val intent = Intent(context, RailWidgetActionReceiver::class.java)
        .setAction(RailWidgetActionReceiver.ACTION_TOGGLE_WATCH)
        .putExtra(RailWidgetActionReceiver.EXTRA_ROUTE, Json.encodeToString(route))
    return PendingIntent.getBroadcast(
        context,
        pendingRequestCode(appWidgetId, route.key, 7),
        intent,
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )
}

private fun pendingRequestCode(appWidgetId: Int, routeKey: String, salt: Int): Int {
    val routePart = routeKey.hashCode() and 0x7fff
    return ((appWidgetId and 0xffff) shl 15) xor routePart xor salt
}

/**
 * Widgets are deliberately event-driven. There is no second polling loop here: foreground route
 * polls and the watch service call this updater after their existing data refreshes. The small
 * debounce coalesces London multi-origin updates and notification/UI refreshes into one render.
 */
object RailWidgetUpdater {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Any()
    private var updateJob: Job? = null

    fun updateAll(context: Context) {
        val app = context.applicationContext
        synchronized(lock) {
            updateJob?.cancel()
            updateJob = scope.launch {
                delay(180)
                val manager = AppWidgetManager.getInstance(app)
                val timetableIds = manager.getAppWidgetIds(ComponentName(app, LiveTimetableWidgetProvider::class.java))
                if (timetableIds.isNotEmpty()) LiveTimetableWidgetProvider.updateWidgets(app, timetableIds)
            }
        }
    }

    fun updateTimetables(context: Context, ids: IntArray) {
        val app = context.applicationContext
        scope.launch { LiveTimetableWidgetProvider.updateWidgets(app, ids) }
    }
}
