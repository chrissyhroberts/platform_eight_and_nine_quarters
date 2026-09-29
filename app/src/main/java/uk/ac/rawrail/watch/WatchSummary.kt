package uk.ac.rawrail.watch

import uk.ac.rawrail.data.LiveSnapshot
import uk.ac.rawrail.data.Route
import uk.ac.rawrail.data.ageText
import uk.ac.rawrail.data.liveState
import uk.ac.rawrail.data.shortTime
import uk.ac.rawrail.model.RailEvent
import uk.ac.rawrail.model.RailEventType
import uk.ac.rawrail.model.RawService
import uk.ac.rawrail.model.CallingPoint

/**
 * The compact information shown for an active watch. This is deliberately shared by the
 * foreground-service notification and the dashboard so both surfaces show the same live view.
 */
data class WatchSummary(
    val lines: List<String>,
    val status: String,
)

fun buildWatchSummary(
    route: Route,
    snapshots: List<LiveSnapshot>,
    cadenceSeconds: Int,
    now: Long = System.currentTimeMillis(),
): WatchSummary {
    val lastSuccess = snapshots.mapNotNull { it.lastSuccess }.maxOrNull()
    val generatedAt = snapshots.mapNotNull { it.board?.generatedAt }.maxOrNull()
    val failed = snapshots.isNotEmpty() && snapshots.all { it.failed }
    val offline = snapshots.isNotEmpty() && snapshots.all { it.offline }
    val status = liveState(lastSuccess, generatedAt, now, failed, offline, cadenceSeconds)
    val healthy = snapshots.count { it.board != null && !it.failed && !it.offline }
    val feedText = if (snapshots.size > 1) " · $healthy/${snapshots.size} feeds" else ""
    val summary = "$status$feedText · ${cadenceSeconds}s · updated ${ageText(lastSuccess, now)}"

    data class Candidate(val service: RawService, val timeline: List<RailEvent>)
    val candidates = snapshots.flatMap { snapshot ->
        snapshot.board?.services.orEmpty()
            .filter(route::matches)
            .map { Candidate(it, snapshot.timelines[it.identity].orEmpty()) }
    }
        .distinctBy { it.service.identity }
        .filter { it.service.actualDeparture.isNullOrBlank() && !it.service.isDeleted }
        .sortedBy { it.service.scheduledDeparture ?: it.service.expectedDeparture ?: "9999" }
        .take(3)

    val lines = if (candidates.isEmpty()) {
        listOf("No upcoming matching trains in the current board")
    } else {
        candidates.map { candidate ->
            val service = candidate.service
            val point = route.destinationPoint(service)
            val advertised = advertisedService(service)
            val depart = liveDepartureTime(service)
            val arrive = liveCallingTime(point)
            val platform = compactPlatform(service, candidate.timeline)
            val disruption = disruptionText(service, point)
            "$advertised · Dep $depart→$arrive · $platform · $disruption"
        }
    }

    return WatchSummary(lines = lines, status = summary)
}


private fun advertisedService(service: RawService): String {
    val advertisedTime = shortTime(service.scheduledDeparture ?: service.scheduledArrival)
    val destination = service.displayDestination
    return "$advertisedTime $destination"
}

private fun liveDepartureTime(service: RawService): String {
    val expected = service.expectedDeparture
    val value = when {
        !service.actualDeparture.isNullOrBlank() -> service.actualDeparture
        expected.equals("On time", ignoreCase = true) -> service.scheduledDeparture
        isClockValue(expected) -> expected
        else -> service.scheduledDeparture
    }
    return shortTime(value)
}

private fun liveCallingTime(point: CallingPoint?): String {
    if (point == null) return "—"
    val estimated = point.estimatedTime
    val value = when {
        !point.actualTime.isNullOrBlank() -> point.actualTime
        estimated.equals("On time", ignoreCase = true) -> point.scheduledTime
        isClockValue(estimated) -> estimated
        else -> point.scheduledTime
    }
    return shortTime(value)
}

private fun disruptionText(service: RawService, point: CallingPoint?): String {
    if (service.isCancelled) return service.cancelReason?.let { "CANCELLED · $it" }
        ?: service.cancelReasonCode?.let { "CANCELLED C$it" } ?: "CANCELLED"
    if (point?.isCancelled == true) return point.cancelReason?.let { "stop cancelled · $it" }
        ?: point.cancelReasonCode?.let { "stop cancelled C$it" } ?: "stop cancelled"
    if (service.futureCancellation == true) return "cancellation expected"
    service.delayReason?.let { return it }
    point?.delayReason?.let { return it }
    if (service.futureDelay == true) return "delay expected"

    val departureDelay = delayMinutes(service.scheduledDeparture, service.expectedDeparture)
    val arrivalDelay = delayMinutes(point?.scheduledTime, point?.estimatedTime)
    val delay = listOfNotNull(departureDelay, arrivalDelay).maxOrNull()
    if (delay != null && delay > 0) return "+${delay}m"
    service.delayReasonCode?.let { return "DLY $it" }
    point?.delayReasonCode?.let { return "DLY $it" }
    if (service.expectedDeparture.equals("Delayed", ignoreCase = true) ||
        point?.estimatedTime.equals("Delayed", ignoreCase = true)) return "delayed"
    if (service.overdueMessage != null) return "overdue"
    return "on time"
}

private fun compactPlatform(service: RawService, timeline: List<RailEvent>): String {
    val platform = service.normalizedPlatform
    val lastKnown = timeline.lastOrNull {
        it.type in setOf(RailEventType.PLATFORM_SET, RailEventType.PLATFORM_CHANGED) && !it.current.isNullOrBlank()
    }?.current
    return when {
        service.publicPlatform != null -> "P${service.publicPlatform}"
        service.serviceIsSuppressed == true && platform != null -> "SUP P$platform"
        service.serviceIsSuppressed == true && lastKnown != null -> "SUP P$lastKnown*"
        service.serviceIsSuppressed == true -> "SUPPRESSED"
        service.platformIsHidden == true && platform != null -> "HID P$platform"
        service.platformIsHidden == true && lastKnown != null -> "HID P$lastKnown*"
        service.platformIsHidden == true -> "HIDDEN"
        service.stationPlatformsAreHidden == true && platform != null -> "HID P$platform"
        service.stationPlatformsAreHidden == true && lastKnown != null -> "HID P$lastKnown*"
        service.stationPlatformsAreHidden == true -> "HIDDEN"
        platform != null -> "RAW P$platform"
        else -> "P—"
    }
}

private fun isClockValue(value: String?): Boolean = value?.let {
    Regex("(?:^|T)\\d{2}:\\d{2}").containsMatchIn(it)
} == true

private fun delayMinutes(scheduled: String?, live: String?): Int? {
    val scheduledMinutes = clockMinutes(scheduled) ?: return null
    val liveMinutes = clockMinutes(live) ?: return null
    var diff = liveMinutes - scheduledMinutes
    if (diff < -720) diff += 1440
    if (diff > 720) diff -= 1440
    return diff.coerceAtLeast(0)
}

private fun clockMinutes(value: String?): Int? {
    val time = shortTime(value)
    val match = Regex("^(\\d{2}):(\\d{2})$").matchEntire(time) ?: return null
    return match.groupValues[1].toInt() * 60 + match.groupValues[2].toInt()
}
