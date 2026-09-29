package uk.ac.rawrail.data

import android.content.Context
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import okhttp3.Request
import uk.ac.rawrail.model.CallingPoint
import uk.ac.rawrail.model.RawService
import uk.ac.rawrail.model.StationBoard
import uk.ac.rawrail.model.StationRef
import uk.ac.rawrail.security.DarwinConnection
import java.io.File

class StationDirectory(
    private val context: Context,
    private val http: OkHttpClient = OkHttpClient.Builder()
        .followRedirects(false)
        .followSslRedirects(false)
        .callTimeout(12, java.util.concurrent.TimeUnit.SECONDS)
        .build()
) {
    private val cacheFile = File(context.filesDir, "stations.json")
    private val bundled: List<StationRef> = loadBundledStations()
    private var stations: List<StationRef> = mergeStations(
        bundled.ifEmpty { seedStations() },
        loadCache()
    )

    fun search(query: String, limit: Int = 30): List<StationRef> {
        val q = query.trim().lowercase()
        if (q.isBlank()) return stations.take(limit)

        val aliases = aliasStations(q)
        return (aliases + stations.asSequence()
            .map { s ->
                val score = when {
                    s.crs.equals(q, true) -> 0
                    s.name.equals(query.trim(), true) -> 1
                    s.name.lowercase().startsWith(q) -> 2
                    s.crs.lowercase().startsWith(q) -> 3
                    s.searchText.contains(q) -> 4
                    else -> 100
                }
                score to s
            }
            .filter { it.first < 100 }
            .sortedWith(compareBy<Pair<Int, StationRef>> { it.first }.thenBy { it.second.name })
            .take(limit)
            .map { it.second }
            .toList())
            .distinctBy { it.crs }
            .take(limit)
    }

    fun upsert(ref: StationRef) {
        if (ref.crs.length != 3 || ref.name.isBlank()) return
        val current = stations.firstOrNull { it.crs.equals(ref.crs, true) }
        if (current?.name == ref.name) return
        stations = mergeStations(stations, listOf(ref))
        saveCache()
    }

    /**
     * Learn human-readable CRS/name pairs exposed by Darwin. The bundled list is already complete
     * for current passenger stations, but this keeps newly-added stations useful before an app update.
     */
    fun learn(board: StationBoard): Boolean {
        val learned = linkedMapOf<String, StationRef>()
        fun consider(crs: String?, name: String?) {
            val code = crs?.trim()?.uppercase()?.takeIf { it.length == 3 } ?: return
            if (code == Route.londonAny.crs) return
            val label = name?.trim()?.takeIf(::looksLikeHumanName) ?: return
            val existing = stations.firstOrNull { it.crs == code }
            if (existing == null) learned[code] = StationRef(code, label)
        }

        consider(board.crs, board.stationName)
        board.services.forEach { service ->
            service.destinationCrs.zip(service.destinations).forEach { (crs, name) -> consider(crs, name) }
            (service.previousCallingPoints + service.subsequentCallingPoints).forEach { cp ->
                consider(cp.crs, cp.locationName)
            }
        }
        if (learned.isEmpty()) return false
        stations = mergeStations(stations, learned.values.toList())
        saveCache()
        return true
    }

    /** Replace feed codes with the local passenger-station name or a known operational location. */
    fun enrich(board: StationBoard): StationBoard {
        val byCrs = stations.associateBy { it.crs.uppercase() }
        fun point(cp: CallingPoint): CallingPoint {
            val stationName = cp.crs?.uppercase()?.let { byCrs[it]?.name }
            val operationalCode = cp.tiploc?.uppercase()
                ?: cp.locationName.trim().uppercase().takeIf { it.matches(Regex("[A-Z0-9]{4,10}")) }
            val operationalName = operationalCode?.let(operationalNames::get)
            return cp.copy(
                locationName = stationName ?: operationalName ?: cp.locationName,
                tiploc = cp.tiploc ?: operationalCode.takeIf { operationalName != null }
            )
        }
        fun service(s: RawService): RawService {
            val destinations = if (s.destinationCrs.size == s.destinations.size) {
                s.destinations.mapIndexed { i, raw -> byCrs[s.destinationCrs[i].uppercase()]?.name ?: raw }
            } else s.destinations
            return s.copy(
                destinations = destinations,
                previousCallingPoints = s.previousCallingPoints.map(::point),
                subsequentCallingPoints = s.subsequentCallingPoints.map(::point)
            )
        }
        return board.copy(
            stationName = byCrs[board.crs.uppercase()]?.name ?: board.stationName,
            services = board.services.map(::service)
        )
    }

    fun count(): Int = stations.size

    suspend fun syncFromReferenceData(connection: DarwinConnection): Result<Int> =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching {
                require(connection.stationListEndpoint.isNotBlank()) {
                    "Add the Reference Data GetStationList endpoint in Settings."
                }
                require(connection.stationListApiKey.isNotBlank()) {
                    "Add the Reference Data API key in Settings."
                }

                val endpoint = java.net.URI(connection.stationListEndpoint)
                require(endpoint.scheme == "https" && endpoint.host == "api1.raildata.org.uk") {
                    "Use the official HTTPS RDM station endpoint."
                }
                val request = Request.Builder()
                    .url(connection.stationListEndpoint)
                    .header("x-apikey", connection.stationListApiKey)
                    .header("Accept", "application/json")
                    .header("User-Agent", "Platform-8-9-4-Android/0.20.3")
                    .build()

                http.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) error("Reference data HTTP ${response.code}")
                    val root = Json { ignoreUnknownKeys = true }
                        .parseToJsonElement(response.body.string())

                    val found = mutableListOf<StationRef>()
                    collectStationObjects(root, found)
                    require(found.isNotEmpty()) { "No stations found in reference-data response." }

                    // Reference Data wins where supplied; the bundled snapshot remains a fallback.
                    stations = mergeStations(bundled, found)
                    saveCache()
                    stations.size
                }
            }
        }

    private fun collectStationObjects(node: JsonElement, out: MutableList<StationRef>) {
        when (node) {
            is JsonArray -> node.forEach { collectStationObjects(it, out) }
            is JsonObject -> {
                val crs = firstString(node, "crs", "CRS", "stationCrs", "stationCRS")
                val name = firstString(node, "description", "name", "locationName", "stationName", "Value")
                if (crs?.length == 3 && !name.isNullOrBlank()) out += StationRef(crs.uppercase(), name)
                node.values.forEach { collectStationObjects(it, out) }
            }
            else -> Unit
        }
    }

    private fun firstString(obj: JsonObject, vararg keys: String): String? =
        keys.firstNotNullOfOrNull { k -> obj[k]?.jsonPrimitive?.contentOrNull }

    private fun loadBundledStations(): List<StationRef> = runCatching {
        context.assets.open("gb_stations.csv").bufferedReader().useLines { lines ->
            lines.mapNotNull { line ->
                val value = line.trim()
                if (value.isBlank() || value.startsWith("#") || '|' !in value) return@mapNotNull null
                val (crs, name) = value.split('|', limit = 2)
                StationRef(crs.trim().uppercase(), name.trim())
                    .takeIf { it.crs.length == 3 && it.name.isNotBlank() }
            }.toList()
        }
    }.getOrDefault(emptyList())

    private fun loadCache(): List<StationRef> = runCatching {
        if (!cacheFile.exists()) return@runCatching emptyList()
        val root = Json.parseToJsonElement(cacheFile.readText()).jsonArray
        root.mapNotNull {
            val o = it.jsonObject
            val crs = o["crs"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val name = o["name"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            StationRef(crs.uppercase(), name)
        }
    }.getOrDefault(emptyList())

    private fun saveCache() {
        val json = buildJsonArray {
            stations.forEach {
                add(buildJsonObject {
                    put("crs", it.crs)
                    put("name", it.name)
                })
            }
        }
        cacheFile.writeText(json.toString())
    }

    private fun mergeStations(vararg groups: List<StationRef>): List<StationRef> {
        val byCrs = linkedMapOf<String, StationRef>()
        groups.forEach { group ->
            group.forEach { ref ->
                val code = ref.crs.trim().uppercase()
                if (code.length == 3 && ref.name.isNotBlank()) byCrs[code] = ref.copy(crs = code)
            }
        }
        // Migrate the two incorrect Cambridge mappings shipped in an early local seed.
        byCrs["CBN"] = StationRef("CBN", "Camborne")
        byCrs["CMB"] = StationRef("CMB", "Cambridge North")
        byCrs["CMS"] = StationRef("CMS", "Cambridge South")
        byCrs["KGX"] = StationRef("KGX", "London King's Cross")
        return byCrs.values.sortedBy { it.name }
    }

    private fun looksLikeHumanName(value: String): Boolean {
        val v = value.trim()
        if (v.isBlank()) return false
        if (v.matches(Regex("[A-Z0-9]{3,10}"))) return false
        return v.any { it.isLowerCase() } || v.contains(' ') || v.contains('-') || v.contains('&')
    }

    private fun aliasStations(q: String): List<StationRef> = when {
        q.contains("saffron") || q == "walden" -> listOf(StationRef("AUD", "Audley End (for Saffron Walden)"))
        q == "kingston" || q.contains("kingston upon thames") -> listOf(StationRef("KNG", "Kingston"))
        q.contains("stansted") -> listOf(StationRef("SSD", "Stansted Airport"), StationRef("SST", "Stansted Mountfitchet"))
        q.contains("canary") -> listOf(StationRef("CWX", "Canary Wharf"))
        q.contains("stratford") -> listOf(StationRef("SRA", "Stratford (London)"))
        q.contains("city airport") -> listOf(StationRef("LCY", "London City Airport"))
        else -> emptyList()
    }

    private fun seedStations() = listOf(
        StationRef("RYS", "Royston"),
        StationRef("FXN", "Foxton"),
        StationRef("STH", "Shepreth"),
        StationRef("MEL", "Meldreth"),
        StationRef("AWM", "Ashwell & Morden"),
        StationRef("BDK", "Baldock"),
        StationRef("LET", "Letchworth Garden City"),
        StationRef("HIT", "Hitchin"),
        StationRef("SVG", "Stevenage"),
        StationRef("KBW", "Knebworth"),
        StationRef("WGC", "Welwyn Garden City"),
        StationRef("WMG", "Welham Green"),
        StationRef("BPK", "Brookmans Park"),
        StationRef("PBR", "Potters Bar"),
        StationRef("FPK", "Finsbury Park"),
        StationRef("KGX", "London King's Cross"),
        StationRef("STP", "London St Pancras International"),
        StationRef("CBG", "Cambridge"),
        StationRef("CMS", "Cambridge South"),
        StationRef("CMB", "Cambridge North"),
        StationRef("WBC", "Waterbeach"),
        StationRef("ELY", "Ely"),
        StationRef("LTP", "Littleport"),
        StationRef("DOW", "Downham Market"),
        StationRef("WTG", "Watlington"),
        StationRef("KLN", "Kings Lynn")
    )

    companion object {
        /** Operational timing points do not have passenger CRS codes, so they are separate from the station dictionary. */
        private val operationalNames = mapOf(
            "DIGSWEL" to "Digswell",
            "WLMRGRN" to "Woolmer Green Junction",
            "SHPRTBJ" to "Shepreth Branch Junction",
            "CLDHMLJ" to "Coldham Lane Junction",
            "ELYYDLN" to "Ely Dock Junction"
        )
    }
}
