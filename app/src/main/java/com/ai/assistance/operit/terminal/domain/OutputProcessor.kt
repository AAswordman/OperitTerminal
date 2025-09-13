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
        chunk: String,
        sessionManager: SessionManager
    ) {
        val session = sessionManager.getSession(sessionId) ?: return
        session.rawBuffer.append(chunk)

        Log.d(TAG, "Processing chunk for session $sessionId. New buffer size: ${session.rawBuffer.length}")

        // 始终检查全屏模式切换
        if (detectFullscreenMode(sessionId, session.rawBuffer, sessionManager)) {
            // 如果检测到模式切换，缓冲区可能已被修改，及早返回以处理下一个块
            return
        }

        // 如果在全屏模式下，将所有内容附加到屏幕内容
        if (session.isFullscreen) {
            updateScreenContent(sessionId, chunk, sessionManager)
            // Do not clear the raw buffer here, the parser needs the stream
            return
        }
        
        // 从缓冲区中提取并处理行
        while (session.rawBuffer.isNotEmpty()) {
            val bufferContent = session.rawBuffer.toString()
            val newlineIndex = bufferContent.indexOf('\n')
            val carriageReturnIndex = bufferContent.indexOf('\r')

            if (carriageReturnIndex != -1 && (newlineIndex == -1 || carriageReturnIndex < newlineIndex)) {
                // We have a carriage return.
                val line = bufferContent.substring(0, carriageReturnIndex)
                
                val isCRLF = carriageReturnIndex + 1 < bufferContent.length && bufferContent[carriageReturnIndex + 1] == '\n'
                val consumedLength = if (isCRLF) carriageReturnIndex + 2 else carriageReturnIndex + 1
                
                session.rawBuffer.delete(0, consumedLength)

                if (isCRLF) {
                    // It's a CRLF, treat as a normal line.
                    Log.d(TAG, "Processing CRLF line: '$line'")
                    processLine(sessionId, line, sessionManager)
                } else {
                    // It's just CR, treat as a progress update.
                    Log.d(TAG, "Processing CR line: '$line'")
                    handleProgressLine(sessionId, line, sessionManager)
                }
            } else if (newlineIndex != -1) {
                // We have a newline without a preceding carriage return.
                val line = bufferContent.substring(0, newlineIndex)
                session.rawBuffer.delete(0, newlineIndex + 1)
                Log.d(TAG, "Processing LF line: '$line'")
                processLine(sessionId, line, sessionManager)
            } else {
                // No full line-terminator found in the buffer.
                // Check if the remaining buffer is a prompt.
                val remainingContent = stripAnsi(bufferContent)
                if (isPrompt(remainingContent) || isInteractivePrompt(remainingContent)) {
                    Log.d(TAG, "Processing remaining buffer as interactive/shell prompt: '$bufferContent'")
                    processLine(sessionId, bufferContent, sessionManager)
                    session.rawBuffer.clear()
                }
                break // Exit loop, wait for more data.
            }
        }
    }

    private fun handleProgressLine(sessionId: String, line: String, sessionManager: SessionManager) {
        val cleanLine = stripAnsi(line)
        if (cleanLine.isEmpty()) {
            return
        }
        val session = sessionManager.getSession(sessionId) ?: return
        if (session.initState != SessionInitState.READY) {
            processLine(sessionId, line, sessionManager)
            return
        }
        updateProgressOutput(sessionId, cleanLine, sessionManager)
    }

    private fun processLine(
        sessionId: String,
        line: String,
        sessionManager: SessionManager
    ) {
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

        // 如果在全屏模式下，不应到达这里，但作为安全措施
        if (session.isFullscreen) {
            updateScreenContent(sessionId, line, sessionManager)
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
        sessionManager: SessionManager
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
            updateCommandOutput(sessionId, cleanLine, sessionManager)
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
        sessionManager: SessionManager
    ) {
        val session = sessionManager.getSession(sessionId) ?: return
        val builder = session.currentCommandOutputBuilder
        if (builder.isNotEmpty() && builder.last() != '\n') {
            builder.append('\n')
        }
        builder.appendLine(cleanLine)
        
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

        // Progress updates should manipulate the currentCommandOutputBuilder
        val builderContent = session.currentCommandOutputBuilder.toString()
        val lines = if (builderContent.isEmpty()) {
            mutableListOf()
        } else {
            builderContent.split('\n').toMutableList()
        }

        if (lines.isNotEmpty()) {
            lines[lines.size - 1] = cleanLine
        } else {
            lines.add(cleanLine)
        }
        
        session.currentCommandOutputBuilder.clear()
        session.currentCommandOutputBuilder.append(lines.joinToString("\n"))

        val sessionCommandHistory = session.commandHistory
        val lastExecutingIndex = sessionCommandHistory.indexOfLast { it.isExecuting }
        
        if (lastExecutingIndex != -1) {
            val updatedHistory = sessionCommandHistory.toMutableList()
            val oldItem = updatedHistory[lastExecutingIndex]
            
            // Update history from the builder
            updatedHistory[lastExecutingIndex] = oldItem.copy(
                output = session.currentCommandOutputBuilder.toString().trimEnd()
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
            
            if (lines.isNotEmpty()) {
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

    /**
     * 检测并处理全屏模式切换
     * @return 如果处理了全屏模式切换，则返回 true
     */
    private fun detectFullscreenMode(sessionId: String, buffer: StringBuilder, sessionManager: SessionManager): Boolean {
        // CSI ? 1049 h: 启用备用屏幕缓冲区（进入全屏模式）
        // CSI ? 1049 l: 禁用备用屏幕缓冲区（退出全屏模式）
        val enterFullscreen = "\u001B[?1049h"
        val exitFullscreen = "\u001B[?1049l"

        val bufferContent = buffer.toString()

        val enterIndex = bufferContent.indexOf(enterFullscreen)
        val exitIndex = bufferContent.indexOf(exitFullscreen)

        if (enterIndex != -1) {
            Log.d(TAG, "Entering fullscreen mode for session $sessionId")
            val remainingContent = bufferContent.substring(enterIndex + enterFullscreen.length)
            
            sessionManager.updateSession(sessionId) { session ->
                session.copy(
                    isFullscreen = true, 
                    screenContent = remainingContent // 将切换后的所有内容视为屏幕内容
                )
            }
            buffer.clear()
            return true
        }

        if (exitIndex != -1) {
            Log.d(TAG, "Exiting fullscreen mode for session $sessionId")
            val outputBeforeExit = bufferContent.substring(0, exitIndex)
            
            // 更新最后一个命令的输出
            if (outputBeforeExit.isNotEmpty()) {
                updateCommandOutput(sessionId, outputBeforeExit, sessionManager)
            }

            sessionManager.updateSession(sessionId) { session ->
                session.copy(isFullscreen = false, screenContent = "")
            }
            
            // 消耗包括退出代码在内的所有内容
            buffer.delete(0, exitIndex + exitFullscreen.length)

            // 退出全屏后，我们可能需要重新绘制提示符
            finishCurrentCommand(sessionId, sessionManager)
            return true
        }
        return false
    }

    /**
     * 更新全屏内容
     */
    private fun updateScreenContent(sessionId: String, content: String, sessionManager: SessionManager) {
        val session = sessionManager.getSession(sessionId) ?: return
        val processedContent = session.ansiParser.parse(content)
        sessionManager.updateSession(sessionId) { sessionToUpdate ->
            // Here we replace the content entirely since vim and other fullscreen apps
            // send full screen updates. The AnsiParser now manages the screen buffer.
            sessionToUpdate.copy(screenContent = processedContent)
        }
    }
}