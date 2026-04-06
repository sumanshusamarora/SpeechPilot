package com.speechpilot.session

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

internal const val REALTIME_PROTOCOL_VERSION = "1.0"

internal data class RealtimeTranscriptSegment(
    val id: String,
    val text: String,
    val startTimeMs: Int,
    val endTimeMs: Int,
    val wordCount: Int,
)

internal data class RealtimePaceUpdate(
    val sessionId: String,
    val wordsPerMinute: Double,
    val band: String,
    val source: String,
    val totalWords: Int,
    val speakingDurationMs: Int,
    val silenceDurationMs: Int,
    val windowDurationMs: Int,
)

internal data class RealtimeFeedbackUpdate(
    val sessionId: String,
    val decision: String,
    val reason: String,
    val confidence: Double,
)

internal data class RealtimeSessionSummary(
    val sessionId: String,
    val durationMs: Int,
    val transcriptSegments: Int,
    val totalWords: Int,
    val averageWpm: Double?,
    val speakingDurationMs: Int,
    val silenceDurationMs: Int,
    val paceBand: String,
    val notes: List<String>,
)

internal data class RealtimeDebugState(
    val sessionId: String?,
    val lifecycle: String,
    val activeProvider: String?,
    val replayMode: Boolean?,
    val chunksReceived: Int,
    val partialUpdates: Int,
    val finalSegments: Int,
    val totalWords: Int,
    val wordsPerMinute: Double?,
    val paceBand: String,
    val feedbackCount: Int,
    val lastFeedbackDecision: String?,
    val lastFeedbackReason: String?,
    val lastFeedbackConfidence: Double?,
    val detail: String?,
)

internal data class RealtimeErrorEvent(
    val code: String,
    val message: String,
    val retryable: Boolean,
    val detail: String?,
)

internal sealed interface RealtimeServerEvent {
    data class TranscriptPartial(
        val sessionId: String,
        val text: String,
        val sequence: Int,
    ) : RealtimeServerEvent

    data class TranscriptFinal(
        val sessionId: String,
        val segment: RealtimeTranscriptSegment,
    ) : RealtimeServerEvent

    data class PaceUpdate(val payload: RealtimePaceUpdate) : RealtimeServerEvent
    data class FeedbackUpdate(val payload: RealtimeFeedbackUpdate) : RealtimeServerEvent
    data class SessionSummary(val payload: RealtimeSessionSummary) : RealtimeServerEvent
    data class DebugState(val payload: RealtimeDebugState) : RealtimeServerEvent
    data class Error(val payload: RealtimeErrorEvent) : RealtimeServerEvent
}

internal object RealtimeProtocol {
    private val json = Json { ignoreUnknownKeys = true }

    fun createSessionStart(sessionId: String, client: String, locale: String?): String =
        buildJsonObject {
            put("version", JsonPrimitive(REALTIME_PROTOCOL_VERSION))
            put("timestamp", JsonPrimitive(isoTimestamp()))
            put("type", JsonPrimitive("session.start"))
            put("payload", buildJsonObject {
                put("sessionId", JsonPrimitive(sessionId))
                put("client", JsonPrimitive(client))
                put("replayMode", JsonPrimitive(false))
                locale?.takeIf { it.isNotBlank() }?.let { put("locale", JsonPrimitive(it)) }
            })
        }.toString()

    fun createAudioChunk(
        sessionId: String,
        sequence: Int,
        sampleRateHz: Int,
        durationMs: Int,
        dataBase64: String,
        channelCount: Int = 1,
        encoding: String = "pcm16le",
    ): String = buildJsonObject {
        put("version", JsonPrimitive(REALTIME_PROTOCOL_VERSION))
        put("timestamp", JsonPrimitive(isoTimestamp()))
        put("type", JsonPrimitive("audio.chunk"))
        put("payload", buildJsonObject {
            put("sessionId", JsonPrimitive(sessionId))
            put("sequence", JsonPrimitive(sequence))
            put("encoding", JsonPrimitive(encoding))
            put("sampleRateHz", JsonPrimitive(sampleRateHz))
            put("channelCount", JsonPrimitive(channelCount))
            put("durationMs", JsonPrimitive(durationMs))
            put("dataBase64", JsonPrimitive(dataBase64))
        })
    }.toString()

    fun createSessionStop(sessionId: String, reason: String): String = buildJsonObject {
        put("version", JsonPrimitive(REALTIME_PROTOCOL_VERSION))
        put("timestamp", JsonPrimitive(isoTimestamp()))
        put("type", JsonPrimitive("session.stop"))
        put("payload", buildJsonObject {
            put("sessionId", JsonPrimitive(sessionId))
            put("reason", JsonPrimitive(reason))
        })
    }.toString()

    fun parseServerEvent(message: String): RealtimeServerEvent? {
        val root = json.parseToJsonElement(message).jsonObject
        val type = root.string("type") ?: return null
        val payload = root.objectValue("payload") ?: return null
        return when (type) {
            "transcript.partial" -> parseTranscriptPartial(payload)
            "transcript.final" -> parseTranscriptFinal(payload)
            "pace.update" -> parsePaceUpdate(payload)
            "feedback.update" -> parseFeedbackUpdate(payload)
            "session.summary" -> parseSessionSummary(payload)
            "debug.state" -> parseDebugState(payload)
            "error" -> parseError(payload)
            else -> null
        }
    }

    private fun parseTranscriptPartial(payload: JsonObject): RealtimeServerEvent.TranscriptPartial? {
        val sessionId = payload.string("sessionId") ?: return null
        val text = payload.string("text") ?: return null
        val sequence = payload.int("sequence") ?: return null
        return RealtimeServerEvent.TranscriptPartial(sessionId, text, sequence)
    }

    private fun parseTranscriptFinal(payload: JsonObject): RealtimeServerEvent.TranscriptFinal? {
        val sessionId = payload.string("sessionId") ?: return null
        val segmentObject = payload.objectValue("segment") ?: return null
        val segment = RealtimeTranscriptSegment(
            id = segmentObject.string("id") ?: return null,
            text = segmentObject.string("text") ?: return null,
            startTimeMs = segmentObject.int("startTimeMs") ?: return null,
            endTimeMs = segmentObject.int("endTimeMs") ?: return null,
            wordCount = segmentObject.int("wordCount") ?: return null,
        )
        return RealtimeServerEvent.TranscriptFinal(sessionId, segment)
    }

    private fun parsePaceUpdate(payload: JsonObject): RealtimeServerEvent.PaceUpdate? {
        val sessionId = payload.string("sessionId") ?: return null
        val pace = RealtimePaceUpdate(
            sessionId = sessionId,
            wordsPerMinute = payload.double("wordsPerMinute") ?: return null,
            band = payload.string("band") ?: "unknown",
            source = payload.string("source") ?: "unknown",
            totalWords = payload.int("totalWords") ?: 0,
            speakingDurationMs = payload.int("speakingDurationMs") ?: 0,
            silenceDurationMs = payload.int("silenceDurationMs") ?: 0,
            windowDurationMs = payload.int("windowDurationMs") ?: 0,
        )
        return RealtimeServerEvent.PaceUpdate(pace)
    }

    private fun parseFeedbackUpdate(payload: JsonObject): RealtimeServerEvent.FeedbackUpdate? {
        val sessionId = payload.string("sessionId") ?: return null
        val feedback = RealtimeFeedbackUpdate(
            sessionId = sessionId,
            decision = payload.string("decision") ?: return null,
            reason = payload.string("reason") ?: return null,
            confidence = payload.double("confidence") ?: 0.0,
        )
        return RealtimeServerEvent.FeedbackUpdate(feedback)
    }

    private fun parseSessionSummary(payload: JsonObject): RealtimeServerEvent.SessionSummary? {
        val sessionId = payload.string("sessionId") ?: return null
        val summary = RealtimeSessionSummary(
            sessionId = sessionId,
            durationMs = payload.int("durationMs") ?: 0,
            transcriptSegments = payload.int("transcriptSegments") ?: 0,
            totalWords = payload.int("totalWords") ?: 0,
            averageWpm = payload.double("averageWpm"),
            speakingDurationMs = payload.int("speakingDurationMs") ?: 0,
            silenceDurationMs = payload.int("silenceDurationMs") ?: 0,
            paceBand = payload.string("paceBand") ?: "unknown",
            notes = payload.stringList("notes"),
        )
        return RealtimeServerEvent.SessionSummary(summary)
    }

    private fun parseDebugState(payload: JsonObject): RealtimeServerEvent.DebugState {
        val debug = RealtimeDebugState(
            sessionId = payload.string("sessionId"),
            lifecycle = payload.string("lifecycle") ?: "unknown",
            activeProvider = payload.string("activeProvider"),
            replayMode = payload.boolean("replayMode"),
            chunksReceived = payload.int("chunksReceived") ?: 0,
            partialUpdates = payload.int("partialUpdates") ?: 0,
            finalSegments = payload.int("finalSegments") ?: 0,
            totalWords = payload.int("totalWords") ?: 0,
            wordsPerMinute = payload.double("wordsPerMinute"),
            paceBand = payload.string("paceBand") ?: "unknown",
            feedbackCount = payload.int("feedbackCount") ?: 0,
            lastFeedbackDecision = payload.string("lastFeedbackDecision"),
            lastFeedbackReason = payload.string("lastFeedbackReason"),
            lastFeedbackConfidence = payload.double("lastFeedbackConfidence"),
            detail = payload.string("detail"),
        )
        return RealtimeServerEvent.DebugState(debug)
    }

    private fun parseError(payload: JsonObject): RealtimeServerEvent.Error? {
        val code = payload.string("code") ?: return null
        val message = payload.string("message") ?: return null
        return RealtimeServerEvent.Error(
            RealtimeErrorEvent(
                code = code,
                message = message,
                retryable = payload.boolean("retryable") ?: false,
                detail = payload.string("detail"),
            )
        )
    }

    private fun JsonObject.string(name: String): String? =
        (this[name] as? JsonPrimitive)?.contentOrNull

    private fun JsonObject.int(name: String): Int? =
        (this[name] as? JsonPrimitive)?.intOrNull

    private fun JsonObject.double(name: String): Double? =
        (this[name] as? JsonPrimitive)?.doubleOrNull

    private fun JsonObject.boolean(name: String): Boolean? =
        (this[name] as? JsonPrimitive)?.booleanOrNull

    private fun JsonObject.objectValue(name: String): JsonObject? =
        (this[name] as? JsonObject) ?: this[name]?.jsonObject

    private fun JsonObject.stringList(name: String): List<String> =
        (this[name] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull } ?: emptyList()

    private fun isoTimestamp(): String = java.time.Instant.now().toString()
}