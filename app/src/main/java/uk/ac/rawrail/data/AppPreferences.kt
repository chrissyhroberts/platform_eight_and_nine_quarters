package uk.ac.rawrail.data

import android.content.Context
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Small, non-secret app preferences. API credentials remain in SecureCredentialStore. */
class AppPreferences(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("platform_934_preferences", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }

    fun pollIntervalSeconds(): Int =
        prefs.getInt(KEY_POLL_SECONDS, DEFAULT_POLL_SECONDS).takeIf { it in POLL_OPTIONS }
            ?: DEFAULT_POLL_SECONDS

    fun setPollIntervalSeconds(seconds: Int) {
        require(seconds in POLL_OPTIONS) { "Polling interval must be one of $POLL_OPTIONS" }
        prefs.edit().putInt(KEY_POLL_SECONDS, seconds).apply()
    }

    fun favouriteRoutes(): List<Route> = prefs.getStringSet(KEY_FAVOURITES, emptySet()).orEmpty()
        .mapNotNull { encoded -> runCatching { json.decodeFromString<Route>(encoded) }.getOrNull() }
        .distinctBy { it.key }
        .sortedBy { it.title }

    fun isFavourite(route: Route): Boolean = favouriteRoutes().any { it.key == route.key }

    fun toggleFavourite(route: Route): List<Route> {
        val routes = favouriteRoutes().associateBy { it.key }.toMutableMap()
        if (route.key in routes) routes.remove(route.key) else routes[route.key] = route
        prefs.edit().putStringSet(KEY_FAVOURITES, routes.values.map { json.encodeToString(it) }.toSet()).apply()
        return routes.values.sortedBy { it.title }
    }

    fun removeFavourite(routeKey: String): List<Route> {
        val routes = favouriteRoutes().associateBy { it.key }.toMutableMap()
        routes.remove(routeKey)
        prefs.edit().putStringSet(KEY_FAVOURITES, routes.values.map { json.encodeToString(it) }.toSet()).apply()
        return routes.values.sortedBy { it.title }
    }

    /** One-time, idempotent migration from the former separate Watching list. */
    fun migrateSavedWatchesToFavourites(): List<Route> {
        val merged = (favouriteRoutes() + watchRoutes()).distinctBy { it.key }.sortedBy { it.title }
        prefs.edit().putStringSet(KEY_FAVOURITES, merged.map { json.encodeToString(it) }.toSet()).apply()
        return merged
    }

    /** Saved watch routes survive app sessions; active monitoring is deliberately session-scoped. */
    fun watchRoutes(): List<Route> = prefs.getStringSet(KEY_WATCH_ROUTES, emptySet()).orEmpty()
        .mapNotNull { encoded -> runCatching { json.decodeFromString<Route>(encoded) }.getOrNull() }
        .distinctBy { it.key }
        .sortedBy { it.title }

    fun saveWatchRoute(route: Route): List<Route> {
        val routes = watchRoutes().associateBy { it.key }.toMutableMap()
        routes[route.key] = route
        prefs.edit().putStringSet(KEY_WATCH_ROUTES, routes.values.map { json.encodeToString(it) }.toSet()).apply()
        return routes.values.sortedBy { it.title }
    }

    fun removeWatchRoute(routeKey: String): List<Route> {
        val routes = watchRoutes().associateBy { it.key }.toMutableMap()
        routes.remove(routeKey)
        prefs.edit().putStringSet(KEY_WATCH_ROUTES, routes.values.map { json.encodeToString(it) }.toSet()).apply()
        return routes.values.sortedBy { it.title }
    }

    fun isSavedWatch(route: Route): Boolean = watchRoutes().any { it.key == route.key }

    /** Optional expiry used by the home-screen widget's deliberately short live-monitoring lease. */
    fun watchLeaseExpiry(routeKey: String): Long? =
        prefs.getLong(leaseKey(routeKey), 0L).takeIf { it > 0L }

    fun startWatchLease(routeKey: String, durationMs: Long = DEFAULT_WATCH_LEASE_MS): Long {
        require(durationMs > 0L) { "Watch lease duration must be positive" }
        val expiry = System.currentTimeMillis() + durationMs
        prefs.edit().putLong(leaseKey(routeKey), expiry).apply()
        return expiry
    }

    fun clearWatchLease(routeKey: String) {
        prefs.edit().remove(leaseKey(routeKey)).apply()
    }

    private fun leaseKey(routeKey: String) = "$KEY_WATCH_LEASE_PREFIX$routeKey"

    /** Emits whenever a saved watch is added/removed, including changes initiated by a widget. */
    fun watchRoutesFlow(): Flow<List<Route>> = callbackFlow {
        trySend(watchRoutes())
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == KEY_WATCH_ROUTES) trySend(watchRoutes())
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        awaitClose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }.distinctUntilChanged()

    companion object {
        const val DEFAULT_POLL_SECONDS = 30
        val POLL_OPTIONS = setOf(15, 30, 60)
        private const val KEY_POLL_SECONDS = "poll_interval_seconds"
        private const val KEY_FAVOURITES = "favourite_routes"
        private const val KEY_WATCH_ROUTES = "watch_routes"
        private const val KEY_WATCH_LEASE_PREFIX = "watch_lease_expiry_"
        const val DEFAULT_WATCH_LEASE_MS = 30L * 60L * 1000L
    }
}
