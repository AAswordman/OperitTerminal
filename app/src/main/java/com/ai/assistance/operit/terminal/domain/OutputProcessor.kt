package com.ai.assistance.operit.terminal.domain

import android.util.Log
import com.ai.assistance.operit.terminal.CommandExecutionEvent
import com.ai.assistance.operit.terminal.SessionDirectoryEvent
import com.ai.assistance.operit.terminal.data.CommandHistoryItem
import com.ai.assistance.operit.terminal.data.SessionInitState
import com.ai.assistance.operit.terminal.data.TerminalSessionData
import java.util.UUID

/**
 * 终端输出的会话处理状态
 * @property justHandledCarriageReturn 如果最近处理的行分隔符是回车符（CR），则为 true
 */
private data class SessionProcessingState(
    var justHandledCarriageReturn: Boolean = false
)

/**
 * 终端输出处理器
 * 负责处理和解析终端输出，更新会话状态
 */
class OutputProcessor(
    private val onCommandExecutionEvent: (CommandExecutionEvent) -> Unit = {},
    private val onDirectoryChangeEvent: (SessionDirectoryEvent) -> Unit = {}
) {

    private val sessionStates = mutableMapOf<String, SessionProcessingState>()

    companion object {
        private const val TAG = "OutputProcessor"
        private const val MAX_LINES_PER_HISTORY_ITEM = 10
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

        val state = sessionStates.getOrPut(sessionId) { SessionProcessingState() }

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
                    // It's a CRLF, treat as a normal line. `processLine` will handle the
                    // case where this CRLF finalizes a progress-updated line.
                    Log.d(TAG, "Processing CRLF line: '$line'")
                    processLine(sessionId, line, sessionManager)
                } else {
                    // It's just CR, treat as a progress update.
                    Log.d(TAG, "Processing CR line: '$line'")
                    handleCarriageReturn(sessionId, line, sessionManager)
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
                if (isPrompt(remainingContent) || isPersistentInteractivePrompt(remainingContent) || isInteractivePrompt(remainingContent)) {
                    Log.d(TAG, "Processing remaining buffer as interactive/shell prompt: '$bufferContent'")
                    processLine(sessionId, bufferContent, sessionManager)
                    session.rawBuffer.clear()
                }
                break // Exit loop, wait for more data.
            }
        }
    }

    private fun handleCarriageReturn(sessionId: String, line: String, sessionManager: SessionManager) {
        val cleanLine = stripAnsi(line)
        val session = sessionManager.getSession(sessionId) ?: return
        if (session.initState != SessionInitState.READY) {
            processLine(sessionId, line, sessionManager)
            return
        }
        updateProgressOutput(sessionId, cleanLine, sessionManager)
        sessionStates[sessionId]?.justHandledCarriageReturn = true
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
                val state = sessionStates.getOrPut(sessionId) { SessionProcessingState() }
                if (state.justHandledCarriageReturn) {
                    // A newline is received after a carriage return. This finalizes the line that was being updated.
                    state.justHandledCarriageReturn = false // Reset state immediately

                    val cleanLine = stripAnsi(line)

                    // If the line following the CR is not empty, it means we need to overwrite the
                    // current line's content before finalizing with a newline. This happens with
                    // tools like 'wget' or 'curl' that print status on the same line.
                    // e.g., "progress... 50%\rprogress... 100%\n"
                    if (cleanLine.isNotEmpty()) {
                        updateProgressOutput(sessionId, cleanLine, sessionManager)
                    }

                    // Always append a newline to finalize the line and move to the next.
                    val currentItem = session.currentExecutingCommand
                    if (currentItem != null) {
                        // Get the line content BEFORE adding the newline.
                        val builder = session.currentCommandOutputBuilder
                        val lastNewlineIndex = builder.lastIndexOf('\n')
                        val finalizedLine = if (lastNewlineIndex != -1) {
                            builder.substring(lastNewlineIndex + 1)
                        } else {
                            builder.toString()
                        }

                        // Send event if the finalized line is not just whitespace
                        if (finalizedLine.isNotBlank()) {
                            onCommandExecutionEvent(CommandExecutionEvent(
                                commandId = currentItem.id,
                                sessionId = sessionId,
                                outputChunk = finalizedLine,
                                isCompleted = false
                            ))
                        }

                        // Now, finalize the line in the buffer.
                        session.currentCommandOutputBuilder.append('\n')
                        currentItem.setOutput(session.currentCommandOutputBuilder.toString())
                    } else {
                        val lastItem = session.commandHistory.lastOrNull()
                        // Check if the last item is a pure output block and not empty
                        lastItem?.let {
                            if (!it.isExecuting && it.prompt.isBlank() && it.command.isBlank() && it.output.isNotEmpty()) {
                                it.setOutput(it.output + "\n")
                            }
                        }
                    }
                } else {
                    handleReadyState(sessionId, line, sessionManager)
                }
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
            sessionManager.getSession(sessionId)?.let { session ->
                val welcomeHistoryItem = CommandHistoryItem(
                    id = UUID.randomUUID().toString(),
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
                session.commandHistory.clear()
                session.commandHistory.add(welcomeHistoryItem)
                session.currentCommandOutputBuilder.clear()
                sessionManager.updateSession(sessionId) {
                    it.copy(initState = SessionInitState.LOGGED_IN)
                }
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

        // 优先处理常规提示符，因为它表示命令结束，应退出任何交互模式
        if (handlePrompt(sessionId, cleanLine, sessionManager)) {
            return
        }

        // 检测持久性交互提示符 (e.g., node >)
        if (isPersistentInteractivePrompt(cleanLine)) {
            handlePersistentInteractivePrompt(sessionId, cleanLine, sessionManager)
            return
        }

        // 检测临时性交互提示符 (e.g., [y/n])
        if (isInteractivePrompt(cleanLine)) {
            handleInteractivePrompt(sessionId, cleanLine, sessionManager)
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
            
            // 发出目录变化事件
            onDirectoryChangeEvent(SessionDirectoryEvent(
                sessionId = sessionId,
                currentDirectory = "$path $"
            ))
            
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
                
                // 发出目录变化事件
                onDirectoryChangeEvent(SessionDirectoryEvent(
                    sessionId = sessionId,
                    currentDirectory = "${cleanPrompt} $"
                ))
                
                Log.d(TAG, "Matched fallback prompt: $cleanPrompt")
                true
            } else {
                false
            }
        }

        if (isAPrompt) {
            // 检测到常规提示符，表示我们回到了shell。
            // 确保退出任何持久的交互模式。
            if (session.isInteractiveMode) {
                sessionManager.updateSession(sessionId) {
                    it.copy(
                        isInteractiveMode = false,
                        interactivePrompt = ""
                    )
                }
            }
            finishCurrentCommand(sessionId, sessionManager)
            return true
        }
        return false
    }

    /**
     * 检测是否是临时性交互提示符 (例如 y/n)
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

    /**
     * 检测是否是持久性交互提示符 (例如 Node.js REPL)
     */
    private fun isPersistentInteractivePrompt(line: String): Boolean {
        val trimmed = line.trim()
        return trimmed == ">" ||
                trimmed.startsWith("> ") ||
                trimmed == "..." ||
                trimmed.startsWith("... ") ||
                trimmed == ">>>" ||
                trimmed.startsWith(">>> ")
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
                // Do not set isInteractiveMode to true for temporary prompts
                // isInteractiveMode = true, 
                interactivePrompt = cleanLine
            )
        }

        // 将交互式提示添加到当前命令的输出中
        if (cleanLine.isNotBlank()) {
            updateCommandOutput(sessionId, cleanLine, sessionManager)
        }
    }

    private fun handlePersistentInteractivePrompt(
        sessionId: String,
        cleanLine: String,
        sessionManager: SessionManager
    ) {
        Log.d(TAG, "Detected persistent interactive prompt: $cleanLine")
        finishCurrentCommand(sessionId, sessionManager)
        sessionManager.updateSession(sessionId) { session ->
            session.copy(
                isInteractiveMode = true,
                interactivePrompt = cleanLine.trim()
            )
        }
    }

    private fun isCommandEcho(cleanLine: String, session: TerminalSessionData): Boolean {
        val lastExecutingItem = session.currentExecutingCommand
        if (lastExecutingItem != null && lastExecutingItem.isExecuting) {
            if (session.currentCommandOutputBuilder.isEmpty() &&
                cleanLine.trim() == lastExecutingItem.command.trim()) {
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
        val currentItem = session.currentExecutingCommand

        if (currentItem != null && currentItem.isExecuting) {
            val builder = session.currentCommandOutputBuilder
            if (builder.isNotEmpty() && builder.last() != '\n') {
                builder.append('\n')
            }
            builder.append(cleanLine)

            // 实时更新当前输出块
            currentItem.setOutput(builder.toString())
            session.currentOutputLineCount++
            
            // 发出命令执行过程事件
            onCommandExecutionEvent(CommandExecutionEvent(
                commandId = currentItem.id,
                sessionId = sessionId,
                outputChunk = cleanLine,
                isCompleted = false
            ))

            if (session.currentOutputLineCount >= MAX_LINES_PER_HISTORY_ITEM) {
                // 当前页已满，将其添加到已完成的页面列表并开始新的一页
                currentItem.outputPages.add(currentItem.output)
                builder.clear()
                session.currentOutputLineCount = 0
                currentItem.setOutput("") // 为新页面清空实时输出
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
        val builder = session.currentCommandOutputBuilder

        // More efficient way to replace the last line
        val lastNewlineIndex = builder.lastIndexOf('\n')
        if (lastNewlineIndex != -1) {
            // Found a newline, replace everything after it
            builder.setLength(lastNewlineIndex + 1)
            builder.append(cleanLine)
        } else {
            // No newline, replace the whole buffer
            builder.clear()
            builder.append(cleanLine)
        }

        val lastExecutingItem = session.currentExecutingCommand

        if (lastExecutingItem != null && lastExecutingItem.isExecuting) {
            // Update history from the builder
            lastExecutingItem.setOutput(builder.toString().trimEnd())
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
        val lastExecutingItem = session.currentExecutingCommand

        if (lastExecutingItem != null && lastExecutingItem.isExecuting) {
            lastExecutingItem.setOutput(session.currentCommandOutputBuilder.toString().trim())
            lastExecutingItem.setExecuting(false)
            
            // 发出命令完成事件
            onCommandExecutionEvent(CommandExecutionEvent(
                commandId = lastExecutingItem.id,
                sessionId = sessionId,
                outputChunk = "",
                isCompleted = true
            ))

            // Clear the reference since command is no longer executing
            session.currentExecutingCommand = null
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

        val lastItem = currentHistory.lastOrNull()

        // 尝试追加到最后一项。如果 lastItem 不为 null 且是纯输出块，则追加。
        val appended = lastItem?.let {
            if (!it.isExecuting && it.prompt.isBlank() && it.command.isBlank()) {
                it.setOutput(if (it.output.isEmpty()) line else it.output + "\n" + line)
                true // 返回 true 表示追加成功
            } else {
                false // 不满足追加条件
            }
        } ?: false // lastItem 为 null，无法追加

        // 如果没有追加成功，则创建一个新的历史记录项
        if (!appended) {
            currentHistory.add(CommandHistoryItem(id = UUID.randomUUID().toString(), prompt = "", command = "", output = line, isExecuting = false))
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
            currentHistory.add(CommandHistoryItem(id = UUID.randomUUID().toString(), prompt = "", command = "", output = line, isExecuting = false))
        } else {
            val lastItem = currentHistory.last()

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

            lastItem.setOutput(lines.joinToString("\n"))
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
        session.ansiParser.parse(content)
        val renderedScreen = session.ansiParser.renderScreenToString()
        sessionManager.updateSession(sessionId) { sessionToUpdate ->
            // Here we replace the content entirely since vim and other fullscreen apps
            // send full screen updates. The AnsiParser now manages the screen buffer.
            sessionToUpdate.copy(screenContent = renderedScreen)
        }
    }
}