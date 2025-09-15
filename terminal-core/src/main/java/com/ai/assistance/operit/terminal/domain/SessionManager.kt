package com.ai.assistance.operit.terminal.domain

import android.util.Log
import com.ai.assistance.operit.terminal.TerminalManager
import com.ai.assistance.operit.terminal.data.SessionInitState
import com.ai.assistance.operit.terminal.data.TerminalSessionData
import com.ai.assistance.operit.terminal.data.TerminalState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

/**
 * 终端会话管理器
 * 负责管理多个终端会话的生命周期
 */
class SessionManager(private val terminalManager: TerminalManager) {
    
    private val _state = MutableStateFlow(TerminalState())
    val state: StateFlow<TerminalState> = _state.asStateFlow()
    
    /**
     * 创建新会话
     */
    fun createNewSession(): TerminalSessionData {
        val currentState = _state.value
        val sessionCount = currentState.sessions.size + 1
        val newSession = TerminalSessionData(
            title = "Ubuntu $sessionCount"
        )
        newSession.commandHistory.add(
                com.ai.assistance.operit.terminal.data.CommandHistoryItem(
                    id = UUID.randomUUID().toString(),
                    prompt = "",
                    command = "Initializing environment...",
                    output = "",
                    isExecuting = false
            )
        )
        
        _state.value = currentState.copy(
            sessions = currentState.sessions + newSession,
            currentSessionId = newSession.id
        )
        
        Log.d("SessionManager", "Created new session: ${newSession.id}")
        return newSession
    }
    
    /**
     * 切换到指定会话
     */
    fun switchToSession(sessionId: String): Boolean {
        val currentState = _state.value
        return if (currentState.sessions.any { it.id == sessionId }) {
            _state.value = currentState.copy(currentSessionId = sessionId)
            Log.d("SessionManager", "Switched to session: $sessionId")
            true
        } else {
            Log.w("SessionManager", "Session not found: $sessionId")
            false
        }
    }
    
    /**
     * 关闭会话
     */
    fun closeSession(sessionId: String) {
        val currentState = _state.value
        val sessionToClose = currentState.sessions.find { it.id == sessionId }
        
        sessionToClose?.let { session ->
            try {
                // 清理资源
                session.readJob?.cancel()
                session.sessionWriter?.close()
                terminalManager.closeTerminalSession(session.id)
            } catch (e: Exception) {
                Log.e("SessionManager", "Error cleaning up session", e)
            }
        }
        
        val updatedSessions = currentState.sessions.filter { it.id != sessionId }
        val newCurrentSessionId = if (currentState.currentSessionId == sessionId) {
            updatedSessions.firstOrNull()?.id
        } else {
            currentState.currentSessionId
        }
        
        _state.value = currentState.copy(
            sessions = updatedSessions,
            currentSessionId = newCurrentSessionId
        )
        
        // 如果没有会话了，创建一个新的
        if (updatedSessions.isEmpty()) {
            createNewSession()
        }
        
        Log.d("SessionManager", "Closed session: $sessionId")
    }
    
    /**
     * 更新会话数据
     */
    fun updateSession(sessionId: String, updater: (TerminalSessionData) -> TerminalSessionData) {
        val currentState = _state.value
        val updatedSessions = currentState.sessions.map { session ->
            if (session.id == sessionId) {
                updater(session)
            } else {
                session
            }
        }
        
        _state.value = currentState.copy(sessions = updatedSessions)
    }
    
    /**
     * 获取当前会话
     */
    fun getCurrentSession(): TerminalSessionData? {
        return _state.value.currentSession
    }
    
    /**
     * 获取指定会话
     */
    fun getSession(sessionId: String): TerminalSessionData? {
        return _state.value.sessions.find { it.id == sessionId }
    }
    
    /**
     * 清理会话资源
     */
     /*
    private fun cleanupSession(session: TerminalSessionData) {
        // 首先取消读取协程
        session.readJob?.cancel()
        
        // 然后关闭流和进程
        session.sessionWriter?.close()
        session.terminalSession?.process?.destroy()
    }
    */
    
    /**
     * 清理所有会话
     */
    fun cleanup() {
        val currentState = _state.value
        currentState.sessions.forEach { session ->
            try {
                closeSession(session.id)
            } catch (e: Exception) {
                Log.e("SessionManager", "Error cleaning up session ${session.id}", e)
            }
        }
        
        _state.value = TerminalState()
        Log.d("SessionManager", "All sessions cleaned up")
    }
} 