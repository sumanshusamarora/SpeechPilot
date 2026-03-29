package com.speechpilot.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.speechpilot.feedback.FeedbackEvent

@Composable
fun MainScreen(
    viewModel: MainViewModel = viewModel(),
    onOpenSettings: () -> Unit = {},
    onOpenHistory: () -> Unit = {}
) {
    val state by viewModel.uiState.collectAsState()
    MainContent(
        state = state,
        onStartSession = viewModel::startSession,
        onStopSession = viewModel::stopSession,
        onOpenSettings = onOpenSettings,
        onOpenHistory = onOpenHistory
    )
}

@Composable
private fun MainContent(
    state: MainUiState,
    onStartSession: () -> Unit,
    onStopSession: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenHistory: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Navigation row at the top
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End
        ) {
            TextButton(onClick = onOpenHistory) { Text("History") }
            TextButton(onClick = onOpenSettings) { Text("Settings") }
        }

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = state.statusText,
            style = MaterialTheme.typography.headlineMedium
        )

        if (state.isListening) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "🎤 Listening",
                style = MaterialTheme.typography.bodyLarge
            )
        }

        // Prefer smoothed WPM once available; fall back to raw current WPM before smoothing kicks in.
        // The "~" prefix signals this is an approximate proxy, not exact words-per-minute.
        val displayWpm = if (state.smoothedWpm > 0f) state.smoothedWpm else state.currentWpm
        if (displayWpm > 0f) {
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "~%.0f WPM".format(displayWpm),
                style = MaterialTheme.typography.headlineLarge
            )
        }

        if (state.segmentCount > 0) {
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "${state.segmentCount} segment${if (state.segmentCount == 1) "" else "s"}",
                style = MaterialTheme.typography.bodySmall
            )
        }

        state.latestFeedback?.let { feedback ->
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = when (feedback) {
                    FeedbackEvent.SlowDown -> "⚠ Slow down"
                    FeedbackEvent.SpeedUp -> "⚠ Speed up"
                    FeedbackEvent.OnTarget -> "On target ✓"
                },
                style = MaterialTheme.typography.bodyLarge,
                color = if (state.alertActive) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.onSurface
            )
        }

        Spacer(modifier = Modifier.height(32.dp))

        if (!state.permissionGranted) {
            Text(
                text = "Microphone permission required to start a session.",
                style = MaterialTheme.typography.bodyMedium
            )
        } else if (state.isSessionActive) {
            Button(onClick = onStopSession) {
                Text("Stop Session")
            }
        } else {
            Button(onClick = onStartSession) {
                Text("Start Session")
            }
        }
    }
}
