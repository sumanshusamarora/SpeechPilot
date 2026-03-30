package com.speechpilot.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.IOException

private val Context.speechPilotPrefs: DataStore<Preferences> by preferencesDataStore(
    name = "user_preferences"
)

/**
 * DataStore-backed implementation of [AppSettings].
 *
 * Preferences are persisted locally in the app's private data directory.
 * No data leaves the device.
 *
 * Pass [Application][android.app.Application] context to ensure the DataStore
 * singleton is tied to the application lifecycle.
 */
class DataStoreAppSettings(context: Context) : AppSettings {

    private val dataStore: DataStore<Preferences> = context.applicationContext.speechPilotPrefs

    override val preferences: Flow<UserPreferences> = dataStore.data
        .catch { e ->
            if (e is IOException) emit(emptyPreferences())
            else throw e
        }
        .map { store ->
            val defaults = UserPreferences()
            UserPreferences(
                targetWpm = store[Keys.TARGET_WPM] ?: defaults.targetWpm,
                tolerancePct = store[Keys.TOLERANCE_PCT] ?: defaults.tolerancePct,
                feedbackCooldownMs = store[Keys.FEEDBACK_COOLDOWN_MS] ?: defaults.feedbackCooldownMs,
                micSampleRate = store[Keys.MIC_SAMPLE_RATE] ?: defaults.micSampleRate,
                localTranscriptDebugEnabled = store[Keys.LOCAL_TRANSCRIPT_DEBUG_ENABLED] ?: defaults.localTranscriptDebugEnabled
            )
        }

    override suspend fun update(prefs: UserPreferences) {
        dataStore.edit { store ->
            store[Keys.TARGET_WPM] = prefs.targetWpm
            store[Keys.TOLERANCE_PCT] = prefs.tolerancePct
            store[Keys.FEEDBACK_COOLDOWN_MS] = prefs.feedbackCooldownMs
            store[Keys.MIC_SAMPLE_RATE] = prefs.micSampleRate
            store[Keys.LOCAL_TRANSCRIPT_DEBUG_ENABLED] = prefs.localTranscriptDebugEnabled
        }
    }

    private object Keys {
        val TARGET_WPM = intPreferencesKey("target_wpm")
        val TOLERANCE_PCT = floatPreferencesKey("tolerance_pct")
        val FEEDBACK_COOLDOWN_MS = longPreferencesKey("feedback_cooldown_ms")
        val MIC_SAMPLE_RATE = intPreferencesKey("mic_sample_rate")
        val LOCAL_TRANSCRIPT_DEBUG_ENABLED = booleanPreferencesKey("local_transcript_debug_enabled")
    }
}
