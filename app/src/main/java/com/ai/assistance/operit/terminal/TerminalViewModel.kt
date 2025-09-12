package com.ai.assistance.operit.terminal

import android.app.Application
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.OutputStreamWriter

data class CommandHistoryItem(
    val prompt: String,
    val command: String,
    val output: String,
    val isExecuting: Boolean = false
)

@RequiresApi(Build.VERSION_CODES.O)
class TerminalViewModel(application: Application) : AndroidViewModel(application) {

    private val terminalManager = TerminalManager(application)
    private var terminalSession: TerminalSession? = null
    private var sessionWriter: OutputStreamWriter? = null

    private val _terminalOutput = MutableStateFlow("Initializing environment...\n")
    val terminalOutput = _terminalOutput.asStateFlow()
    
    private val _currentDirectory = MutableStateFlow("$ ")
    val currentDirectory = _currentDirectory.asStateFlow()
    
    private val _commandHistory = MutableStateFlow<List<CommandHistoryItem>>(emptyList())
    val commandHistory = _commandHistory.asStateFlow()
    
    private var currentCommandOutputBuilder = StringBuilder()
    
    // 添加交互模式状态
    private var isWaitingForInteractiveInput = false
    private var lastInteractivePrompt = ""
    
    // 暴露交互状态给UI
    private val _isInteractiveMode = MutableStateFlow(false)
    val isInteractiveMode = _isInteractiveMode.asStateFlow()
    
    private val _interactivePrompt = MutableStateFlow("")
    val interactivePrompt = _interactivePrompt.asStateFlow()

    init {
        _commandHistory.value = listOf(CommandHistoryItem("", "Initializing environment...", "", false))
        initialize()
    }

    private fun initialize() {
        viewModelScope.launch {
            val success = terminalManager.initializeEnvironment()
            if (success) {
                // _terminalOutput.value += "Environment initialized. Starting session...\n"
                appendOutputToHistory("Environment initialized. Starting session...")
                startSession()
            } else {
                // _terminalOutput.value += "FATAL: Environment initialization failed. Check logs.\n"
                appendOutputToHistory("FATAL: Environment initialization failed. Check logs.")
            }
        }
    }

    private fun startSession() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                terminalSession = terminalManager.startTerminalSession()
                sessionWriter = terminalSession?.stdin?.writer()
                // _terminalOutput.value += "Session started.\n"
                appendOutputToHistory("Session started.")
                
                // 发送初始命令来获取提示符
                sessionWriter?.write("echo 'TERMINAL_READY'\n")
                sessionWriter?.flush()

                // Coroutine to continuously read from stdout
                launch {
                    terminalSession?.stdout?.bufferedReader()?.use { reader ->
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
                                    processOutput(line)
                                    lineBuilder.delete(0, firstIndex + 1)
                                } else { // separator == '\r'
                                    // Check for \r\n sequence
                                    if (firstIndex + 1 < lineBuilder.length && lineBuilder[firstIndex + 1] == '\n') {
                                        Log.d("TerminalViewModel", "Full line read (CRLF): '$line'")
                                        processOutput(line)
                                        lineBuilder.delete(0, firstIndex + 2) // Consume both \r and \n
                                    } else {
                                        Log.d("TerminalViewModel", "Progress line read (CR): '$line'")
                                        processProgressOutput(line)
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
                                    processOutput(remaining)
                                    lineBuilder.clear()
                                }
                            }
                        }
                        
                        // 处理缓冲区中剩余的内容
                        val remaining = lineBuilder.toString()
                        if (remaining.isNotEmpty()) {
                            Log.d("TerminalViewModel", "Final remaining content: '$remaining'")
                            processOutput(remaining)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("TerminalViewModel", "Error starting session", e)
                // _terminalOutput.value += "Error starting terminal session: ${e.message}\n"
                appendOutputToHistory("Error starting terminal session: ${e.message}")
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

    private fun handlePrompt(line: String): Boolean {
        val cwdPromptRegex = Regex("<cwd>(.*)</cwd>.*[#$]")
        val match = cwdPromptRegex.find(line)

        val isAPrompt = if (match != null) {
            val path = match.groups[1]?.value?.trim() ?: "~"
            _currentDirectory.value = "$path $"
            Log.d("TerminalViewModel", "Matched CWD prompt. Path: $path")

            val outputBeforePrompt = line.substring(0, match.range.first)
            if (outputBeforePrompt.isNotBlank()) {
                currentCommandOutputBuilder.append(outputBeforePrompt)
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
                _currentDirectory.value = "${cleanPrompt} $"
                Log.d("TerminalViewModel", "Matched fallback prompt: $cleanPrompt")
                true
            } else {
                false
            }
        }

        if (isAPrompt) {
            if (isWaitingForInteractiveInput) {
                isWaitingForInteractiveInput = false
                lastInteractivePrompt = ""
                _isInteractiveMode.value = false
                _interactivePrompt.value = ""
                Log.d("TerminalViewModel", "Interactive input session ended")
            }

            val lastExecutingIndex = _commandHistory.value.indexOfLast { it.isExecuting }
            if (lastExecutingIndex != -1) {
                val updatedHistory = _commandHistory.value.toMutableList()
                val oldItem = updatedHistory[lastExecutingIndex]
                updatedHistory[lastExecutingIndex] = oldItem.copy(
                    output = currentCommandOutputBuilder.toString().trim(),
                    isExecuting = false
                )
                _commandHistory.value = updatedHistory
                currentCommandOutputBuilder.clear()
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

    private fun processOutput(line: String) {
        Log.d("TerminalViewModel", "Received line: $line")

        val cleanLine = stripAnsi(line)
        
        // 跳过TERMINAL_READY信号
        if (cleanLine.trim() == "TERMINAL_READY") {
            return
        }
        
        // 检测命令回显
        val lastExecutingIndex = _commandHistory.value.indexOfLast { it.isExecuting }
        if (lastExecutingIndex != -1) {
            val executingItem = _commandHistory.value[lastExecutingIndex]
            if (currentCommandOutputBuilder.isEmpty() && cleanLine.trim() == executingItem.command.trim()) {
                Log.d("TerminalViewModel", "Ignoring command echo: '$cleanLine'")
                return
            }
        }
        
        // 检测交互式提示符
        if (isInteractivePrompt(cleanLine)) {
            Log.d("TerminalViewModel", "Detected interactive prompt: $cleanLine")
            isWaitingForInteractiveInput = true
            lastInteractivePrompt = cleanLine
            _isInteractiveMode.value = true
            _interactivePrompt.value = cleanLine
            
            // 将交互式提示添加到当前命令的输出中
            if (cleanLine.isNotBlank()) {
                currentCommandOutputBuilder.appendLine(cleanLine)
                
                // 更新当前执行命令的输出
                val lastExecutingIndex = _commandHistory.value.indexOfLast { it.isExecuting }
                if (lastExecutingIndex != -1) {
                    val updatedHistory = _commandHistory.value.toMutableList()
                    val oldItem = updatedHistory[lastExecutingIndex]
                    updatedHistory[lastExecutingIndex] = oldItem.copy(
                        output = currentCommandOutputBuilder.toString().trim()
                    )
                    _commandHistory.value = updatedHistory
                } else {
                    appendOutputToHistory(cleanLine)
                }
            }
            return
        }
        
        if (handlePrompt(cleanLine)) {
            return
        }

        // 处理命令输出
        if (cleanLine.isNotBlank()) {
            currentCommandOutputBuilder.appendLine(cleanLine)
            
            // 更新当前执行命令的输出
            val lastExecutingIndex = _commandHistory.value.indexOfLast { it.isExecuting }
            if (lastExecutingIndex != -1) {
                val updatedHistory = _commandHistory.value.toMutableList()
                val oldItem = updatedHistory[lastExecutingIndex]
                updatedHistory[lastExecutingIndex] = oldItem.copy(
                    output = currentCommandOutputBuilder.toString().trim()
                )
                _commandHistory.value = updatedHistory
            } else {
                // 没有执行中的命令，这是系统输出
                appendOutputToHistory(cleanLine)
            }
        }
    }
    
    private fun processProgressOutput(line: String) {
        Log.d("TerminalViewModel", "Processing progress output: '$line'")
        
        val cleanLine = stripAnsi(line)
        
        // 跳过空行
        if (cleanLine.trim().isEmpty()) {
            return
        }
        
        // 检测命令回显
        val lastExecutingIndex = _commandHistory.value.indexOfLast { it.isExecuting }
        if (lastExecutingIndex != -1) {
            val executingItem = _commandHistory.value[lastExecutingIndex]
            // 检查整个 builder 而不是单个行，因为进度输出是累积的
            if (currentCommandOutputBuilder.toString().trim() == executingItem.command.trim()) {
                Log.d("TerminalViewModel", "Ignoring progress command echo: '$cleanLine'")
                return
            }
        }
        
        // 检测交互式提示符
        if (isInteractivePrompt(cleanLine)) {
            Log.d("TerminalViewModel", "Detected prompt in progress output: $cleanLine")
            if (lastExecutingIndex != -1) {
                // 标记命令为完成状态
                val updatedHistory = _commandHistory.value.toMutableList()
                val oldItem = updatedHistory[lastExecutingIndex]
                updatedHistory[lastExecutingIndex] = oldItem.copy(
                    output = currentCommandOutputBuilder.toString().trim(),
                    isExecuting = false
                )
                _commandHistory.value = updatedHistory
                currentCommandOutputBuilder.clear()
            }
            // 更新提示符
            _currentDirectory.value = cleanLine
            return
        }

        // 更新当前执行命令的输出，替换最后一行而不是添加新行
        if (lastExecutingIndex != -1) {
            val updatedHistory = _commandHistory.value.toMutableList()
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
            _commandHistory.value = updatedHistory
            
            // 也更新StringBuilder
            if (isProgressLine(cleanLine)) {
                // 替换StringBuilder的最后一行
                val builderContent = currentCommandOutputBuilder.toString()
                val builderLines = builderContent.split('\n').toMutableList()
                if (builderLines.isNotEmpty()) {
                    builderLines[builderLines.size - 1] = cleanLine
                    currentCommandOutputBuilder.clear()
                    currentCommandOutputBuilder.append(builderLines.joinToString("\n"))
                } else {
                    currentCommandOutputBuilder.append(cleanLine)
                }
            } else {
                currentCommandOutputBuilder.appendLine(cleanLine)
            }
        } else {
            // 没有执行中的命令，直接添加到历史
            appendProgressOutputToHistory(cleanLine)
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
    
    private fun appendProgressOutputToHistory(line: String) {
        val currentHistory = _commandHistory.value
        if (currentHistory.isEmpty()) {
            _commandHistory.value = listOf(CommandHistoryItem("", "", line, false))
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
            _commandHistory.value = updatedHistory
        }
    }
    
    private fun appendOutputToHistory(line: String) {
        val currentHistory = _commandHistory.value
        if (currentHistory.isEmpty()) {
             _commandHistory.value = listOf(CommandHistoryItem("", "", line, false))
        } else {
            val lastItem = currentHistory.last()
            val updatedHistory = currentHistory.toMutableList()
            updatedHistory[currentHistory.size - 1] = lastItem.copy(
                output = if (lastItem.output.isEmpty()) line else lastItem.output + "\n" + line
            )
            _commandHistory.value = updatedHistory
        }
    }
    
    fun sendCommand(command: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                if (isWaitingForInteractiveInput) {
                    // 交互模式：直接发送输入，不添加到命令历史
                    Log.d("TerminalViewModel", "Sending interactive input: $command")
                    sessionWriter?.write(command + "\n")
                    sessionWriter?.flush()
                    
                    // 将用户的输入添加到当前命令的输出中
                    currentCommandOutputBuilder.appendLine(command)
                    val lastExecutingIndex = _commandHistory.value.indexOfLast { it.isExecuting }
                    if (lastExecutingIndex != -1) {
                        val updatedHistory = _commandHistory.value.toMutableList()
                        val oldItem = updatedHistory[lastExecutingIndex]
                        updatedHistory[lastExecutingIndex] = oldItem.copy(
                            output = currentCommandOutputBuilder.toString().trim()
                        )
                        _commandHistory.value = updatedHistory
                    }
                    
                    // 重置交互状态（某些程序可能需要多次交互）
                    isWaitingForInteractiveInput = false
                    lastInteractivePrompt = ""
                    _isInteractiveMode.value = false
                    _interactivePrompt.value = ""
                } else {
                    // 普通命令模式
                    currentCommandOutputBuilder.clear()
                    
                    val newCommandItem = CommandHistoryItem(
                        prompt = _currentDirectory.value,
                        command = command,
                        output = "",
                        isExecuting = true
                    )
                    _commandHistory.value = _commandHistory.value + newCommandItem
                    
                    sessionWriter?.write(command + "\n")
                    sessionWriter?.flush()
                    Log.d("TerminalViewModel", "Sent command: $command")
                }
            } catch (e: Exception) {
                Log.e("TerminalViewModel", "Error sending command", e)
                appendOutputToHistory("Error sending command: ${e.message}")
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        try {
            sessionWriter?.close()
            terminalSession?.process?.destroy()
        } catch (e: Exception) {
            Log.e("TerminalViewModel", "Error during cleanup", e)
        }
    }
} 