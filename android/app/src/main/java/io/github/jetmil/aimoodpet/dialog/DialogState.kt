package io.github.jetmil.aimoodpet.dialog

enum class DialogPhase { Idle, Recording, Sent, Transcribing, Replying, Done }

data class DialogUiState(
    val phase: DialogPhase = DialogPhase.Idle,
    val transcript: String = "",
    val reply: String = "",
    val connectionLabel: String = "offline",
)
