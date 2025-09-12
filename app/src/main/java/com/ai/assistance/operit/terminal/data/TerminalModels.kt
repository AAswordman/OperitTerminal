package com.ai.assistance.operit.terminal.data

import kotlinx.coroutines.Job
import java.io.OutputStreamWriter
import java.util.UUID

/**
 * 命令历史项数据类
 */
data class CommandHistoryItem(
    val id: String = UUID.randomUUID().toString(),
    val prompt: String,
    val command: String,
    val output: String,
    val isExecuting: Boolean = false
)

/**
 * 会话初始化状态枚举
 */
enum class SessionInitState {
    INITIALIZING,
    LOGGED_IN,
    AWAITING_FIRST_PROMPT,
    READY
}

/**
 * 终端会话数据类
 */
data class TerminalSessionData(
    val id: String = UUID.randomUUID().toString(),
    val title: String,
    val terminalSession: com.ai.assistance.operit.terminal.TerminalSession? = null,
    val sessionWriter: OutputStreamWriter? = null,
    val currentDirectory: String = "$ ",
    val commandHistory: List<CommandHistoryItem> = emptyList(),
    val currentCommandOutputBuilder: StringBuilder = StringBuilder(),
    val isWaitingForInteractiveInput: Boolean = false,
    val lastInteractivePrompt: String = "",
    val isInteractiveMode: Boolean = false,
    val interactivePrompt: String = "",
    val isInitializing: Boolean = true,
    val initState: SessionInitState = SessionInitState.INITIALIZING,
    val readJob: Job? = null
)

/**
 * 终端状态数据类
 */
data class TerminalState(
    val sessions: List<TerminalSessionData> = emptyList(),
    val currentSessionId: String? = null,
    val isLoading: Boolean = false,
    val error: String? = null
) {
    val currentSession: TerminalSessionData?
        get() = currentSessionId?.let { sessionId ->
            sessions.find { it.id == sessionId }
        }
} 