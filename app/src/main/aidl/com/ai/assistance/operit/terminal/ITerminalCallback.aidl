package com.ai.assistance.operit.terminal;

import com.ai.assistance.operit.terminal.TerminalStateParcelable;

oneway interface ITerminalCallback {
    void onStateUpdated(in TerminalStateParcelable state);
} 