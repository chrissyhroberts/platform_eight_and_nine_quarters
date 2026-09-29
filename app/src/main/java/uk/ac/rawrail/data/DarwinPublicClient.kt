package uk.ac.rawrail.data

import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import uk.ac.rawrail.model.CallingPoint
import uk.ac.rawrail.model.RawService
import uk.ac.rawrail.model.StationBoard
import uk.ac.rawrail.security.AuthMode
import uk.ac.rawrail.security.DarwinConnection

class DarwinPublicClient(
    private val http: OkHttpClient = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false).callTimeout(12, java.util.concurrent.TimeUnit.SECONDS).build()
) {
    data class ReasonDescription(
        val code: Int,
        val lateReason: String? = null,
        val cancellationReason: String? = null,
    )

    suspend fun departures(
        crs: String,
        connection: DarwinConnection,
        numRows: Int = 149,
        startOffsetMinutes: Int = 0,
        timeWindowMinutes: Int = 119,
    ): StationBoard = withContext(Dispatchers.IO) {
        val cleanCrs = crs.trim().uppercase()
        require(cleanCrs.matches(Regex("[A-Z]{3}"))) { "CRS must be three letters." }

        val endpoint = when (connection.authMode) {
            AuthMode.RDM_API_KEY -> connection.boardEndpointTemplate
            AuthMode.LEGACY_BASIC ->
                "https://realtime.nationalrail.co.uk/LDBWS/api/20220120/GetDepBoardWithDetails/{crs}"
        }
        require(endpoint.contains("{crs}")) { "Board endpoint must contain {crs}." }

        val usesExplicitTimePath = endpoint.contains("{time}")
        val detailRowLimited = endpoint.contains("GetDepBoardWithDetails", ignoreCase = true)
        val londonNow = java.time.OffsetDateTime.now(java.time.ZoneId.of("Europe/London"))
            .plusMinutes(startOffsetMinutes.toLong())
        val expanded = endpoint
            .replace("{crs}", cleanCrs)
            .replace("{time}", londonNow.format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss")))
        val checked = expanded.toHttpUrl()
        require(checked.isHttps && checked.host in setOf("api1.raildata.org.uk", "realtime.nationalrail.co.uk")) { "Use an official HTTPS railway endpoint." }
        val safeRows = if (detailRowLimited) numRows.coerceIn(1, 9) else numRows.coerceIn(1, 149)
        val safeWindow = timeWindowMinutes.coerceIn(1, 119)
        val builder = expanded
            .toHttpUrl()
            .newBuilder()
            .addQueryParameter("numRows", safeRows.toString())
            .addQueryParameter("timeWindow", safeWindow.toString())
        // Staff LDBSVWS uses the explicit {time} path parameter. Public LDBWS
        // instead uses timeOffset relative to now. Do not send the public-only
        // offset parameter to the Staff operation.
        if (!usesExplicitTimePath) {
            builder.addQueryParameter("timeOffset", startOffsetMinutes.coerceIn(-119, 119).toString())
        }
        val url = builder.build()

        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/json")
            .header("User-Agent", "Platform-8-9-4-Android/0.20.1")
            .apply {
                when (connection.authMode) {
                    AuthMode.RDM_API_KEY -> {
                        require(connection.apiKey.isNotBlank()) { "RDM API key is missing." }
                        header("x-apikey", connection.apiKey)
                    }
                    AuthMode.LEGACY_BASIC -> {
                        require(connection.username.isNotBlank() && connection.password.isNotBlank()) {
                            "Legacy National Rail credentials are missing."
                        }
                        val raw = "${connection.username}:${connection.password}"
                        val auth = Base64.encodeToString(
                            raw.toByteArray(Charsets.UTF_8), Base64.NO_WRAP
                        )
                        header("Authorization", "Basic $auth")
                    }
                }
            }
            .build()

        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                error("Darwin HTTP ${response.code}: ${response.message}")
            }
            parseBoard(response.body.string(), cleanCrs)
        }
    }

    suspend fun test(connection: DarwinConnection): Result<Unit> = runCatching {
        departures("EUS", connection, 1)
        Unit
    }

    suspend fun reasonCodeList(connection: DarwinConnection): Map<Int, ReasonDescription> =
        withContext(Dispatchers.IO) {
            require(connection.authMode == AuthMode.RDM_API_KEY) { "Reason-code lookup requires an RDM API key." }
            require(connection.apiKey.isNotBlank()) { "RDM API key is missing." }
            val prefix = connection.boardEndpointTemplate.substringBefore("/api/", missingDelimiterValue = "")
            require(prefix.isNotBlank()) { "Staff endpoint must contain /api/." }
            val url = "$prefix/api/20220120/GetReasonCodeList".toHttpUrl()
            require(url.isHttps && url.host in setOf("api1.raildata.org.uk", "realtime.nationalrail.co.uk")) {
                "Use an official HTTPS railway endpoint."
            }
            val request = Request.Builder()
                .url(url)
                .header("Accept", "application/json")
                .header("User-Agent", "Platform-8-9-4-Android/0.20.1")
                .header("x-apikey", connection.apiKey)
                .build()
            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) error("Darwin reason-code HTTP ${response.code}: ${response.message}")
                parseReasonCodeList(response.body.string())
            }
        }

    fun parseReasonCodeList(json: String): Map<Int, ReasonDescription> {
        val root = Json { ignoreUnknownKeys = true }.parseToJsonElement(json)
        val items = when (root) {
            is JsonArray -> root
            is JsonObject -> listOf("reasons", "reasonCodes", "reason").firstNotNullOfOrNull { key ->
                root[key] as? JsonArray
            } ?: JsonArray(emptyList())
            else -> JsonArray(emptyList())
        }
        return items.mapNotNull { item ->
            val obj = item.jsonObjectOrNull() ?: return@mapNotNull null
            val code = int(obj, "code", "Value", "value") ?: return@mapNotNull null
            ReasonDescription(
                code = code,
                lateReason = string(obj, "lateReason", "delayReason"),
                cancellationReason = string(obj, "cancReason", "cancelReason", "cancellationReason"),
            )
        }.associateBy { it.code }
    }

    fun parseBoard(json: String, requestedCrs: String): StationBoard {
        val root = Json { ignoreUnknownKeys = true }.parseToJsonElement(json).jsonObject
        val platformsHidden = bool(root, "platformsAreHidden")

        val services = array(root, "trainServices")
            .mapIndexed { index, node ->
                parseService(
                    node.jsonObject,
                    requestedCrs,
                    root["generatedAt"]?.jsonPrimitive?.contentOrNull,
                    platformsHidden,
                    index
                )
            }

        return StationBoard(
            rawPayload = json,
            truncated = bool(root, "isTruncated") == true,
            crs = string(root, "crs") ?: requestedCrs,
            stationName = string(root, "locationName") ?: requestedCrs,
            generatedAt = string(root, "generatedAt"),
            stationManager = string(root, "stationManager"),
            stationManagerCode = string(root, "stationManagerCode"),
            filterLocationName = string(root, "filterLocationName"),
            filterCrs = string(root, "filtercrs", "filterCrs"),
            platformAvailable = bool(root, "platformAvailable"),
            areServicesAvailable = bool(root, "areServicesAvailable") ?: bool(root, "servicesAreUnavailable")?.not(),
            qos = double(root, "qos"),
            platformsAreHidden = platformsHidden,
            nrccMessages = textList(root["nrccMessages"]),
            services = services
        )
    }

    private fun parseService(
        obj: JsonObject,
        crs: String,
        generatedAt: String?,
        stationPlatformsHidden: Boolean?,
        index: Int
    ): RawService {
        val cancelReason = reasonData(obj["cancelReason"])
        val delayReason = reasonData(obj["delayReason"])
        val diversionReason = reasonData(obj["diversionReason"])
            ?: reasonData(obj["diversion"]?.jsonObjectOrNull()?.get("reason"))
        return RawService(
            serviceId = string(obj, "rid", "serviceID", "serviceId")
                ?: "${crs}_${generatedAt?.take(10)}_${string(obj, "std")}_${string(obj, "operatorCode")}_${locations(obj["destination"]).joinToString()}",
            rid = string(obj, "rid"),
            destinationCrs = (obj["destination"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject)?.let { o -> string(o, "crs") } },
            futureDelay = bool(obj, "futureDelay"),
            futureCancellation = bool(obj, "futureCancellation"),
            arrivalSource = listOfNotNull(string(obj, "arrivalSource"), string(obj, "arrivalSourceInstance")).joinToString(" / ").ifBlank { null },
            departureSource = listOfNotNull(string(obj, "departureSource"), string(obj, "departureSourceInstance")).joinToString(" / ").ifBlank { null },
            loading = (obj["formation"] as? JsonObject)?.get("serviceLoading")?.let { (it as? JsonObject)?.get("loadingPercentage") }?.let { (it as? JsonObject)?.let { o -> "${string(o, "Value") ?: "—"}% ${string(o, "type") ?: ""}" } },
            coachLoading = ((obj["formation"] as? JsonObject)?.get("coaches") as? JsonArray).orEmpty().mapNotNull { c ->
                (c as? JsonObject)?.let { o ->
                    val v = if (bool(o, "loadingSpecified") == false) null else (o["loading"] as? JsonObject)?.let { string(it, "Value") } ?: (o["loading"] as? JsonPrimitive)?.contentOrNull
                    "${string(o, "number") ?: "?"}: ${v?.let { "$it%" } ?: "—"} ${string(o, "coachClass") ?: ""}"
                }
            },
            uid = string(obj, "uid"),
            trainId = string(obj, "trainid", "trainId"),
            retailServiceId = string(obj, "rsid"),
            operatorName = string(obj, "operator"),
            operatorCode = string(obj, "operatorCode"),
            serviceType = string(obj, "serviceType"),

            scheduledArrival = if (bool(obj, "staSpecified") == false) null else string(obj, "sta"),
            expectedArrival = if (bool(obj, "etaSpecified") == false) null else string(obj, "eta"),
            actualArrival = if (bool(obj, "ataSpecified") == false) null else string(obj, "ata"),
            scheduledDeparture = if (bool(obj, "stdSpecified") == false) null else string(obj, "std"),
            expectedDeparture = if (bool(obj, "etdSpecified") == false) null else string(obj, "etd"),
            actualDeparture = if (bool(obj, "atdSpecified") == false) null else string(obj, "atd"),

            origins = locations(obj["origin"]),
            destinations = locations(obj["destination"]),
            currentOrigins = locations(obj["currentOrigins"]),
            currentDestinations = locations(obj["currentDestinations"]),

            rawPlatform = string(obj, "platform"),
            platformIsHidden = bool(obj, "platformIsHidden"),
            stationPlatformsAreHidden = stationPlatformsHidden,
            serviceIsSuppressed = bool(obj, "serviceIsSupressed", "serviceIsSuppressed"),

            isCancelled = bool(obj, "isCancelled") ?: false,
            isLateReinstated = bool(obj, "isLateReinstated") ?: false,
            isDeleted = bool(obj, "isDeleted") ?: false,
            filterLocationCancelled = bool(obj, "filterLocationCancelled") ?: false,

            length = int(obj, "length"),
            detachFront = bool(obj, "detachFront") ?: false,
            reverseFormation = bool(obj, "isReverseFormation") ?: false,
            formationText = obj["formation"]?.toString(),

            cancelReason = cancelReason?.text,
            cancelReasonCode = cancelReason?.code,
            cancelReasonTiploc = cancelReason?.tiploc,
            cancelReasonNear = cancelReason?.near,
            delayReason = delayReason?.text,
            delayReasonCode = delayReason?.code,
            delayReasonTiploc = delayReason?.tiploc,
            delayReasonNear = delayReason?.near,
            overdueMessage = string(obj, "overdueMessage"),
            uncertainty = obj["uncertainty"]?.toString()?.trim('"'),
            diversionReason = diversionReason?.text,
            diversionReasonCode = diversionReason?.code,
            diversionReasonTiploc = diversionReason?.tiploc,
            diversionReasonNear = diversionReason?.near,
            divertedVia = string(obj, "divertedVia")
                ?: obj["diversion"]?.jsonObjectOrNull()?.get("divertedVia")
                    ?.jsonObjectOrNull()?.let { string(it, "Value", "value") },
            rerouteDelay = int(obj, "rerouteDelay")
                ?: obj["diversion"]?.jsonObjectOrNull()?.let { int(it, "rerouteDelay") },

            alerts = textList(obj["adhocAlerts"]),
            previousCallingPoints = callingPoints(obj["previousCallingPoints"] ?: obj["previousLocations"]),
            subsequentCallingPoints = callingPoints(obj["subsequentCallingPoints"] ?: obj["subsequentLocations"]),
            generatedAt = generatedAt
        )
    }

    private fun callingPoints(element: JsonElement?): List<CallingPoint> {
        val groups = when (element) {
            is JsonArray -> element
            is JsonObject -> JsonArray(listOf(element))
            else -> return emptyList()
        }
        return groups.flatMap { group ->
            val g = group.jsonObjectOrNull() ?: return@flatMap emptyList()
            val cp = g["callingPoint"] ?: g["location"] ?: if (g.containsKey("locationName")) g else null
            when (cp) {
                is JsonArray -> cp.mapNotNull { parseCallingPoint(it.jsonObjectOrNull()) }
                is JsonObject -> listOfNotNull(parseCallingPoint(cp))
                else -> emptyList()
            }
        }
    }

    private fun parseCallingPoint(o: JsonObject?): CallingPoint? {
        o ?: return null
        val tiploc = string(o, "tiploc", "fullTiploc")
        val name = string(o, "locationName", "name") ?: tiploc ?: return null
        val cancelReason = reasonData(o["cancelReason"])
        val delayReason = reasonData(o["delayReason"])
        return CallingPoint(
            locationName = name,
            crs = string(o, "crs", "locationCrs"),
            tiploc = tiploc,
            scheduledTime = string(o, "st", "std", "sta"),
            estimatedTime = string(o, "et", "etd", "eta"),
            actualTime = string(o, "at", "atd", "ata"),
            platform = string(o, "platform"),
            isOperational = bool(o, "isOperational", "isOperationalCall"),
            isPass = bool(o, "isPass"),
            isCancelled = bool(o, "isCancelled") ?: false,
            length = int(o, "length"),
            detachFront = bool(o, "detachFront") ?: false,
            cancelReason = cancelReason?.text,
            cancelReasonCode = cancelReason?.code,
            cancelReasonTiploc = cancelReason?.tiploc,
            cancelReasonNear = cancelReason?.near,
            delayReason = delayReason?.text,
            delayReasonCode = delayReason?.code,
            delayReasonTiploc = delayReason?.tiploc,
            delayReasonNear = delayReason?.near,
            alerts = textList(o["adhocAlerts"])
        )
    }

    private fun locations(element: JsonElement?): List<String> {
        return when (element) {
            is JsonArray -> element.mapNotNull { locationName(it) }
            is JsonObject -> listOfNotNull(locationName(element))
            else -> emptyList()
        }
    }

    private fun locationName(element: JsonElement): String? {
        val o = element.jsonObjectOrNull() ?: return element.jsonPrimitiveOrNull()?.contentOrNull
        return string(o, "locationName", "name", "Value")
    }

    private fun textList(element: JsonElement?): List<String> {
        if (element == null || element is JsonNull) return emptyList()
        return when (element) {
            is JsonArray -> element.flatMap(::textList)
            is JsonPrimitive -> listOfNotNull(element.contentOrNull)
            is JsonObject -> {
                val preferred = listOf(
                    "value", "Value", "text", "message", "AdhocAlertTextType",
                    "nrccMessage"
                ).firstNotNullOfOrNull { k -> element[k] }
                if (preferred != null) textList(preferred)
                else element.values.flatMap(::textList)
            }
            else -> emptyList()
        }.map { it.replace(Regex("<[^>]+>"), " ").replace(Regex("\\s+"), " ").trim() }
            .filter { it.isNotBlank() }
            .distinct()
    }

    private data class ReasonData(
        val code: Int? = null,
        val text: String? = null,
        val tiploc: String? = null,
        val near: Boolean? = null,
    )

    private fun reasonData(element: JsonElement?): ReasonData? {
        if (element == null || element is JsonNull) return null
        if (element is JsonPrimitive) {
            return element.intOrNull?.let { ReasonData(code = it) }
                ?: element.contentOrNull?.let { ReasonData(text = it) }
        }
        val o = element.jsonObjectOrNull() ?: return null
        val code = int(o, "Value", "value", "code", "reasonCode")
        val text = string(o, "reasonText", "lateReason", "cancReason", "description", "reason")
        val tiploc = string(o, "tiploc", "TIPLOC")
        val near = bool(o, "near")
        return ReasonData(code = code, text = text, tiploc = tiploc, near = near)
            .takeIf { it.code != null || !it.text.isNullOrBlank() || !it.tiploc.isNullOrBlank() || it.near != null }
    }

    private fun string(o: JsonObject, vararg keys: String): String? =
        keys.firstNotNullOfOrNull { k ->
            o[k]?.jsonPrimitiveOrNull()?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
        }

    private fun bool(o: JsonObject, vararg keys: String): Boolean? =
        keys.firstNotNullOfOrNull { k -> o[k]?.jsonPrimitiveOrNull()?.booleanOrNull }

    private fun int(o: JsonObject, vararg keys: String): Int? =
        keys.firstNotNullOfOrNull { k -> o[k]?.jsonPrimitiveOrNull()?.intOrNull }

    private fun double(o: JsonObject, vararg keys: String): Double? =
        keys.firstNotNullOfOrNull { k -> o[k]?.jsonPrimitiveOrNull()?.doubleOrNull }

    private fun array(o: JsonObject, key: String): JsonArray =
        when (val e = o[key]) {
            is JsonArray -> e
            else -> JsonArray(emptyList())
        }

    private fun JsonElement.jsonObjectOrNull(): JsonObject? = this as? JsonObject
    private fun JsonElement.jsonPrimitiveOrNull(): JsonPrimitive? = this as? JsonPrimitive
}
