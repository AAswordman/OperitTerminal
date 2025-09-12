package com.ai.assistance.operit.terminal

import android.app.Application
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.OutputStreamWriter
import java.util.UUID

data class CommandHistoryItem(
    val prompt: String,
    val command: String,
    val output: String,
    val isExecuting: Boolean = false
)

// 单个终端会话的数据类
data class TerminalSessionData(
    val id: String = UUID.randomUUID().toString(),
    val title: String,
    val terminalSession: TerminalSession? = null,
    val sessionWriter: OutputStreamWriter? = null,
    val currentDirectory: String = "$ ",
    val commandHistory: List<CommandHistoryItem> = emptyList(),
    val currentCommandOutputBuilder: StringBuilder = StringBuilder(),
    val isWaitingForInteractiveInput: Boolean = false,
    val lastInteractivePrompt: String = "",
    val isInteractiveMode: Boolean = false,
    val interactivePrompt: String = "",
    val isInitializing: Boolean = true,
    val readJob: Job? = null  // 添加读取协程的Job引用
)

@RequiresApi(Build.VERSION_CODES.O)
class TerminalViewModel(application: Application) : AndroidViewModel(application) {

    private val terminalManager = TerminalManager(application)
    
    // 多会话管理
    private val _sessions = MutableStateFlow<List<TerminalSessionData>>(emptyList())
    val sessions = _sessions.asStateFlow()
    
    private val _currentSessionId = MutableStateFlow<String?>(null)
    val currentSessionId = _currentSessionId.asStateFlow()
    
    // 当前会话的状态（为了向后兼容）
    private val _currentDirectory = MutableStateFlow("$ ")
    val currentDirectory = _currentDirectory.asStateFlow()
    
    private val _commandHistory = MutableStateFlow<List<CommandHistoryItem>>(emptyList())
    val commandHistory = _commandHistory.asStateFlow()
    
    private val _isInteractiveMode = MutableStateFlow(false)
    val isInteractiveMode = _isInteractiveMode.asStateFlow()
    
    private val _interactivePrompt = MutableStateFlow("")
    val interactivePrompt = _interactivePrompt.asStateFlow()

    init {
        // 创建第一个会话
        createNewSession()
    }

    fun createNewSession() {
        val sessionCount = _sessions.value.size + 1
        val newSession = TerminalSessionData(
            title = "Ubuntu $sessionCount",
            commandHistory = listOf(CommandHistoryItem("", "Initializing environment...", "", false))
        )
        
        _sessions.value = _sessions.value + newSession
        _currentSessionId.value = newSession.id
        updateCurrentSessionStates()
        
        initializeSession(newSession.id)
    }
    
    fun switchToSession(sessionId: String) {
        if (_sessions.value.any { it.id == sessionId }) {
            _currentSessionId.value = sessionId
            updateCurrentSessionStates()
        }
    }
    
    fun closeSession(sessionId: String) {
        val sessionToClose = _sessions.value.find { it.id == sessionId }
        sessionToClose?.let { session ->
            try {
                // 首先取消读取协程
                session.readJob?.cancel()
                
                // 然后关闭流和进程
                session.sessionWriter?.close()
                session.terminalSession?.process?.destroy()
            } catch (e: Exception) {
                Log.e("TerminalViewModel", "Error closing session", e)
            }
        }
        
        val updatedSessions = _sessions.value.filter { it.id != sessionId }
        _sessions.value = updatedSessions
        
        // 如果关闭的是当前会话，切换到第一个可用会话
        if (_currentSessionId.value == sessionId) {
            _currentSessionId.value = updatedSessions.firstOrNull()?.id
            updateCurrentSessionStates()
        }
        
        // 如果没有会话了，创建一个新的
        if (updatedSessions.isEmpty()) {
            createNewSession()
        }
    }
    
    private fun updateCurrentSessionStates() {
        val currentSession = getCurrentSession()
        currentSession?.let { session ->
            _currentDirectory.value = session.currentDirectory
            _commandHistory.value = session.commandHistory
            _isInteractiveMode.value = session.isInteractiveMode
            _interactivePrompt.value = session.interactivePrompt
        }
    }
    
    private fun getCurrentSession(): TerminalSessionData? {
        return _currentSessionId.value?.let { sessionId ->
            _sessions.value.find { it.id == sessionId }
        }
    }
    
    private fun updateSession(sessionId: String, updater: (TerminalSessionData) -> TerminalSessionData) {
        _sessions.value = _sessions.value.map { session ->
            if (session.id == sessionId) {
                updater(session)
            } else {
                session
            }
        }
        
        // 如果更新的是当前会话，同时更新当前状态
        if (_currentSessionId.value == sessionId) {
            updateCurrentSessionStates()
        }
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

                // Coroutine to continuously read from stdout
                val readJob = launch {
                    try {
                        terminalSession.stdout.bufferedReader().use { reader ->
                            val buffer = CharArray(4096)
                            var bytesRead: Int
                            val lineBuilder = StringBuilder()

                            while (reader.read(buffer).also { bytesRead = it } != -1) {
                                val chunk = String(buffer, 0, bytesRead)
                                lineBuilder.append(chunk)

                                // 同时处理换行符和回车符
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
                                        Log.d("TerminalViewModel", "Full line read (NL): '$line'")
                                        processOutput(sessionId, line)
                                        lineBuilder.delete(0, firstIndex + 1)
                                    } else { // separator == '\r'
                                        // Check for \r\n sequence
                                        if (firstIndex + 1 < lineBuilder.length && lineBuilder[firstIndex + 1] == '\n') {
                                            Log.d("TerminalViewModel", "Full line read (CRLF): '$line'")
                                            processOutput(sessionId, line)
                                            lineBuilder.delete(0, firstIndex + 2) // Consume both \r and \n
                                        } else {
                                            Log.d("TerminalViewModel", "Progress line read (CR): '$line'")
                                            processProgressOutput(sessionId, line)
                                            lineBuilder.delete(0, firstIndex + 1)
                                        }
                                    }
                                }

                                // 检查剩余部分是否是提示符
                                val remaining = lineBuilder.toString()
                                if (remaining.isNotEmpty()) {
                                    val cleanRemaining = stripAnsi(remaining)
                                    if (isPrompt(cleanRemaining) || isInteractivePrompt(cleanRemaining)) {
                                        Log.d("TerminalViewModel", "Partial line (prompt or interactive) read: '$remaining'")
                                        processOutput(sessionId, remaining)
                                        lineBuilder.clear()
                                    }
                                }
                            }

                            // 处理缓冲区中剩余的内容
                            val remaining = lineBuilder.toString()
                            if (remaining.isNotEmpty()) {
                                Log.d("TerminalViewModel", "Final remaining content: '$remaining'")
                                processOutput(sessionId, remaining)
                            }
                        }
                    } catch (e: java.io.InterruptedIOException) {
                        // This is expected when the job is cancelled, e.g., when closing a session.
                        Log.i("TerminalViewModel", "Read job interrupted for session $sessionId.")
                    } catch (e: Exception) {
                        Log.e("TerminalViewModel", "Error in read job for session $sessionId", e)
                        appendOutputToHistory(sessionId, "Error reading from terminal: ${e.message}")
                    }
                }
                
                // 更新会话信息，包括readJob引用
                updateSession(sessionId) { session ->
                    session.copy(
                        terminalSession = terminalSession,
                        sessionWriter = sessionWriter,
                        isInitializing = false,
                        readJob = readJob
                    )
                }
            } catch (e: Exception) {
                Log.e("TerminalViewModel", "Error starting session", e)
                appendOutputToHistory(sessionId, "Error starting terminal session: ${e.message}")
            }
        }
    }
    
    private fun isPrompt(line: String): Boolean {
        val cwdPromptRegex = Regex("<cwd>(.*)</cwd>.*[#$]")
        if (cwdPromptRegex.containsMatchIn(line)) {
            return true
        }

        val trimmed = line.trim()
        return trimmed.endsWith("$") ||
                trimmed.endsWith("#") ||
                trimmed.endsWith("$ ") ||
                trimmed.endsWith("# ") ||
                Regex(".*@[a-zA-Z0-9.\\-]+\\s?:\\s?~?/?.*[#$]\\s*$").matches(trimmed) ||
                Regex("root@[a-zA-Z0-9.\\-]+:\\s?~?/?.*#\\s*$").matches(trimmed)
    }

    private fun handlePrompt(sessionId: String, line: String): Boolean {
        val currentSession = _sessions.value.find { it.id == sessionId } ?: return false
        
        val cwdPromptRegex = Regex("<cwd>(.*)</cwd>.*[#$]")
        val match = cwdPromptRegex.find(line)

        val isAPrompt = if (match != null) {
            val path = match.groups[1]?.value?.trim() ?: "~"
            updateSession(sessionId) { session ->
                session.copy(currentDirectory = "$path $")
            }
            Log.d("TerminalViewModel", "Matched CWD prompt. Path: $path")

            val outputBeforePrompt = line.substring(0, match.range.first)
            if (outputBeforePrompt.isNotBlank()) {
                currentSession.currentCommandOutputBuilder.append(outputBeforePrompt)
            }
            true
        } else {
            val trimmed = line.trim()
            val isFallbackPrompt = trimmed.endsWith("$") ||
                    trimmed.endsWith("#") ||
                    trimmed.endsWith("$ ") ||
                    trimmed.endsWith("# ") ||
                    Regex(".*@[a-zA-Z0-9.\\-]+\\s?:\\s?~?/?.*[#$]\\s*$").matches(trimmed) ||
                    Regex("root@[a-zA-Z0-9.\\-]+:\\s?~?/?.*#\\s*$").matches(trimmed)

            if (isFallbackPrompt) {
                val regex = Regex(""".*:\s*(~?/?.*)\s*[#$]$""")
                val matchResult = regex.find(trimmed)
                val cleanPrompt = matchResult?.groups?.get(1)?.value?.trim() ?: trimmed
                updateSession(sessionId) { session ->
                    session.copy(currentDirectory = "${cleanPrompt} $")
                }
                Log.d("TerminalViewModel", "Matched fallback prompt: $cleanPrompt")
                true
            } else {
                false
            }
        }

        if (isAPrompt) {
            updateSession(sessionId) { session ->
                session.copy(
                    isWaitingForInteractiveInput = false,
                    lastInteractivePrompt = "",
                    isInteractiveMode = false,
                    interactivePrompt = ""
                )
            }

            val sessionCommandHistory = currentSession.commandHistory
            val lastExecutingIndex = sessionCommandHistory.indexOfLast { it.isExecuting }
            if (lastExecutingIndex != -1) {
                val updatedHistory = sessionCommandHistory.toMutableList()
                val oldItem = updatedHistory[lastExecutingIndex]
                updatedHistory[lastExecutingIndex] = oldItem.copy(
                    output = currentSession.currentCommandOutputBuilder.toString().trim(),
                    isExecuting = false
                )
                updateSession(sessionId) { session ->
                    session.copy(commandHistory = updatedHistory)
                }
                currentSession.currentCommandOutputBuilder.clear()
            }
            return true
        }
        return false
    }

    private fun stripAnsi(text: String): String {
        return text.replace(Regex("\\x1B\\[[0-?]*[ -/]*[@-~]"), "")
    }
    
    /**
     * 检测是否是交互式提示符
     * 检测常见的交互式提示模式，如 [Y/n], [y/N], (y/n), Continue? 等
     */
    private fun isInteractivePrompt(line: String): Boolean {
        val cleanLine = line.trim().lowercase()
        val interactivePatterns = listOf(
            ".*\\[y/n\\].*",
            ".*\\[y/n/.*\\].*",
            ".*\\(y/n\\).*",
            ".*\\(yes/no\\).*",
            ".*continue\\?.*",
            ".*proceed\\?.*",
            ".*do you want.*",
            ".*are you sure.*",
            ".*confirm.*\\?.*",
            ".*press.*to continue.*",
            ".*\\[.*y.*n.*\\].*"
        )
        
        return interactivePatterns.any { pattern ->
            cleanLine.matches(Regex(pattern))
        }
    }

    private fun processOutput(sessionId: String, line: String) {
        Log.d("TerminalViewModel", "Received line: $line")

        val currentSession = _sessions.value.find { it.id == sessionId } ?: return
        val cleanLine = stripAnsi(line)
        
        // 跳过TERMINAL_READY信号
        if (cleanLine.trim() == "TERMINAL_READY") {
            return
        }
        
        // 检测命令回显
        val sessionCommandHistory = currentSession.commandHistory
        val lastExecutingIndex = sessionCommandHistory.indexOfLast { it.isExecuting }
        if (lastExecutingIndex != -1) {
            val executingItem = sessionCommandHistory[lastExecutingIndex]
            if (currentSession.currentCommandOutputBuilder.isEmpty() && cleanLine.trim() == executingItem.command.trim()) {
                Log.d("TerminalViewModel", "Ignoring command echo: '$cleanLine'")
                return
            }
        }
        
        // 检测交互式提示符
        if (isInteractivePrompt(cleanLine)) {
            Log.d("TerminalViewModel", "Detected interactive prompt: $cleanLine")
            updateSession(sessionId) { session ->
                session.copy(
                    isWaitingForInteractiveInput = true,
                    lastInteractivePrompt = cleanLine,
                    isInteractiveMode = true,
                    interactivePrompt = cleanLine
                )
            }
            
            // 将交互式提示添加到当前命令的输出中
            if (cleanLine.isNotBlank()) {
                currentSession.currentCommandOutputBuilder.appendLine(cleanLine)
                
                // 更新当前执行命令的输出
                val lastExecutingIndex = sessionCommandHistory.indexOfLast { it.isExecuting }
                if (lastExecutingIndex != -1) {
                    val updatedHistory = sessionCommandHistory.toMutableList()
                    val oldItem = updatedHistory[lastExecutingIndex]
                    updatedHistory[lastExecutingIndex] = oldItem.copy(
                        output = currentSession.currentCommandOutputBuilder.toString().trim()
                    )
                    updateSession(sessionId) { session ->
                        session.copy(commandHistory = updatedHistory)
                    }
                } else {
                    appendOutputToHistory(sessionId, cleanLine)
                }
            }
            return
        }
        
        if (handlePrompt(sessionId, cleanLine)) {
            return
        }

        // 处理命令输出
        if (cleanLine.isNotBlank()) {
            currentSession.currentCommandOutputBuilder.appendLine(cleanLine)
            
            // 更新当前执行命令的输出
            val lastExecutingIndex = sessionCommandHistory.indexOfLast { it.isExecuting }
            if (lastExecutingIndex != -1) {
                val updatedHistory = sessionCommandHistory.toMutableList()
                val oldItem = updatedHistory[lastExecutingIndex]
                updatedHistory[lastExecutingIndex] = oldItem.copy(
                    output = currentSession.currentCommandOutputBuilder.toString().trim()
                )
                updateSession(sessionId) { session ->
                    session.copy(commandHistory = updatedHistory)
                }
            } else {
                // 没有执行中的命令，这是系统输出
                appendOutputToHistory(sessionId, cleanLine)
            }
        }
    }
    
    private fun processProgressOutput(sessionId: String, line: String) {
        Log.d("TerminalViewModel", "Processing progress output: '$line'")
        
        val currentSession = _sessions.value.find { it.id == sessionId } ?: return
        val cleanLine = stripAnsi(line)
        
        // 跳过空行
        if (cleanLine.trim().isEmpty()) {
            return
        }
        
        // 检测命令回显
        val sessionCommandHistory = currentSession.commandHistory
        val lastExecutingIndex = sessionCommandHistory.indexOfLast { it.isExecuting }
        if (lastExecutingIndex != -1) {
            val executingItem = sessionCommandHistory[lastExecutingIndex]
            // 检查整个 builder 而不是单个行，因为进度输出是累积的
            if (currentSession.currentCommandOutputBuilder.toString().trim() == executingItem.command.trim()) {
                Log.d("TerminalViewModel", "Ignoring progress command echo: '$cleanLine'")
                return
            }
        }
        
        // 检测交互式提示符
        if (isInteractivePrompt(cleanLine)) {
            Log.d("TerminalViewModel", "Detected prompt in progress output: $cleanLine")
            if (lastExecutingIndex != -1) {
                // 标记命令为完成状态
                val updatedHistory = sessionCommandHistory.toMutableList()
                val oldItem = updatedHistory[lastExecutingIndex]
                updatedHistory[lastExecutingIndex] = oldItem.copy(
                    output = currentSession.currentCommandOutputBuilder.toString().trim(),
                    isExecuting = false
                )
                updateSession(sessionId) { session ->
                    session.copy(
                        commandHistory = updatedHistory,
                        currentDirectory = cleanLine
                    )
                }
                currentSession.currentCommandOutputBuilder.clear()
            }
            return
        }

        // 更新当前执行命令的输出，替换最后一行而不是添加新行
        if (lastExecutingIndex != -1) {
            val updatedHistory = sessionCommandHistory.toMutableList()
            val oldItem = updatedHistory[lastExecutingIndex]
            
            val existingOutput = oldItem.output
            val lines = if (existingOutput.isEmpty()) {
                mutableListOf(cleanLine)
            } else {
                existingOutput.split('\n').toMutableList()
            }
            
            // 如果是进度条格式，替换最后一行
            if (lines.isNotEmpty() && isProgressLine(cleanLine)) {
                lines[lines.size - 1] = cleanLine
            } else {
                // 不是进度条，添加新行
                lines.add(cleanLine)
            }
            
            updatedHistory[lastExecutingIndex] = oldItem.copy(
                output = lines.joinToString("\n")
            )
            updateSession(sessionId) { session ->
                session.copy(commandHistory = updatedHistory)
            }
            
            // 也更新StringBuilder
            if (isProgressLine(cleanLine)) {
                // 替换StringBuilder的最后一行
                val builderContent = currentSession.currentCommandOutputBuilder.toString()
                val builderLines = builderContent.split('\n').toMutableList()
                if (builderLines.isNotEmpty()) {
                    builderLines[builderLines.size - 1] = cleanLine
                    currentSession.currentCommandOutputBuilder.clear()
                    currentSession.currentCommandOutputBuilder.append(builderLines.joinToString("\n"))
                } else {
                    currentSession.currentCommandOutputBuilder.append(cleanLine)
                }
            } else {
                currentSession.currentCommandOutputBuilder.appendLine(cleanLine)
            }
        } else {
            // 没有执行中的命令，直接添加到历史
            appendProgressOutputToHistory(sessionId, cleanLine)
        }
    }
    
    private fun isProgressLine(line: String): Boolean {
        val cleanLine = line.trim()
        // 检查是否包含进度条常见的模式
        return cleanLine.contains("%") || 
               cleanLine.contains("█") || 
               cleanLine.contains("▓") || 
               cleanLine.contains("░") || 
               cleanLine.contains("▌") || 
               cleanLine.contains("▎") || 
               cleanLine.contains("▍") || 
               cleanLine.contains("▋") || 
               cleanLine.contains("▊") || 
               cleanLine.contains("▉") ||
               cleanLine.matches(Regex(".*\\d+/\\d+.*")) || // 如 "5/10"
               cleanLine.matches(Regex(".*\\[.*\\].*")) ||   // 如 "[====    ]"
               cleanLine.contains("downloading") ||
               cleanLine.contains("installing") ||
               cleanLine.contains("progress") ||
               cleanLine.contains("Loading") ||
               cleanLine.contains("Downloading") ||
               cleanLine.contains("Installing")
    }
    
    private fun appendProgressOutputToHistory(sessionId: String, line: String) {
        val currentSession = _sessions.value.find { it.id == sessionId } ?: return
        val currentHistory = currentSession.commandHistory
        if (currentHistory.isEmpty()) {
            updateSession(sessionId) { session ->
                session.copy(commandHistory = listOf(CommandHistoryItem("", "", line, false)))
            }
        } else {
            val lastItem = currentHistory.last()
            val updatedHistory = currentHistory.toMutableList()
            
            val existingOutput = lastItem.output
            val lines = if (existingOutput.isEmpty()) {
                mutableListOf(line)
            } else {
                existingOutput.split('\n').toMutableList()
            }
            
            // 如果是进度条格式，替换最后一行
            if (lines.isNotEmpty() && isProgressLine(line)) {
                lines[lines.size - 1] = line
            } else {
                lines.add(line)
            }
            
            updatedHistory[currentHistory.size - 1] = lastItem.copy(
                output = lines.joinToString("\n")
            )
            updateSession(sessionId) { session ->
                session.copy(commandHistory = updatedHistory)
            }
        }
    }
    
    private fun appendOutputToHistory(sessionId: String, line: String) {
        val currentSession = _sessions.value.find { it.id == sessionId } ?: return
        val currentHistory = currentSession.commandHistory
        if (currentHistory.isEmpty()) {
            updateSession(sessionId) { session ->
                session.copy(commandHistory = listOf(CommandHistoryItem("", "", line, false)))
            }
        } else {
            val lastItem = currentHistory.last()
            val updatedHistory = currentHistory.toMutableList()
            updatedHistory[currentHistory.size - 1] = lastItem.copy(
                output = if (lastItem.output.isEmpty()) line else lastItem.output + "\n" + line
            )
            updateSession(sessionId) { session ->
                session.copy(commandHistory = updatedHistory)
            }
        }
    }
    
    fun sendCommand(command: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val currentSession = getCurrentSession()
                if (currentSession?.isWaitingForInteractiveInput == true) {
                    // 交互模式：直接发送输入，不添加到命令历史
                    Log.d("TerminalViewModel", "Sending interactive input: $command")
                    currentSession.sessionWriter?.write(command + "\n")
                    currentSession.sessionWriter?.flush()
                    
                    // 将用户的输入添加到当前命令的输出中
                    currentSession.currentCommandOutputBuilder.appendLine(command)
                    val sessionCommandHistory = currentSession.commandHistory
                    val lastExecutingIndex = sessionCommandHistory.indexOfLast { it.isExecuting }
                    if (lastExecutingIndex != -1) {
                        val updatedHistory = sessionCommandHistory.toMutableList()
                        val oldItem = updatedHistory[lastExecutingIndex]
                        updatedHistory[lastExecutingIndex] = oldItem.copy(
                            output = currentSession.currentCommandOutputBuilder.toString().trim()
                        )
                        updateSession(currentSession.id) { session ->
                            session.copy(commandHistory = updatedHistory)
                        }
                    }
                    
                    // 重置交互状态（某些程序可能需要多次交互）
                    updateSession(currentSession.id) { session ->
                        session.copy(
                            isWaitingForInteractiveInput = false,
                            lastInteractivePrompt = "",
                            isInteractiveMode = false,
                            interactivePrompt = ""
                        )
                    }
                } else {
                    // 普通命令模式
                    currentSession?.currentCommandOutputBuilder?.clear()
                    
                    val newCommandItem = CommandHistoryItem(
                        prompt = currentSession?.currentDirectory ?: "$ ",
                        command = command,
                        output = "",
                        isExecuting = true
                    )
                    
                    if (currentSession != null) {
                        updateSession(currentSession.id) { session ->
                            session.copy(commandHistory = session.commandHistory + newCommandItem)
                        }
                    }
                    
                    currentSession?.sessionWriter?.write(command + "\n")
                    currentSession?.sessionWriter?.flush()
                    Log.d("TerminalViewModel", "Sent command: $command")
                }
            } catch (e: Exception) {
                Log.e("TerminalViewModel", "Error sending command", e)
                val currentSession = getCurrentSession()
                appendOutputToHistory(currentSession?.id ?: "N/A", "Error sending command: ${e.message}")
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        try {
            _sessions.value.forEach { session ->
                // 首先取消读取协程
                session.readJob?.cancel()
                // 然后关闭流和进程
                session.sessionWriter?.close()
                session.terminalSession?.process?.destroy()
            }
        } catch (e: Exception) {
            Log.e("TerminalViewModel", "Error during cleanup", e)
        }
    }
} 