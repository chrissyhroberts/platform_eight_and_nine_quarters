package uk.ac.rawrail

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.os.Build
import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.BackHandler
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import uk.ac.rawrail.data.*
import kotlinx.coroutines.delay
import androidx.activity.viewModels
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.launch
import uk.ac.rawrail.model.CallingPoint
import uk.ac.rawrail.model.RawService
import uk.ac.rawrail.model.StationBoard
import uk.ac.rawrail.model.StationRef
import uk.ac.rawrail.security.AuthMode
import uk.ac.rawrail.security.DarwinConnection
import uk.ac.rawrail.ui.MainUiState
import uk.ac.rawrail.ui.MainViewModel
import uk.ac.rawrail.widget.RailWidgetUpdater
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class MainActivity : ComponentActivity() {
    private val model: MainViewModel by viewModels()
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        openWatchIntent(intent)
        RailWidgetUpdater.updateAll(this)
        setContent {
            RawRailTheme {
                RawRailScreen(model)
            }
        }
    }
    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); setIntent(intent); openWatchIntent(intent) }

    override fun onDestroy() {
        // Finishing the root activity is an explicit end to this app session (for example,
        // Back from the dashboard).  Active watches become paused, but their saved route
        // definitions remain in AppPreferences for rapid Resume on the next launch.
        if (isFinishing && !isChangingConfigurations) {
            stopService(Intent(this, uk.ac.rawrail.watch.RouteWatchService::class.java))
        }
        super.onDestroy()
    }

    private fun openWatchIntent(intent: Intent?) {
        val route = intent?.getStringExtra("watchRoute")?.let { runCatching { kotlinx.serialization.json.Json.decodeFromString<Route>(it) }.getOrNull() }
        if (route != null) model.selectRoute(route)
    }
}

@Composable
fun RawRailTheme(content: @Composable () -> Unit) {
    val scheme = lightColorScheme(
        primary = Color(0xFF123F36),
        onPrimary = Color.White,
        primaryContainer = Color(0xFFDDEDE6),
        onPrimaryContainer = Color(0xFF0A2923),
        secondary = Color(0xFF52645E),
        onSecondary = Color.White,
        tertiary = Color(0xFF8A642D),
        surface = Color(0xFFF7F8F5),
        surfaceVariant = Color(0xFFE8EEE9),
        surfaceContainerLow = Color(0xFFF1F4F0),
        surfaceContainer = Color(0xFFEBF1EC),
        outline = Color(0xFF72817C),
        outlineVariant = Color(0xFFD3DDD7),
        error = Color(0xFF9D2A2A)
    )
    MaterialTheme(
        colorScheme = scheme,
        typography = Typography(),
        content = content
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RawRailScreen(vm: MainViewModel = viewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    BackHandler(enabled = state.routeOpen && !state.showSettings) {
        vm.closeRoute()
    }
    val activityContext = LocalContext.current
    SideEffect { (activityContext as? android.app.Activity)?.let { androidx.core.view.WindowCompat.getInsetsController(it.window, it.window.decorView).isAppearanceLightStatusBars = true } }
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_START) vm.setVisible(true)
            if (event == Lifecycle.Event.ON_STOP) vm.setVisible(false)
        }
        owner.lifecycle.addObserver(observer)
        vm.setVisible(owner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))
        onDispose { owner.lifecycle.removeObserver(observer); vm.setVisible(false) }
    }

    if (state.showSettings || !state.configured) {
        ConnectionSheet(vm, state)
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            Surface(color = MaterialTheme.colorScheme.surface, shadowElevation = 0.dp) {
                Column {
                    Row(
                        Modifier
                            .statusBarsPadding()
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Surface(
                            modifier = Modifier.size(42.dp),
                            shape = RoundedCornerShape(14.dp),
                            color = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Text("9¾", fontWeight = FontWeight.Black, style = MaterialTheme.typography.titleMedium)
                            }
                        }
                        Spacer(Modifier.width(11.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                "Platform 8 9/4",
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Black
                            )
                            Text(
                                "LIVE GB RAIL · DARWIN INTELLIGENCE",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Surface(
                            modifier = Modifier.size(38.dp).clickable(onClick = vm::openSettings),
                            shape = RoundedCornerShape(14.dp),
                            color = MaterialTheme.colorScheme.surfaceContainer
                        ) {
                            Box(contentAlignment = Alignment.Center) { Text("⚙", style = MaterialTheme.typography.titleMedium) }
                        }
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.75f))
                }
            }
        }
    ) { padding ->
        PullToRefreshBox(isRefreshing = state.loading, onRefresh = vm::refresh, modifier = Modifier.padding(padding)) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize(),
            contentPadding = PaddingValues(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item {
                SearchPanel(state, vm)
            }

            if (state.routeOpen && state.board == null) {
                item { MessageCard(if (state.loading) "Connecting to Staff feed…" else "OFFLINE", "Fetching the route automatically. Successful observations will be saved locally.") }
            }
            state.error?.let { error ->
                item {
                    MessageCard(
                        title = "Update unavailable · retrying",
                        text = error,
                        error = true
                    )
                }
            }

            state.board?.let { board ->
                item {
                    BoardHeader(board, state, vm)
                }

                board.nrccMessages.forEach { message ->
                    item {
                        MessageCard("Network message", message)
                    }
                }

                item {
                    ServiceFilter(state, vm)
                }

                val filter = state.serviceFilter.trim().lowercase()
                val services = board.services.filter { s ->
                    filter.isBlank() ||
                        s.displayDestination.lowercase().contains(filter) ||
                        s.displayOrigin.lowercase().contains(filter) ||
                        s.operatorName.orEmpty().lowercase().contains(filter) ||
                        s.operatorCode.orEmpty().lowercase().contains(filter) ||
                        s.trainId.orEmpty().lowercase().contains(filter) ||
                        s.subsequentCallingPoints.filter { it.isPassengerStop }.any {
                            it.locationName.lowercase().contains(filter) ||
                                it.crs.orEmpty().lowercase().contains(filter)
                        }
                }

                if (services.isEmpty()) {
                    item {
                        Text(
                            "No matching services",
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 24.dp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                } else {
                    items(services, key = { it.identity }) { service ->
                        ServiceCard(service, state.route, state.timelines[service.identity].orEmpty(), state.pollIntervalSeconds)
                    }
                }

                if (state.history.isNotEmpty()) {
                    item {
                        HistoryCard(state)
                    }
                }
            }
        }
        }
    }
}

@Composable
private fun SearchPanel(state: MainUiState, vm: MainViewModel) {
    val context = LocalContext.current
    var denied by remember { mutableStateOf(false) }
    var pendingRun by remember { mutableStateOf<Route?>(null) }
    val expandedFavourites = remember { mutableStateMapOf<String, Boolean>() }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { allowed ->
        val route = pendingRun
        pendingRun = null
        if (allowed && route != null) vm.runFavourite(route) else if (!allowed) denied = true
    }
    val runFavourite: (Route) -> Unit = { route ->
        if (Build.VERSION.SDK_INT < 33 || context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
            vm.runFavourite(route)
        } else {
            pendingRun = route
            permission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
    if (state.routeOpen) {
        TextButton(
            onClick = vm::closeRoute,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp),
            colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.primary)
        ) {
            Text("←  Change route", fontWeight = FontWeight.SemiBold)
        }
        return
    }
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        if (state.favourites.isNotEmpty()) {
            Text(
                "Favourites",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(6.dp))
            state.favourites.forEach { route ->
                val active = route.key in state.activeWatchKeys
                val paused = route.key in state.pausedFavouriteKeys
                val summary = state.watchSummaries[route.key]
                val expanded = expandedFavourites[route.key] == true
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = if (active) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainer,
                    modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
                ) {
                    Column(Modifier.padding(horizontal = 8.dp, vertical = 6.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                if (active) "●" else "★",
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(horizontal = 4.dp)
                            )
                            Text(
                                route.title,
                                modifier = Modifier.weight(1f),
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            FavouriteAction("Open") { vm.openFavourite(route) }
                            if (active) {
                                FavouriteAction("Pause") { vm.pauseFavourite(route) }
                            } else {
                                FavouriteAction(if (paused) "Resume" else "Run") { runFavourite(route) }
                            }
                            FavouriteAction("Remove") {
                                expandedFavourites.remove(route.key)
                                vm.removeFavourite(route)
                            }
                            FavouriteAction(if (expanded) "▴" else "▾") {
                                expandedFavourites[route.key] = !expanded
                            }
                        }
                        if (expanded) {
                            HorizontalDivider(
                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 5.dp),
                                color = MaterialTheme.colorScheme.outlineVariant
                            )
                            Text(
                                when {
                                    active -> "RUNNING · ${summary?.status ?: "Connecting"}"
                                    paused -> "PAUSED · ${summary?.status ?: "No cached train data yet"}"
                                    else -> "SAVED · ${summary?.status ?: "No cached train data yet"}"
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                            )
                            summary?.lines?.take(3)?.forEach { line ->
                                Text(
                                    line,
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 1.dp)
                                )
                            }
                        }
                    }
                }
            }
            if (denied) {
                Text(
                    "Notifications are required for a favourite to keep running outside the app.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
            Spacer(Modifier.height(8.dp))
        }
        Text(
            "From · ${state.selectedStation.name}",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(6.dp))

        OutlinedTextField(
            value = state.searchQuery,
            onValueChange = vm::setSearchQuery,
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            placeholder = { Text("Search station name or CRS") },
            leadingIcon = { Text("⌕", style = MaterialTheme.typography.headlineSmall) },
            trailingIcon = {
                if (state.searchQuery.isNotBlank()) {
                    TextButton(onClick = { vm.setSearchQuery("") }) { Text("Clear") }
                }
            },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { vm.submitSearch() }),
            shape = RoundedCornerShape(18.dp)
        )

        if (state.searchQuery.isNotBlank()) {
            Surface(
                tonalElevation = 2.dp,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp)
            ) {
                Column {
                    state.stationResults.take(8).forEach { station ->
                        StationResult(station) { vm.selectStation(station) }
                    }
                    if (state.stationResults.isEmpty() &&
                        state.searchQuery.matches(Regex("[A-Za-z]{3}"))) {
                        StationResult(
                            StationRef(state.searchQuery.uppercase(), "Use CRS directly")
                        ) { vm.submitSearch() }
                    }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(value = state.destinationQuery, onValueChange = vm::setDestinationQuery,
            label = { Text("To · blank for all departures") }, modifier = Modifier.fillMaxWidth(), singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { vm.openRoute() }), shape = RoundedCornerShape(18.dp))
        state.destinationResults.take(6).forEach { station -> StationResult(station) { vm.selectDestination(station) } }
        if (!state.routeOpen) Button(
            onClick = vm::openRoute,
            enabled = state.configured,
            modifier = Modifier.fillMaxWidth().height(52.dp),
            shape = RoundedCornerShape(18.dp)
        ) {
            Text("Open live route", fontWeight = FontWeight.Bold)
        }
        Text("London (Any) watches the King's Cross / St Pancras cluster for this corridor. It is still a live-board intelligence view, not a multi-leg journey planner.",
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

    }
}

@Composable
private fun FavouriteAction(label: String, onClick: () -> Unit) {
    TextButton(
        onClick = onClick,
        modifier = Modifier.defaultMinSize(minWidth = 0.dp, minHeight = 36.dp),
        contentPadding = PaddingValues(horizontal = 5.dp, vertical = 0.dp)
    ) {
        Text(label, style = MaterialTheme.typography.labelSmall, maxLines = 1)
    }
}

@Composable
private fun StationResult(station: StationRef, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            station.crs,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.width(52.dp)
        )
        Text(station.name, Modifier.weight(1f))
    }
}

@Composable
private fun BoardHeader(board: StationBoard, state: MainUiState, vm: MainViewModel) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var elapsed by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis()
            elapsed = SystemClock.elapsedRealtime()
            delay(250)
        }
    }
    val interval = state.pollIntervalSeconds.coerceIn(15, 60)
    val intervalMs = interval * 1_000L
    val status = liveState(state.lastSuccess, board.generatedAt, now, state.failed, state.offline, interval)
    val remaining = ((state.nextPollElapsed - elapsed).coerceIn(0, intervalMs) / 1000.0)
    val tint = when (status) {
        LiveState.LIVE -> MaterialTheme.colorScheme.primary
        LiveState.STALE -> Color(0xFF765A17)
        LiveState.OFFLINE -> Color(0xFF8E3030)
    }
    val context = LocalContext.current
    var denied by remember { mutableStateOf(false) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { allowed ->
        if (allowed) vm.toggleFavouriteRunState() else denied = true
    }
    Surface(
        color = tint,
        contentColor = Color.White,
        shape = RoundedCornerShape(24.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
        shadowElevation = 1.dp
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 15.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            state.route.title,
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Black,
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(
                            onClick = vm::toggleFavourite,
                            colors = ButtonDefaults.textButtonColors(contentColor = Color.White),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                        ) {
                            Text(if (state.routeIsFavourite) "★" else "☆", style = MaterialTheme.typography.titleLarge)
                        }
                    }
                    Text(
                        "${board.services.size} ${if (board.services.size == 1) "service" else "services"} · Staff primary",
                        style = MaterialTheme.typography.labelMedium
                    )
                }
                Box(Modifier.size(54.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(
                        progress = { (remaining / interval).toFloat().coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxSize(),
                        color = Color.White,
                        trackColor = Color.White.copy(alpha = .2f),
                        strokeWidth = 3.dp
                    )
                    Text(if (state.loading) "↻" else "${kotlin.math.ceil(remaining).toInt()}s", fontWeight = FontWeight.Bold)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                LivePill(status.name)
                Text("Platform 8 9/4 fetched ${ageText(state.lastSuccess, now)}", style = MaterialTheme.typography.labelMedium)
            }
            Text(
                "Railway data ${ageText(railwayInstant(board.generatedAt)?.toEpochMilli(), now).let { if (it == "never") "age unknown" else it }} · next poll ${if (state.loading) "in progress" else "in ${kotlin.math.ceil(remaining).toInt()}s"}",
                style = MaterialTheme.typography.labelMedium
            )
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Poll", style = MaterialTheme.typography.labelSmall)
                listOf(15, 30, 60).forEach { seconds ->
                    val selected = seconds == interval
                    Surface(
                        color = if (selected) Color.White else Color.Transparent,
                        contentColor = if (selected) tint else Color.White,
                        shape = RoundedCornerShape(99.dp),
                        border = BorderStroke(1.dp, Color.White.copy(alpha = if (selected) 1f else .5f)),
                        modifier = Modifier.clickable { vm.setPollIntervalSeconds(seconds) }
                    ) {
                        Text(
                            "${seconds}s",
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = if (selected) FontWeight.Black else FontWeight.Medium
                        )
                    }
                }
            }
            state.staffStatus?.let { Text(it, style = MaterialTheme.typography.labelSmall) }
            Text(
                state.comparatorStatus + (state.publicFetched?.let { " · fetched ${ageText(it, now)} · railway ${ageText(railwayInstant(state.publicGenerated)?.toEpochMilli(), now).replace("never", "age unknown")}" } ?: ""),
                style = MaterialTheme.typography.labelSmall
            )
            if (board.truncated) Text("Feed truncated: more services may exist outside this board.")
            if (board.areServicesAvailable == false) Text("Railway reports services unavailable.")
            if (state.routeIsFavourite) {
                OutlinedButton(
                    onClick = {
                        if (state.watching || Build.VERSION.SDK_INT < 33 || context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
                            vm.toggleFavouriteRunState()
                        } else {
                            permission.launch(Manifest.permission.POST_NOTIFICATIONS)
                        }
                    },
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
                    border = BorderStroke(1.dp, Color.White.copy(alpha = .55f)),
                    shape = RoundedCornerShape(14.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
                ) {
                    Text(
                        if (state.watching) "●  Favourite running · tap to pause"
                        else "▶  Run favourite for 30 minutes",
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
            if (denied) Text("Notifications were not enabled. Live route polling continues here.", style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun LivePill(text: String) {
    Box(
        Modifier
            .clip(RoundedCornerShape(99.dp))
            .background(Color.White.copy(alpha = 0.17f))
            .padding(horizontal = 9.dp, vertical = 4.dp)
    ) {
        Text(text, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun ServiceFilter(state: MainUiState, vm: MainViewModel) {
    OutlinedTextField(
        value = state.serviceFilter,
        onValueChange = vm::setServiceFilter,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp),
        singleLine = true,
        placeholder = { Text("Filter destination, operator or train", maxLines = 1) },
        shape = RoundedCornerShape(20.dp)
    )
}

@Composable
private fun ServiceCard(service: RawService, route: Route, history: List<uk.ac.rawrail.model.RailEvent>, pollIntervalSeconds: Int) {
    ElevatedCard(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        elevation = CardDefaults.elevatedCardElevation(defaultElevation = 1.dp)
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 13.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f)) {
                    Text(
                        shortTime(service.scheduledDeparture ?: service.scheduledArrival),
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Black
                    )
                    Text(
                        service.displayDestination,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        "from ${service.displayOrigin}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                val lastKnownStaffPlatform = history.lastOrNull {
                    it.type in setOf(
                        uk.ac.rawrail.model.RailEventType.PLATFORM_SET,
                        uk.ac.rawrail.model.RailEventType.PLATFORM_CHANGED
                    ) && !it.current.isNullOrBlank()
                }?.current
                PlatformBadge(service, lastKnownStaffPlatform)
            }

            Spacer(Modifier.height(12.dp))

            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                StatusBadge(service)
                service.operatorName?.let { SmallBadge(it) }
                service.length?.takeIf { it > 0 }?.let { SmallBadge("$it coaches") }
                service.trainId?.let { SmallBadge("HC $it") }
                service.delayReasonCode?.let { SmallBadge("DLY $it") }
                service.cancelReasonCode?.let { SmallBadge("CAN $it") }
            }

            if (service.futureDelay == true) AlertStrip("Future delay", "Darwin indicates a future delay", false)
            if (service.futureCancellation == true) AlertStrip("Future cancellation", "Darwin indicates a future cancellation", true)
            service.loading?.let { Text("Train loading · $it", style = MaterialTheme.typography.labelMedium) }
            if (service.coachLoading.isNotEmpty()) FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) { service.coachLoading.forEach { SmallBadge(it) } }
            // Journey timing is route-centric: departure from station A and arrival at station B.
            // RawService arrival fields are the arrival at the origin board station, not at the
            // selected destination, so they must not be shown as the journey arrival.
            if (service.scheduledDeparture != null || service.expectedDeparture != null ||
                service.actualDeparture != null || route.destinationPoint(service) != null) {
                Spacer(Modifier.height(12.dp))
                TimeGrid(service, route)
            }

            if (service.cancelReason != null || service.cancelReasonCode != null) {
                Spacer(Modifier.height(10.dp))
                AlertStrip(
                    reasonTitle("Cancellation", service.cancelReasonCode),
                    reasonBody("cancellation", service.cancelReason, service.cancelReasonCode, service.cancelReasonTiploc, service.cancelReasonNear),
                    true
                )
            }
            if (service.delayReason != null || service.delayReasonCode != null) {
                Spacer(Modifier.height(10.dp))
                AlertStrip(
                    reasonTitle("Delay", service.delayReasonCode),
                    reasonBody("delay", service.delayReason, service.delayReasonCode, service.delayReasonTiploc, service.delayReasonNear),
                    false
                )
            }
            service.overdueMessage?.let {
                Spacer(Modifier.height(10.dp))
                AlertStrip("Movement report", it, false)
            }
            service.uncertainty?.let {
                Spacer(Modifier.height(10.dp))
                AlertStrip("Uncertainty", it, false)
            }
            if (service.diversionReason != null || service.diversionReasonCode != null) {
                Spacer(Modifier.height(10.dp))
                AlertStrip(
                    reasonTitle("Diversion", service.diversionReasonCode),
                    buildString {
                        append(reasonBody("diversion", service.diversionReason, service.diversionReasonCode, service.diversionReasonTiploc, service.diversionReasonNear))
                        service.divertedVia?.let { via -> append(" · via $via") }
                        service.rerouteDelay?.let { mins -> append(" · +$mins min") }
                    },
                    false
                )
            }
            service.alerts.forEach {
                Spacer(Modifier.height(8.dp))
                AlertStrip("Alert", it, false)
            }

            Spacer(Modifier.height(12.dp))
            HorizontalDivider()
            Spacer(Modifier.height(10.dp))
            var stopsExpanded by remember(service.identity) { mutableStateOf(false) }
            val passengerStops = service.subsequentCallingPoints.filter { it.isPassengerStop }
            if (passengerStops.isNotEmpty()) {
                TextButton(onClick = { stopsExpanded = !stopsExpanded }) {
                    Text(
                        if (stopsExpanded) "Hide ${passengerStops.size} stops ▴"
                        else "Show ${passengerStops.size} stops ▾"
                    )
                }
                if (stopsExpanded) {
                    CallingPoints(passengerStops)
                    Spacer(Modifier.height(8.dp))
                    HorizontalDivider()
                    Spacer(Modifier.height(10.dp))
                }
            }
            val platform = service.normalizedPlatform
            val lastKnownStaffPlatform = history.lastOrNull {
                it.type in setOf(
                    uk.ac.rawrail.model.RailEventType.PLATFORM_SET,
                    uk.ac.rawrail.model.RailEventType.PLATFORM_CHANGED
                ) && !it.current.isNullOrBlank()
            }?.current
            val timelinePlatform = platform ?: lastKnownStaffPlatform
            val first = history.firstOrNull {
                it.type in setOf(
                    uk.ac.rawrail.model.RailEventType.PLATFORM_SET,
                    uk.ac.rawrail.model.RailEventType.PLATFORM_CHANGED
                ) && it.current == timelinePlatform
            }
            val released = history.lastOrNull {
                it.type == uk.ac.rawrail.model.RailEventType.PUBLIC_PLATFORM_OBSERVED && it.current == timelinePlatform
            }
            first?.let {
                Text(
                    "P${it.current} first observed ${java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.of("Europe/London")).format(it.observedAt)}",
                    style = MaterialTheme.typography.labelMedium
                )
            }
            if (platform == null && lastKnownStaffPlatform != null &&
                (service.platformIsHidden == true || service.stationPlatformsAreHidden == true || service.serviceIsSuppressed == true)) {
                Text(
                    "P$lastKnownStaffPlatform was the last Staff platform observed; the current Staff response supplies no platform number.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else if (platform == null &&
                (service.platformIsHidden == true || service.stationPlatformsAreHidden == true)) {
                Text(
                    "Staff marks the platform hidden but does not supply its number in the current or stored observations.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            released?.let {
                Text(
                    "Public P${it.current} observed ${java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.of("Europe/London")).format(it.observedAt)}",
                    style = MaterialTheme.typography.labelMedium
                )
            }
            if (first != null && released != null && released.observedAt.isAfter(first.observedAt)) {
                Text(
                    "Observed lead: ${java.time.Duration.between(first.observedAt, released.observedAt).seconds}s · polling resolution ${pollIntervalSeconds}s",
                    style = MaterialTheme.typography.labelSmall
                )
            }
            var expanded by remember(service.identity) { mutableStateOf(false) }
            TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "Hide live intelligence ▴" else "Live intelligence ▾") }
            if (expanded) RawDataGrid(service, lastKnownStaffPlatform)
        }
    }
}

@Composable
private fun PlatformBadge(service: RawService, lastKnownStaffPlatform: String?) {
    val platform = service.normalizedPlatform
    val label = when {
        service.isCancelled -> "CANCELLED"
        service.publicPlatform != null -> "PLATFORM\n${service.publicPlatform}"
        service.serviceIsSuppressed == true && platform != null -> "SUPPRESSED\nP$platform"
        service.serviceIsSuppressed == true && lastKnownStaffPlatform != null -> "SUPPRESSED\nP$lastKnownStaffPlatform\nLAST SEEN"
        service.serviceIsSuppressed == true -> "SERVICE\nSUPPRESSED"
        service.platformIsHidden == true && platform != null -> "HIDDEN\nP$platform"
        service.platformIsHidden == true && lastKnownStaffPlatform != null -> "HIDDEN\nP$lastKnownStaffPlatform\nLAST SEEN"
        service.platformIsHidden == true -> "PLATFORM HIDDEN\nNUMBER NOT SUPPLIED"
        service.stationPlatformsAreHidden == true && platform != null -> "STATION HIDDEN\nP$platform"
        service.stationPlatformsAreHidden == true && lastKnownStaffPlatform != null -> "STATION HIDDEN\nP$lastKnownStaffPlatform\nLAST SEEN"
        service.stationPlatformsAreHidden == true -> "STATION PLATFORMS\nHIDDEN"
        platform != null -> "RAW P$platform\nSTATE UNKNOWN"
        else -> "PLATFORM\n—"
    }
    val container = when {
        service.isCancelled -> MaterialTheme.colorScheme.errorContainer
        service.publicPlatform != null -> MaterialTheme.colorScheme.primaryContainer
        else -> MaterialTheme.colorScheme.surfaceVariant
    }
    Surface(color = container, shape = RoundedCornerShape(14.dp)) {
        Text(
            label,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Black
        )
    }
}

@Composable
private fun StatusBadge(service: RawService) {
    val bad = service.isCancelled
    Surface(
        color = if (bad) MaterialTheme.colorScheme.errorContainer
        else MaterialTheme.colorScheme.primaryContainer,
        shape = RoundedCornerShape(99.dp)
    ) {
        Text(
            shortTime(service.liveStatus),
            Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
private fun SmallBadge(text: String) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(99.dp)
    ) {
        Text(
            text,
            Modifier.padding(horizontal = 9.dp, vertical = 5.dp),
            style = MaterialTheme.typography.labelMedium
        )
    }
}

@Composable
private fun TimeGrid(service: RawService, route: Route) {
    val destinationPoint = route.destinationPoint(service)
    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        if (service.scheduledDeparture != null || service.expectedDeparture != null || service.actualDeparture != null) {
            TimeRow(
                "Depart ${route.origin.name}",
                service.scheduledDeparture,
                service.expectedDeparture,
                service.actualDeparture
            )
        }
        route.destination?.let { destination ->
            if (destinationPoint != null) {
                TimeRow(
                    "Arrive ${destination.name}",
                    destinationPoint.scheduledTime,
                    destinationPoint.estimatedTime,
                    destinationPoint.actualTime
                )
            } else {
                Row(Modifier.fillMaxWidth()) {
                    Text(
                        "Arrive ${destination.name}",
                        Modifier.weight(1.25f),
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        "time unavailable",
                        Modifier.weight(1f),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun TimeRow(label: String, scheduled: String?, expected: String?, actual: String?) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, Modifier.weight(1.25f), fontWeight = FontWeight.SemiBold)
        Text("sched ${shortTime(scheduled)}", Modifier.weight(0.85f))
        Text(
            when {
                actual != null -> "actual ${shortTime(actual)}"
                expected != null -> "live ${shortTime(expected)}"
                else -> "live —"
            },
            Modifier.weight(0.8f),
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
private fun AlertStrip(title: String, text: String, error: Boolean) {
    Surface(
        color = if (error) MaterialTheme.colorScheme.errorContainer
        else MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(Modifier.fillMaxWidth().padding(10.dp)) {
            Text(title, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelMedium)
            Text(text, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

private fun reasonTitle(kind: String, code: Int?): String =
    if (code == null) kind else "$kind · code $code"

private fun reasonBody(
    kind: String,
    text: String?,
    code: Int?,
    tiploc: String?,
    near: Boolean?,
): String = buildList {
    if (!text.isNullOrBlank()) add(text)
    if (text.isNullOrBlank() && code != null) {
        add("Description unavailable — configure Reference Data in Settings")
    }
    if (!tiploc.isNullOrBlank()) add("${if (near == true) "near" else "at"} $tiploc")
}.joinToString(" · ").ifBlank { "Reason supplied by Darwin" }

@Composable
private fun CallingPoints(points: List<CallingPoint>) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        points.forEachIndexed { index, cp ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                Text(
                    if (index == points.lastIndex) "●" else "│",
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.width(24.dp)
                )
                Column(Modifier.weight(1f)) {
                    Text(
                        cp.locationName,
                        fontWeight = if (index == points.lastIndex) FontWeight.Bold else FontWeight.Normal
                    )
                    val detail = buildList {
                        cp.crs?.let(::add)
                            ?: cp.tiploc?.takeIf { !it.equals(cp.locationName, ignoreCase = true) }?.let { add("TIPLOC $it") }
                        cp.platform?.let { add("P$it") }
                        cp.actualTime?.let { add("actual ${shortTime(it)}") }
                            ?: cp.estimatedTime?.let { add("live ${shortTime(it)}") }
                            ?: cp.scheduledTime?.let { add("sched ${shortTime(it)}") }
                        if (cp.isCancelled) add("cancelled")
                        cp.delayReasonCode?.let { add("delay code $it") }
                        cp.cancelReasonCode?.let { add("cancel code $it") }
                    }.joinToString(" · ")
                    if (detail.isNotBlank()) {
                        Text(
                            detail,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    cp.delayReason?.let {
                        Text("Delay: $it", style = MaterialTheme.typography.labelMedium)
                    }
                    cp.cancelReason?.let {
                        Text(
                            "Cancellation: $it",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun RawDataGrid(service: RawService, lastKnownStaffPlatform: String?) {
    Text(
        "LIVE INTELLIGENCE",
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.Black,
        color = MaterialTheme.colorScheme.primary
    )
    Spacer(Modifier.height(5.dp))
    RawLine("raw platform", service.normalizedPlatform)
    if (service.normalizedPlatform == null) RawLine("last Staff platform", lastKnownStaffPlatform)
    RawLine("Staff displayable", service.publicPlatform)
    RawLine("station platforms hidden", service.stationPlatformsAreHidden?.toString() ?: "not supplied")
    RawLine("scheduled departure", service.scheduledDeparture)
    RawLine("forecast departure", service.expectedDeparture)
    RawLine("actual departure", service.actualDeparture)
    RawLine("scheduled arrival", service.scheduledArrival)
    RawLine("forecast arrival", service.expectedArrival)
    RawLine("actual arrival", service.actualArrival)
    RawLine("arrival source", service.arrivalSource)
    RawLine("departure source", service.departureSource)
    RawLine("formation", service.formationText)
    RawLine("platform hidden", service.platformIsHidden?.toString() ?: "not supplied")
    RawLine("service suppressed", service.serviceIsSuppressed?.toString() ?: "not supplied")
    RawLine("service ID", service.serviceId)
    RawLine("RID", service.rid)
    RawLine("UID", service.uid)
    RawLine("headcode", service.trainId)
    RawLine("operator code", service.operatorCode)
    RawLine("service type", service.serviceType)
    RawLine("delay reason code", service.delayReasonCode?.toString())
    RawLine("delay reason location", reasonLocation(service.delayReasonTiploc, service.delayReasonNear))
    RawLine("cancellation code", service.cancelReasonCode?.toString())
    RawLine("cancellation location", reasonLocation(service.cancelReasonTiploc, service.cancelReasonNear))
    RawLine("diversion code", service.diversionReasonCode?.toString())
    RawLine("diversion location", reasonLocation(service.diversionReasonTiploc, service.diversionReasonNear))
    if (service.detachFront) RawLine("detach front", "true")
    if (service.reverseFormation) RawLine("reverse formation", "true")
    if (service.isLateReinstated) RawLine("late reinstated", "true")
    if (service.isDeleted) RawLine("deleted", "true")
}

private fun reasonLocation(tiploc: String?, near: Boolean?): String? =
    tiploc?.takeIf { it.isNotBlank() }?.let { "${if (near == true) "near" else "at"} $it" }

@Composable
private fun RawLine(key: String, value: String?) {
    if (value.isNullOrBlank()) return
    Row(Modifier.fillMaxWidth().padding(vertical = 1.dp)) {
        Text(
            key,
            modifier = Modifier.width(138.dp),
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            value,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace
        )
    }
}

@Composable
private fun MessageCard(title: String, text: String, error: Boolean = false) {
    Surface(
        color = if (error) MaterialTheme.colorScheme.errorContainer
        else MaterialTheme.colorScheme.primaryContainer,
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        tonalElevation = 1.dp
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
            Text(title, fontWeight = FontWeight.Bold)
            Text(text)
        }
    }
}

@Composable
private fun HistoryCard(state: MainUiState) {
    ElevatedCard(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        shape = RoundedCornerShape(20.dp)
    ) {
        Column(Modifier.padding(14.dp)) {
            Text("Observed changes", fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(6.dp))
            state.history.take(12).forEach { event ->
                val time = DateTimeFormatter.ofPattern("HH:mm:ss")
                    .withZone(ZoneId.systemDefault())
                    .format(event.observedAt)
                Text(
                    "$time  ${event.type}  ${event.previous ?: "—"} → ${event.current ?: "—"}",
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.labelSmall
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ConnectionSheet(vm: MainViewModel, state: MainUiState) {
    val existing = remember(state.showSettings, state.configured) { vm.connection() }
    var mode by remember { mutableStateOf(existing.authMode) }
    var apiKey by remember { mutableStateOf(existing.apiKey) }
    var username by remember { mutableStateOf(existing.username) }
    var password by remember { mutableStateOf(existing.password) }
    var boardEndpoint by remember { mutableStateOf(existing.boardEndpointTemplate) }
    var stationListEndpoint by remember { mutableStateOf(existing.stationListEndpoint) }
    var stationListApiKey by remember { mutableStateOf(existing.stationListApiKey) }
    var reasonCodeEndpoint by remember { mutableStateOf(existing.reasonCodeEndpoint) }
    var staffEndpoint by remember { mutableStateOf(existing.staffEndpointTemplate) }
    var staffApiKey by remember { mutableStateOf(existing.staffApiKey) }
    var showAdvanced by remember { mutableStateOf(false) }
    var testing by remember { mutableStateOf(false) }
    var testMessage by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    ModalBottomSheet(
        onDismissRequest = { if (state.configured) vm.closeSettings() },
        dragHandle = { BottomSheetDefaults.DragHandle() }
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
        ) {
            Text(
                if (state.configured) "Data connections" else "Connect Platform 8 9/4",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Black
            )
            Text(
                "Your credentials stay encrypted on this device. Platform 8 9/4 ships with no shared key.",
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(Modifier.height(14.dp))

            Text(
                "Staff feed (primary · required)",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Text(
                "Paste the RDM consumer key for the Staff Live Departure Board product.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            ApiKeyField("Staff API key", staffApiKey) { staffApiKey = it }

            Spacer(Modifier.height(10.dp))
            Text(
                "Delay and cancellation descriptions",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Text(
                "Darwin sends numeric reason codes on the Staff feed. Paste the consumer key and GetReasonCodeList endpoint from the separate RDM Reference Data product to show their text.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            ApiKeyField("Reference Data API key", stationListApiKey) { stationListApiKey = it }
            OutlinedTextField(
                value = reasonCodeEndpoint,
                onValueChange = { reasonCodeEndpoint = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("GetReasonCodeList endpoint") },
                supportingText = { Text("Ends with /LDBSVWS/api/ref/20211101/GetReasonCodeList") },
                singleLine = false
            )

            Spacer(Modifier.height(10.dp))
            Text(
                "Public comparator (optional)",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Text(
                "Use the Public LDBWS consumer key here if you have one. Leave blank and the Staff feed still works.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            ApiKeyField("Public comparator API key", apiKey) { apiKey = it }

            TextButton(onClick = { showAdvanced = !showAdvanced }) {
                Text(if (showAdvanced) "Hide advanced endpoints" else "Advanced endpoints")
            }

            if (showAdvanced) {
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    SegmentedButton(
                        selected = mode == AuthMode.RDM_API_KEY,
                        onClick = { mode = AuthMode.RDM_API_KEY },
                        shape = SegmentedButtonDefaults.itemShape(0, 2)
                    ) { Text("RDM API key") }
                    SegmentedButton(
                        selected = mode == AuthMode.LEGACY_BASIC,
                        onClick = { mode = AuthMode.LEGACY_BASIC },
                        shape = SegmentedButtonDefaults.itemShape(1, 2)
                    ) { Text("Legacy Basic") }
                }

                Spacer(Modifier.height(10.dp))

                if (mode == AuthMode.RDM_API_KEY) {
                    OutlinedTextField(
                        value = boardEndpoint,
                        onValueChange = { boardEndpoint = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Public GetDepartureBoard endpoint") },
                        supportingText = { Text("Must contain {crs}") },
                        singleLine = false
                    )
                } else {
                    OutlinedTextField(
                        value = username,
                        onValueChange = { username = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Username") },
                        singleLine = true
                    )
                    ApiKeyField("Password", password, cleanWhitespace = false) { password = it }
                }

                Spacer(Modifier.height(8.dp))
                Text(
                    "Station directory",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    "A complete GB passenger-station CRS directory is bundled offline. Reference Data is optional and can refresh it between app releases.",
                    style = MaterialTheme.typography.bodySmall
                )
                OutlinedTextField(
                    value = stationListEndpoint,
                    onValueChange = { stationListEndpoint = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("GetStationList endpoint") }
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = staffEndpoint,
                    onValueChange = { staffEndpoint = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Staff board endpoint template") }
                )
            }

            val candidate = DarwinConnection(
                authMode = mode,
                apiKey = apiKey.trim(),
                username = username.trim(),
                password = password,
                boardEndpointTemplate = boardEndpoint.trim(),
                stationListEndpoint = stationListEndpoint.trim(),
                stationListApiKey = stationListApiKey.trim(),
                reasonCodeEndpoint = reasonCodeEndpoint.trim(),
                staffEndpointTemplate = staffEndpoint.trim(),
                staffApiKey = staffApiKey.trim()
            )

            Row(
                Modifier.fillMaxWidth().padding(top = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = {
                        context.startActivity(
                            Intent(Intent.ACTION_VIEW, Uri.parse("https://raildata.org.uk/"))
                        )
                    }
                ) { Text("Rail Data Marketplace") }

                Button(
                    enabled = !testing,
                    onClick = {
                        testing = true
                        scope.launch {
                            val result = vm.testConnection(candidate)
                            testing = false
                            testMessage = if (result.isSuccess) {
                                if (stationListApiKey.isNotBlank() || reasonCodeEndpoint.isNotBlank()) {
                                    "Staff and Reference Data connections successful"
                                } else {
                                    "Staff connection successful · Reference Data not configured"
                                }
                            } else {
                                "Connection failed. Check the Staff and Reference Data endpoints, keys and connection."
                            }
                        }
                    }
                ) { Text(if (testing) "Testing…" else "Test live board") }
            }

            testMessage?.let {
                Text(
                    it,
                    modifier = Modifier.padding(top = 6.dp),
                    color = if (it.startsWith("Connection successful"))
                        MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.error
                )
            }

            Button(
                onClick = { vm.saveConnection(candidate) },
                enabled = candidate.staffApiKey.isNotBlank() && candidate.staffEndpointTemplate.contains("{crs}"),
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp)
            ) {
                Text("Save securely")
            }

            if (state.configured) {
                OutlinedButton(
                    onClick = vm::syncStations,
                    enabled = !state.syncingStations &&
                        stationListEndpoint.isNotBlank() &&
                        stationListApiKey.isNotBlank(),
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                ) {
                    Text(
                        if (state.syncingStations) "Indexing stations…"
                        else "Refresh station directory (${state.stationCount} bundled/cached)"
                    )
                }
                state.stationSyncMessage?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall)
                }

                TextButton(
                    onClick = vm::clearConnection,
                    modifier = Modifier.align(Alignment.CenterHorizontally)
                ) {
                    Text("Remove all stored connections")
                }
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun ApiKeyField(
    label: String,
    value: String,
    cleanWhitespace: Boolean = true,
    onChange: (String) -> Unit
) {
    OutlinedTextField(
        value = value,
        onValueChange = { raw ->
            onChange(if (cleanWhitespace) raw.filterNot { it.isWhitespace() } else raw)
        },
        modifier = Modifier.fillMaxWidth(),
        label = { Text(label) },
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.Ascii,
            imeAction = ImeAction.Done
        ),
        singleLine = true
    )
}
