package com.speechpilot.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.speechpilot.settings.UserPreferences
import kotlin.math.roundToInt

@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel = viewModel(),
    onBack: () -> Unit
) {
    val prefs by viewModel.preferences.collectAsState()
    SettingsContent(
        prefs = prefs,
        onTargetWpmChange = viewModel::updateTargetWpm,
        onTolerancePctChange = viewModel::updateTolerancePct,
        onFeedbackCooldownChange = viewModel::updateFeedbackCooldownMs,
        onTranscriptionChange = viewModel::updateTranscriptionEnabled,
        onPreferWhisperChange = viewModel::updatePreferWhisperBackend,
        onBack = onBack
    )
}

@Composable
private fun SettingsContent(
    prefs: UserPreferences,
    onTargetWpmChange: (Int) -> Unit,
    onTolerancePctChange: (Float) -> Unit,
    onFeedbackCooldownChange: (Long) -> Unit,
    onTranscriptionChange: (Boolean) -> Unit,
    onPreferWhisperChange: (Boolean) -> Unit,
    onBack: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
            .padding(20.dp),
        verticalArrangement = Arrangement.Top
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Image(
                    painter = painterResource(id = com.speechpilot.ui.R.drawable.speechpilot_brand_logo),
                    contentDescription = "SpeechPilot logo",
                    modifier = Modifier
                        .size(38.dp)
                        .clip(RoundedCornerShape(10.dp))
                )
                Spacer(modifier = Modifier.size(10.dp))
                Text("Settings", style = MaterialTheme.typography.headlineSmall)
            }
            FilledTonalButton(onClick = onBack) { Text("Back") }
        }

        Spacer(modifier = Modifier.height(16.dp))

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                SettingRow(label = "Target pace", value = "~${prefs.targetWpm} WPM")
                Slider(
                    value = prefs.targetWpm.toFloat(),
                    onValueChange = { onTargetWpmChange(it.roundToInt()) },
                    valueRange = 60f..250f,
                    modifier = Modifier.fillMaxWidth()
                )

                val tolerancePct = (prefs.tolerancePct * 100).roundToInt()
                SettingRow(label = "Tolerance band", value = "±$tolerancePct%")
                Slider(
                    value = prefs.tolerancePct,
                    onValueChange = onTolerancePctChange,
                    valueRange = 0.05f..0.40f,
                    modifier = Modifier.fillMaxWidth()
                )

                val cooldownSec = (prefs.feedbackCooldownMs / 1_000L).toInt()
                SettingRow(label = "Feedback cooldown", value = "${cooldownSec}s")
                Slider(
                    value = prefs.feedbackCooldownMs.toFloat(),
                    onValueChange = { onFeedbackCooldownChange(it.toLong()) },
                    valueRange = 1_000f..30_000f,
                    steps = 28,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(text = "Transcription", style = MaterialTheme.typography.titleMedium)
                    Text(
                        text = "On-device speech-to-text. Provides live transcript text and text-derived WPM for accurate pace coaching.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = prefs.transcriptionEnabled,
                    onCheckedChange = onTranscriptionChange
                )
            }
        }

        if (prefs.transcriptionEnabled) {
            Spacer(modifier = Modifier.height(14.dp))

            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Use Whisper.cpp backend",
                            style = MaterialTheme.typography.titleMedium
                        )
                        Text(
                            text = if (prefs.preferWhisperBackend)
                                "Whisper.cpp (ggml-tiny.en) is the primary STT backend. Requires ~75 MB model download. Tuned for faster live on-device English transcription."
                            else
                                "Vosk is the primary STT backend. Requires ~40 MB model download. Toggle to switch to Whisper.cpp.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = prefs.preferWhisperBackend,
                        onCheckedChange = onPreferWhisperChange
                    )
                }
            }
        }
    }
}

@Composable
private fun SettingRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text = label, style = MaterialTheme.typography.bodyLarge)
        Text(
            text = value,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.primary
        )
    }
}
