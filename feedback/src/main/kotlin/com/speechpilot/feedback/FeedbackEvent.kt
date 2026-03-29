package com.speechpilot.feedback

sealed class FeedbackEvent {
    data object SlowDown : FeedbackEvent()
    data object SpeedUp : FeedbackEvent()
    data object OnTarget : FeedbackEvent()
}
