package com.speechpilot.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Persisted record of a completed coaching session.
 *
 * [averageEstimatedWpm] and [peakEstimatedWpm] are proxy-based estimates — not true WPM values.
 * See [com.speechpilot.pace.PaceMetrics] for details on the estimation methodology.
 *
 * [audioFileUri] is non-null for sessions that analyzed an uploaded audio file. It stores the
 * content URI string so the file can be re-analyzed from session history.
 *
 * Stored locally in the Room database. Never transmitted externally.
 */
@Entity(tableName = "session_records")
data class SessionRecord(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val startedAtMs: Long,
    val endedAtMs: Long,
    val durationMs: Long = endedAtMs - startedAtMs,
    /** Cumulative duration (ms) of speech-active segments in this session. */
    val totalSpeechActiveDurationMs: Long = 0L,
    /** Number of speech segments detected during the session. */
    val segmentCount: Int = 0,
    /** Arithmetic mean of per-segment estimated WPM. Approximate proxy only. */
    val averageEstimatedWpm: Double = 0.0,
    /** Peak per-segment estimated WPM observed. Approximate proxy only. */
    val peakEstimatedWpm: Double = 0.0,
    /**
     * Content URI of the audio file analyzed in this session, or null for live-microphone sessions.
     * When non-null the file can be re-analyzed from session history.
     */
    val audioFileUri: String? = null
)
