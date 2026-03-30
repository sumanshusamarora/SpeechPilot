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
import androidx.compose.material3.HorizontalDivider
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
import com.speechpilot.session.SessionMode

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
        onDismissError = viewModel::dismissError,
        onOpenSettings = onOpenSettings,
        onOpenHistory = onOpenHistory
    )
}

@Composable
private fun MainContent(
    state: MainUiState,
    onStartSession: () -> Unit,
    onStopSession: () -> Unit,
    onDismissError: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenHistory: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Top,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Navigation row
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End
        ) {
            TextButton(onClick = onOpenHistory) { Text("History") }
            TextButton(onClick = onOpenSettings) { Text("Settings") }
        }

        Spacer(modifier = Modifier.height(32.dp))

        // Status headline
        Text(
            text = state.statusText,
            style = MaterialTheme.typography.headlineMedium
        )

        Spacer(modifier = Modifier.height(8.dp))

        // Listening / speech indicators
        if (state.isListening) {
            Text(
                text = if (state.isSpeechDetected) "🗣 Speech detected" else "🎤 Listening…",
                style = MaterialTheme.typography.bodyLarge
            )
        }

        // Session mode badge — shown while actively listening in Passive mode.
        // Mode resets to Active when the session stops, so this only appears during a live session.
        if (state.isListening && state.sessionMode == SessionMode.Passive) {
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Passive mode — feedback suppressed",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.secondary
            )
        }

        // WPM display
        val displayWpm = if (state.smoothedWpm > 0f) state.smoothedWpm else state.currentWpm
        if (displayWpm > 0f) {
            Spacer(modifier = Modifier.height(24.dp))
            Text(
                text = "~%.0f WPM".format(displayWpm),
                style = MaterialTheme.typography.displaySmall
            )
            if (state.segmentCount > 0) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "${state.segmentCount} segment${if (state.segmentCount == 1) "" else "s"}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        // Feedback event indicator
        state.latestFeedback?.let { feedback ->
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = when (feedback) {
                    FeedbackEvent.SlowDown -> "⚠ Slow down"
                    FeedbackEvent.SpeedUp -> "⚠ Speed up"
                    FeedbackEvent.OnTarget -> "✓ On target"
                },
                style = MaterialTheme.typography.bodyLarge,
                color = if (state.alertActive) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.onSurface
            )
        }

        // Error message
        state.errorMessage?.let { error ->
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = error,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error
            )
            TextButton(onClick = onDismissError) { Text("Dismiss") }
        }

        Spacer(modifier = Modifier.weight(1f))

        // Debug panel — shown automatically while a session is active or has produced
        // speech data. Surfaces raw and smoothed pace signals, threshold, cooldown state,
        // and last decision reason for real-device calibration.
        if (state.isListening || state.isSpeechDetected) {
            DebugPanel(state = state)
            Spacer(modifier = Modifier.height(12.dp))
        }

        // Primary action
        when {
            !state.permissionGranted -> {
                Text(
                    text = "Microphone permission required to start a session.",
                    style = MaterialTheme.typography.bodyMedium
                )
            }
            state.isSessionActive -> {
                Button(onClick = onStopSession) {
                    Text("Stop Session")
                }
            }
            else -> {
                Button(
                    onClick = onStartSession,
                    modifier = Modifier.fillMaxWidth(0.7f)
                ) {
                    Text("Start Session")
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))
    }
}

/**
 * Development-focused debug panel that surfaces the internal pipeline state.
 *
 * Shown during and immediately after an active session to aid real-device calibration.
 * Displays the raw and smoothed pace signals alongside threshold, decision reason,
 * cooldown state, and alert status.
 */
@Composable
private fun DebugPanel(state: MainUiState) {
    val debug = state.debugInfo

    HorizontalDivider()
    Spacer(modifier = Modifier.height(8.dp))
    Text(
        text = "— Debug —",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    Spacer(modifier = Modifier.height(4.dp))
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        val speechLabel = when {
            state.isSpeechDetected -> "yes (this session)"
            state.isListening -> "none yet"
            else -> "no"
        }
        DebugRow(label = "Speech seen", value = speechLabel)
        DebugRow(label = "Raw pace", value = "%.1f est-WPM".format(state.currentWpm))
        DebugRow(label = "Smoothed pace", value = "%.1f est-WPM".format(state.smoothedWpm))
        DebugRow(label = "Target", value = "%.0f est-WPM".format(debug.targetWpm))
        DebugRow(label = "Last decision", value = debug.lastDecisionReason)
        DebugRow(label = "Cooldown", value = if (debug.isInCooldown) "active" else "clear")
        DebugRow(label = "Alert", value = if (state.alertActive) "triggered" else "suppressed/none")
    }
    Spacer(modifier = Modifier.height(8.dp))
}

@Composable
private fun DebugRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}
