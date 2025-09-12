package com.ai.assistance.operit.terminal.aidl;

import com.ai.assistance.operit.terminal.aidl.TerminalStateParcelable;

oneway interface ITerminalCallback {
    void onStateUpdated(in TerminalStateParcelable state);
} 