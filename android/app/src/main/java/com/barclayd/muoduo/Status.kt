@file:UseSerializers(InstantSerializer::class)

package com.barclayd.muoduo

import java.time.Instant
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/** Mirrors `GET /status` on the Worker. */
@Serializable
data class Status(
    val config: Config,
    val health: Health,
    val pace: Pace,
    /** Apple is answering, but even a model that's always in stock shows nothing, so "nothing in stock" may be wrong. */
    val resultsMayBeStale: Boolean = false,
    val sightings: List<Sighting> = emptyList(),
    val areas: List<Area> = emptyList(),
    /** Most recent first. */
    val lastSeen: List<Episode> = emptyList(),
    val trackingSince: Instant? = null,
    /** Most recent first. */
    val log: List<LogEntry> = emptyList(),
    val loggingSince: Instant? = null,
    /** Most recent first. */
    val recentChecks: List<Check> = emptyList(),
    val devices: Int = 0,
    /** Null until this phone has registered for notifications. */
    val device: Device? = null,
    /** "friend" with a friend's key, which can't pause checking. */
    val role: String? = null,
    val nextCheckAt: Instant? = null,
    val models: List<Model> = emptyList(),
) {
    @Serializable
    data class Config(val intervalSeconds: Int, val paused: Boolean)

    /** This phone's own settings: what it hears about, and when stock rings an alarm. */
    @Serializable
    data class Device(
        val parts: List<String>,
        val alarm: Alarm,
        /** The searches of the areas it hears about. */
        val areas: List<String>,
        val muted: Boolean,
    )

    /** Stock found between these UK hours also rings an alarm. From is included, to isn't. */
    @Serializable
    data class Alarm(val enabled: Boolean, val from: Int, val to: Int) {
        val allDay get() = from == 0 && to == 24
        val hours get() = if (allDay) "All day" else "${clock(from)}–${clock(to)}"

        companion object {
            /** 24, the end of the day, reads as 00:00. */
            fun clock(hour: Int) = "%02d:00".format(hour % 24)
        }
    }

    @Serializable
    data class Health(
        val lastCheckAt: Instant? = null,
        val lastSuccessAt: Instant? = null,
        val lastError: String? = null,
        val consecutiveFailures: Int = 0,
    )

    /** How often each model is actually checked in each area. Slower than configured after Apple pushes back. */
    @Serializable
    data class Pace(val intervalSeconds: Int, val reason: String? = null)

    @Serializable
    data class Sighting(val part: String, val storeNumber: String, val storeName: String, val quote: String)

    /** A search the Worker asks Apple about, and the stores near it that it watches. */
    @Serializable
    data class Area(val name: String, val search: String, val stores: List<Store>) {
        val id get() = search
    }

    @Serializable
    data class Store(
        val number: String,
        val name: String,
        val city: String? = null,
        val latitude: Double? = null,
        val longitude: Double? = null,
        /** False if Apple left the store out of its last answer, so it isn't being watched. */
        val covered: Boolean = true,
    )

    /** A stretch of time a model was in stock at a store. */
    @Serializable
    data class Episode(val part: String, val storeNumber: String, val storeName: String, val from: Instant, val to: Instant)

    /**
     * One request the Worker made to Apple: about one model, or, with no part, about every watched model
     * at every store in the area.
     */
    @Serializable
    data class Check(val at: Instant, val area: String, val part: String? = null, val ok: Boolean, val ms: Int) {
        val id get() = "${at.toEpochMilli()}|$area|${part.orEmpty()}"
    }

    /**
     * Something for the stock log: a watched model coming into stock at a store or going again, or the
     * checker stopping and starting.
     */
    @Serializable
    data class LogEntry(
        val at: Instant,
        val kind: Kind,
        val part: String? = null,
        val storeNumber: String? = null,
        val storeName: String? = null,
        val quote: String? = null,
        /** When stock that's gone came in. */
        val since: Instant? = null,
        val detail: String? = null,
    ) {
        val id get() = "${at.toEpochMilli()}|$kind|${part.orEmpty()}|${storeNumber.orEmpty()}"

        @Serializable
        enum class Kind {
            @SerialName("in") InStock,
            @SerialName("gone") Gone,
            @SerialName("failing") Failing,
            @SerialName("recovered") Recovered,
            @SerialName("stale") Stale,
            @SerialName("normal") Normal,
            @SerialName("paused") Paused,
            @SerialName("resumed") Resumed,
        }
    }

    @Serializable
    data class Model(
        val part: String,
        val name: String,
        val capacity: String,
        val colour: String,
        val url: String,
        val imageUrl: String,
    ) {
        val finish get() = Finish.entries.firstOrNull { it.title == colour }
    }

    fun model(part: String) = models.firstOrNull { it.part == part }

    /** What a check asked Apple about. */
    fun subject(check: Check) = check.part?.let { model(it)?.name ?: it } ?: "Every store"

    fun models(finish: Finish) = models.filter { it.finish == finish }

    fun area(store: String) = areas.firstOrNull { area -> area.stores.any { it.number == store } }

    val storeCount get() = areas.sumOf { it.stores.size }

    /** The stores in the areas this phone hears about. */
    fun storeCount(searches: List<String>) = areas.filter { it.search in searches }.sumOf { it.stores.size }

    val isOwner get() = role != "friend"

    val state: LiveState
        get() = when {
            config.paused -> LiveState.Paused
            health.consecutiveFailures > 0 -> LiveState.Failing(health.lastError)
            resultsMayBeStale -> LiveState.Stale
            pace.reason != null -> LiveState.Slowed(pace.reason)
            else -> LiveState.Live
        }

    fun inStock(store: String) = sightings.filter { it.storeNumber == store }
}

sealed interface LiveState {
    data object Live : LiveState
    data object Paused : LiveState
    data class Failing(val error: String?) : LiveState
    data object Stale : LiveState
    data class Slowed(val reason: String) : LiveState
}

@Serializable
data class ConfigPatch(val paused: Boolean? = null)

/** Changes to this phone's own settings. [areas] are the searches it wants to hear about. */
@Serializable
data class DevicePatch(
    val parts: List<String>? = null,
    val alarm: Status.Alarm? = null,
    val areas: List<String>? = null,
    val muted: Boolean? = null,
)

@Serializable
data class TestResult(val delivered: Boolean, val errors: List<String> = emptyList())

/** The Worker's dates: ISO 8601, with milliseconds. */
object InstantSerializer : KSerializer<Instant> {
    override val descriptor = PrimitiveSerialDescriptor("Instant", PrimitiveKind.STRING)
    override fun deserialize(decoder: Decoder): Instant = Instant.parse(decoder.decodeString())
    override fun serialize(encoder: Encoder, value: Instant) = encoder.encodeString(value.toString())
}
