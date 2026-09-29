package uk.ac.rawrail.ui

import android.app.Application
import android.content.Intent
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import uk.ac.rawrail.data.*
import uk.ac.rawrail.model.*
import uk.ac.rawrail.security.*
import uk.ac.rawrail.watch.RouteWatchService
import uk.ac.rawrail.watch.WatchSummary
import uk.ac.rawrail.watch.buildWatchSummary

data class MainUiState(
    val searchQuery: String = "",
    val stationResults: List<StationRef> = emptyList(),
    val selectedStation: StationRef = StationRef("RYS", "Royston"),
    val destinationQuery: String = "London (Any)",
    val destination: StationRef? = Route.londonAny,
    val destinationResults: List<StationRef> = emptyList(),
    val serviceFilter: String = "",
    val loading: Boolean = false,
    val board: StationBoard? = null,
    val history: List<RailEvent> = emptyList(),
    val timelines: Map<String, List<RailEvent>> = emptyMap(),
    val error: String? = null,
    val showSettings: Boolean = false,
    val configured: Boolean = false,
    val stationCount: Int = 0,
    val syncingStations: Boolean = false,
    val stationSyncMessage: String? = null,
    val lastSuccess: Long? = null,
    val nextPollElapsed: Long = 0,
    val failed: Boolean = false,
    val offline: Boolean = false,
    val comparatorStatus: String = "",
    val staffStatus: String? = null,
    val publicFetched: Long? = null,
    val publicGenerated: String? = null,
    val watching: Boolean = false,
    val routeOpen: Boolean = false,
    val pollIntervalSeconds: Int = AppPreferences.DEFAULT_POLL_SECONDS,
    val favourites: List<Route> = emptyList(),
    /** Saved watch routes, including paused watches retained for rapid resume. */
    val watchRoutes: List<Route> = emptyList(),
    /** Route keys currently polling in the background service. */
    val activeWatchKeys: Set<String> = emptySet(),
    val watchSummaries: Map<String, WatchSummary> = emptyMap(),
    val watchSaved: Boolean = false
) {
    val route: Route get() = Route(selectedStation, destination)
    val routeIsFavourite: Boolean get() = favourites.any { it.key == route.key }
}

class MainViewModel @JvmOverloads constructor(application: Application, private val repository: LiveRepository = LiveRepository.get(application)) : AndroidViewModel(application) {
    private val credentials = SecureCredentialStore(application)
    private val stations = StationDirectory(application)
    private val preferences = AppPreferences(application)
    private var collectJob: Job? = null
    private var visible = false
    private val savedWatches = MutableStateFlow(preferences.watchRoutes())
    private val _state = MutableStateFlow(
        MainUiState(
            configured = credentials.isConfigured(),
            stationCount = stations.count(),
            pollIntervalSeconds = preferences.pollIntervalSeconds(),
            favourites = preferences.favouriteRoutes(),
            watchRoutes = savedWatches.value,
            watchSaved = preferences.isSavedWatch(Route(StationRef("RYS", "Royston"), Route.londonAny))
        )
    )
    val state = _state.asStateFlow()
    init {
        viewModelScope.launch {
            preferences.watchRoutesFlow().collect { routes ->
                if (routes != savedWatches.value) savedWatches.value = routes
            }
        }
        viewModelScope.launch {
            combine(repository.watches, savedWatches) { active, saved -> active to saved }
                .flatMapLatest { (active, saved) ->
                    // Service-started routes are also saved by the service, but include them here
                    // immediately so the dashboard never waits on SharedPreferences propagation.
                    val routes = (saved + active.values).distinctBy { it.key }.sortedBy { it.title }
                    val activeKeys = active.keys
                    val crs = routes.flatMap { it.originCrsSet() }.distinct()
                    if (crs.isEmpty()) {
                        flowOf(Triple(routes, activeKeys, emptyMap<String, WatchSummary>()))
                    } else {
                        val snapshotsFlow = combine(crs.map(repository::observe)) { it.toList() }
                        combine(snapshotsFlow, watchRefreshTicker()) { snapshots, now ->
                            val byCrs = snapshots.associateBy { it.crs }
                            val cadence = repository.pollIntervalSeconds()
                            Triple(
                                routes,
                                activeKeys,
                                routes.associate { route ->
                                    route.key to buildWatchSummary(
                                        route,
                                        route.originCrsSet().mapNotNull(byCrs::get),
                                        cadence,
                                        now
                                    )
                                }
                            )
                        }
                    }
                }
                .collect { (routes, activeKeys, summaries) ->
                    val currentKey = _state.value.route.key
                    _state.value = _state.value.copy(
                        watchRoutes = routes,
                        activeWatchKeys = activeKeys,
                        watchSummaries = summaries,
                        watching = currentKey in activeKeys,
                        watchSaved = routes.any { it.key == currentKey }
                    )
                }
        }
    }
    private fun watchRefreshTicker(): Flow<Long> = flow {
        while (currentCoroutineContext().isActive) {
            emit(System.currentTimeMillis())
            delay(15_000)
        }
    }

    fun connection() = credentials.load()
    private fun originCrsSet(): Set<String> = _state.value.route.originCrsSet()

    fun setSearchQuery(value: String) {
        val extras = if ("london (any)".contains(value.trim().lowercase()) || value.trim().equals("london", true)) listOf(Route.londonAny) else emptyList()
        _state.value = _state.value.copy(searchQuery = value, stationResults = extras + stations.search(value))
    }
    fun selectStation(station: StationRef) {
        closeRoute()
        _state.value = _state.value.copy(selectedStation = station, searchQuery = "", stationResults = emptyList(), board = null, error = null)
    }
    fun submitSearch() {
        val q = _state.value.searchQuery.trim()
        val station = if (q.equals("london", true) || q.equals("london any", true) || q.equals("london (any)", true)) Route.londonAny
        else stations.search(q, 1).firstOrNull() ?: q.takeIf { it.matches(Regex("[A-Za-z]{3}")) }?.let { StationRef(it.uppercase(), it.uppercase()) }
        if (station != null) selectStation(station)
        else _state.value = _state.value.copy(error = "Choose a station or enter its three-letter CRS code.")
    }
    fun setDestinationQuery(value: String) {
        closeRoute()
        _state.value = _state.value.copy(destinationQuery = value, destination = null,
            destinationResults = (if ("london (any)".contains(value.lowercase())) listOf(Route.londonAny) else emptyList()) + stations.search(value))
    }
    fun selectDestination(station: StationRef?) {
        closeRoute()
        _state.value = _state.value.copy(destination = station, destinationQuery = station?.name.orEmpty(), destinationResults = emptyList())
    }
    fun selectRoute(route: Route) { selectStation(route.origin); selectDestination(route.destination); openRoute() }
    fun openRoute() {
        if (_state.value.searchQuery.isNotBlank()) { submitSearch(); if (_state.value.searchQuery.isNotBlank()) return }
        if (_state.value.destination == null && _state.value.destinationQuery.isNotBlank()) {
            val q = _state.value.destinationQuery
            val target = stations.search(q, 1).firstOrNull() ?: q.takeIf { it.matches(Regex("[A-Za-z]{3}")) }?.let { StationRef(it.uppercase(), it.uppercase()) }
            if (target == null) { _state.value = _state.value.copy(error = "Choose a destination result or enter a CRS code."); return }
            selectDestination(target)
        }
        _state.value = _state.value.copy(
            routeOpen = true,
            serviceFilter = "",
            error = null,
            watching = repository.isWatched(_state.value.route),
            watchSaved = preferences.isSavedWatch(_state.value.route)
        )
        val route = _state.value.route
        collectJob?.cancel()
        collectJob = viewModelScope.launch {
            val origins = originCrsSet()
            combine(origins.map { repository.observe(it) }) { snapshots ->
                snapshots.toList()
            }.collect { snapshots ->
                val merged = mergeSnapshots(route, snapshots)
                val learnedStation = merged.board?.let(stations::learn) == true
                val enrichedBoard = merged.board?.let(stations::enrich)
                _state.value = _state.value.copy(
                    board = enrichedBoard,
                    loading = merged.loading,
                    history = merged.history,
                    timelines = merged.timelines,
                    lastSuccess = merged.lastSuccess,
                    nextPollElapsed = merged.nextPollElapsed,
                    failed = merged.failed,
                    offline = merged.offline,
                    error = merged.error,
                    comparatorStatus = merged.comparatorStatus,
                    staffStatus = merged.staffStatus,
                    publicFetched = merged.publicFetched,
                    publicGenerated = merged.publicGenerated,
                    stationCount = if (learnedStation) stations.count() else _state.value.stationCount
                )
            }
        }
        activate()
    }
    private fun mergeSnapshots(route: Route, snapshots: List<LiveSnapshot>): LiveSnapshot {
        if (snapshots.size == 1) {
            val s = snapshots.single()
            val filtered = s.board?.copy(services = s.board.services.filter(route::matches))
            val ids = filtered?.services.orEmpty().map { it.identity }.toSet()
            return s.copy(board = filtered, history = s.history.filter { it.serviceId in ids })
        }
        val boards = snapshots.mapNotNull { it.board }
        val services = boards.flatMap { board ->
            board.services.filter(route::matches)
        }.distinctBy { it.identity }.sortedBy { it.scheduledDeparture ?: it.scheduledArrival ?: "" }
        val ids = services.map { it.identity }.toSet()
        val mergedBoard = StationBoard(
            crs = route.origin.crs,
            stationName = route.origin.name,
            generatedAt = boards.mapNotNull { it.generatedAt }.maxOrNull(),
            rawPayload = "",
            truncated = boards.any { it.truncated },
            platformsAreHidden = boards.any { it.platformsAreHidden == true },
            services = services
        )
        val publicConfigured = snapshots.any {
            it.publicFetched != null ||
                it.comparatorStatus == "Public comparator" ||
                it.comparatorStatus.startsWith("Public comparator unavailable")
        }
        val healthyCount = snapshots.count { it.board != null && !it.failed && !it.offline }
        val failedCrs = snapshots.filter { it.failed || it.offline }.mapNotNull { it.crs }.distinct()
        val allFailed = snapshots.all { it.failed }
        val staffStatus = if (failedCrs.isNotEmpty() && healthyCount > 0) {
            "Staff feeds $healthyCount/${snapshots.size} live · ${failedCrs.joinToString()} retrying"
        } else null
        return LiveSnapshot(
            crs = route.origin.crs,
            board = mergedBoard,
            lastSuccess = snapshots.mapNotNull { it.lastSuccess }.maxOrNull(),
            nextPollElapsed = snapshots.map { it.nextPollElapsed }.filter { it > 0 }.minOrNull() ?: 0,
            loading = snapshots.any { it.loading },
            failed = allFailed,
            offline = snapshots.all { it.offline },
            error = if (allFailed) snapshots.firstNotNullOfOrNull { it.error } else null,
            comparatorStatus = if (publicConfigured) "Public comparator across London origins" else "Public comparator not configured",
            staffStatus = staffStatus,
            publicFetched = snapshots.mapNotNull { it.publicFetched }.maxOrNull(),
            publicGenerated = snapshots.mapNotNull { it.publicGenerated }.maxOrNull(),
            history = snapshots.flatMap { it.history }.filter { it.serviceId in ids },
            timelines = snapshots.flatMap { it.timelines.entries }.associate { it.key to it.value }
        )
    }
    fun closeRoute() { collectJob?.cancel(); _state.value = _state.value.copy(routeOpen = false, board = null); repository.foregroundAll(emptySet()) }
    fun setVisible(value: Boolean) { visible = value; activate() }
    private fun activate() {
        repository.foregroundAll(if (visible && _state.value.routeOpen && _state.value.configured && !_state.value.showSettings) originCrsSet() else emptySet())
    }
    fun setPollIntervalSeconds(seconds: Int) {
        repository.setPollIntervalSeconds(seconds)
        _state.value = _state.value.copy(pollIntervalSeconds = preferences.pollIntervalSeconds())
    }
    fun toggleFavourite() {
        val updated = preferences.toggleFavourite(_state.value.route)
        _state.value = _state.value.copy(favourites = updated)
    }
    fun openFavourite(route: Route) = selectRoute(route)
    fun refresh() { if (_state.value.routeOpen) originCrsSet().forEach(repository::refresh) }
    fun setServiceFilter(value: String) { _state.value = _state.value.copy(serviceFilter = value) }
    fun openSettings() { _state.value = _state.value.copy(showSettings = true); activate() }
    fun closeSettings() { _state.value = _state.value.copy(showSettings = false); activate() }
    fun saveConnection(connection: DarwinConnection) {
        credentials.save(connection)
        _state.value = _state.value.copy(configured = credentials.isConfigured(), showSettings = false)
        activate(); refresh()
    }
    fun clearConnection() {
        getApplication<Application>().stopService(Intent(getApplication(), RouteWatchService::class.java))
        repository.clearWatches(); repository.foreground(null); credentials.clear(); collectJob?.cancel()
        _state.value = _state.value.copy(configured = false, board = null, routeOpen = false, showSettings = true)
    }
    suspend fun testConnection(connection: DarwinConnection): Result<Unit> = try { DarwinStaffClient().departures("RYS", connection); Result.success(Unit) } catch (e: CancellationException) { throw e } catch (_: Exception) { Result.failure(IllegalStateException("Check the Staff endpoint, API key and connection.")) }
    /** Active -> pause. Paused/saved or new -> resume/start immediately. */
    fun toggleWatch() {
        val route = _state.value.route
        if (_state.value.watching) pauseWatch(route) else resumeWatch(route)
    }

    fun resumeWatch(route: Route) {
        val saved = preferences.saveWatchRoute(route)
        savedWatches.value = saved
        _state.value = _state.value.copy(watchSaved = true)
        runCatching { RouteWatchService.start(getApplication(), route) }
            .onFailure { _state.value = _state.value.copy(error = "Android could not start monitoring. Reopen the route and try again.") }
    }

    fun pauseWatch(route: Route) {
        RouteWatchService.pause(getApplication(), route.key)
    }

    fun removeWatch(route: Route) {
        if (route.key in repository.watches.value) RouteWatchService.pause(getApplication(), route.key)
        val saved = preferences.removeWatchRoute(route.key)
        savedWatches.value = saved
        if (_state.value.route.key == route.key) {
            _state.value = _state.value.copy(watchSaved = false, watching = false)
        }
    }
    fun syncStations() { viewModelScope.launch {
        _state.value = _state.value.copy(syncingStations = true)
        val result = stations.syncFromReferenceData(credentials.load())
        _state.value = _state.value.copy(syncingStations = false, stationCount = stations.count(), stationSyncMessage = if (result.isSuccess) "${stations.count()} stations indexed" else "Reference Data refresh unavailable; the bundled GB station directory remains active.")
    } }
    override fun onCleared() { repository.foreground(null); super.onCleared() }
}
