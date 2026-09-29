package uk.ac.rawrail

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.*
import org.junit.Assert.*
import kotlinx.coroutines.*
import uk.ac.rawrail.data.*
import uk.ac.rawrail.model.*
import uk.ac.rawrail.security.*
import uk.ac.rawrail.ui.MainViewModel
import java.time.Instant
import java.io.IOException
import java.io.File

class LiveRouteTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val app get() = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Application
    private fun board(platform: String = "2") = StationBoard("RYS", "Royston", Instant.now().toString(), platformsAreHidden = false,
        services = listOf(RawService("opaque", rid = "202609201234567", scheduledDeparture = "18:42", expectedDeparture = "On time", operatorName = "Great Northern", operatorCode = "GN", origins = listOf("Cambridge"), destinations = listOf("London King's Cross"), destinationCrs = listOf("KGX"), rawPlatform = platform, platformIsHidden = true, stationPlatformsAreHidden = false, serviceIsSuppressed = false, futureDelay = true)))

    @Test fun databaseSurvivesReopenAndDeduplicatesEvents() {
        val ctx = app
        ctx.deleteDatabase("persistence_observations.db")
        val first = board()
        var db = ObservationStore(ctx, "persistence_observations.db")
        assertTrue(db.record(first, "staff", System.currentTimeMillis()).isNotEmpty())
        db.close(); db = ObservationStore(ctx, "persistence_observations.db")
        assertEquals("2", db.latestBoard("RYS")!!.first.services.single().rawPlatform)
        assertTrue(db.record(first, "staff", System.currentTimeMillis()).isEmpty())
        assertTrue(db.record(board("4"), "staff", System.currentTimeMillis()).any { it.type == RailEventType.PLATFORM_CHANGED })
        val public = first.copy(services = first.services.map { it.copy(rid = null, serviceId = "public", platformIsHidden = null) })
        db.record(public, "public", System.currentTimeMillis())
        assertEquals(1, db.compare(first, public, System.currentTimeMillis()).size)
        assertTrue(db.compare(first, public, System.currentTimeMillis()).isEmpty())
        val count = db.readableDatabase.rawQuery("SELECT COUNT(*) FROM observations", null).use { it.moveToFirst(); it.getInt(0) }
        assertEquals(4, count)
        assertTrue(db.history("RYS").any { it.type == RailEventType.PUBLIC_PLATFORM_OBSERVED })
        db.close()
    }

    @Test fun automaticPollingFailureRetentionAndForegroundStop() {
        val ctx = app
        ctx.deleteDatabase("polling_observations.db")
        var calls = 0
        var fail = false
        lateinit var repo: LiveRepository
        lateinit var vm: MainViewModel
        compose.runOnUiThread {
            repo = LiveRepository(app, staffFetch = { _, _ -> calls++; delay(50); if (fail) throw IOException("test offline") else board() }, store = ObservationStore(ctx, "polling_observations.db"))
            vm = MainViewModel(app, repo)
            vm.saveConnection(DarwinConnection(staffApiKey = "instrumented-test-not-a-credential"))
        }
        compose.setContent { RawRailTheme { RawRailScreen(vm) } }
        compose.onNodeWithText("Open live route").performClick()
        compose.waitUntil(5000) { vm.state.value.board != null }
        assertEquals(1, calls)
        compose.onNodeWithText("London King's Cross").assertExists()
        compose.onNodeWithText("LIVE", useUnmergedTree = true).assertExists()
        screenshot("live-route.png")
        // The second fetch must be automatic, with no user refresh action.
        compose.waitUntil(18_000) { calls >= 2 }
        compose.runOnUiThread { repo.watch(vm.state.value.route); vm.setVisible(false); fail = true; vm.refresh() }
        compose.waitUntil(5000) { vm.state.value.offline }
        assertEquals("2", vm.state.value.board!!.services.single().rawPlatform)
        compose.onNodeWithText("OFFLINE", useUnmergedTree = true).assertExists()
        compose.waitForIdle()
        Thread.sleep(300)
        screenshot("offline-route.png")
        // A bell consumer continues even with no foreground screen.
        val backgroundAt = calls
        compose.runOnUiThread { fail = false }
        compose.waitUntil(18_000) { calls > backgroundAt && !vm.state.value.offline }
        compose.runOnUiThread { repo.watch(null) }
        val stoppedAt = calls
        Thread.sleep(16_000)
        assertEquals(stoppedAt, calls)
        compose.runOnUiThread { fail = false; vm.setVisible(true) }
        compose.waitUntil(5000) { calls > stoppedAt && !vm.state.value.offline }
        compose.runOnUiThread { vm.setVisible(false); repo.close() }
        SecureCredentialStore(app).clear()
    }
    @Test fun foregroundServiceStartsAndStopsWithNotification() {
        SecureCredentialStore(app).clear()
        val route = Route(StationRef("RYS", "Royston"), Route.londonAny)
        compose.runOnUiThread { uk.ac.rawrail.watch.RouteWatchService.start(app, route) }
        compose.waitUntil(5000) { LiveRepository.get(app).watch.value == route }
        val manager = app.getSystemService(android.app.NotificationManager::class.java)
        assertTrue(manager.activeNotifications.any { it.id == 1 })
        compose.runOnUiThread { app.stopService(android.content.Intent(app, uk.ac.rawrail.watch.RouteWatchService::class.java)) }
        compose.waitUntil(5000) { LiveRepository.get(app).watch.value == null }
        assertFalse(manager.activeNotifications.any { it.id == 1 })
    }
    private fun screenshot(name: String) {
        val device = InstrumentationRegistry.getInstrumentation().uiAutomation
        val bitmap = device.takeScreenshot()
        File(app.getExternalFilesDir(null), name).outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
    }
}
