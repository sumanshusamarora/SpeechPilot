package com.speechpilot.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.speechpilot.feedback.FeedbackEvent
import com.speechpilot.session.SessionMode
import com.speechpilot.session.TranscriptDebugStatus

@Composable
fun MainScreen(
    viewModel: MainViewModel = viewModel(),
    onOpenSettings: () -> Unit = {},
    onOpenHistory: () -> Unit = {}
) {
    val state by viewModel.uiState.collectAsState()
    val context = LocalContext.current

    val fileLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let {
            try {
                context.contentResolver.takePersistableUriPermission(
                    it,
                    android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (_: SecurityException) {
                // Some providers do not grant persistable permissions; continue anyway.
            }
            viewModel.startFileSession(it)
        }
    }

    MainContent(
        state = state,
        onStartSession = viewModel::startSession,
        onStopSession = viewModel::stopSession,
        onDismissError = viewModel::dismissError,
        onAnalyzeFile = { fileLauncher.launch(arrayOf("audio/*")) },
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
    onAnalyzeFile: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenHistory: () -> Unit
) {
    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            Surface(shadowElevation = 10.dp) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surface)
                        .navigationBarsPadding()
                        .padding(horizontal = 20.dp, vertical = 14.dp)
                ) {
                    when {
                        !state.permissionGranted -> {
                            Text(
                                text = "Microphone permission is required for live coaching. You can still analyze audio files.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.height(10.dp))
                            FilledTonalButton(
                                onClick = onAnalyzeFile,
                                modifier = Modifier.fillMaxWidth()
                            ) { Text("Analyze Audio File") }
                        }

                        state.isSessionActive -> {
                            Button(onClick = onStopSession, modifier = Modifier.fillMaxWidth()) {
                                Text(if (state.isFileSession) "Stop Analysis" else "Stop Session")
                            }
                        }

                        else -> {
                            Button(onClick = onStartSession, modifier = Modifier.fillMaxWidth()) {
                                Text("Start Session")
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                            FilledTonalButton(
                                onClick = onAnalyzeFile,
                                modifier = Modifier.fillMaxWidth()
                            ) { Text("Analyze Audio File") }
                        }
                    }
                }
            }
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .padding(horizontal = 20.dp),
            contentPadding = PaddingValues(
                top = 16.dp,
                bottom = innerPadding.calculateBottomPadding() + 20.dp
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item { BrandHeader() }

            item {
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
            }

            item { SessionStatusCard(state = state) }

            val displayWpm = if (state.smoothedWpm > 0f) state.smoothedWpm else state.currentWpm
            if (displayWpm > 0f) {
                item {
                    LivePaceCard(
                        displayWpm = displayWpm,
                        segmentCount = state.segmentCount
                    )
                }
            }

            state.latestFeedback?.let { feedback ->
                item { FeedbackChip(feedback = feedback, isAlertActive = state.alertActive) }
            }

            state.errorMessage?.let { error ->
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
                    ) {
                        Column(modifier = Modifier.padding(14.dp)) {
                            Text(
                                text = error,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                                style = MaterialTheme.typography.bodyMedium
                            )
                            TextButton(onClick = onDismissError) { Text("Dismiss") }
                        }
                    }
                }
            }

            if (state.isListening || state.isSpeechDetected || state.transcriptDebugEnabled) {
                item { DebugPanel(state = state) }
            }
        }
    }
}

@Composable
private fun BrandHeader() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Image(
                painter = painterResource(id = com.speechpilot.ui.R.drawable.speechpilot_brand_logo),
                contentDescription = "SpeechPilot logo",
                modifier = Modifier
                    .size(58.dp)
                    .clip(RoundedCornerShape(16.dp)),
                contentScale = ContentScale.Crop
            )

            Column(modifier = Modifier.weight(1f)) {
                Text("SpeechPilot", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text(
                    "AI speech pace coach",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Box(
                modifier = Modifier
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primaryContainer)
                    .padding(horizontal = 10.dp, vertical = 6.dp)
            ) {
                Text(
                    text = "LIVE",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    }
}

@Composable
private fun SessionStatusCard(state: MainUiState) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(20.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = state.statusText,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f)
                )
                if (state.isSessionActive) {
                    AudioLevelBars(
                        level = state.micLevel,
                        isSpeechActive = state.isSpeechActive
                    )
                }
            }

            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = when {
                    state.isFileSession && state.isSessionActive -> "Processing uploaded audio file"
                    state.isSpeechActive -> "Speaking now"
                    state.isListening && state.isSpeechDetected -> "Speech detected this session"
                    state.isListening -> "Waiting for speech…"
                    else -> "Ready to coach your pace"
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            if (state.isListening && state.sessionMode == SessionMode.Passive) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Passive mode active — feedback suppressed",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.secondary
                )
            }
        }
    }
}

@Composable
private fun LivePaceCard(displayWpm: Float, segmentCount: Int) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(18.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("Live Pace", style = MaterialTheme.typography.labelLarge)
            Text(
                text = "~%.0f WPM".format(displayWpm),
                style = MaterialTheme.typography.displaySmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )
            Text(
                text = "$segmentCount segment${if (segmentCount == 1) "" else "s"}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun FeedbackChip(feedback: FeedbackEvent, isAlertActive: Boolean) {
    val message = when (feedback) {
        FeedbackEvent.SlowDown -> "⚠ Slow down"
        FeedbackEvent.SpeedUp -> "⚠ Speed up"
        FeedbackEvent.OnTarget -> "✓ On target"
    }
    val cardColor = if (isAlertActive) {
        MaterialTheme.colorScheme.errorContainer
    } else {
        MaterialTheme.colorScheme.tertiary.copy(alpha = 0.2f)
    }
    val contentColor = if (isAlertActive) {
        MaterialTheme.colorScheme.onErrorContainer
    } else {
        MaterialTheme.colorScheme.onSurface
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = cardColor)
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodyLarge,
            color = contentColor,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp)
        )
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
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                text = "Debug Calibration",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

            val speechLabel = when {
                state.isSpeechActive -> "yes (right now)"
                state.isSpeechDetected -> "yes (this session)"
                state.isListening -> "none yet"
                else -> "no"
            }

            val rows = listOf(
                "Speech active" to speechLabel,
                "Mic level" to "%.2f".format(state.micLevel),
                "VAD frame RMS" to "%.1f".format(debug.vadFrameRms),
                "VAD threshold" to "%.0f".format(debug.vadThreshold),
                "VAD frame class" to debug.vadFrameClassification.name.lowercase(),
                "Segment open" to if (debug.isSegmentOpen) "yes" else "no",
                "Open seg frames" to debug.openSegmentFrameCount.toString(),
                "Open seg silence" to debug.openSegmentSilenceFrameCount.toString(),
                "Finalized segs" to debug.finalizedSegmentsCount.toString(),
                "Heuristic pace" to "%.1f est-WPM".format(state.currentWpm),
                "Smoothed heuristic" to "%.1f est-WPM".format(state.smoothedWpm),
                "Transcript debug" to if (state.transcriptDebugEnabled) "enabled" else "disabled",
                "Transcript status" to transcriptStatusLabel(state.transcriptDebug.status),
                "Transcript engine" to state.transcriptDebug.engineStatus.name.lowercase(),
                "Partial transcript" to if (state.transcriptDebug.partialTranscriptPresent) "yes" else "no",
                "Final transcript words" to state.transcriptDebug.finalizedWordCount.toString(),
                "Rolling transcript words" to state.transcriptDebug.rollingWordCount.toString(),
                "Transcript WPM" to if (state.transcriptDebug.wpmPendingFinalRecognition) {
                    "pending final recognition"
                } else {
                    "%.1f text-WPM".format(state.transcriptDebug.rollingWpm)
                },
                "Transcript last update" to (state.transcriptDebug.lastUpdateAtMs?.toString() ?: "none"),
                "Target" to "%.0f est-WPM".format(debug.targetWpm),
                "Transcription" to debug.transcriptionStatus.name.lowercase(),
                "Last decision" to debug.lastDecisionReason,
                "Cooldown" to if (debug.isInCooldown) "active" else "clear",
                "Alert" to if (state.alertActive) "triggered" else "suppressed/none"
            )

            rows.forEach { (label, value) ->
                DebugRow(label = label, value = value)
            }

            if (state.transcriptDebugEnabled) {
                Spacer(modifier = Modifier.height(6.dp))
                val transcriptText = state.transcriptDebug.transcriptText
                val transcriptMessage = when {
                    transcriptText.isNotBlank() -> transcriptText
                    state.transcriptDebug.status == TranscriptDebugStatus.Disabled ->
                        "Transcript debug disabled in settings."

                    state.transcriptDebug.status == TranscriptDebugStatus.Unavailable ->
                        "Transcription unavailable on this device/runtime."

                    state.transcriptDebug.status == TranscriptDebugStatus.Error ->
                        "Transcription error. Engine will retry while session is active."

                    state.transcriptDebug.partialTranscriptPresent ->
                        "Partial transcript available, waiting for final recognition."

                    else -> "No transcript text recognized yet."
                }
                Text(
                    text = "Transcript: $transcriptMessage",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

private fun transcriptStatusLabel(status: TranscriptDebugStatus): String = when (status) {
    TranscriptDebugStatus.Disabled -> "disabled"
    TranscriptDebugStatus.Listening -> "listening"
    TranscriptDebugStatus.WaitingForSpeech -> "waiting for speech"
    TranscriptDebugStatus.PartialAvailable -> "partial transcript available"
    TranscriptDebugStatus.FinalAvailable -> "final transcript available"
    TranscriptDebugStatus.Unavailable -> "unavailable"
    TranscriptDebugStatus.Error -> "error"
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
        Spacer(modifier = Modifier.width(8.dp))
        Text(text = value, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun AudioLevelBars(
    level: Float,
    isSpeechActive: Boolean,
    modifier: Modifier = Modifier
) {
    val animatedLevel by animateFloatAsState(
        targetValue = level,
        animationSpec = spring(dampingRatio = 0.6f, stiffness = 300f),
        label = "micLevel"
    )
    val barColor = if (isSpeechActive) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.outline
    }

    val heightFractions = listOf(0.4f, 0.7f, 1.0f, 0.7f, 0.4f)
    val maxBarHeightDp = 28f

    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        heightFractions.forEach { fraction ->
            val barHeight = (animatedLevel * fraction).coerceAtLeast(0.15f) * maxBarHeightDp
            Box(
                modifier = Modifier
                    .width(5.dp)
                    .height(barHeight.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(barColor)
            )
        }
    }
}
