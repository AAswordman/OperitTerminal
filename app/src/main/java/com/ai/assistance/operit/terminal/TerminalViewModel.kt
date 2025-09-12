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
                        var line: String?
                        while (reader.readLine().also { line = it } != null) {
                            line?.let { 
                                Log.d("TerminalViewModel", "Raw output: '$it'")
                                processOutput(it) 
                            }
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
        val trimmed = line.trim()
        // 改进的提示符检测 - 支持Ubuntu和其他Linux环境的提示符
        return trimmed.endsWith("$") || 
               trimmed.endsWith("#") || 
               trimmed.endsWith("$ ") ||
               trimmed.endsWith("# ") ||
               Regex(".*@[a-zA-Z0-9.\\-]+\\s?:\\s?~?/?.*[#$]\\s*$").matches(trimmed) ||
               Regex("root@[a-zA-Z0-9.\\-]+:\\s?~?/?.*#\\s*$").matches(trimmed)
    }
    
    private fun stripAnsi(text: String): String {
        return text.replace(Regex("\\x1B\\[[0-?]*[ -/]*[@-~]"), "")
    }

    private fun processOutput(line: String) {
        Log.d("TerminalViewModel", "Received line: $line")

        val cleanLine = stripAnsi(line)
        
        // 跳过TERMINAL_READY信号
        if (cleanLine.trim() == "TERMINAL_READY") {
            return
        }
        
        if (isPrompt(cleanLine)) {
            updatePrompt(cleanLine.trim())
            
            // 完成当前执行中的命令
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
        } else {
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
    
    private fun updatePrompt(prompt: String) {
        // 提示符通常包含不想显示的额外字符，进行清理
        // 尝试从 user@host:path$ 这种格式中提取路径
        val regex = Regex(""".*:\s*(~?/?.*)\s*[#$]$""")
        val matchResult = regex.find(prompt)
        
        val cleanPrompt = matchResult?.groups?.get(1)?.value?.trim() ?: prompt.trim()
        
        _currentDirectory.value = "${cleanPrompt} $"
        Log.d("TerminalViewModel", "Updated prompt to: ${cleanPrompt} $")
    }

    fun sendCommand(command: String) {
        viewModelScope.launch(Dispatchers.IO) {
            // 清空上一个命令的输出缓存，为新命令做准备
            currentCommandOutputBuilder.clear()
            
            val newCommandItem = CommandHistoryItem(
                prompt = _currentDirectory.value,
                command = command,
                output = "",
                isExecuting = true
            )
            _commandHistory.value = _commandHistory.value + newCommandItem
            
            try {
                sessionWriter?.write(command + "\n")
                sessionWriter?.flush()
                Log.d("TerminalViewModel", "Sent command: $command")
            } catch (e: Exception) {
                Log.e("TerminalViewModel", "Error sending command", e)
                // _terminalOutput.value += "Error sending command: ${e.message}\n"
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