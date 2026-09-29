package uk.ac.rawrail.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.content.ContentValues
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import uk.ac.rawrail.model.*
import java.time.Instant

class ObservationStore(context: Context, name: String = "observations.db") : SQLiteOpenHelper(context, name, null, 1) {
    private val json = Json { ignoreUnknownKeys = true }
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE observations (id INTEGER PRIMARY KEY, station TEXT, feed TEXT, fetched INTEGER, generated TEXT, payload TEXT, usable INTEGER)")
        db.execSQL("CREATE TABLE latest (station TEXT, feed TEXT, identity TEXT, payload TEXT, PRIMARY KEY(station,feed,identity))")
        db.execSQL("CREATE TABLE events (id INTEGER PRIMARY KEY, station TEXT, identity TEXT, observed INTEGER, type TEXT, previous TEXT, current TEXT)")
        db.execSQL("CREATE TABLE public_platform (station TEXT, identity TEXT, platform TEXT, PRIMARY KEY(station,identity))")
        db.execSQL("CREATE INDEX observation_lookup ON observations(station,feed,fetched)")
        db.execSQL("CREATE INDEX event_timeline ON events(station,identity,current,id)")
        db.execSQL("CREATE INDEX event_history ON events(station,id)")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
    @Synchronized fun latestBoard(station: String): Pair<StationBoard, Long>? = readableDatabase.rawQuery(
        "SELECT payload,fetched FROM observations WHERE station=? AND feed='staff' AND usable=1 ORDER BY id DESC LIMIT 1", arrayOf(station)
    ).use { c -> if (!c.moveToFirst()) null else json.decodeFromString<StationBoard>(c.getString(0)) to c.getLong(1) }

    @Synchronized fun record(board: StationBoard, feed: String, fetched: Long): List<RailEvent> {
        val db = writableDatabase
        val events = mutableListOf<RailEvent>()
        db.beginTransaction()
        try {
            db.insertOrThrow("observations", null, ContentValues().apply {
                put("usable", if (board.areServicesAvailable == false) 0 else 1); put("station", board.crs); put("feed", feed); put("fetched", fetched); put("generated", board.generatedAt); put("payload", json.encodeToString(board))
            })
            board.services.forEach { current ->
                val old = db.rawQuery("SELECT payload FROM latest WHERE station=? AND feed=? AND identity=?", arrayOf(board.crs, feed, current.identity)).use { c ->
                    if (c.moveToFirst()) json.decodeFromString<RawService>(c.getString(0)) else null
                }
                if (feed == "staff") events += changes(old, current, Instant.ofEpochMilli(fetched))
                db.insertWithOnConflict("latest", null, ContentValues().apply {
                    put("station", board.crs); put("feed", feed); put("identity", current.identity); put("payload", json.encodeToString(current))
                }, SQLiteDatabase.CONFLICT_REPLACE)
            }
            events.forEach { insertEvent(db, board.crs, it) }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        return events
    }
    @Synchronized fun compare(staff: StationBoard, public: StationBoard, fetched: Long): List<RailEvent> {
        val events = mutableListOf<RailEvent>()
        // A stale public board cannot establish when passenger information became available.
        val age = railwayInstant(public.generatedAt)?.let { fetched - it.toEpochMilli() } ?: return events
        if (age !in -30_000..60_000) return events
        val db = writableDatabase
        db.beginTransaction()
        try {
            staff.services.forEach { service ->
                val match = comparatorMatch(service, public.services, staff.services) ?: return@forEach
                val platform = match.normalizedPlatform
                val before = db.rawQuery("SELECT platform FROM public_platform WHERE station=? AND identity=?", arrayOf(staff.crs, service.identity)).use { if (it.moveToFirst()) it.getString(0) else null }
                if (platform != before) {
                    if (platform != null) {
                        val event = RailEvent(service.identity, Instant.ofEpochMilli(fetched), RailEventType.PUBLIC_PLATFORM_OBSERVED, before, platform)
                        insertEvent(db, staff.crs, event); events += event
                    }
                    db.insertWithOnConflict("public_platform", null, ContentValues().apply { put("station", staff.crs); put("identity", service.identity); put("platform", platform) }, SQLiteDatabase.CONFLICT_REPLACE)
                }
            }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        return events
    }
    private fun insertEvent(db: SQLiteDatabase, station: String, e: RailEvent) {
        db.insertOrThrow("events", null, ContentValues().apply {
            put("station", station); put("identity", e.serviceId); put("observed", e.observedAt.toEpochMilli()); put("type", e.type.name); put("previous", e.previous); put("current", e.current)
        })
    }
    @Synchronized fun platformTimeline(station: String, service: RawService): List<RailEvent> = readableDatabase.rawQuery(
        "SELECT identity,observed,type,previous,current FROM events WHERE station=? AND identity=? AND type IN ('PLATFORM_SET','PLATFORM_CHANGED','PLATFORM_HIDDEN','PLATFORM_RELEASED','SERVICE_SUPPRESSED','SERVICE_UNSUPPRESSED','PUBLIC_PLATFORM_OBSERVED') ORDER BY id ASC",
        arrayOf(station, service.identity)
    ).use { c -> buildList {
        while (c.moveToNext()) add(
            RailEvent(
                c.getString(0),
                Instant.ofEpochMilli(c.getLong(1)),
                RailEventType.valueOf(c.getString(2)),
                c.getString(3),
                c.getString(4)
            )
        )
    } }
    @Synchronized fun history(station: String): List<RailEvent> = readableDatabase.rawQuery(
        "SELECT identity,observed,type,previous,current FROM events WHERE station=? ORDER BY id DESC LIMIT 200", arrayOf(station)
    ).use { c -> buildList { while (c.moveToNext()) add(RailEvent(c.getString(0), Instant.ofEpochMilli(c.getLong(1)), RailEventType.valueOf(c.getString(2)), c.getString(3), c.getString(4))) } }
}
