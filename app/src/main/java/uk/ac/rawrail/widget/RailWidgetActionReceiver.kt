package uk.ac.rawrail.widget

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import uk.ac.rawrail.data.AppPreferences
import uk.ac.rawrail.data.LiveRepository
import uk.ac.rawrail.data.Route
import uk.ac.rawrail.watch.RouteWatchService

/** Handles explicit user actions from the home-screen departure-board widget. */
class RailWidgetActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_TOGGLE_WATCH) return
        val route = intent.getStringExtra(EXTRA_ROUTE)?.let { encoded ->
            runCatching { Json.decodeFromString<Route>(encoded) }.getOrNull()
        } ?: return

        val repository = LiveRepository.get(context)
        if (route.key in repository.watches.value) {
            RouteWatchService.pause(context, route.key)
        } else {
            // A widget tap grants this route a short live lease rather than leaving it polling
            // indefinitely.  The foreground service enforces the persisted expiry.
            AppPreferences(context).startWatchLease(route.key)
            RouteWatchService.start(context, route)
        }

        RailWidgetUpdater.updateAll(context)
        val pending = goAsync()
        actionScope.launch {
            try {
                // Give the service enough time to publish its new active-watch state before the
                // second render; this avoids a momentary stale Pause/Resume label after a tap.
                delay(500)
                RailWidgetUpdater.updateAll(context)
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION_TOGGLE_WATCH = "uk.ac.rawrail.widget.TOGGLE_WATCH"
        const val EXTRA_ROUTE = "route"
        private val actionScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }
}
