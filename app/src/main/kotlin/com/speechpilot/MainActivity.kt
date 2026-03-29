package com.speechpilot

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import com.speechpilot.service.SpeechCoachingService
import com.speechpilot.ui.AppNavigation
import com.speechpilot.ui.MainViewModel
import com.speechpilot.ui.theme.SpeechPilotTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private lateinit var viewModel: MainViewModel

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        viewModel.onPermissionResult(granted)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        viewModel = ViewModelProvider(this)[MainViewModel::class.java]
        checkOrRequestMicrophonePermission()
        observeSessionServiceLifecycle()

        setContent {
            SpeechPilotTheme {
                AppNavigation(mainViewModel = viewModel)
            }
        }
    }

    /**
     * Observes session active state and starts/stops the foreground service on state transitions.
     *
     * [previouslyActive] tracks the last-seen active state so that start/stop are only called
     * when the state actually changes — not on every UI state emission. On activity recreation
     * after a config change, a single start call may be made if a session is already running,
     * which is safe since [startForegroundService] is idempotent for an already-running service.
     */
    private fun observeSessionServiceLifecycle() {
        lifecycleScope.launch {
            var previouslyActive = false
            viewModel.uiState.collect { state ->
                val isActive = state.isSessionActive
                if (isActive && !previouslyActive) {
                    startForegroundService(SpeechCoachingService.startIntent(this@MainActivity))
                } else if (!isActive && previouslyActive) {
                    stopService(SpeechCoachingService.stopIntent(this@MainActivity))
                }
                previouslyActive = isActive
            }
        }
    }

    private fun checkOrRequestMicrophonePermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            == PackageManager.PERMISSION_GRANTED
        ) {
            viewModel.onPermissionResult(true)
        } else {
            requestPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }
}
