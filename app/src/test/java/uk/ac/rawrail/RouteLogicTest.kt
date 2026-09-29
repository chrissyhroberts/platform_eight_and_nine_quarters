package uk.ac.rawrail

import org.junit.Test
import org.junit.Assert.*
import uk.ac.rawrail.data.*
import uk.ac.rawrail.model.*
import java.time.Instant

class RouteLogicTest {
    private val now = Instant.parse("2026-09-20T17:18:40Z")
    private val base = RawService("opaque", rid = "202609201", scheduledDeparture = "2026-09-20T18:42:00", operatorCode = "GN", destinationCrs = listOf("KGX"), rawPlatform = "2", platformIsHidden = true, stationPlatformsAreHidden = false, serviceIsSuppressed = false)
    @Test fun londonIncludesThroughServicesButNotCambridge() {
        val route = Route(StationRef("RYS", "Royston"), Route.londonAny)
        assertTrue(route.matches(base))
        assertTrue(route.matches(base.copy(destinationCrs = listOf("BTN"), subsequentCallingPoints = listOf(CallingPoint("St Pancras", "STP")))))
        assertTrue(route.matches(base.copy(destinationCrs = listOf("BTN"), subsequentCallingPoints = listOf(CallingPoint("St Pancras Thameslink", "SPX")))))
        assertFalse(route.matches(base.copy(destinationCrs = listOf("CBG"))))
    }
    @Test fun londonAnyOriginIsKingsCrossStPancrasCluster() {
        assertEquals(setOf("KGX", "STP", "SPX"), Route.londonOrigin)
    }
    @Test fun routeAndDestinationIgnoreOperationalAndPassLocations() {
        val route = Route(StationRef("KGX", "London King's Cross"), StationRef("RYS", "Royston"))
        val operationalOnly = base.copy(
            destinationCrs = listOf("CBG"),
            subsequentCallingPoints = listOf(
                CallingPoint("Royston", "RYS", isOperational = true),
                CallingPoint("Royston", "RYS", isPass = true)
            )
        )
        assertFalse(route.matches(operationalOnly))
        assertNull(route.destinationPoint(operationalOnly))

        val passengerCall = operationalOnly.copy(
            subsequentCallingPoints = operationalOnly.subsequentCallingPoints + CallingPoint("Royston", "RYS")
        )
        assertTrue(route.matches(passengerCall))
        assertEquals("RYS", route.destinationPoint(passengerCall)?.crs)
        assertEquals(1, passengerCall.subsequentCallingPoints.count { it.isPassengerStop })
    }
    @Test fun ridKeepsPositionDespiteReorderingAndChangedOpaqueId() {
        val b = base.copy(rid = "second", serviceId = "other")
        val updated = base.copy(serviceId = "changed", rawPlatform = "4")
        val rows = stableServices(listOf(base, b), listOf(b, updated, updated))
        assertEquals(listOf(base.identity, b.identity), rows.map { it.identity })
        assertEquals("4", rows.first().rawPlatform)
    }
    @Test fun separateLocalAndRailwayAge() {
        assertEquals(LiveState.LIVE, liveState(now.toEpochMilli(), "2026-09-20T18:18:35+01:00", now.toEpochMilli(), false, false))
        assertEquals(LiveState.STALE, liveState(now.toEpochMilli(), "2026-09-20T18:15:00+01:00", now.toEpochMilli(), false, false))
        assertEquals(LiveState.STALE, liveState(now.toEpochMilli(), now.toString(), now.toEpochMilli(), true, false))
        assertEquals(LiveState.OFFLINE, liveState(now.toEpochMilli(), now.toString(), now.toEpochMilli(), true, true))
        assertEquals(LiveState.STALE, liveState(now.toEpochMilli(), null, now.toEpochMilli(), false, false))
    }
    @Test fun stationSuppressionAndUnknownFlagsDoNotRelease() {
        val old = base.copy(platformIsHidden = false, stationPlatformsAreHidden = true)
        assertFalse(changes(old, old.copy(stationPlatformsAreHidden = null), now).any { it.type == RailEventType.PLATFORM_RELEASED })
        assertTrue(changes(old, old.copy(stationPlatformsAreHidden = false), now).any { it.type == RailEventType.PLATFORM_RELEASED })
        assertFalse(changes(old, old.copy(stationPlatformsAreHidden = false, serviceIsSuppressed = true), now).any { it.type == RailEventType.PLATFORM_RELEASED })
    }
    @Test fun detectTransitionsWithoutRepeatingUnchangedEvents() {
        val changed = base.copy(rawPlatform = "4", futureDelay = true, futureCancellation = true, isCancelled = true)
        val types = changes(base, changed, now).map { it.type }
        assertTrue(types.containsAll(listOf(RailEventType.PLATFORM_CHANGED, RailEventType.FUTURE_DELAY, RailEventType.FUTURE_CANCELLATION, RailEventType.CANCELLED)))
        assertTrue(changes(changed, changed, now).isEmpty())
        assertTrue(changes(changed, base, now).any { it.type == RailEventType.REINSTATED })
    }
    @Test fun publicMatchingRejectsAmbiguityAndWrongOperator() {
        val p = base.copy(serviceId = "public", rid = null, scheduledDeparture = "18:42")
        assertEquals(p, comparatorMatch(base, listOf(p), listOf(base)))
        assertNull(comparatorMatch(base, listOf(p, p.copy(serviceId = "second")), listOf(base)))
        assertNull(comparatorMatch(base, listOf(p.copy(operatorCode = "TL")), listOf(base)))
        assertNull(comparatorMatch(base, listOf(p), listOf(base, base.copy(rid = "another"))))
    }
    @Test fun firstPlatformAfterUnknownIsAFirstObservation() {
        assertTrue(changes(base.copy(rawPlatform = null), base, now).any { it.type == RailEventType.PLATFORM_SET })
    }
}
