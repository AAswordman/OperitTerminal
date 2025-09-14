package com.ai.assistance.operit.terminal.data

import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import com.ai.assistance.operit.terminal.domain.AnsiParser
import kotlinx.coroutines.Job
import java.io.OutputStreamWriter
import java.util.UUID

/**
 * 命令历史项数据类
 */
class CommandHistoryItem(
    val id: String,
    prompt: String,
    command: String,
    output: String,
    isExecuting: Boolean = false
) {
    private var _prompt by mutableStateOf(prompt)
    private var _command by mutableStateOf(command)
    private var _output by mutableStateOf(output)
    private var _isExecuting by mutableStateOf(isExecuting)
    
    val outputPages = mutableStateListOf<String>()
    
    // 为AIDL序列化提供稳定的getter
    val prompt: String get() = _prompt
    val command: String get() = _command
    val output: String get() = _output
    val isExecuting: Boolean get() = _isExecuting
    
    // 为UI更新提供setter方法
    fun setPrompt(value: String) { _prompt = value }
    fun setCommand(value: String) { _command = value }
    fun setOutput(value: String) { _output = value }
    fun setExecuting(value: Boolean) { _isExecuting = value }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as CommandHistoryItem
        return id == other.id
    }

    override fun hashCode(): Int {
        return id.hashCode()
    }

    override fun toString(): String {
        return "CommandHistoryItem(id='$id', prompt='$prompt', command='$command', output='${output.take(20)}...', isExecuting=$isExecuting)"
    }
}

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
    val commandHistory: SnapshotStateList<CommandHistoryItem> = mutableStateListOf(),
    @Transient val currentCommandOutputBuilder: StringBuilder = StringBuilder(),
    @Transient val rawBuffer: StringBuilder = StringBuilder(),
    val isWaitingForInteractiveInput: Boolean = false,
    val lastInteractivePrompt: String = "",
    val isInteractiveMode: Boolean = false,
    val interactivePrompt: String = "",
    val isInitializing: Boolean = true,
    val initState: SessionInitState = SessionInitState.INITIALIZING,
    val readJob: Job? = null,
    val isFullscreen: Boolean = false,
    val screenContent: String = "",
    @Transient val ansiParser: AnsiParser = AnsiParser(),
    @Transient var currentExecutingCommand: CommandHistoryItem? = null,
    @Transient var currentOutputLineCount: Int = 0
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