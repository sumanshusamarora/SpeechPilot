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
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.speechpilot.feedback.FeedbackEvent
import com.speechpilot.modelmanager.ModelInstallState
import com.speechpilot.session.PaceSignalSource
import com.speechpilot.session.SessionMode
import com.speechpilot.session.TranscriptDebugStatus
import com.speechpilot.transcription.TranscriptionBackend

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
        onOpenHistory = onOpenHistory,
        onRetryModelInstall = viewModel::retryActiveModelInstall,
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
    onOpenHistory: () -> Unit,
    onRetryModelInstall: () -> Unit,
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

            // Show model provisioning status when transcription is enabled and model is not Ready.
            val modelState = state.activeModelInstallState
            if (state.transcriptionEnabled && modelState != null && modelState !is ModelInstallState.Ready) {
                item {
                    ModelProvisioningCard(
                        installState = modelState,
                        onRetry = onRetryModelInstall,
                        modelDisplayName = state.activeModelDisplayName,
                        approxSizeMb = state.activeModelApproxSizeMb,
                        wifiRecommended = state.activeModelWifiRecommended,
                    )
                }
            }

            // Show a persistent warning when Whisper is selected but the native library
            // failed to load. This surfaces the real failure reason instead of silently
            // falling back to Android SpeechRecognizer.
            if (state.whisperSelected && !state.whisperNativeLibLoaded) {
                item { WhisperRuntimeWarningCard(state.transcriptDebug.diagnostics) }
            }

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

            if (state.transcriptionEnabled && state.isSessionActive) {
                item { TranscriptCard(state = state) }
            }

            item { LivePaceCard(state = state) }

            state.latestFeedback?.let { feedback ->
                item {
                    FeedbackChip(
                        feedback = feedback,
                        isAlertActive = state.alertActive,
                        activePaceSource = state.debugInfo.activePaceSource,
                        paceSourceReason = state.debugInfo.paceSourceReason
                    )
                }
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

            if (state.isListening || state.isSpeechDetected) {
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
private fun TranscriptCard(state: MainUiState) {
    val transcript = resolveTranscriptSurfacePresentation(state)
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = transcript.title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = transcript.helperText,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary
            )
            Text(
                text = transcript.bodyText ?: "No final words yet.",
                style = MaterialTheme.typography.bodyLarge,
                fontStyle = if (transcript.showAsFinal) FontStyle.Normal else FontStyle.Italic,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun LivePaceCard(state: MainUiState) {
    val pace = resolvePaceMetricPresentation(state)
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
            Text(pace.headline, style = MaterialTheme.typography.labelLarge)
            Text(
                text = pace.primaryValue,
                style = MaterialTheme.typography.displaySmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )
            Text(
                text = pace.primaryLabel,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )
            Text(
                text = pace.detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )
            Text(
                text = "${state.segmentCount} segment${if (state.segmentCount == 1) "" else "s"}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )
        }
    }
}

@Composable
private fun FeedbackChip(
    feedback: FeedbackEvent,
    isAlertActive: Boolean,
    activePaceSource: PaceSignalSource,
    paceSourceReason: String
) {
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
        Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
            Text(
                text = message,
                style = MaterialTheme.typography.bodyLarge,
                color = contentColor
            )
            if (activePaceSource == PaceSignalSource.Transcript) {
                Text(
                    text = "Using text WPM for coaching decisions.",
                    style = MaterialTheme.typography.bodySmall,
                    color = contentColor
                )
            } else if (activePaceSource == PaceSignalSource.Heuristic) {
                val fallbackText = when (paceSourceReason) {
                    "transcript-pending-fallback" -> "Transcript pending — using heuristic pace."
                    "transcript-unavailable-fallback" -> "Transcript unavailable — using heuristic pace."
                    "transcript-error-fallback" -> "Transcript error — using heuristic pace."
                    else -> "Using heuristic pace for coaching decisions."
                }
                Text(
                    text = fallbackText,
                    style = MaterialTheme.typography.bodySmall,
                    color = contentColor
                )
            }
        }
    }
}

@Composable
private fun DebugPanel(state: MainUiState) {
    val debug = state.debugInfo
    val transcriptDiagnostics = state.transcriptDebug.diagnostics

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
                "Selected backend" to backendLabel(transcriptDiagnostics.selectedBackend),
                "Active backend" to backendLabel(transcriptDiagnostics.activeBackend),
                "Selected backend status" to transcriptDiagnostics.selectedBackendStatus.name.lowercase(),
                "Active backend status" to transcriptDiagnostics.activeBackendStatus.name.lowercase(),
                "Backend fallback" to if (transcriptDiagnostics.fallbackActive) "yes" else "no",
                "Fallback reason" to (transcriptDiagnostics.fallbackReason?.message ?: "none"),
                "Whisper selected" to if (state.whisperSelected) "yes" else "no",
                "Whisper native lib" to when (transcriptDiagnostics.nativeLibraryLoaded) {
                    true -> "loaded"
                    false -> "not loaded"
                    null -> "n/a"
                },
                "Native load error" to (transcriptDiagnostics.nativeLibraryLoadError ?: "none"),
                "Model file present" to if (transcriptDiagnostics.modelFilePresent) "yes" else "no",
                "Model path" to (transcriptDiagnostics.modelPath ?: "n/a"),
                "Primary init succeeded" to if (transcriptDiagnostics.selectedBackendInitSucceeded) "yes" else "no",
                "Audio source attached" to if (transcriptDiagnostics.audioSourceAttached) "yes" else "no",
                "Audio reached primary" to if (transcriptDiagnostics.selectedBackendAudioFramesReceived > 0) "yes" else "no",
                "Primary audio frames" to transcriptDiagnostics.selectedBackendAudioFramesReceived.toString(),
                "Whisper buffer samples" to transcriptDiagnostics.selectedBackendBufferedSamples.toString(),
                "Chunks processed" to transcriptDiagnostics.chunksProcessed.toString(),
                "Primary transcript updates" to transcriptDiagnostics.selectedBackendTranscriptUpdatesEmitted.toString(),
                "Fallback transcript updates" to transcriptDiagnostics.fallbackTranscriptUpdatesEmitted.toString(),
                "Last transcript source" to backendLabel(transcriptDiagnostics.lastTranscriptSource),
                "Last transcript error" to (transcriptDiagnostics.lastTranscriptError?.message ?: "none"),
                "Last transcript success" to (transcriptDiagnostics.lastSuccessfulTranscriptAtMs?.toString() ?: "none"),
                "Transcript status" to transcriptStatusLabel(state.transcriptDebug.status),
                "Text WPM" to if (state.transcriptDebug.wpmPendingFinalRecognition) {
                    "pending final recognition"
                } else {
                    "%.1f text-WPM".format(state.transcriptDebug.rollingWpm)
                },
                "Heuristic pace" to if (debug.heuristicWpm > 0.0) {
                    "%.1f est-WPM".format(debug.heuristicWpm)
                } else {
                    "none"
                },
                "Decision signal" to debug.activePaceSource.name.lowercase(),
                "Source reason" to debug.paceSourceReason,
                "Pace fallback" to if (debug.fallbackActive) "yes" else "no",
                "Transcript ready" to if (debug.transcriptReadyForDecision) "yes" else "no",
                "Decision pace" to if (debug.decisionWpm > 0.0) {
                    "%.1f WPM".format(debug.decisionWpm)
                } else {
                    "none"
                },
                "Target" to "%.0f WPM".format(debug.targetWpm),
                "Last decision" to debug.lastDecisionReason,
                "Cooldown" to if (debug.isInCooldown) "active" else "clear"
            )

            rows.forEach { (label, value) ->
                DebugRow(label = label, value = value)
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
    TranscriptDebugStatus.ModelUnavailable -> "model unavailable — using fallback"
    TranscriptDebugStatus.NativeLibraryUnavailable -> "native library not loaded — using fallback"
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
private fun WhisperRuntimeWarningCard(diagnostics: com.speechpilot.transcription.TranscriptionDiagnostics) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                text = "Whisper runtime unavailable — using Android fallback",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onErrorContainer,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = diagnostics.fallbackReason?.message ?: "The Whisper speech engine could not start on this device. " +
                    "Transcription is running via Android SpeechRecognizer instead.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            Text(
                text = diagnostics.nativeLibraryLoadError?.let { "Native loader detail: $it" }
                    ?: "Try reinstalling the app if Whisper was expected to be available.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
        }
    }
}

private fun backendLabel(backend: TranscriptionBackend): String = when (backend) {
    TranscriptionBackend.DedicatedLocalStt -> "Vosk"
    TranscriptionBackend.WhisperCpp -> "Whisper"
    TranscriptionBackend.AndroidSpeechRecognizer -> "Android recognizer"
    TranscriptionBackend.None -> "None"
}

@Composable
private fun ModelProvisioningCard(
    installState: ModelInstallState,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
    modelDisplayName: String = "Speech Model",
    approxSizeMb: Int = 0,
    wifiRecommended: Boolean = false,
) {
    val isFailed = installState is ModelInstallState.Failed
    val cardColor = if (isFailed) {
        MaterialTheme.colorScheme.errorContainer
    } else {
        MaterialTheme.colorScheme.surfaceVariant
    }
    val contentColor = if (isFailed) {
        MaterialTheme.colorScheme.onErrorContainer
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = cardColor),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = modelDisplayName,
                style = MaterialTheme.typography.labelLarge,
                color = contentColor,
                fontWeight = FontWeight.SemiBold,
            )

            when (installState) {
                is ModelInstallState.NotInstalled, ModelInstallState.Queued -> {
                    if (approxSizeMb > 0) {
                        val sizeLabel = if (approxSizeMb >= 1000) {
                            "~${approxSizeMb / 1024} GB"
                        } else {
                            "~$approxSizeMb MB"
                        }
                        val wifiNote = if (wifiRecommended) " · Wi-Fi recommended" else ""
                        Text(
                            text = "Download size: $sizeLabel$wifiNote",
                            style = MaterialTheme.typography.bodySmall,
                            color = contentColor,
                        )
                    }
                    Text(
                        text = "Preparing model download…",
                        style = MaterialTheme.typography.bodySmall,
                        color = contentColor,
                    )
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }

                is ModelInstallState.Downloading -> {
                    val label = if (installState.progressPercent >= 0) {
                        val mb = installState.bytesReceived / (1024 * 1024)
                        val totalMb = if (installState.totalBytes > 0) {
                            " / ${installState.totalBytes / (1024 * 1024)} MB"
                        } else ""
                        "Downloading… ${installState.progressPercent}% (${mb} MB$totalMb)"
                    } else {
                        val mb = installState.bytesReceived / (1024 * 1024)
                        "Downloading… ${mb} MB received"
                    }
                    Text(
                        text = label,
                        style = MaterialTheme.typography.bodySmall,
                        color = contentColor,
                    )
                    if (installState.progressPercent >= 0) {
                        LinearProgressIndicator(
                            progress = { installState.progressPercent / 100f },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    } else {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                }

                ModelInstallState.Unpacking -> {
                    Text(
                        text = "Installing model…",
                        style = MaterialTheme.typography.bodySmall,
                        color = contentColor,
                    )
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }

                ModelInstallState.Verifying -> {
                    Text(
                        text = "Verifying model…",
                        style = MaterialTheme.typography.bodySmall,
                        color = contentColor,
                    )
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }

                is ModelInstallState.Failed -> {
                    Text(
                        text = "Model setup failed: ${installState.reason}",
                        style = MaterialTheme.typography.bodySmall,
                        color = contentColor,
                    )
                    Text(
                        text = "Transcription will use the Android fallback until the model is installed.",
                        style = MaterialTheme.typography.bodySmall,
                        color = contentColor,
                    )
                    TextButton(onClick = onRetry) {
                        Text("Retry Download", color = MaterialTheme.colorScheme.error)
                    }
                }

                ModelInstallState.Ready -> {
                    // Not shown — the card is hidden when state is Ready.
                }
            }
        }
    }
}


@Composable
fun AudioLevelBars(
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
