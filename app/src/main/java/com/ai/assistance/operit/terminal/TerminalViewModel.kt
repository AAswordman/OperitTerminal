package com.ai.assistance.operit.terminal

import android.app.Application
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ai.assistance.operit.terminal.data.CommandHistoryItem
import com.ai.assistance.operit.terminal.data.TerminalState
import com.ai.assistance.operit.terminal.domain.SessionManager
import com.ai.assistance.operit.terminal.domain.OutputProcessor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.io.OutputStreamWriter
import androidx.compose.runtime.snapshots.SnapshotStateList

@RequiresApi(Build.VERSION_CODES.O)
class TerminalViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        private const val TAG = "TerminalViewModel"
        private const val MAX_HISTORY_ITEMS = 500
        private const val MAX_OUTPUT_LINES_PER_ITEM = 1000
    }

    private val terminalManager = TerminalManager(application)
    private val sessionManager = SessionManager(terminalManager)
    private val outputProcessor = OutputProcessor()
    
    // 暴露会话管理器的状态
    val terminalState: StateFlow<TerminalState> = sessionManager.state
    
    // 为了向后兼容，提供单独的状态流
    val sessions = terminalState.map { it.sessions }
    val currentSessionId = terminalState.map { it.currentSessionId }
    val commandHistory = terminalState.map { 
        it.currentSession?.commandHistory ?: androidx.compose.runtime.snapshots.SnapshotStateList<CommandHistoryItem>()
    }
    val currentDirectory = terminalState.map { it.currentSession?.currentDirectory ?: "$ " }
    val isInteractiveMode = terminalState.map { it.currentSession?.isInteractiveMode ?: false }
    val interactivePrompt = terminalState.map { it.currentSession?.interactivePrompt ?: "" }
    val isFullscreen = terminalState.map { it.currentSession?.isFullscreen ?: false }
    val screenContent = terminalState.map { it.currentSession?.screenContent ?: "" }

    init {
        // 创建第一个会话
        createNewSession()
    }

    fun createNewSession(): com.ai.assistance.operit.terminal.data.TerminalSessionData {
        val newSession = sessionManager.createNewSession()
        initializeSession(newSession.id)
        return newSession
    }
    
    fun switchToSession(sessionId: String) {
        sessionManager.switchToSession(sessionId)
    }
    
    fun closeSession(sessionId: String) {
        sessionManager.closeSession(sessionId)
    }

    private fun initializeSession(sessionId: String) {
        viewModelScope.launch {
            val success = terminalManager.initializeEnvironment()
            if (success) {
                appendOutputToHistory(sessionId, "Environment initialized. Starting session...")
                startSession(sessionId)
            } else {
                appendOutputToHistory(sessionId, "FATAL: Environment initialization failed. Check logs.")
            }
        }
    }

    private fun startSession(sessionId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val terminalSession = terminalManager.startTerminalSession(sessionId)
                val sessionWriter = terminalSession.stdin.writer()
                
                appendOutputToHistory(sessionId, "Session started.")
                
                // 发送初始命令来获取提示符
                sessionWriter.write("echo 'TERMINAL_READY'\n")
                sessionWriter.flush()

                // 启动读取协程
                val readJob = launch {
                    try {
                        terminalSession.stdout.bufferedReader().use { reader ->
                            val buffer = CharArray(4096)
                            var bytesRead: Int
                            while (reader.read(buffer).also { bytesRead = it } != -1) {
                                val chunk = String(buffer, 0, bytesRead)
                                Log.d(TAG, "Read chunk: '$chunk'")
                                outputProcessor.processOutput(sessionId, chunk, sessionManager)
                            }
                        }
                    } catch (e: java.io.InterruptedIOException) {
                        Log.i(TAG, "Read job interrupted for session $sessionId.")
                    } catch (e: Exception) {
                        Log.e(TAG, "Error in read job for session $sessionId", e)
                        appendOutputToHistory(sessionId, "Error reading from terminal: ${e.message}")
                    }
                }
                
                // 更新会话信息
                sessionManager.updateSession(sessionId) { session ->
                    session.copy(
                        terminalSession = terminalSession,
                        sessionWriter = sessionWriter,
                        isInitializing = false,
                        readJob = readJob
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error starting session", e)
                appendOutputToHistory(sessionId, "Error starting terminal session: ${e.message}")
            }
        }
    }
    
    fun sendCommand(command: String) {
        // Wrapper for sending a command, which is just input with a newline.
        sendInput(command, isCommand = true)
    }

    fun sendInput(input: String, isCommand: Boolean = false) {
        viewModelScope.launch(Dispatchers.IO) {
            val session = sessionManager.getCurrentSession() ?: return@launch
            
            try {
                val fullInput = if (isCommand) "$input\n" else input
                session.sessionWriter?.write(fullInput)
                session.sessionWriter?.flush()
                Log.d(TAG, "Sent input. isCommand=$isCommand, input='$fullInput'")

                if (isCommand) {
                    // Only commands are added to history and trigger state changes
                    handleCommandLogic(input, session)
                }

            } catch (e: Exception) {
                Log.e(TAG, "Error sending input", e)
                appendOutputToHistory(session.id, "Error sending input: ${e.message}")
            }
        }
    }

    private fun handleCommandLogic(command: String, session: com.ai.assistance.operit.terminal.data.TerminalSessionData) {
        if (session.isWaitingForInteractiveInput) {
            // Interactive mode: input is part of the previous command's output
            Log.d(TAG, "Handling interactive input: $command")
            // The PTY will echo the input, so we don't need to manually append it here.
            // session.currentCommandOutputBuilder.appendLine(command)
            // updateCurrentCommandOutput(session)
            sessionManager.updateSession(session.id) {
                it.copy(
                    isWaitingForInteractiveInput = false,
                    lastInteractivePrompt = "",
                    isInteractiveMode = false,
                    interactivePrompt = ""
                )
            }
        } else {
            // Normal command
            if (command.trim() == "clear") {
                handleClearCommand(session)
            } else {
                handleRegularCommand(command, session)
            }
        }
    }
    
    private fun handleClearCommand(session: com.ai.assistance.operit.terminal.data.TerminalSessionData) {
        // Special handling for clear command: keep welcome message
        val welcomeItem = session.commandHistory.firstOrNull {
            it.prompt.isEmpty() && it.command.isEmpty() && it.output.contains("Operit")
        }
        
        session.commandHistory.clear()
        welcomeItem?.let { session.commandHistory.add(it) }
    }
    
    private fun handleRegularCommand(command: String, session: com.ai.assistance.operit.terminal.data.TerminalSessionData) {
        session.currentCommandOutputBuilder.clear()
        session.currentOutputLineCount = 0
        
        val newCommandItem = CommandHistoryItem(
            prompt = session.currentDirectory,
            command = command,
            output = "",
            isExecuting = true
        )
        
        // Set the current executing command reference for efficient access
        session.currentExecutingCommand = newCommandItem
        session.commandHistory.add(newCommandItem)
    }

    fun sendInterruptSignal() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val currentSession = sessionManager.getCurrentSession()
                currentSession?.sessionWriter?.apply {
                    write(3) // ETX character (Ctrl+C)
                    flush()
                    Log.d(TAG, "Sent interrupt signal (Ctrl+C) to session ${currentSession.id}")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error sending interrupt signal", e)
                val currentSession = sessionManager.getCurrentSession()
                appendOutputToHistory(currentSession?.id ?: "N/A", "Error sending interrupt signal: ${e.message}")
            }
        }
    }
    
    private fun appendOutputToHistory(sessionId: String, line: String) {
        val session = sessionManager.getSession(sessionId) ?: return
        val currentHistory = session.commandHistory
        if (currentHistory.isEmpty()) {
            currentHistory.add(CommandHistoryItem(prompt = "", command = "", output = line, isExecuting = false))
        } else {
            val lastItem = currentHistory.last()
            lastItem.output = if (lastItem.output.isEmpty()) line else lastItem.output + "\n" + line
        }
    }
    
    private fun stripAnsi(text: String): String {
        return text.replace(Regex("\\x1B\\[[0-?]*[ -/]*[@-~]"), "")
    }

    private fun applyLogLimits(history: List<CommandHistoryItem>): List<CommandHistoryItem> {
        val sizeLimitedHistory = if (history.size > MAX_HISTORY_ITEMS) {
            history.takeLast(MAX_HISTORY_ITEMS)
        } else {
            history
        }

        return sizeLimitedHistory.map { item ->
            val lines = item.output.lines()
            if (lines.size > MAX_OUTPUT_LINES_PER_ITEM) {
                val truncatedOutput = "... (output truncated, showing last ${MAX_OUTPUT_LINES_PER_ITEM} lines) ...\n" +
                        lines.takeLast(MAX_OUTPUT_LINES_PER_ITEM).joinToString("\n")
                
                // Since CommandHistoryItem is now a class, we can't use copy()
                // We will create a new item for the limited view model history
                CommandHistoryItem(
                    id = item.id,
                    prompt = item.prompt,
                    command = item.command,
                    output = truncatedOutput,
                    isExecuting = item.isExecuting
                )
            } else {
                item
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        terminalManager.cleanup()
    }
} 