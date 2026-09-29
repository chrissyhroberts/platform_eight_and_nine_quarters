package uk.ac.rawrail.data

import uk.ac.rawrail.model.*
import java.time.Instant

/**
 * In-memory transition helper retained for callers that do not use ObservationStore.
 * Transition semantics are intentionally shared with the durable store via changes().
 */
class PlatformHistory {
    private val previous = mutableMapOf<String, RawService>()
    private val events = mutableListOf<RailEvent>()

    fun ingest(services: List<RawService>, observedAt: Instant = Instant.now()): List<RailEvent> {
        val emitted = buildList {
            services.forEach { current ->
                val old = previous[current.identity]
                addAll(changes(old, current, observedAt))
                previous[current.identity] = current
            }
        }
        events += emitted
        return emitted
    }

    fun allEvents(): List<RailEvent> = events.toList()
}
