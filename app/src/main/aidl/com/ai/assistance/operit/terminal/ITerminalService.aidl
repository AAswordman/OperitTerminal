package com.ai.assistance.operit.terminal;

import com.ai.assistance.operit.terminal.ITerminalCallback;
import com.ai.assistance.operit.terminal.TerminalStateParcelable;

interface ITerminalService {
    String createSession();
    void switchToSession(String sessionId);
    void closeSession(String sessionId);
    void sendCommand(String command);
    void sendInterruptSignal();
    void registerCallback(ITerminalCallback callback);
    void unregisterCallback(ITerminalCallback callback);
    void requestStateUpdate();
} 