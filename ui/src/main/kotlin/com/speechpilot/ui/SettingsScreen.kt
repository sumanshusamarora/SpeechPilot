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
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
        onBack = onBack
    )
}

@Composable
private fun SettingsContent(
    prefs: UserPreferences,
    onTargetWpmChange: (Int) -> Unit,
    onTolerancePctChange: (Float) -> Unit,
    onFeedbackCooldownChange: (Long) -> Unit,
    onBack: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Top
    ) {
        Text(
            text = "Settings",
            style = MaterialTheme.typography.headlineMedium
        )

        Spacer(modifier = Modifier.height(24.dp))

        // ── Target WPM ────────────────────────────────────────────────────────
        SettingRow(label = "Target pace", value = "~${prefs.targetWpm} WPM")
        Slider(
            value = prefs.targetWpm.toFloat(),
            onValueChange = { onTargetWpmChange(it.roundToInt()) },
            valueRange = 60f..250f,
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(16.dp))

        // ── Tolerance band ────────────────────────────────────────────────────
        val tolerancePct = (prefs.tolerancePct * 100).roundToInt()
        SettingRow(label = "Tolerance band", value = "±$tolerancePct%")
        Slider(
            value = prefs.tolerancePct,
            onValueChange = onTolerancePctChange,
            valueRange = 0.05f..0.40f,
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(16.dp))

        // ── Feedback cooldown ─────────────────────────────────────────────────
        val cooldownSec = (prefs.feedbackCooldownMs / 1_000L).toInt()
        SettingRow(label = "Feedback cooldown", value = "${cooldownSec}s")
        Slider(
            value = prefs.feedbackCooldownMs.toFloat(),
            onValueChange = { onFeedbackCooldownChange(it.toLong()) },
            valueRange = 1_000f..30_000f,
            steps = 28,
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "Feedback mode: Vibration",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(32.dp))

        Button(onClick = onBack, modifier = Modifier.align(Alignment.CenterHorizontally)) {
            Text("Back")
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
