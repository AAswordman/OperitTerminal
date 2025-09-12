package com.ai.assistance.operit.terminal.domain

import android.util.Log
import com.ai.assistance.operit.terminal.data.CommandHistoryItem
import com.ai.assistance.operit.terminal.data.SessionInitState
import com.ai.assistance.operit.terminal.data.TerminalSessionData

/**
 * 终端输出处理器
 * 负责处理和解析终端输出，更新会话状态
 */
class OutputProcessor {
    
    companion object {
        private const val TAG = "OutputProcessor"
    }
    
    /**
     * 处理终端输出
     */
    fun processOutput(
        sessionId: String,
        line: String,
        sessionManager: SessionManager
    ) {
        Log.d(TAG, "Processing output for session $sessionId: $line")
        
        val session = sessionManager.getSession(sessionId) ?: return
        
        when (session.initState) {
            SessionInitState.INITIALIZING -> {
                handleInitializingState(sessionId, line, sessionManager)
            }
            SessionInitState.LOGGED_IN -> {
                handleLoggedInState(sessionId, line, sessionManager)
            }
            SessionInitState.AWAITING_FIRST_PROMPT -> {
                handleAwaitingFirstPromptState(sessionId, line, sessionManager)
            }
            SessionInitState.READY -> {
                handleReadyState(sessionId, line, sessionManager)
            }
        }
    }
    
    /**
     * 处理进度输出（回车符分隔的输出）
     */
    fun processProgressOutput(
        sessionId: String,
        line: String,
        sessionManager: SessionManager
    ) {
        Log.d(TAG, "Processing progress output for session $sessionId: $line")
        
        val session = sessionManager.getSession(sessionId) ?: return
        
        if (session.initState != SessionInitState.READY) {
            return // 在会话完全准备好之前，丢弃所有进度输出
        }
        
        val cleanLine = stripAnsi(line)
        
        if (cleanLine.trim().isEmpty()) {
            return
        }
        
        // 检测命令回显
        if (isCommandEcho(cleanLine, session)) {
            Log.d(TAG, "Ignoring progress command echo: '$cleanLine'")
            return
        }
        
        // 检测交互式提示符
        if (isInteractivePrompt(cleanLine)) {
            handleInteractivePrompt(sessionId, cleanLine, sessionManager, isProgress = true)
            return
        }
        
        // 更新进度输出
        updateProgressOutput(sessionId, cleanLine, sessionManager)
    }
    
    private fun handleInitializingState(
        sessionId: String,
        line: String,
        sessionManager: SessionManager
    ) {
        if (line.contains("LOGIN_SUCCESSFUL")) {
            Log.d(TAG, "Login successful marker found.")
            sessionManager.updateSession(sessionId) { session ->
                val welcomeHistoryItem = CommandHistoryItem(
                    prompt = "",
                    command = "",
                    output = """
  ___                   _ _   
 / _ \ _ __   ___ _ __ (_) |_ 
| | | | '_ \ / _ \ '__ | | __|
| |_| | |_) |  __/ |   | | |_
 \___/| .__/ \___|_|   |_|\__|
      |_|                    

  >> Your portable Ubuntu environment on Android <<
""".trimIndent(),
                    isExecuting = false
                )
                session.copy(
                    initState = SessionInitState.LOGGED_IN,
                    commandHistory = listOf(welcomeHistoryItem),
                    currentCommandOutputBuilder = StringBuilder()
                )
            }
        }
    }
    
    private fun handleLoggedInState(
        sessionId: String,
        line: String,
        sessionManager: SessionManager
    ) {
        if (stripAnsi(line).trim() == "TERMINAL_READY") {
            Log.d(TAG, "TERMINAL_READY marker found.")
            sessionManager.updateSession(sessionId) { session ->
                session.copy(initState = SessionInitState.AWAITING_FIRST_PROMPT)
            }
        }
    }
    
    private fun handleAwaitingFirstPromptState(
        sessionId: String,
        line: String,
        sessionManager: SessionManager
    ) {
        val cleanLine = stripAnsi(line)
        if (handlePrompt(sessionId, cleanLine, sessionManager)) {
            Log.d(TAG, "First prompt detected. Session is now ready.")
            sessionManager.updateSession(sessionId) { session ->
                session.copy(initState = SessionInitState.READY)
            }
        }
    }
    
    private fun handleReadyState(
        sessionId: String,
        line: String,
        sessionManager: SessionManager
    ) {
        val cleanLine = stripAnsi(line)
        
        // 跳过TERMINAL_READY信号
        if (cleanLine.trim() == "TERMINAL_READY") {
            return
        }
        
        val session = sessionManager.getSession(sessionId) ?: return
        
        // 检测命令回显
        if (isCommandEcho(cleanLine, session)) {
            Log.d(TAG, "Ignoring command echo: '$cleanLine'")
            return
        }
        
        // 检测交互式提示符
        if (isInteractivePrompt(cleanLine)) {
            handleInteractivePrompt(sessionId, cleanLine, sessionManager)
            return
        }
        
        // 处理提示符
        if (handlePrompt(sessionId, cleanLine, sessionManager)) {
            return
        }
        
        // 处理普通输出
        if (cleanLine.isNotBlank()) {
            updateCommandOutput(sessionId, cleanLine, sessionManager)
        }
    }
    
    /**
     * 检测是否是提示符
     */
    fun isPrompt(line: String): Boolean {
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
    
    /**
     * 处理提示符
     */
    private fun handlePrompt(
        sessionId: String,
        line: String,
        sessionManager: SessionManager
    ): Boolean {
        val session = sessionManager.getSession(sessionId) ?: return false
        
        val cwdPromptRegex = Regex("<cwd>(.*)</cwd>.*[#$]")
        val match = cwdPromptRegex.find(line)

        val isAPrompt = if (match != null) {
            val path = match.groups[1]?.value?.trim() ?: "~"
            sessionManager.updateSession(sessionId) { session ->
                session.copy(currentDirectory = "$path $")
            }
            Log.d(TAG, "Matched CWD prompt. Path: $path")

            val outputBeforePrompt = line.substring(0, match.range.first)
            if (outputBeforePrompt.isNotBlank()) {
                session.currentCommandOutputBuilder.append(outputBeforePrompt)
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
                sessionManager.updateSession(sessionId) { session ->
                    session.copy(currentDirectory = "${cleanPrompt} $")
                }
                Log.d(TAG, "Matched fallback prompt: $cleanPrompt")
                true
            } else {
                false
            }
        }

        if (isAPrompt) {
            finishCurrentCommand(sessionId, sessionManager)
            return true
        }
        return false
    }
    
    /**
     * 检测是否是交互式提示符
     */
    fun isInteractivePrompt(line: String): Boolean {
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
    
    private fun handleInteractivePrompt(
        sessionId: String,
        cleanLine: String,
        sessionManager: SessionManager,
        isProgress: Boolean = false
    ) {
        Log.d(TAG, "Detected interactive prompt: $cleanLine")
        sessionManager.updateSession(sessionId) { session ->
            session.copy(
                isWaitingForInteractiveInput = true,
                lastInteractivePrompt = cleanLine,
                isInteractiveMode = true,
                interactivePrompt = cleanLine
            )
        }
        
        // 将交互式提示添加到当前命令的输出中
        if (cleanLine.isNotBlank()) {
            updateCommandOutput(sessionId, cleanLine, sessionManager, isProgress)
        }
    }
    
    private fun isCommandEcho(cleanLine: String, session: TerminalSessionData): Boolean {
        val sessionCommandHistory = session.commandHistory
        val lastExecutingIndex = sessionCommandHistory.indexOfLast { it.isExecuting }
        if (lastExecutingIndex != -1) {
            val executingItem = sessionCommandHistory[lastExecutingIndex]
            if (session.currentCommandOutputBuilder.isEmpty() && 
                cleanLine.trim() == executingItem.command.trim()) {
                return true
            }
        }
        return false
    }
    
    private fun updateCommandOutput(
        sessionId: String,
        cleanLine: String,
        sessionManager: SessionManager,
        isProgress: Boolean = false
    ) {
        val session = sessionManager.getSession(sessionId) ?: return
        session.currentCommandOutputBuilder.appendLine(cleanLine)
        
        val sessionCommandHistory = session.commandHistory
        val lastExecutingIndex = sessionCommandHistory.indexOfLast { it.isExecuting }
        
        if (lastExecutingIndex != -1) {
            val updatedHistory = sessionCommandHistory.toMutableList()
            val oldItem = updatedHistory[lastExecutingIndex]
            updatedHistory[lastExecutingIndex] = oldItem.copy(
                output = session.currentCommandOutputBuilder.toString().trim()
            )
            sessionManager.updateSession(sessionId) { session ->
                session.copy(commandHistory = updatedHistory)
            }
        } else {
            // 没有执行中的命令，这是系统输出
            appendOutputToHistory(sessionId, cleanLine, sessionManager)
        }
    }
    
    private fun updateProgressOutput(
        sessionId: String,
        cleanLine: String,
        sessionManager: SessionManager
    ) {
        val session = sessionManager.getSession(sessionId) ?: return
        val sessionCommandHistory = session.commandHistory
        val lastExecutingIndex = sessionCommandHistory.indexOfLast { it.isExecuting }
        
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
                lines.add(cleanLine)
            }
            
            updatedHistory[lastExecutingIndex] = oldItem.copy(
                output = lines.joinToString("\n")
            )
            sessionManager.updateSession(sessionId) { session ->
                session.copy(commandHistory = updatedHistory)
            }
        } else {
            appendProgressOutputToHistory(sessionId, cleanLine, sessionManager)
        }
    }
    
    private fun finishCurrentCommand(sessionId: String, sessionManager: SessionManager) {
        sessionManager.updateSession(sessionId) { session ->
            session.copy(
                isWaitingForInteractiveInput = false,
                lastInteractivePrompt = "",
                isInteractiveMode = false,
                interactivePrompt = ""
            )
        }

        val session = sessionManager.getSession(sessionId) ?: return
        val sessionCommandHistory = session.commandHistory
        val lastExecutingIndex = sessionCommandHistory.indexOfLast { it.isExecuting }
        
        if (lastExecutingIndex != -1) {
            val updatedHistory = sessionCommandHistory.toMutableList()
            val oldItem = updatedHistory[lastExecutingIndex]
            updatedHistory[lastExecutingIndex] = oldItem.copy(
                output = session.currentCommandOutputBuilder.toString().trim(),
                isExecuting = false
            )
            sessionManager.updateSession(sessionId) { session ->
                session.copy(commandHistory = updatedHistory)
            }
            session.currentCommandOutputBuilder.clear()
        }
    }
    
    private fun appendOutputToHistory(
        sessionId: String,
        line: String,
        sessionManager: SessionManager
    ) {
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
    
    private fun appendProgressOutputToHistory(
        sessionId: String,
        line: String,
        sessionManager: SessionManager
    ) {
        val session = sessionManager.getSession(sessionId) ?: return
        val currentHistory = session.commandHistory
        
        if (currentHistory.isEmpty()) {
            sessionManager.updateSession(sessionId) { session ->
                session.copy(commandHistory = listOf(CommandHistoryItem(prompt = "", command = "", output = line, isExecuting = false)))
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
            
            if (lines.isNotEmpty() && isProgressLine(line)) {
                lines[lines.size - 1] = line
            } else {
                lines.add(line)
            }
            
            updatedHistory[currentHistory.size - 1] = lastItem.copy(
                output = lines.joinToString("\n")
            )
            sessionManager.updateSession(sessionId) { session ->
                session.copy(commandHistory = updatedHistory)
            }
        }
    }
    
    private fun isProgressLine(line: String): Boolean {
        val cleanLine = line.trim()
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
               cleanLine.matches(Regex(".*\\d+/\\d+.*")) ||
               cleanLine.matches(Regex(".*\\[.*\\].*")) ||
               cleanLine.contains("downloading") ||
               cleanLine.contains("installing") ||
               cleanLine.contains("progress") ||
               cleanLine.contains("Loading") ||
               cleanLine.contains("Downloading") ||
               cleanLine.contains("Installing")
    }
    
    /**
     * 去除ANSI转义序列
     */
    private fun stripAnsi(text: String): String {
        return text.replace(Regex("\\x1B\\[[0-?]*[ -/]*[@-~]"), "")
    }
} 