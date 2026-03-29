package com.speechpilot.vad

sealed class VadResult {
    data object Speech : VadResult()
    data object Silence : VadResult()
}
