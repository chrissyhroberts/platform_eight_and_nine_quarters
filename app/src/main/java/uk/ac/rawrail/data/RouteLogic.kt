package uk.ac.rawrail.data

import kotlinx.serialization.Serializable
import uk.ac.rawrail.model.*
import java.time.*

@Serializable
data class Route(val origin: StationRef, val destination: StationRef? = null) {
    val key: String get() = "${origin.crs}:${destination?.crs.orEmpty()}"
    val title: String get() = origin.name + (destination?.let { " → ${it.name}" } ?: " departures")
    fun matches(s: RawService): Boolean {
        val target = destination ?: return true
        val passengerCalls = s.subsequentCallingPoints.filter { it.isPassengerStop }
        val codes = s.destinationCrs.map { it.trim().uppercase() } +
            passengerCalls.mapNotNull { it.crs?.trim()?.uppercase() }
        if (target.crs == "LON") {
            if (codes.any { it in london }) return true
            return passengerCalls.any { normaliseLocationName(it.locationName) in londonNames }
        }
        if (target.crs.uppercase() in codes) return true
        val targetName = normaliseLocationName(target.name)
        return passengerCalls.any { normaliseLocationName(it.locationName) == targetName }
    }
    /** Return the passenger calling point corresponding to this route's selected destination.
     *
     * This is deliberately route-centric: RawService arrival fields describe the train at the
     * board/origin station, whereas the UI needs the arrival at station B.
     */
    fun destinationPoint(service: RawService): CallingPoint? {
        val target = destination ?: return null
        return service.subsequentCallingPoints.firstOrNull { point ->
            if (!point.isPassengerStop) return@firstOrNull false
            val pointCrs = point.crs?.trim()?.uppercase()
            if (target.crs == londonAny.crs) {
                pointCrs in london || normaliseLocationName(point.locationName) in londonNames
            } else {
                pointCrs == target.crs.uppercase() ||
                    normaliseLocationName(point.locationName) == normaliseLocationName(target.name)
            }
        }
    }

    fun originCrsSet(): Set<String> = if (origin.crs == londonAny.crs) londonOrigin else setOf(origin.crs)
    companion object {
        // Direct services calling at any of these central London stations; not a fares group.
        val london = setOf("KGX", "STP", "SPX", "EUS", "PAD", "MYB", "LST", "FST", "VIC", "WAT", "WAE", "CHX", "CST", "LBG", "BFR", "CTK", "MOG", "OLD", "VXH", "ZFD")
        val londonOrigin = setOf("KGX", "STP", "SPX")
        val londonNames = setOf(
            "london kings cross", "kings cross", "london st pancras international",
            "st pancras international", "st pancras", "london bridge", "london blackfriars",
            "city thameslink", "farringdon", "london euston", "london paddington",
            "london marylebone", "london liverpool street", "london fenchurch street",
            "london victoria", "london waterloo", "london waterloo east", "london charing cross",
            "london cannon street", "moorgate", "old street", "vauxhall"
        )
        val londonAny = StationRef("LON", "London (Any)")
    }
}

private fun normaliseLocationName(value: String): String = value.lowercase()
    .replace("’", "'")
    .replace("'", "")
    .replace(Regex("[^a-z0-9]+"), " ")
    .trim()

fun railwayInstant(value: String?): Instant? = value?.let {
    runCatching { Instant.parse(it) }.getOrNull()
        ?: runCatching { OffsetDateTime.parse(it).toInstant() }.getOrNull()
        ?: runCatching { LocalDateTime.parse(it).atZone(ZoneId.of("Europe/London")).toInstant() }.getOrNull()
}
fun shortTime(value: String?): String = value?.let {
    if (it.contains('T')) it.substringAfter('T').take(5) else it
} ?: "—"
fun ageText(at: Long?, now: Long): String {
    if (at == null) return "never"
    val seconds = ((now - at).coerceAtLeast(0) / 1000)
    return if (seconds < 60) "${seconds}s ago" else if (seconds < 3600) "${seconds / 60}m ${seconds % 60}s ago" else "${seconds / 3600}h ${seconds % 3600 / 60}m ago"
}
enum class LiveState { LIVE, STALE, OFFLINE }
fun liveState(
    lastSuccess: Long?,
    generatedAt: String?,
    now: Long,
    failed: Boolean,
    offline: Boolean,
    pollIntervalSeconds: Int = 15
): LiveState {
    val intervalMs = pollIntervalSeconds.coerceIn(15, 60) * 1_000L
    val fetchStaleAfter = maxOf(30_000L, intervalMs * 2)
    val railwayStaleAfter = maxOf(60_000L, intervalMs * 2)
    return when {
        offline || lastSuccess == null -> LiveState.OFFLINE
        failed || now - lastSuccess > fetchStaleAfter || railwayInstant(generatedAt)?.let { now - it.toEpochMilli() > railwayStaleAfter } != false -> LiveState.STALE
        else -> LiveState.LIVE
    }
}
fun stableServices(old: List<RawService>, fresh: List<RawService>): List<RawService> {
    val byId = fresh.associateBy { it.identity }.toMutableMap()
    return old.mapNotNull { byId.remove(it.identity) } + byId.values.sortedBy { it.scheduledDeparture }
}

/** Public serviceID is not a RID. Never equate opaque IDs or match by time alone. */
fun comparatorMatch(staff: RawService, public: List<RawService>, staffPeers: List<RawService>): RawService? {
    staff.rid?.let { rid -> public.filter { it.rid == rid }.singleOrNull()?.let { return it } }
    fun same(a: RawService, b: RawService) = a.operatorCode != null && a.operatorCode == b.operatorCode &&
        a.scheduledDeparture != null && shortTime(a.scheduledDeparture) == shortTime(b.scheduledDeparture) &&
        a.destinationCrs.isNotEmpty() && a.destinationCrs.toSet() == b.destinationCrs.toSet()
    val match = public.filter { same(staff, it) }.singleOrNull() ?: return null
    return match.takeIf { staffPeers.count { s -> same(s, match) } == 1 }
}

fun changes(old: RawService?, current: RawService, at: Instant): List<RailEvent> = buildList {
    fun addEvent(type: RailEventType, before: String?, after: String?) { add(RailEvent(current.identity, at, type, before, after)) }
    val oldPlatform = old?.normalizedPlatform
    val currentPlatform = current.normalizedPlatform
    if (old == null) addEvent(RailEventType.FIRST_SEEN, null, currentPlatform)
    if (currentPlatform != oldPlatform) addEvent(if (oldPlatform == null) RailEventType.PLATFORM_SET else RailEventType.PLATFORM_CHANGED, oldPlatform, currentPlatform)

    // Platform concealment and whole-service suppression are distinct Darwin states.
    fun platformHidden(s: RawService) = s.platformIsHidden == true || s.stationPlatformsAreHidden == true
    fun platformVisible(s: RawService) = s.normalizedPlatform != null && s.platformIsHidden == false && s.stationPlatformsAreHidden == false && s.serviceIsSuppressed == false
    if (old != null && !platformHidden(old) && platformHidden(current)) addEvent(RailEventType.PLATFORM_HIDDEN, oldPlatform, currentPlatform)
    // A Staff release is any transition from a known raw platform that was not safely displayable
    // (hidden, suppressed or visibility flags unknown) to an explicitly displayable platform.
    if (old != null && oldPlatform != null && !platformVisible(old) && platformVisible(current)) {
        addEvent(RailEventType.PLATFORM_RELEASED, oldPlatform, currentPlatform)
    }
    if (old != null && old.serviceIsSuppressed != true && current.serviceIsSuppressed == true) addEvent(RailEventType.SERVICE_SUPPRESSED, old.serviceIsSuppressed?.toString(), "true")
    if (old != null && old.serviceIsSuppressed == true && current.serviceIsSuppressed == false) addEvent(RailEventType.SERVICE_UNSUPPRESSED, "true", "false")

    if (current.isCancelled && old?.isCancelled != true) addEvent(RailEventType.CANCELLED, old?.isCancelled?.toString(), "true")
    if (old?.isCancelled == true && !current.isCancelled) addEvent(RailEventType.REINSTATED, "true", "false")
    if (current.futureDelay == true && old?.futureDelay != true) addEvent(RailEventType.FUTURE_DELAY, old?.futureDelay?.toString(), "true")
    if (current.futureCancellation == true && old?.futureCancellation != true) addEvent(RailEventType.FUTURE_CANCELLATION, old?.futureCancellation?.toString(), "true")
    if (old != null && old.expectedDeparture != current.expectedDeparture) addEvent(RailEventType.EXPECTED_TIME_CHANGED, old.expectedDeparture, current.expectedDeparture)
}
