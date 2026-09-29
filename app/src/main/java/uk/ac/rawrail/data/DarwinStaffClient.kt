package uk.ac.rawrail.data

import android.os.SystemClock
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import uk.ac.rawrail.model.CallingPoint
import uk.ac.rawrail.model.RawService
import uk.ac.rawrail.model.StationBoard
import uk.ac.rawrail.security.AuthMode
import uk.ac.rawrail.security.DarwinConnection
import java.util.concurrent.ConcurrentHashMap

/**
 * Staff LDBSVWS has a much smaller row limit for GetDepBoardWithDetails than the
 * ordinary departure board. A busy station can therefore return only the first
 * few departures even though the requested time window is much longer. That was
 * why routes such as Cambridge -> Meldreth showed the immediate train but not the
 * following half-hourly/hourly services.
 *
 * Keep the frequent poll cheap (one request), but periodically build a complete
 * ~2-hour detailed horizon. If Darwin reports a window as truncated, split only
 * that window until the returned leaf windows are small enough not to truncate.
 * The horizon is cached for 15 minutes and the current poll always overrides its
 * cached copy of a service. This avoids turning a 15/30/60-second live cadence
 * into a burst of many requests on every poll.
 */
class DarwinStaffClient(private val client: DarwinPublicClient = DarwinPublicClient()) {
    private data class HorizonCache(
        val refreshedElapsed: Long,
        val connectionFingerprint: Int,
        val board: StationBoard,
    )

    private val horizons = ConcurrentHashMap<String, HorizonCache>()
    private val reasonMutex = Mutex()
    @Volatile private var reasonCache: ReasonCache? = null
    @Volatile private var nextReasonRetryElapsed: Long = 0L

    private data class ReasonCache(
        val refreshedElapsed: Long,
        val connectionFingerprint: Int,
        val values: Map<Int, DarwinPublicClient.ReasonDescription>,
    )

    suspend fun departures(crs: String, connection: DarwinConnection): StationBoard {
        val current = client.departures(
            crs = crs,
            connection = connection.copy(
                authMode = AuthMode.RDM_API_KEY,
                apiKey = connection.staffApiKey,
                boardEndpointTemplate = connection.staffEndpointTemplate,
            ),
            numRows = STAFF_DETAIL_ROWS,
            startOffsetMinutes = 0,
            timeWindowMinutes = STAFF_HORIZON_MINUTES,
        )

        val now = SystemClock.elapsedRealtime()
        val fingerprint = 31 * connection.staffEndpointTemplate.hashCode() + connection.staffApiKey.hashCode()
        val cached = horizons[crs]
        val horizon = if (
            cached == null ||
            cached.connectionFingerprint != fingerprint ||
            now - cached.refreshedElapsed >= HORIZON_REFRESH_MS
        ) {
            val leaves = if (likelyRowLimited(current)) {
                fetchCompleteWindow(
                    crs = crs,
                    connection = connection,
                    startOffsetMinutes = 0,
                    windowMinutes = STAFF_HORIZON_MINUTES,
                    prefetched = current,
                )
            } else {
                listOf(current)
            }
            mergeBoards(leaves).also { merged ->
                horizons[crs] = HorizonCache(now, fingerprint, merged)
            }
        } else {
            cached.board
        }

        // Live/current data wins for duplicate RIDs; cached horizon contributes only
        // services not present in the small row-limited current response.
        val merged = mergeBoards(listOf(horizon, current), preferLaterBoard = true)
            .copy(truncated = horizon.truncated)
        return enrichReasonDescriptions(merged, reasonDescriptions(connection))
    }

    private suspend fun reasonDescriptions(
        connection: DarwinConnection,
    ): Map<Int, DarwinPublicClient.ReasonDescription> {
        val now = SystemClock.elapsedRealtime()
        val fingerprint = 31 * connection.staffEndpointTemplate.hashCode() + connection.staffApiKey.hashCode()
        reasonCache?.takeIf {
            it.connectionFingerprint == fingerprint && now - it.refreshedElapsed < REASON_CACHE_MS
        }?.let { return it.values }
        if (now < nextReasonRetryElapsed) return reasonCache?.values.orEmpty()

        return reasonMutex.withLock {
            val lockedNow = SystemClock.elapsedRealtime()
            reasonCache?.takeIf {
                it.connectionFingerprint == fingerprint && lockedNow - it.refreshedElapsed < REASON_CACHE_MS
            }?.let { return@withLock it.values }
            if (lockedNow < nextReasonRetryElapsed) return@withLock reasonCache?.values.orEmpty()

            val staffConnection = connection.copy(
                authMode = AuthMode.RDM_API_KEY,
                apiKey = connection.staffApiKey,
                boardEndpointTemplate = connection.staffEndpointTemplate,
            )
            runCatching { client.reasonCodeList(staffConnection) }
                .onSuccess { values ->
                    reasonCache = ReasonCache(lockedNow, fingerprint, values)
                    nextReasonRetryElapsed = 0L
                }
                .onFailure {
                    nextReasonRetryElapsed = lockedNow + REASON_RETRY_MS
                }
                .getOrElse { reasonCache?.values.orEmpty() }
        }
    }

    private suspend fun fetchCompleteWindow(
        crs: String,
        connection: DarwinConnection,
        startOffsetMinutes: Int,
        windowMinutes: Int,
        prefetched: StationBoard? = null,
    ): List<StationBoard> {
        val board = prefetched ?: client.departures(
            crs = crs,
            connection = connection.copy(
                authMode = AuthMode.RDM_API_KEY,
                apiKey = connection.staffApiKey,
                boardEndpointTemplate = connection.staffEndpointTemplate,
            ),
            numRows = STAFF_DETAIL_ROWS,
            startOffsetMinutes = startOffsetMinutes,
            timeWindowMinutes = windowMinutes,
        )
        if (!likelyRowLimited(board) || windowMinutes <= MIN_SPLIT_WINDOW_MINUTES) return listOf(board)

        val first = windowMinutes / 2
        val second = windowMinutes - first
        return fetchCompleteWindow(crs, connection, startOffsetMinutes, first) +
            fetchCompleteWindow(crs, connection, startOffsetMinutes + first, second)
    }

    private fun likelyRowLimited(board: StationBoard): Boolean =
        board.truncated || board.services.size >= STAFF_DETAIL_ROWS

    private fun mergeBoards(
        boards: List<StationBoard>,
        preferLaterBoard: Boolean = false,
    ): StationBoard {
        require(boards.isNotEmpty())
        val base = boards.first()
        val byId = LinkedHashMap<String, RawService>()
        boards.forEach { board ->
            board.services.forEach { service ->
                if (preferLaterBoard || service.identity !in byId) byId[service.identity] = service
            }
        }
        val services = byId.values.sortedWith(compareBy<RawService>({ clockSortKey(it.scheduledDeparture) }, { it.identity }))
        return base.copy(
            generatedAt = boards.mapNotNull { it.generatedAt }.maxOrNull() ?: base.generatedAt,
            truncated = boards.any { it.truncated },
            platformsAreHidden = boards.any { it.platformsAreHidden == true },
            services = services,
        )
    }

    private fun clockSortKey(value: String?): Int {
        val short = shortTime(value)
        val match = Regex("^(\\d{2}):(\\d{2})$").matchEntire(short) ?: return Int.MAX_VALUE
        return match.groupValues[1].toInt() * 60 + match.groupValues[2].toInt()
    }

    companion object {
        // OpenLDBSVWS GetDepBoardWithDetails accepts fewer than 10 rows and a
        // timeWindow smaller than 120 minutes. Keep requests inside that contract.
        private const val STAFF_DETAIL_ROWS = 9
        private const val STAFF_HORIZON_MINUTES = 119
        private const val MIN_SPLIT_WINDOW_MINUTES = 15
        private const val HORIZON_REFRESH_MS = 15 * 60 * 1_000L
        private const val REASON_CACHE_MS = 24 * 60 * 60 * 1_000L
        private const val REASON_RETRY_MS = 15 * 60 * 1_000L
    }
}

internal fun enrichReasonDescriptions(
    board: StationBoard,
    reasons: Map<Int, DarwinPublicClient.ReasonDescription>,
): StationBoard {
    if (reasons.isEmpty()) return board

    fun CallingPoint.enriched(): CallingPoint = copy(
        delayReason = delayReason ?: delayReasonCode?.let { reasons[it]?.lateReason },
        cancelReason = cancelReason ?: cancelReasonCode?.let { reasons[it]?.cancellationReason },
    )

    return board.copy(services = board.services.map { service ->
        service.copy(
            delayReason = service.delayReason
                ?: service.delayReasonCode?.let { reasons[it]?.lateReason },
            cancelReason = service.cancelReason
                ?: service.cancelReasonCode?.let { reasons[it]?.cancellationReason },
            diversionReason = service.diversionReason
                ?: service.diversionReasonCode?.let { reasons[it]?.lateReason },
            previousCallingPoints = service.previousCallingPoints.map { it.enriched() },
            subsequentCallingPoints = service.subsequentCallingPoints.map { it.enriched() },
        )
    })
}
