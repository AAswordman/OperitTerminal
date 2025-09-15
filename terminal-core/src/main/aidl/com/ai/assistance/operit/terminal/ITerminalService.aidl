package com.ai.assistance.operit.terminal;

import com.ai.assistance.operit.terminal.ITerminalCallback;

interface ITerminalService {
    String createSession();
    void switchToSession(String sessionId);
    void closeSession(String sessionId);
    String sendCommand(String command);
    void sendInterruptSignal();
    void registerCallback(ITerminalCallback callback);
    void unregisterCallback(ITerminalCallback callback);
    void requestStateUpdate();
} 