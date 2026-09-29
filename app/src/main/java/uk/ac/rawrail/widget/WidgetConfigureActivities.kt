package uk.ac.rawrail.widget

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import uk.ac.rawrail.RawRailTheme
import uk.ac.rawrail.data.AppPreferences
import uk.ac.rawrail.data.Route
import uk.ac.rawrail.data.StationDirectory
import uk.ac.rawrail.model.StationRef

class LiveTimetableWidgetConfigureActivity : ComponentActivity() {
    private var appWidgetId: Int = AppWidgetManager.INVALID_APPWIDGET_ID

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setResult(Activity.RESULT_CANCELED)
        appWidgetId = intent?.getIntExtra(
            AppWidgetManager.EXTRA_APPWIDGET_ID,
            AppWidgetManager.INVALID_APPWIDGET_ID
        ) ?: AppWidgetManager.INVALID_APPWIDGET_ID
        if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            finish()
            return
        }

        val preferences = AppPreferences(this)
        val existing = WidgetRouteStore.load(this, appWidgetId)
        val savedRoutes = (preferences.watchRoutes() + preferences.favouriteRoutes() + listOfNotNull(existing))
            .distinctBy { it.key }
            .sortedBy { it.title }
        val stations = StationDirectory(this)

        setContent {
            RawRailTheme {
                TimetableWidgetConfigScreen(
                    savedRoutes = savedRoutes,
                    stations = stations,
                    initialRoute = existing,
                    onChoose = ::finishWithRoute,
                )
            }
        }
    }

    private fun finishWithRoute(route: Route) {
        WidgetRouteStore.save(this, appWidgetId, route)
        RailWidgetUpdater.updateTimetables(this, intArrayOf(appWidgetId))
        setResult(
            Activity.RESULT_OK,
            Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
        )
        finish()
    }
}

@Composable
private fun ConfigFrame(
    title: String,
    subtitle: String,
    content: @Composable () -> Unit,
) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 18.dp, vertical = 22.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Platform 8 9/4", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Black)
        Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(2.dp))
        content()
    }
}

@Composable
private fun TimetableWidgetConfigScreen(
    savedRoutes: List<Route>,
    stations: StationDirectory,
    initialRoute: Route?,
    onChoose: (Route) -> Unit,
) {
    val initial = initialRoute ?: Route(StationRef("RYS", "Royston"), Route.londonAny)
    var originQuery by remember { mutableStateOf(initial.origin.name) }
    var origin by remember { mutableStateOf<StationRef?>(initial.origin) }
    var destinationQuery by remember { mutableStateOf(initial.destination?.name.orEmpty()) }
    var destination by remember { mutableStateOf<StationRef?>(initial.destination) }

    ConfigFrame(
        title = "Pin a departure board",
        subtitle = "Shows the next three matching trains. Use the switch on the widget to turn live updating on or off."
    ) {
        if (savedRoutes.isNotEmpty()) {
            Text("Saved routes", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
            savedRoutes.forEach { route -> RouteChoice(route, onChoose) }
            Spacer(Modifier.height(5.dp))
            Text("Or choose a route", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
        }

        OutlinedTextField(
            value = originQuery,
            onValueChange = {
                originQuery = it
                origin = null
            },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("From") },
            singleLine = true,
        )
        if (origin == null && originQuery.isNotBlank()) {
            widgetStationResults(stations, originQuery).take(6).forEach { station ->
                StationChoice(station) {
                    origin = station
                    originQuery = station.name
                }
            }
        } else if (origin != null) {
            Text(
                "${origin!!.crs} · ${origin!!.name}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary
            )
        }

        OutlinedTextField(
            value = destinationQuery,
            onValueChange = {
                destinationQuery = it
                destination = null
            },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("To · blank for all departures") },
            singleLine = true,
        )
        if (destinationQuery.isBlank()) {
            Text("All departures", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
        } else if (destination == null) {
            widgetStationResults(stations, destinationQuery).take(6).forEach { station ->
                StationChoice(station) {
                    destination = station
                    destinationQuery = station.name
                }
            }
            TextButton(onClick = {
                destinationQuery = ""
                destination = null
            }) { Text("Use all departures") }
        } else {
            Text(
                "${destination!!.crs} · ${destination!!.name}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary
            )
            TextButton(onClick = {
                destinationQuery = ""
                destination = null
            }) { Text("Use all departures") }
        }

        val route = origin?.let { Route(it, if (destinationQuery.isBlank()) null else destination) }
        Button(
            onClick = { route?.let(onChoose) },
            enabled = route != null && (destinationQuery.isBlank() || destination != null),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Pin departure board")
        }
    }
}

@Composable
private fun RouteChoice(route: Route, onChoose: (Route) -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onChoose(route) }
    ) {
        Row(
            Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(route.title, fontWeight = FontWeight.SemiBold)
                Text(
                    route.origin.crs + (route.destination?.let { " → ${it.crs}" } ?: " · all departures"),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Text("Pin", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun StationChoice(station: StationRef, onChoose: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onChoose)
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(station.crs, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
        Text(station.name, modifier = Modifier.padding(start = 12.dp))
    }
}

private fun widgetStationResults(directory: StationDirectory, query: String): List<StationRef> {
    val q = query.trim().lowercase()
    val extras = if ("london" in q || q in setOf("lon", "london any", "london (any)")) {
        listOf(Route.londonAny)
    } else {
        emptyList()
    }
    return (extras + directory.search(query, 8)).distinctBy { it.crs }
}
