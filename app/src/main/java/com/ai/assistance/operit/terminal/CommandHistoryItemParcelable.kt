package com.ai.assistance.operit.terminal

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

@Parcelize
data class CommandHistoryItemParcelable(
    val id: String,
    val prompt: String,
    val command: String,
    val output: String,
    val isExecuting: Boolean
) : Parcelable 