package com.ai.assistance.operit.terminal

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

@Parcelize
data class TerminalSessionDataParcelable(
    val id: String,
    val title: String,
    val currentDirectory: String,
    val commandHistory: List<CommandHistoryItemParcelable>,
    val isInteractiveMode: Boolean,
    val interactivePrompt: String,
    val isInitializing: Boolean
) : Parcelable 