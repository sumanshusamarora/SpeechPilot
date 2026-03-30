package com.speechpilot.ui

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.viewmodel.compose.viewModel

/** The set of top-level screens reachable from the main flow. */
private enum class AppScreen { MAIN, SETTINGS, HISTORY }

/**
 * Root composable that manages top-level screen navigation.
 *
 * A lightweight state-based switcher is used instead of a nav library to
 * keep the dependency footprint minimal in Phase 1. Navigation state is held
 * in memory and resets to MAIN on configuration changes.
 *
 * [BackHandler] intercepts the system back gesture/button whenever a sub-screen
 * is active, returning the user to the main screen instead of exiting the app.
 *
 * "Re-analyze" from history navigates back to main and starts a file session on the
 * shared [MainViewModel] so results are immediately visible.
 */
@Composable
fun AppNavigation(
    mainViewModel: MainViewModel = viewModel(),
    settingsViewModel: SettingsViewModel = viewModel(),
    historyViewModel: HistoryViewModel = viewModel()
) {
    var currentScreen by remember { mutableStateOf(AppScreen.MAIN) }

    // Intercept system back on sub-screens so the user returns to main rather than exiting.
    BackHandler(enabled = currentScreen != AppScreen.MAIN) {
        currentScreen = AppScreen.MAIN
    }

    when (currentScreen) {
        AppScreen.MAIN -> MainScreen(
            viewModel = mainViewModel,
            onOpenSettings = { currentScreen = AppScreen.SETTINGS },
            onOpenHistory = { currentScreen = AppScreen.HISTORY }
        )
        AppScreen.SETTINGS -> SettingsScreen(
            viewModel = settingsViewModel,
            onBack = { currentScreen = AppScreen.MAIN }
        )
        AppScreen.HISTORY -> HistoryScreen(
            viewModel = historyViewModel,
            onBack = { currentScreen = AppScreen.MAIN },
            onReanalyze = { uriString ->
                currentScreen = AppScreen.MAIN
                mainViewModel.startFileSession(Uri.parse(uriString))
            }
        )
    }
}
