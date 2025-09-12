package com.ai.assistance.operit.terminal.aidl

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

@Parcelize
data class TerminalStateParcelable(
    val sessions: List<TerminalSessionDataParcelable>,
    val currentSessionId: String?,
    val isLoading: Boolean,
    val error: String?
) : Parcelable 