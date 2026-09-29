package uk.ac.rawrail.watch

import android.app.*
import android.content.*
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import kotlinx.coroutines.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import uk.ac.rawrail.MainActivity
import uk.ac.rawrail.data.*
import uk.ac.rawrail.widget.RailWidgetUpdater
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** User-started monitoring. One foreground service owns any number of independent route watches. */
class RouteWatchService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var repository: LiveRepository
    private lateinit var preferences: AppPreferences
    private val activeRoutes = linkedMapOf<String, Route>()
    private var observer: Job? = null
    private var foregroundRouteKey: String? = null

    override fun onCreate() {
        super.onCreate()
        repository = LiveRepository.get(this)
        preferences = AppPreferences(this)
        val manager = getSystemService(NotificationManager::class.java)

        // v0.8: active watches remain persistent, but per-train change alerts are intentionally
        // disabled. Delete the old channels on upgrade so previously configured audible platform
        // alerts cannot fire again.
        manager.deleteNotificationChannel("changes")
        manager.deleteNotificationChannel("platform_changes_v1")
        // v0.17 moves the foreground board onto a fresh channel. Channel importance is immutable,
        // so a new id is required to escape the old LOW-priority presentation on upgraded installs.
        manager.deleteNotificationChannel("watch")
        manager.createNotificationChannel(
            NotificationChannel(WATCH_CHANNEL_ID, "Live departure board", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "At-a-glance live train board while monitoring is active"
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                enableVibration(false)
                setSound(null, null)
                setShowBadge(false)
            }
        )
        manager.createNotificationChannel(
            NotificationChannel("watch_status", "Watch status", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Important status messages when background route monitoring stops"
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PAUSE_ROUTE -> {
                intent.getStringExtra(EXTRA_ROUTE_KEY)?.let(::pauseRoute)
                if (activeRoutes.isEmpty()) stopSelf()
                return START_NOT_STICKY
            }
            ACTION_STOP_ALL -> {
                stopAllRoutes()
                return START_NOT_STICKY
            }
        }

        val route = runCatching {
            Json.decodeFromString<Route>(intent?.getStringExtra(EXTRA_ROUTE) ?: "")
        }.getOrNull()
        if (route == null) {
            if (activeRoutes.isEmpty()) stopSelf()
            return START_NOT_STICKY
        }
        addRoute(route)
        return START_NOT_STICKY
    }

    private fun addRoute(route: Route) {
        val isNew = route.key !in activeRoutes
        preferences.watchLeaseExpiry(route.key)?.takeIf { it <= System.currentTimeMillis() }?.let {
            preferences.clearWatchLease(route.key)
        }
        // Persist the route itself separately from its active state. Pausing therefore removes
        // background polling/notification but keeps the pinned route ready to resume later.
        preferences.saveWatchRoute(route)
        activeRoutes[route.key] = route
        repository.addWatch(route)

        val starting = watchNotification(
            route,
            listOf(if (isNew) "Starting live watch…" else "Refreshing live watch…"),
            "Connecting · ${repository.pollIntervalSeconds()}s polling"
        )
        val id = notificationId(route.key)
        if (foregroundRouteKey == null) {
            foregroundRouteKey = route.key
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(id, starting, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            } else {
                startForeground(id, starting)
            }
        } else {
            getSystemService(NotificationManager::class.java).notify(id, starting)
        }
        ensureObserver()
    }

    private fun pauseRoute(routeKey: String) {
        preferences.clearWatchLease(routeKey)
        val removed = activeRoutes.remove(routeKey) ?: return
        repository.removeWatch(routeKey)
        val manager = getSystemService(NotificationManager::class.java)
        val removedId = notificationId(removed.key)

        if (activeRoutes.isEmpty()) {
            manager.cancel(removedId)
            stopSelf()
            return
        }

        if (foregroundRouteKey == routeKey) {
            val replacement = activeRoutes.values.first()
            foregroundRouteKey = replacement.key
            val replacementNotification = currentWatchNotification(replacement)
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(
                    notificationId(replacement.key),
                    replacementNotification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                )
            } else {
                startForeground(notificationId(replacement.key), replacementNotification)
            }
        }
        manager.cancel(removedId)
        RailWidgetUpdater.updateAll(this)
    }

    private fun stopAllRoutes() {
        val manager = getSystemService(NotificationManager::class.java)
        activeRoutes.values.forEach { route ->
            manager.cancel(notificationId(route.key))
            preferences.clearWatchLease(route.key)
        }
        foregroundRouteKey = null
        activeRoutes.clear()
        repository.clearWatches()
        RailWidgetUpdater.updateAll(this)
        stopSelf()
    }

    private fun ensureObserver() {
        if (observer?.isActive == true) return
        observer = scope.launch {
            while (isActive) {
                val now = System.currentTimeMillis()
                val expired = activeRoutes.keys.filter { key ->
                    preferences.watchLeaseExpiry(key)?.let { it <= now } == true
                }
                expired.forEach(::pauseRoute)
                if (activeRoutes.isEmpty()) break

                val manager = getSystemService(NotificationManager::class.java)
                activeRoutes.values.toList().forEach { route ->
                    manager.notify(notificationId(route.key), currentWatchNotification(route))
                }
                // Keep the home-screen interaction layer visually aligned with the notifications.
                // This does not fetch; LiveRepository remains the sole polling owner.
                RailWidgetUpdater.updateAll(this@RouteWatchService)
                // Lease expiry/status can refresh independently; only LiveRepository performs network polling.
                delay(15_000)
            }
        }
    }

    private fun currentWatchNotification(route: Route): Notification {
        val snapshots = route.originCrsSet().map { repository.observe(it).value }
        val summary = buildWatchSummary(route, snapshots, repository.pollIntervalSeconds())
        val lastSuccess = snapshots.mapNotNull { it.lastSuccess }.maxOrNull()
        return watchNotification(route, summary.lines, notificationStatus(route, summary.status, lastSuccess))
    }

    private fun watchNotification(route: Route, lines: List<String>, summary: String): Notification {
        val safeLines = lines.take(3).ifEmpty { listOf("No upcoming matching trains") }
        val style = Notification.InboxStyle().setBigContentTitle(route.title)
        safeLines.forEach(style::addLine)
        style.setSummaryText(summary)

        return Notification.Builder(this, WATCH_CHANNEL_ID)
            .setSmallIcon(uk.ac.rawrail.R.drawable.ic_train_notification)
            // Each route is its own lock-screen card: the route itself is the headline and the
            // next three advertised services sit underneath it when Android gives us the expanded
            // template. The system still owns the final collapsed/expanded state.
            .setContentTitle(route.title)
            .setContentText(safeLines.first())
            .setSubText(summary)
            .setStyle(style)
            .setContentIntent(openPendingIntent(route))
            .setDeleteIntent(pausePendingIntent(route))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setCategory(Notification.CATEGORY_TRANSPORT)
            .setColor(0xFF123F36.toInt())
            .setShowWhen(false)
            .setLocalOnly(true)
            // Give every route its own explicit group key. This is a best-effort hint to Android
            // 16's automatic grouping logic that these are separate live boards, not one digest.
            .setGroup("route:${route.key}")
            .setSortKey(route.title)
            .addAction(Notification.Action.Builder(null, "Pause", pausePendingIntent(route)).build())
            .apply {
                if (Build.VERSION.SDK_INT >= 31) {
                    setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
                }
            }
            .build()
    }

    private fun notificationStatus(route: Route, baseStatus: String, lastSuccess: Long?): String {
        // Relative ages do not tick inside a notification, so a string such as "updated 12s ago"
        // quickly becomes false. Keep relative age for the live widget chronometer, but make the
        // lock-screen board truthful with an absolute railway-app refresh time.
        val core = baseStatus.split(" · ")
            .filterNot { it.startsWith("updated ", ignoreCase = true) }
            .joinToString(" · ")
        val updated = lastSuccess?.let {
            "Updated ${LOCKSCREEN_TIME.format(Instant.ofEpochMilli(it))}"
        } ?: "Not updated yet"
        val lease = leaseMinutesRemaining(route)
        return buildList {
            add(core)
            lease?.let { add("${it}m left") }
            add(updated)
        }.filter { it.isNotBlank() }.joinToString(" · ")
    }

    private fun leaseMinutesRemaining(route: Route): Int? {
        val expiry = preferences.watchLeaseExpiry(route.key) ?: return null
        val remaining = (expiry - System.currentTimeMillis()).coerceAtLeast(0L)
        return ((remaining + 59_999L) / 60_000L).toInt()
    }

    private fun statusNotification(route: Route, text: String): Notification {
        return Notification.Builder(this, "watch_status")
            .setSmallIcon(uk.ac.rawrail.R.drawable.ic_train_notification)
            .setContentTitle(route.title)
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setContentIntent(openPendingIntent(route))
            .setOngoing(false)
            .setOnlyAlertOnce(true)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setCategory(Notification.CATEGORY_STATUS)
            .build()
    }


    private fun openPendingIntent(route: Route): PendingIntent = PendingIntent.getActivity(
        this,
        notificationId(route.key),
        Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP).apply {
            putExtra("watchRoute", Json.encodeToString(route))
        },
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    private fun pausePendingIntent(route: Route): PendingIntent = PendingIntent.getService(
        this,
        notificationId(route.key) + 1,
        Intent(this, RouteWatchService::class.java)
            .setAction(ACTION_PAUSE_ROUTE)
            .putExtra(EXTRA_ROUTE_KEY, route.key),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    override fun onTaskRemoved(rootIntent: Intent?) {
        // The route definitions live in AppPreferences, so ending the app session must only
        // pause live monitoring. Removing Platform 8 9/4 from Recents therefore stops all
        // network polling and watch notifications while leaving every watch on the dashboard
        // ready for one-tap Resume next time the app is opened.
        stopAllRoutes()
        super.onTaskRemoved(rootIntent)
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        val manager = getSystemService(NotificationManager::class.java)
        activeRoutes.values.forEach { manager.cancel(notificationId(it.key)) }
        val route = activeRoutes.values.firstOrNull()
        if (route != null) {
            manager.notify(
                99,
                statusNotification(
                    route,
                    "Android’s background monitoring time limit was reached. Open Platform 8 9/4 to restart watching."
                )
            )
        }
        activeRoutes.values.forEach { preferences.clearWatchLease(it.key) }
        foregroundRouteKey = null
        activeRoutes.clear()
        repository.clearWatches()
        RailWidgetUpdater.updateAll(this)
        stopSelf()
    }

    override fun onDestroy() {
        repository.clearWatches()
        val manager = getSystemService(NotificationManager::class.java)
        activeRoutes.values.forEach { route ->
            manager.cancel(notificationId(route.key))
            preferences.clearWatchLease(route.key)
        }
        foregroundRouteKey = null
        activeRoutes.clear()
        RailWidgetUpdater.updateAll(this)
        scope.cancel()
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val ACTION_PAUSE_ROUTE = "uk.ac.rawrail.watch.PAUSE_ROUTE"
        private const val ACTION_STOP_ALL = "uk.ac.rawrail.watch.STOP_ALL"
        private const val EXTRA_ROUTE = "route"
        private const val EXTRA_ROUTE_KEY = "routeKey"
        private const val WATCH_CHANNEL_ID = "watch_live_v2"
        private val LOCKSCREEN_TIME: DateTimeFormatter = DateTimeFormatter
            .ofPattern("HH:mm")
            .withZone(ZoneId.of("Europe/London"))
        private fun notificationId(routeKey: String): Int = 1_000 + ((routeKey.hashCode() and 0x7fffffff) % 100_000)

        fun start(context: Context, route: Route) {
            context.startForegroundService(
                Intent(context, RouteWatchService::class.java).putExtra(EXTRA_ROUTE, Json.encodeToString(route))
            )
        }

        fun pause(context: Context, routeKey: String) {
            context.startService(
                Intent(context, RouteWatchService::class.java)
                    .setAction(ACTION_PAUSE_ROUTE)
                    .putExtra(EXTRA_ROUTE_KEY, routeKey)
            )
        }

        fun stopAll(context: Context) {
            context.startService(Intent(context, RouteWatchService::class.java).setAction(ACTION_STOP_ALL))
        }
    }
}
