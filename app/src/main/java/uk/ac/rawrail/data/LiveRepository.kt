package uk.ac.rawrail.data

import android.content.Context
import android.os.SystemClock
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import uk.ac.rawrail.model.*
import uk.ac.rawrail.security.*
import uk.ac.rawrail.widget.RailWidgetUpdater
import java.io.IOException

data class LiveSnapshot(
    val crs: String? = null,
    val board: StationBoard? = null,
    val lastSuccess: Long? = null,
    val nextPollElapsed: Long = 0,
    val loading: Boolean = false,
    val failed: Boolean = false,
    val offline: Boolean = false,
    val error: String? = null,
    val comparatorStatus: String = "Public comparator not configured",
    val staffStatus: String? = null,
    val publicFetched: Long? = null,
    val publicGenerated: String? = null,
    val history: List<RailEvent> = emptyList(),
    val timelines: Map<String, List<RailEvent>> = emptyMap()
)

class LiveRepository(
    context: Context,
    private val staffFetch: suspend (String, DarwinConnection) -> StationBoard = DarwinStaffClient()::departures,
    private val publicFetch: suspend (String, DarwinConnection) -> StationBoard = reusablePublicFeed(),
    private val store: ObservationStore = ObservationStore(context)
) {
    private val app: android.app.Application = context.applicationContext as android.app.Application
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val credentials = SecureCredentialStore(app)
    private val preferences = AppPreferences(app)
    private val sessions = mutableMapOf<String, Session>()
    private var foreground: Set<String> = emptySet()

    /** All route watches currently owned by the foreground watch service, keyed by route key. */
    val watches = MutableStateFlow<Map<String, Route>>(emptyMap())
    val alerts = MutableSharedFlow<Pair<Route, List<RailEvent>>>(extraBufferCapacity = 32)

    private inner class Session(val crs: String) {
        val state = MutableStateFlow(LiveSnapshot(crs = crs))
        val wake = Channel<Unit>(Channel.CONFLATED)
        var active = false
        init { scope.launch { runSession(this@Session) } }
    }

    fun observe(crs: String): StateFlow<LiveSnapshot> = session(crs).state.asStateFlow()
    private fun session(crs: String) = sessions.getOrPut(crs) { Session(crs) }

    fun foreground(crs: String?) { foreground = crs?.let { setOf(it) }.orEmpty(); updateActive() }
    fun foregroundAll(crs: Set<String>) { foreground = crs; updateActive() }

    fun addWatch(route: Route) {
        if (watches.value[route.key] == route) return
        watches.value = watches.value + (route.key to route)
        updateActive()
        RailWidgetUpdater.updateAll(app)
    }

    fun removeWatch(routeKey: String) {
        if (routeKey !in watches.value) return
        watches.value = watches.value - routeKey
        updateActive()
        RailWidgetUpdater.updateAll(app)
    }

    fun clearWatches() {
        if (watches.value.isEmpty()) return
        watches.value = emptyMap()
        updateActive()
        RailWidgetUpdater.updateAll(app)
    }

    fun isWatched(route: Route): Boolean = route.key in watches.value
    fun refresh(crs: String) { session(crs).wake.trySend(Unit) }
    fun pollIntervalSeconds(): Int = preferences.pollIntervalSeconds()

    fun setPollIntervalSeconds(seconds: Int) {
        preferences.setPollIntervalSeconds(seconds)
        val now = SystemClock.elapsedRealtime()
        sessions.values.filter { it.active }.forEach { s ->
            s.state.value = s.state.value.copy(nextPollElapsed = now + seconds * 1_000L)
            // Wake the sequential session loop. If a request is in progress this remains conflated,
            // so changing cadence cannot create an overlapping fetch.
            s.wake.trySend(Unit)
        }
        RailWidgetUpdater.updateAll(app)
    }

    private fun updateActive() {
        val watched = watches.value.values.flatMapTo(linkedSetOf()) { it.originCrsSet() }
        (foreground + watched).forEach { session(it) }
        sessions.values.forEach { s ->
            val active = s.crs in foreground || s.crs in watched
            val started = active && !s.active
            s.active = active
            if (started) s.wake.trySend(Unit)
        }
    }

    private suspend fun runSession(s: Session) {
        runCatching { withContext(Dispatchers.IO) { store.latestBoard(s.crs) } }.getOrNull()?.let { (board, at) ->
            s.state.value = LiveSnapshot(
                crs = s.crs,
                board = board,
                lastSuccess = at,
                failed = true,
                history = withContext(Dispatchers.IO) { store.history(s.crs) },
                timelines = withContext(Dispatchers.IO) { board.services.associate { it.identity to store.platformTimeline(s.crs, it) } }
            )
        }
        while (currentCoroutineContext().isActive) {
            if (!s.active) { s.wake.receive(); if (!s.active) continue }
            s.wake.tryReceive()
            val start = SystemClock.elapsedRealtime()
            val intervalMs = preferences.pollIntervalSeconds() * 1_000L
            s.state.value = s.state.value.copy(loading = true, nextPollElapsed = start + intervalMs)
            try {
                val connection = credentials.load()
                val board = staffFetch(s.crs, connection)
                val at = System.currentTimeMillis()
                val emitted = withContext(Dispatchers.IO) { store.record(board, "staff", at) }.toMutableList()
                if (board.areServicesAvailable == false) throw RailwayServicesUnavailableException(s.crs)
                s.state.value = s.state.value.copy(
                    board = board.copy(services = stableServices(s.state.value.board?.services.orEmpty(), board.services)),
                    lastSuccess = at,
                    loading = false,
                    failed = false,
                    offline = false,
                    error = null
                )
                if (connection.apiKey.isNotBlank()) {
                    try {
                        val compared = publicFetch(s.crs, connection)
                        val publicAt = System.currentTimeMillis()
                        withContext(Dispatchers.IO) {
                            store.record(compared, "public", publicAt)
                            emitted += store.compare(board, compared, publicAt)
                        }
                        s.state.value = s.state.value.copy(
                            comparatorStatus = "Public comparator",
                            publicFetched = publicAt,
                            publicGenerated = compared.generatedAt
                        )
                    } catch (e: CancellationException) { throw e }
                    catch (_: Exception) {
                        s.state.value = s.state.value.copy(comparatorStatus = "Public comparator unavailable · retrying automatically")
                    }
                }
                val history = withContext(Dispatchers.IO) { store.history(s.crs) }
                val timelines = withContext(Dispatchers.IO) { board.services.associate { it.identity to store.platformTimeline(s.crs, it) } }
                s.state.value = s.state.value.copy(history = history, timelines = timelines)
                // Home-screen widgets render from this same repository/store state. This is an
                // event-driven UI update only; widgets never create a second railway polling loop.
                RailWidgetUpdater.updateAll(app)

                // One station session can serve several watched destinations. Partition the same
                // emitted state changes into each route rather than creating duplicate pollers.
                watches.value.values
                    .filter { s.crs in it.originCrsSet() }
                    .forEach { route ->
                        val ids = board.services.filter(route::matches).map { it.identity }.toSet()
                        val firstSeenIds = emitted.filter { it.type == RailEventType.FIRST_SEEN }.map { it.serviceId }.toSet()
                        val selected = emitted.filter { event ->
                            event.serviceId in ids &&
                                event.type !in setOf(RailEventType.FIRST_SEEN, RailEventType.EXPECTED_TIME_CHANGED, RailEventType.PLATFORM_HIDDEN) &&
                                !(event.type == RailEventType.PLATFORM_SET && event.serviceId in firstSeenIds)
                        }
                        if (selected.isNotEmpty()) alerts.emit(route to selected)
                    }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                val message = when (e) {
                    is IOException -> "Connection unavailable. Retaining the last observation and retrying automatically."
                    is RailwayServicesUnavailableException -> "Railway reports services unavailable at ${e.crs}. Retaining the last observation and retrying automatically."
                    else -> "Staff feed or local storage unavailable${e.message?.takeIf { it.isNotBlank() }?.let { ": $it" }.orEmpty()}. Retaining the last observation and retrying automatically."
                }
                s.state.value = s.state.value.copy(loading = false, failed = true, offline = e is IOException, error = message)
                RailWidgetUpdater.updateAll(app)
            }
            val remaining = (start + intervalMs - SystemClock.elapsedRealtime()).coerceAtLeast(0)
            // Conflated manual requests cannot overlap the current request.
            if (s.active && remaining > 0) withTimeoutOrNull(remaining) { s.wake.receive() }
        }
    }

    fun close() { scope.cancel(); store.close() }

    companion object {
        @Volatile private var instance: LiveRepository? = null
        fun get(context: Context): LiveRepository = instance ?: synchronized(this) {
            instance ?: LiveRepository(context).also { instance = it }
        }
    }
}

private class RailwayServicesUnavailableException(val crs: String) : IllegalStateException("Railway services unavailable at $crs")

private fun reusablePublicFeed(): suspend (String, DarwinConnection) -> StationBoard {
    val client = DarwinPublicClient()
    return { crs, connection -> client.departures(crs, connection) }
}
