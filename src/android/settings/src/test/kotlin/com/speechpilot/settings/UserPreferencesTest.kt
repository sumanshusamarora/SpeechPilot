package com.speechpilot.settings

import org.junit.Assert.assertEquals
import org.junit.Test

class UserPreferencesTest {

    @Test
    fun `default targetWpm is 130`() {
        assertEquals(130, UserPreferences().targetWpm)
    }

    @Test
    fun `default tolerancePct is 0_15`() {
        assertEquals(0.15f, UserPreferences().tolerancePct, 0.001f)
    }

    @Test
    fun `default feedbackCooldownMs is 5000`() {
        assertEquals(5_000L, UserPreferences().feedbackCooldownMs)
    }

    @Test
    fun `default micSampleRate is 16000`() {
        assertEquals(16_000, UserPreferences().micSampleRate)
    }

    @Test
    fun `default transcriptionEnabled is true`() {
        assertEquals(true, UserPreferences().transcriptionEnabled)
    }

    @Test
    fun `default preferWhisperBackend is true`() {
        assertEquals(true, UserPreferences().preferWhisperBackend)
    }

    @Test
    fun `default whisperModelId is tiny`() {
        assertEquals("whisper-ggml-tiny-en", UserPreferences().whisperModelId)
    }

    @Test
    fun `copy updates only specified field`() {
        val base = UserPreferences()
        val updated = base.copy(targetWpm = 160)
        assertEquals(160, updated.targetWpm)
        assertEquals(base.tolerancePct, updated.tolerancePct, 0.001f)
        assertEquals(base.feedbackCooldownMs, updated.feedbackCooldownMs)
        assertEquals(base.micSampleRate, updated.micSampleRate)
        assertEquals(base.transcriptionEnabled, updated.transcriptionEnabled)
        assertEquals(base.preferWhisperBackend, updated.preferWhisperBackend)
        assertEquals(base.whisperModelId, updated.whisperModelId)
    }

    @Test
    fun `copy preserves unchanged cooldown`() {
        val base = UserPreferences(targetWpm = 120)
        val updated = base.copy(feedbackCooldownMs = 3_000L)
        assertEquals(120, updated.targetWpm)
        assertEquals(3_000L, updated.feedbackCooldownMs)
    }

    @Test
    fun `custom values are stored correctly`() {
        val prefs = UserPreferences(
            targetWpm = 150,
            tolerancePct = 0.20f,
            feedbackCooldownMs = 8_000L,
            micSampleRate = 44_100,
            transcriptionEnabled = false,
            preferWhisperBackend = false,
            whisperModelId = "whisper-ggml-base-en"
        )
        assertEquals(150, prefs.targetWpm)
        assertEquals(0.20f, prefs.tolerancePct, 0.001f)
        assertEquals(8_000L, prefs.feedbackCooldownMs)
        assertEquals(44_100, prefs.micSampleRate)
        assertEquals(false, prefs.transcriptionEnabled)
        assertEquals(false, prefs.preferWhisperBackend)
        assertEquals("whisper-ggml-base-en", prefs.whisperModelId)
    }
}
