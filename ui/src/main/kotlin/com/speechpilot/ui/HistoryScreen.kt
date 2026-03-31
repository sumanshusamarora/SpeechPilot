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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
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
import com.speechpilot.data.SessionRecord
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

@Composable
fun HistoryScreen(
    viewModel: HistoryViewModel = viewModel(),
    onBack: () -> Unit,
    onReanalyze: (String) -> Unit = {}
) {
    val sessions by viewModel.sessions.collectAsState()
    HistoryContent(sessions = sessions, onBack = onBack, onReanalyze = onReanalyze)
}

@Composable
private fun HistoryContent(
    sessions: List<SessionRecord>,
    onBack: () -> Unit,
    onReanalyze: (String) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
            .padding(20.dp)
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
                Text(text = "Session History", style = MaterialTheme.typography.headlineSmall)
            }
            FilledTonalButton(onClick = onBack) { Text("Back") }
        }

        Spacer(modifier = Modifier.height(16.dp))

        if (sessions.isEmpty()) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                shape = RoundedCornerShape(16.dp)
            ) {
                Text(
                    text = "No sessions recorded yet.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(18.dp)
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(sessions) { record ->
                    SessionSummaryCard(
                        record = record,
                        onReanalyze = record.audioFileUri?.let { uri ->
                            { onReanalyze(uri) }
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun SessionSummaryCard(
    record: SessionRecord,
    onReanalyze: (() -> Unit)? = null
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = formatTimestamp(record.startedAtMs),
                    style = MaterialTheme.typography.titleSmall
                )
                if (record.audioFileUri != null) {
                    Text(
                        text = "File analysis",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.secondary
                    )
                }
            }
            Spacer(modifier = Modifier.height(6.dp))
            HorizontalDivider()
            Spacer(modifier = Modifier.height(6.dp))
            SummaryRow("Duration", formatDuration(record.durationMs))
            SummaryRow("Speech time", formatDuration(record.totalSpeechActiveDurationMs))
            SummaryRow("Segments", "${record.segmentCount}")
            SummaryRow("Avg ~WPM", "~%.0f".format(record.averageEstimatedWpm))
            SummaryRow("Peak ~WPM", "~%.0f".format(record.peakEstimatedWpm))
            if (onReanalyze != null) {
                Spacer(modifier = Modifier.height(10.dp))
                FilledTonalButton(
                    onClick = onReanalyze,
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Re-analyze") }
            }
        }
    }
}

@Composable
private fun SummaryRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(text = label, style = MaterialTheme.typography.bodySmall)
        Text(text = value, style = MaterialTheme.typography.bodySmall)
    }
}

private fun formatTimestamp(epochMs: Long): String {
    val fmt = SimpleDateFormat("MMM d, yyyy  HH:mm", Locale.getDefault())
    return fmt.format(Date(epochMs))
}

private fun formatDuration(ms: Long): String {
    val totalSec = TimeUnit.MILLISECONDS.toSeconds(ms)
    val min = totalSec / 60
    val sec = totalSec % 60
    return if (min > 0) "${min}m ${sec}s" else "${sec}s"
}
