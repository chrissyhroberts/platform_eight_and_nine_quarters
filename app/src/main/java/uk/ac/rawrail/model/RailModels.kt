package uk.ac.rawrail.model

import java.time.Instant
import kotlinx.serialization.Serializable

@Serializable
data class StationRef(
    val crs: String,
    val name: String,
) {
    val searchText: String get() = "$name $crs".lowercase()
}

@Serializable
data class CallingPoint(
    val locationName: String,
    val crs: String? = null,
    val tiploc: String? = null,
    val scheduledTime: String? = null,
    val estimatedTime: String? = null,
    val actualTime: String? = null,
    val platform: String? = null,
    val isOperational: Boolean? = null,
    val isPass: Boolean? = null,
    val isCancelled: Boolean = false,
    val length: Int? = null,
    val detachFront: Boolean = false,
    val cancelReason: String? = null,
    val cancelReasonCode: Int? = null,
    val cancelReasonTiploc: String? = null,
    val cancelReasonNear: Boolean? = null,
    val delayReason: String? = null,
    val delayReasonCode: Int? = null,
    val delayReasonTiploc: String? = null,
    val delayReasonNear: Boolean? = null,
    val alerts: List<String> = emptyList(),
) {
    /** Staff-only timing points and pass locations are not passenger stops.
     *
     * Do not require a CRS here. Staff detail payloads can omit CRS on a genuine passenger
     * calling point; StationDirectory can enrich it later from the name/TIPLOC. Requiring a CRS
     * caused valid route matches (notably London -> Royston) to disappear before enrichment.
     */
    val isPassengerStop: Boolean
        get() = isOperational != true && isPass != true
}

@Serializable
data class RawService(
    val serviceId: String,
    val rid: String? = null,
    val destinationCrs: List<String> = emptyList(),
    val futureDelay: Boolean? = null,
    val futureCancellation: Boolean? = null,
    val arrivalSource: String? = null,
    val departureSource: String? = null,
    val coachLoading: List<String> = emptyList(),
    val loading: String? = null,
    val uid: String? = null,
    val trainId: String? = null,
    val retailServiceId: String? = null,
    val operatorName: String? = null,
    val operatorCode: String? = null,
    val serviceType: String? = null,

    val scheduledArrival: String? = null,
    val expectedArrival: String? = null,
    val actualArrival: String? = null,
    val scheduledDeparture: String? = null,
    val expectedDeparture: String? = null,
    val actualDeparture: String? = null,

    val origins: List<String> = emptyList(),
    val destinations: List<String> = emptyList(),
    val currentOrigins: List<String> = emptyList(),
    val currentDestinations: List<String> = emptyList(),

    val rawPlatform: String? = null,
    val platformIsHidden: Boolean? = null,
    val stationPlatformsAreHidden: Boolean? = null,
    val serviceIsSuppressed: Boolean? = null,

    val isCancelled: Boolean = false,
    val isLateReinstated: Boolean = false,
    val isDeleted: Boolean = false,
    val filterLocationCancelled: Boolean = false,

    val length: Int? = null,
    val detachFront: Boolean = false,
    val reverseFormation: Boolean = false,
    val formationText: String? = null,

    val cancelReason: String? = null,
    val cancelReasonCode: Int? = null,
    val cancelReasonTiploc: String? = null,
    val cancelReasonNear: Boolean? = null,
    val delayReason: String? = null,
    val delayReasonCode: Int? = null,
    val delayReasonTiploc: String? = null,
    val delayReasonNear: Boolean? = null,
    val overdueMessage: String? = null,
    val uncertainty: String? = null,
    val diversionReason: String? = null,
    val diversionReasonCode: Int? = null,
    val diversionReasonTiploc: String? = null,
    val diversionReasonNear: Boolean? = null,
    val divertedVia: String? = null,
    val rerouteDelay: Int? = null,

    val alerts: List<String> = emptyList(),
    val previousCallingPoints: List<CallingPoint> = emptyList(),
    val subsequentCallingPoints: List<CallingPoint> = emptyList(),

    val generatedAt: String? = null,
) {
    val identity: String get() = rid?.takeIf { it.isNotBlank() } ?: serviceId
    val normalizedPlatform: String?
        get() = rawPlatform?.trim()?.takeIf { it.isNotEmpty() }
    val publicPlatform: String?
        get() = normalizedPlatform.takeUnless {
            platformIsHidden != false || stationPlatformsAreHidden != false || serviceIsSuppressed != false
        }

    val displayDestination: String
        get() = (currentDestinations.ifEmpty { destinations }).joinToString(" / ")
            .ifBlank { "Unknown destination" }

    val displayOrigin: String
        get() = (currentOrigins.ifEmpty { origins }).joinToString(" / ")
            .ifBlank { "Unknown origin" }

    val bestDeparture: String?
        get() = actualDeparture ?: expectedDeparture ?: scheduledDeparture

    val liveStatus: String
        get() = when {
            isCancelled -> "Cancelled"
            actualDeparture != null -> "Departed $actualDeparture"
            expectedDeparture.equals("On time", ignoreCase = true) -> "On time"
            !expectedDeparture.isNullOrBlank() -> expectedDeparture
            overdueMessage != null -> overdueMessage
            else -> "No live estimate"
        }
}

@Serializable
data class StationBoard(
    val crs: String,
    val stationName: String,
    val generatedAt: String?,
    val stationManager: String? = null,
    val stationManagerCode: String? = null,
    val filterLocationName: String? = null,
    val filterCrs: String? = null,
    val platformAvailable: Boolean? = null,
    val areServicesAvailable: Boolean? = null,
    val qos: Double? = null,
    val platformsAreHidden: Boolean? = null,
    val rawPayload: String = "",
    val truncated: Boolean = false,
    val nrccMessages: List<String> = emptyList(),
    val services: List<RawService>,
)

enum class RailEventType {
    FIRST_SEEN,
    PLATFORM_SET,
    PLATFORM_CHANGED,
    PLATFORM_HIDDEN,
    PLATFORM_RELEASED,
    SERVICE_SUPPRESSED,
    SERVICE_UNSUPPRESSED,
    EXPECTED_TIME_CHANGED,
    CANCELLED,
    REINSTATED,
    FUTURE_DELAY,
    FUTURE_CANCELLATION,
    PUBLIC_PLATFORM_OBSERVED
}

data class RailEvent(
    val serviceId: String,
    val observedAt: Instant,
    val type: RailEventType,
    val previous: String?,
    val current: String?,
)
