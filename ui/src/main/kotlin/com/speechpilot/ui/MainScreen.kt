package com.speechpilot.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
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
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.Top,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        BrandHeader()

        Spacer(modifier = Modifier.height(12.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            FilledTonalButton(onClick = onOpenHistory, modifier = Modifier.weight(1f)) {
                Text("History")
            }
            FilledTonalButton(onClick = onOpenSettings, modifier = Modifier.weight(1f)) {
                Text("Settings")
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            shape = RoundedCornerShape(18.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = state.statusText,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold
                )

                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = if (state.isSpeechDetected) "Speech activity detected" else "Ready to coach your pace",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                if (state.isListening && state.sessionMode == SessionMode.Passive) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "Passive mode active — feedback suppressed",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.secondary
                    )
                }
            }
        }

        val displayWpm = if (state.smoothedWpm > 0f) state.smoothedWpm else state.currentWpm
        if (displayWpm > 0f) {
            Spacer(modifier = Modifier.height(12.dp))
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(18.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text("Live Pace", style = MaterialTheme.typography.labelLarge)
                    Text(
                        text = "~%.0f WPM".format(displayWpm),
                        style = MaterialTheme.typography.displaySmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                    Text(
                        text = "${state.segmentCount} segment${if (state.segmentCount == 1) "" else "s"}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        state.latestFeedback?.let { feedback ->
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = when (feedback) {
                    FeedbackEvent.SlowDown -> "⚠ Slow down"
                    FeedbackEvent.SpeedUp -> "⚠ Speed up"
                    FeedbackEvent.OnTarget -> "✓ On target"
                },
                style = MaterialTheme.typography.bodyLarge,
                color = if (state.alertActive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
            )
        }

        state.errorMessage?.let { error ->
            Spacer(modifier = Modifier.height(10.dp))
            Text(text = error, color = MaterialTheme.colorScheme.error)
            TextButton(onClick = onDismissError) { Text("Dismiss") }
        }

        Spacer(modifier = Modifier.weight(1f))

        if (state.isListening || state.isSpeechDetected) {
            DebugPanel(state = state)
            Spacer(modifier = Modifier.height(12.dp))
        }

        when {
            !state.permissionGranted -> {
                Text(
                    text = "Microphone permission required to start a session.",
                    style = MaterialTheme.typography.bodyMedium
                )
            }
            state.isSessionActive -> {
                Button(onClick = onStopSession, modifier = Modifier.fillMaxWidth()) {
                    Text("Stop Session")
                }
            }
            else -> {
                Button(onClick = onStartSession, modifier = Modifier.fillMaxWidth()) {
                    Text("Start Session")
                }
            }
        }
        Spacer(modifier = Modifier.height(12.dp))
    }
}

@Composable
private fun BrandHeader() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Box(
            modifier = Modifier
                .size(56.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(MaterialTheme.colorScheme.surface),
            contentAlignment = Alignment.Center
        ) {
            androidx.compose.foundation.Image(
                painter = painterResource(id = com.speechpilot.ui.R.drawable.ic_speechpilot_logo),
                contentDescription = "SpeechPilot logo",
                modifier = Modifier.size(42.dp),
                contentScale = ContentScale.Fit
            )
        }

        Column(modifier = Modifier.weight(1f)) {
            Text("SpeechPilot", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text(
                "Live speech pace coach",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun DebugPanel(state: MainUiState) {
    val debug = state.debugInfo

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                text = "Debug Calibration",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

            val speechLabel = when {
                state.isSpeechDetected -> "yes (this session)"
                state.isListening -> "none yet"
                else -> "no"
            }
            DebugRow(label = "Speech seen", value = speechLabel)
            DebugRow(label = "Heuristic pace", value = "%.1f est-WPM".format(state.currentWpm))
            DebugRow(label = "Smoothed heuristic", value = "%.1f est-WPM".format(state.smoothedWpm))
            DebugRow(label = "Transcript WPM", value = "%.1f text-WPM".format(state.transcriptRollingWpm))
            DebugRow(label = "Target", value = "%.0f est-WPM".format(debug.targetWpm))
            DebugRow(label = "Transcription", value = debug.transcriptionStatus.name.lowercase())
            DebugRow(label = "Last decision", value = debug.lastDecisionReason)
            DebugRow(label = "Cooldown", value = if (debug.isInCooldown) "active" else "clear")
            DebugRow(label = "Alert", value = if (state.alertActive) "triggered" else "suppressed/none")

            if (state.transcriptText.isNotBlank()) {
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "Transcript: ${state.transcriptText}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
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
        Text(text = value, style = MaterialTheme.typography.bodySmall)
    }
}
