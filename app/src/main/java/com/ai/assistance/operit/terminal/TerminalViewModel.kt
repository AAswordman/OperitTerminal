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

@RequiresApi(Build.VERSION_CODES.O)
class TerminalViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        private const val TAG = "TerminalViewModel"
    }

    private val terminalManager = TerminalManager(application)
    private val sessionManager = SessionManager()
    private val outputProcessor = OutputProcessor()
    
    // 暴露会话管理器的状态
    val terminalState: StateFlow<TerminalState> = sessionManager.state
    
    // 为了向后兼容，提供单独的状态流
    val sessions = terminalState.map { it.sessions }
    val currentSessionId = terminalState.map { it.currentSessionId }
    val commandHistory = terminalState.map { it.currentSession?.commandHistory ?: emptyList() }
    val currentDirectory = terminalState.map { it.currentSession?.currentDirectory ?: "$ " }
    val isInteractiveMode = terminalState.map { it.currentSession?.isInteractiveMode ?: false }
    val interactivePrompt = terminalState.map { it.currentSession?.interactivePrompt ?: "" }

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
                val terminalSession = terminalManager.startTerminalSession()
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
                            val lineBuilder = StringBuilder()

                            while (reader.read(buffer).also { bytesRead = it } != -1) {
                                val chunk = String(buffer, 0, bytesRead)
                                lineBuilder.append(chunk)

                                // 处理换行符和回车符
                                while (true) {
                                    val newlineIndex = lineBuilder.indexOf('\n')
                                    val carriageReturnIndex = lineBuilder.indexOf('\r')

                                    val firstIndex = when {
                                        newlineIndex != -1 && carriageReturnIndex != -1 -> minOf(newlineIndex, carriageReturnIndex)
                                        newlineIndex != -1 -> newlineIndex
                                        carriageReturnIndex != -1 -> carriageReturnIndex
                                        else -> -1
                                    }

                                    if (firstIndex == -1) {
                                        break // No more separators
                                    }

                                    val line = lineBuilder.substring(0, firstIndex)
                                    val separator = lineBuilder[firstIndex]

                                    if (separator == '\n') {
                                        Log.d(TAG, "Full line read (NL): '$line'")
                                        outputProcessor.processOutput(sessionId, line, sessionManager)
                                        lineBuilder.delete(0, firstIndex + 1)
                                    } else { // separator == '\r'
                                        // Check for \r\n sequence
                                        if (firstIndex + 1 < lineBuilder.length && lineBuilder[firstIndex + 1] == '\n') {
                                            Log.d(TAG, "Full line read (CRLF): '$line'")
                                            outputProcessor.processOutput(sessionId, line, sessionManager)
                                            lineBuilder.delete(0, firstIndex + 2) // Consume both \r and \n
                                        } else {
                                            Log.d(TAG, "Progress line read (CR): '$line'")
                                            outputProcessor.processProgressOutput(sessionId, line, sessionManager)
                                            lineBuilder.delete(0, firstIndex + 1)
                                        }
                                    }
                                }

                                // 检查剩余部分是否是提示符
                                val remaining = lineBuilder.toString()
                                if (remaining.isNotEmpty()) {
                                    val cleanRemaining = stripAnsi(remaining)
                                    if (outputProcessor.isPrompt(cleanRemaining) || outputProcessor.isInteractivePrompt(cleanRemaining)) {
                                        Log.d(TAG, "Partial line (prompt or interactive) read: '$remaining'")
                                        outputProcessor.processOutput(sessionId, remaining, sessionManager)
                                        lineBuilder.clear()
                                    }
                                }
                            }

                            // 处理缓冲区中剩余的内容
                            val remaining = lineBuilder.toString()
                            if (remaining.isNotEmpty()) {
                                Log.d(TAG, "Final remaining content: '$remaining'")
                                outputProcessor.processOutput(sessionId, remaining, sessionManager)
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
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val currentSession = sessionManager.getCurrentSession()
                if (currentSession?.isWaitingForInteractiveInput == true) {
                    // 交互模式：直接发送输入，不添加到命令历史
                    handleInteractiveInput(command, currentSession)
                } else {
                    // 普通命令模式
                    if (command.trim() == "clear") {
                        handleClearCommand(currentSession)
                    } else {
                        handleRegularCommand(command, currentSession)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error sending command", e)
                val currentSession = sessionManager.getCurrentSession()
                appendOutputToHistory(currentSession?.id ?: "N/A", "Error sending command: ${e.message}")
            }
        }
    }
    
    private suspend fun handleInteractiveInput(command: String, session: com.ai.assistance.operit.terminal.data.TerminalSessionData) {
        Log.d(TAG, "Sending interactive input: $command")
        session.sessionWriter?.write(command + "\n")
        session.sessionWriter?.flush()
        
        // 将用户的输入添加到当前命令的输出中
        session.currentCommandOutputBuilder.appendLine(command)
        updateCurrentCommandOutput(session)
        
        // 重置交互状态
        sessionManager.updateSession(session.id) { session ->
                session.copy(
                    isWaitingForInteractiveInput = false,
                    lastInteractivePrompt = "",
                    isInteractiveMode = false,
                    interactivePrompt = ""
                )
            }
    }
    
    private suspend fun handleClearCommand(session: com.ai.assistance.operit.terminal.data.TerminalSessionData?) {
        // 特殊处理clear命令：保留欢迎信息，清空其他历史
        val welcomeItem = session?.commandHistory?.firstOrNull { 
            it.prompt.isEmpty() && it.command.isEmpty() && it.output.contains("Operit")
        }
        
        if (welcomeItem != null && session != null) {
            sessionManager.updateSession(session.id) { session ->
                session.copy(commandHistory = listOf(welcomeItem))
                    }
                }
        
        // 发送clear命令到终端
        session?.sessionWriter?.write("clear\n")
        session?.sessionWriter?.flush()
        Log.d(TAG, "Sent clear command")
                    }
    
    private suspend fun handleRegularCommand(command: String, session: com.ai.assistance.operit.terminal.data.TerminalSessionData?) {
        session?.currentCommandOutputBuilder?.clear()
        
        val newCommandItem = CommandHistoryItem(
            prompt = session?.currentDirectory ?: "$ ",
            command = command,
            output = "",
            isExecuting = true
        )
        
        if (session != null) {
            sessionManager.updateSession(session.id) { session ->
                session.copy(commandHistory = session.commandHistory + newCommandItem)
                }
            }
        
        session?.sessionWriter?.write(command + "\n")
        session?.sessionWriter?.flush()
        Log.d(TAG, "Sent command: $command")
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
    
    private fun updateCurrentCommandOutput(session: com.ai.assistance.operit.terminal.data.TerminalSessionData) {
        val sessionCommandHistory = session.commandHistory
        val lastExecutingIndex = sessionCommandHistory.indexOfLast { it.isExecuting }
        if (lastExecutingIndex != -1) {
            val updatedHistory = sessionCommandHistory.toMutableList()
            val oldItem = updatedHistory[lastExecutingIndex]
            updatedHistory[lastExecutingIndex] = oldItem.copy(
                output = session.currentCommandOutputBuilder.toString().trim()
            )
            sessionManager.updateSession(session.id) { session ->
                session.copy(commandHistory = updatedHistory)
            }
        }
    }
    
    private fun appendOutputToHistory(sessionId: String, line: String) {
        val session = sessionManager.getSession(sessionId) ?: return
        val currentHistory = session.commandHistory
        if (currentHistory.isEmpty()) {
            sessionManager.updateSession(sessionId) { session ->
                session.copy(commandHistory = listOf(CommandHistoryItem(prompt = "", command = "", output = line, isExecuting = false)))
            }
        } else {
            val lastItem = currentHistory.last()
            val updatedHistory = currentHistory.toMutableList()
            updatedHistory[currentHistory.size - 1] = lastItem.copy(
                output = if (lastItem.output.isEmpty()) line else lastItem.output + "\n" + line
            )
            sessionManager.updateSession(sessionId) { session ->
                session.copy(commandHistory = updatedHistory)
            }
        }
    }
    
    private fun stripAnsi(text: String): String {
        return text.replace(Regex("\\x1B\\[[0-?]*[ -/]*[@-~]"), "")
    }

    override fun onCleared() {
        super.onCleared()
        sessionManager.cleanup()
    }
} 