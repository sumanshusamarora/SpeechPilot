package com.speechpilot.settings

import kotlinx.coroutines.flow.Flow

interface AppSettings {
    val preferences: Flow<UserPreferences>
    suspend fun update(prefs: UserPreferences)
}
