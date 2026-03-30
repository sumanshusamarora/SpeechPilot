package com.speechpilot.transcription

import java.util.ArrayDeque
import kotlin.math.max

/**
 * Builds a rolling transcript and computes transcript-derived WPM from finalized words.
 *
 * WPM only counts words from final hypotheses. Partial hypotheses are shown in the transcript
 * preview but excluded from counts to avoid over-counting revised interim text.
 */
class RollingTranscriptWpmCalculator(
    private val windowMs: Long = 30_000L
) {
    private val observations = ArrayDeque<WordObservation>()
    private val finalizedSegments = ArrayDeque<String>()

    private var partialText: String = ""
    private var finalizedWordCount: Int = 0

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
                        observations.addLast(WordObservation(timestampMs = update.receivedAtMs, words = words))
                    }
                }
            }
        }

        evictOld(update.receivedAtMs)
        val wordsInWindow = observations.sumOf { it.words }
        val wpm = if (windowMs <= 0L) {
            0.0
        } else {
            wordsInWindow * 60_000.0 / windowMs
        }

        return TranscriptWpmSnapshot(
            rollingWpm = max(0.0, wpm),
            rollingWordCount = wordsInWindow,
            finalizedWordCount = finalizedWordCount,
            transcriptPreview = buildTranscriptPreview()
        )
    }

    fun reset() {
        observations.clear()
        finalizedSegments.clear()
        partialText = ""
        finalizedWordCount = 0
    }

    private fun buildTranscriptPreview(maxChars: Int = 240): String {
        val finalized = finalizedSegments.joinToString(separator = " ")
        val full = if (partialText.isNotBlank()) {
            listOf(finalized, "[$partialText]").filter { it.isNotBlank() }.joinToString(" ")
        } else {
            finalized
        }
        return if (full.length <= maxChars) full else full.takeLast(maxChars)
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
    val transcriptPreview: String
)
