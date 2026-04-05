package com.speechpilot.transcription

import java.util.ArrayDeque
import kotlin.math.max

/**
 * Builds a rolling transcript and computes transcript-derived WPM from finalized words.
 *
 * WPM only counts words from final hypotheses. Partial hypotheses are shown in the transcript
 * surface text but excluded from counts to avoid over-counting revised interim text.
 *
 * ## Chunk-based WPM hold
 *
 * When [chunkBased] is `true` (e.g. Whisper.cpp backend), updates arrive in large discrete
 * bursts every [chunkDurationMs] milliseconds rather than continuously. Without smoothing, the
 * rolling WPM would oscillate to near-zero between bursts.
 *
 * To avoid this, the calculator maintains a [heldWpm] value that is carried forward for up to
 * [wpmHoldDurationMs] after the last final update. The held value is returned as long as:
 * - [chunkBased] is `true`, AND
 * - The time since the last final update is ≤ [wpmHoldDurationMs], AND
 * - The live rolling WPM from the window is lower than the held value (i.e. the window is
 *   draining, not reflecting new speech).
 *
 * This gives a smooth, readable pace estimate between Whisper chunks rather than rapid
 * oscillation. Once the hold expires or the rolling WPM exceeds the held value, the live value
 * resumes.
 *
 * @param windowMs Duration of the rolling WPM window in milliseconds. Default: 30 seconds.
 * @param chunkBased Set to `true` for chunk-based backends (Whisper) to enable WPM hold.
 * @param chunkDurationMs Expected inter-chunk gap in milliseconds. Used to set sensible defaults
 *   for [wpmHoldDurationMs]. Default matches [WhisperCppLocalTranscriber.CHUNK_DURATION_SAMPLES]
 *   at 16 kHz (2 seconds).
 * @param wpmHoldDurationMs How long to hold the last WPM value after a final update when in
 *   chunk-based mode. Defaults to 2× [chunkDurationMs] to span one full gap plus buffer.
 */
class RollingTranscriptWpmCalculator(
    private val windowMs: Long = 30_000L,
    chunkBased: Boolean = false,
    val chunkDurationMs: Long = 2_000L,
    val wpmHoldDurationMs: Long = chunkDurationMs * 2,
) {
    /** `true` when the active backend emits Final-only chunk updates (e.g. Whisper.cpp). */
    var chunkBased: Boolean = chunkBased
        private set
    private val observations = ArrayDeque<WordObservation>()
    private val finalizedSegments = ArrayDeque<String>()

    private var partialText: String = ""
    private var finalizedWordCount: Int = 0

    private var heldWpm: Double = 0.0
    private var lastFinalUpdateMs: Long = 0L

    fun onUpdate(update: TranscriptUpdate): TranscriptWpmSnapshot {
        val cleanText = update.text.trim()
        if (cleanText.isNotEmpty()) {
            when (update.stability) {
                TranscriptStability.Partial -> partialText = cleanText
                TranscriptStability.Final -> {
                    partialText = ""
                    finalizedSegments.addLast(cleanText)
                    val words = countWords(cleanText)
                    if (words > 0) {
                        finalizedWordCount += words
                        observations.addLast(
                            WordObservation(timestampMs = update.receivedAtMs, words = words)
                        )
                        lastFinalUpdateMs = update.receivedAtMs
                    }
                }
            }
        }

        evictOld(update.receivedAtMs)
        val wordsInWindow = observations.sumOf { it.words }
        val liveWpm = if (windowMs <= 0L) {
            0.0
        } else {
            max(0.0, wordsInWindow * 60_000.0 / windowMs)
        }

        val reportedWpm = computeReportedWpm(liveWpm, update.receivedAtMs)

        return TranscriptWpmSnapshot(
            rollingWpm = reportedWpm,
            rollingWordCount = wordsInWindow,
            finalizedWordCount = finalizedWordCount,
            transcriptPreview = buildTranscriptPreview(),
            partialTranscriptPresent = partialText.isNotBlank(),
            isChunkBased = chunkBased,
            lastChunkAtMs = if (lastFinalUpdateMs > 0L) lastFinalUpdateMs else null,
        )
    }

    fun reset() {
        observations.clear()
        finalizedSegments.clear()
        partialText = ""
        finalizedWordCount = 0
        heldWpm = 0.0
        lastFinalUpdateMs = 0L
    }

    /** Updates [chunkBased] mode. Callers should [reset] before calling if a backend switch occurred. */
    fun setChunkBased(value: Boolean) {
        chunkBased = value
    }

    /**
     * Returns the WPM value to report.
     *
     * In chunk-based mode: returns [heldWpm] when the live window is draining and the hold
     * period has not yet expired. Otherwise updates [heldWpm] to [liveWpm] and returns it.
     * In streaming mode: returns [liveWpm] directly.
     */
    private fun computeReportedWpm(liveWpm: Double, nowMs: Long): Double {
        if (!chunkBased) return liveWpm

        val timeSinceLastChunk = if (lastFinalUpdateMs > 0L) nowMs - lastFinalUpdateMs else Long.MAX_VALUE

        return if (timeSinceLastChunk <= wpmHoldDurationMs && liveWpm < heldWpm) {
            // The window is draining between chunks — hold the last known good WPM.
            heldWpm
        } else {
            // New chunk just arrived, or hold expired, or live WPM overtook the hold.
            heldWpm = liveWpm
            liveWpm
        }
    }

    private fun buildTranscriptPreview(): String {
        val finalized = finalizedSegments.joinToString(separator = " ")
        return if (partialText.isNotBlank()) {
            listOf(finalized, "[$partialText]").filter { it.isNotBlank() }.joinToString(" ")
        } else {
            finalized
        }
    }

    private fun evictOld(nowMs: Long) {
        while (true) {
            val first = observations.peekFirst() ?: break
            if (nowMs - first.timestampMs > windowMs) {
                observations.removeFirst()
            } else {
                break
            }
        }
    }

    private fun countWords(text: String): Int {
        return text
            .trim()
            .split(Regex("\\s+"))
            .count { token -> token.any { it.isLetterOrDigit() } }
    }

    private data class WordObservation(val timestampMs: Long, val words: Int)
}

data class TranscriptWpmSnapshot(
    val rollingWpm: Double,
    val rollingWordCount: Int,
    val finalizedWordCount: Int,
    val transcriptPreview: String,
    val partialTranscriptPresent: Boolean,
    val isChunkBased: Boolean = false,
    val lastChunkAtMs: Long? = null,
)

