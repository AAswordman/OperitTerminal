package com.ai.assistance.operit.terminal;

import com.ai.assistance.operit.terminal.CommandExecutionEvent;
import com.ai.assistance.operit.terminal.SessionDirectoryEvent;

oneway interface ITerminalCallback {
    void onCommandExecutionUpdate(in CommandExecutionEvent event);
    void onSessionDirectoryChanged(in SessionDirectoryEvent event);
} 