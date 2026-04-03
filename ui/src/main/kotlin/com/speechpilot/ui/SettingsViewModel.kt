package com.speechpilot.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.speechpilot.settings.DataStoreAppSettings
import com.speechpilot.settings.UserPreferences
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SettingsViewModel(application: Application) : AndroidViewModel(application) {

    private val appSettings = DataStoreAppSettings(getApplication())

    val preferences: StateFlow<UserPreferences> = appSettings.preferences
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = UserPreferences()
        )

    fun updateTargetWpm(wpm: Int) {
        viewModelScope.launch {
            appSettings.update(preferences.value.copy(targetWpm = wpm))
        }
    }

    fun updateTolerancePct(pct: Float) {
        viewModelScope.launch {
            appSettings.update(preferences.value.copy(tolerancePct = pct))
        }
    }

    fun updateFeedbackCooldownMs(ms: Long) {
        viewModelScope.launch {
            appSettings.update(preferences.value.copy(feedbackCooldownMs = ms))
        }
    }

    fun updateTranscriptionEnabled(enabled: Boolean) {
        viewModelScope.launch {
            appSettings.update(preferences.value.copy(transcriptionEnabled = enabled))
        }
    }
}
