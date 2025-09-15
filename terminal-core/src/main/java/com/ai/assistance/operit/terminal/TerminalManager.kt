package com.ai.assistance.operit.terminal

import android.content.Context
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.util.zip.ZipInputStream
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import java.util.zip.ZipEntry
import java.io.BufferedInputStream
import java.io.FileInputStream
import java.nio.file.Paths
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Job
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.map
import com.ai.assistance.operit.terminal.data.TerminalState
import com.ai.assistance.operit.terminal.data.CommandHistoryItem
import com.ai.assistance.operit.terminal.domain.SessionManager
import com.ai.assistance.operit.terminal.domain.OutputProcessor
import java.util.UUID
import java.io.OutputStreamWriter
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.update

@RequiresApi(Build.VERSION_CODES.O)
class TerminalManager private constructor(
    private val context: Context
) {
    private val coroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val filesDir: File = context.filesDir
    private val usrDir: File = File(filesDir, "usr")
    private val binDir: File = File(usrDir, "bin")
    private val nativeLibDir: String = context.applicationInfo.nativeLibraryDir
    private val activeSessions = ConcurrentHashMap<String, TerminalSession>()
    
    // 核心组件
    private val sessionManager = SessionManager(this)
    private val outputProcessor = OutputProcessor(
        onCommandExecutionEvent = { event ->
            coroutineScope.launch {
                _commandExecutionEvents.emit(event)
            }
        },
        onDirectoryChangeEvent = { event ->
            coroutineScope.launch {
                _directoryChangeEvents.emit(event)
            }
        }
    )
    
    // 状态和事件流
    private val _commandExecutionEvents = MutableSharedFlow<CommandExecutionEvent>()
    val commandExecutionEvents: SharedFlow<CommandExecutionEvent> = _commandExecutionEvents.asSharedFlow()
    
    private val _directoryChangeEvents = MutableSharedFlow<SessionDirectoryEvent>()
    val directoryChangeEvents: SharedFlow<SessionDirectoryEvent> = _directoryChangeEvents.asSharedFlow()
    
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

    companion object {
        @Volatile
        private var INSTANCE: TerminalManager? = null

        fun getInstance(context: Context): TerminalManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: TerminalManager(context.applicationContext).also { INSTANCE = it }
            }
        }
        
        private const val TAG = "TerminalManager"
        private const val UBUNTU_FILENAME = "ubuntu-noble-aarch64-pd-v4.18.0.tar.xz"
        private const val MAX_HISTORY_ITEMS = 500
        private const val MAX_OUTPUT_LINES_PER_ITEM = 1000
    }

    init {
        // 创建第一个会话
        createNewSession()
    }
    
    /**
     * 创建新会话
     */
    fun createNewSession(): com.ai.assistance.operit.terminal.data.TerminalSessionData {
        val newSession = sessionManager.createNewSession()
        initializeSession(newSession.id)
        return newSession
    }
    
    /**
     * 切换到会话
     */
    fun switchToSession(sessionId: String) {
        sessionManager.switchToSession(sessionId)
    }
    
    /**
     * 关闭会话
     */
    fun closeSession(sessionId: String) {
        sessionManager.closeSession(sessionId)
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun createBusyboxSymlinks() {
        val links = listOf(
            "awk", "ash", "basename", "bzip2", "curl", "cp", "chmod", "cut", "cat", "du", "dd",
            "find", "grep", "gzip", "hexdump", "head", "id", "lscpu", "mkdir", "realpath", "rm",
            "sed", "stat", "sh", "tr", "tar", "uname", "xargs", "xz", "xxd"
        )
        val busybox = File(binDir, "busybox")
        for (linkName in links) {
            try {
                createSymbolicLink(busybox, linkName, binDir, true)
                Log.d(TAG, "Created busybox link for '$linkName'")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to create link for '$linkName'", e)
            }
        }
        try {
            val fileLink = File(binDir, "file")
            if (!fileLink.exists()) {
                Files.createSymbolicLink(fileLink.toPath(), File("/system/bin/file").toPath())
                Log.d(TAG, "Created symlink for 'file'")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create symlink for 'file'", e)
        }
    }

    /**
     * 发送命令
     */
    fun sendCommand(command: String): String {
        val commandId = UUID.randomUUID().toString()
        sendInput(command, isCommand = true, commandId = commandId)
        return commandId
    }

    /**
     * 发送输入
     */
    fun sendInput(input: String, isCommand: Boolean = false, commandId: String? = null) {
        coroutineScope.launch(Dispatchers.IO) {
            val session = sessionManager.getCurrentSession() ?: return@launch
            
            try {
                val fullInput = if (isCommand) "$input\n" else input
                session.sessionWriter?.write(fullInput)
                session.sessionWriter?.flush()
                Log.d(TAG, "Sent input. isCommand=$isCommand, input='$fullInput'")

                if (isCommand) {
                    require(commandId != null) { "commandId must be provided when isCommand is true" }
                    handleCommandLogic(input, session, commandId)
                }

            } catch (e: Exception) {
                Log.e(TAG, "Error sending input", e)
                appendOutputToHistory(session.id, "Error sending input: ${e.message}")
            }
        }
    }

    /**
     * 发送中断信号
     */
    fun sendInterruptSignal() {
        coroutineScope.launch(Dispatchers.IO) {
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
    
    private fun initializeSession(sessionId: String) {
        coroutineScope.launch {
            val success = initializeEnvironment()
            if (success) {
                appendOutputToHistory(sessionId, "Environment initialized. Starting session...")
                startSession(sessionId)
            } else {
                appendOutputToHistory(sessionId, "FATAL: Environment initialization failed. Check logs.")
            }
        }
    }

    private fun startSession(sessionId: String) {
        coroutineScope.launch(Dispatchers.IO) {
            try {
                val terminalSession = startTerminalSession(sessionId)
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

    @RequiresApi(Build.VERSION_CODES.O)
    suspend fun initializeEnvironment(): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                Log.d(TAG, "Starting environment initialization...")

                // 1. Create necessary directories
                createDirectories()

                // 2. Link native libraries
                linkNativeLibs()
                createBusyboxSymlinks()

                // 3. Extract assets
                extractAssets()

                // 4. Generate and write startup script
                val startScript = generateStartScript()
                File(filesDir, "common.sh").writeText(startScript)


                Log.d(TAG, "Environment initialization completed successfully.")
                true
            } catch (e: Exception) {
                Log.e(TAG, "Environment initialization failed", e)
                false
            }
        }
    }

    private fun createDirectories() {
        if (!usrDir.exists()) {
            usrDir.mkdirs()
        }
        if (!binDir.exists()) {
            binDir.mkdirs()
            Log.d(TAG, "Created bin directory at: ${binDir.absolutePath}")
        }
        File(filesDir, "tmp").mkdirs()
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun linkNativeLibs() {
        Log.d(TAG, "Linking native libraries from: $nativeLibDir")
        
        val nativeLibDirFile = File(nativeLibDir)
        if (!nativeLibDirFile.exists() || !nativeLibDirFile.isDirectory) {
            Log.e(TAG, "Native library directory not found or is not a directory.")
            return
        }

        Log.d(TAG, "Native lib directory contents:")
        nativeLibDirFile.listFiles()?.forEach { file ->
            Log.d(TAG, "  - ${file.name} (file ${file.length()} bytes)")
        }
        
        val busybox = File(binDir, "busybox")

        // First, we need to link busybox itself so we can use it.
        val busyboxSo = File(nativeLibDir, "libbusybox.so")
        Log.d(TAG, "Checking busybox: libbusybox.so exists = ${busyboxSo.exists()}, busybox exists = ${busybox.exists()}")
        
        if (!busyboxSo.exists()) {
            Log.e(TAG, "libbusybox.so not found, cannot create busybox link")
            return
        }
        
        // Always ensure proper busybox link - remove any existing file/broken link first
        try {
            val link = busybox.toPath()
            val target = busyboxSo.toPath()
            
            // Delete existing file/broken link if it exists to prevent FileAlreadyExistsException
            Files.deleteIfExists(link)
            
            // CRITICAL: Set execute permission on the target .so file before creating symlink
            busyboxSo.setExecutable(true, false)
            
            // Create the symbolic link
            Files.createSymbolicLink(link, target)
            Log.d(TAG, "Created busybox symbolic link using Java NIO")
            
            // Verify the link was created successfully and is functional
            if (busybox.exists() && busybox.canExecute()) {
                Log.d(TAG, "Verification: busybox link exists and is executable at ${busybox.absolutePath}")
            } else {
                Log.e(TAG, "Verification failed: busybox link not functional after creation")
                return
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create busybox link using Java NIO", e)
            return
        }

        // Symlink other binaries
        val libraries = mapOf(
            "libproot.so" to "proot",
            "libloader.so" to "loader",
            "liblibtalloc.so.2.so" to "libtalloc.so.2", // Keep .so extension for libs
            "libbash.so" to "bash",
            "libsudo.so" to "sudo"
        )

        libraries.forEach { (libName, linkName) ->
            val libFile = File(nativeLibDir, libName)
            val linkFile = File(binDir, linkName)
            
            Log.d(TAG, "Checking $libName at ${libFile.absolutePath}, exists: ${libFile.exists()}")
            
            if (!libFile.exists()) {
                Log.w(TAG, "Native library not found: $libName")
                return@forEach
            }
            
            // Always ensure proper link - remove any existing file/broken link first
            try {
                val link = linkFile.toPath()
                val target = libFile.toPath()
                
                // Delete existing file/broken link if it exists to prevent FileAlreadyExistsException
                Files.deleteIfExists(link)
                
                // CRITICAL: Set execute permission on the target .so file before creating symlink
                libFile.setExecutable(true, false)
                
                // Create the symbolic link
                Files.createSymbolicLink(link, target)
                Log.d(TAG, "Created $linkName symbolic link using Java NIO")
                
                // Verify the link was created successfully and is executable
                if (linkFile.exists() && linkFile.canExecute()) {
                    Log.d(TAG, "Verification: $linkName link exists and is executable at ${linkFile.absolutePath}")
                } else {
                    Log.w(TAG, "Verification failed: $linkName link not executable after creation")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to create $linkName link using Java NIO", e)
            }
        }
    }
    
    @RequiresApi(Build.VERSION_CODES.O)
    @Throws(IOException::class)
    private fun createSymbolicLink(target: File, linkName: String, linkDir: File, force: Boolean) {
        val linkFile = File(linkDir, linkName)

        // Use relative path for target if it's in the same directory
        val targetPath = if (target.parentFile == linkDir) {
            Paths.get(target.name)
        } else {
            target.toPath()
        }

        if (force) {
            Files.deleteIfExists(linkFile.toPath())
        }
        Files.createSymbolicLink(linkFile.toPath(), targetPath)
    }

    private fun extractAssets() {
        try {
            val assets = listOf(
                "proot-distro.zip",
                UBUNTU_FILENAME
            )
            assets.forEach { assetName ->
                 val assetFile = File(filesDir, assetName)
                 if (!assetFile.exists()) {
                    context.assets.open(assetName).use { input ->
                        assetFile.outputStream().use { output ->
                            input.copyTo(output)
                        }
                    }
                    Log.d(TAG, "Extracted $assetName")
                } else {
                    Log.d(TAG, "Asset $assetName already exists.")
                }
            }
        } catch (e: IOException) {
            Log.e(TAG, "Failed to extract assets", e)
            throw e
        }
    }
    
    private fun generateStartScript(): String {
        val ubuntuName = UBUNTU_FILENAME.replace(Regex("-pd.*"), "")
        val tmpDir = File(filesDir, "tmp").absolutePath
        val binDir = binDir.absolutePath
        val homeDir = filesDir.absolutePath
        val usrDir = usrDir.absolutePath
        val prootDistroPath = "$usrDir/var/lib/proot-distro"
        val ubuntuPath = "$prootDistroPath/installed-rootfs/ubuntu"

        val common = """
        export TMPDIR=$tmpDir
        export BIN=$binDir
        export HOME=$homeDir
        export UBUNTU_PATH=$ubuntuPath
        export UBUNTU=$UBUNTU_FILENAME
        export UBUNTU_NAME=$ubuntuName
        export L_NOT_INSTALLED="not installed"
        export L_INSTALLING="installing"
        export L_INSTALLED="installed"
        clear_lines(){
          printf "\\033[1A" # Move cursor up one line
          printf "\\033[K"  # Clear the line
          printf "\\033[1A" # Move cursor up one line
          printf "\\033[K"  # Clear the line
        }
        progress_echo(){
          echo -e "\\033[31m- ${'$'}@\\033[0m"
          echo "${'$'}@" > "${'$'}TMPDIR/progress_des"
        }
        bump_progress(){
          current=0
          if [ -f "${'$'}TMPDIR/progress" ]; then
            current=${'$'}(cat "${'$'}TMPDIR/progress" 2>/dev/null || echo 0)
          fi
          next=${'$'}((current + 1))
          printf "${'$'}next" > "${'$'}TMPDIR/progress"
        }
        """.trimIndent()

        val changeUbuntuNobleSource = """
        change_ubuntu_source(){
          cat <<EOF > ${'$'}UBUNTU_PATH/etc/apt/sources.list
        # 默认注释了源码镜像以提高 apt update 速度，如有需要可自行取消注释
        # Defaultly commented out source mirrors to speed up apt update, uncomment if needed
        deb http://mirrors.tuna.tsinghua.edu.cn/ubuntu-ports/ noble main restricted universe multiverse
        # deb-src https://mirrors.tuna.tsinghua.edu.cn/ubuntu-ports/ noble main restricted universe multiverse
        deb http://mirrors.tuna.tsinghua.edu.cn/ubuntu-ports/ noble-updates main restricted universe multiverse
        # deb-src https://mirrors.tuna.tsinghua.edu.cn/ubuntu-ports/ noble-updates main restricted universe multiverse
        deb http://mirrors.tuna.tsinghua.edu.cn/ubuntu-ports/ noble-backports main restricted universe multiverse
        # deb-src https://mirrors.tuna.tsinghua.edu.cn/ubuntu-ports/ noble-backports main restricted universe multiverse
        # 以下安全更新软件源包含了官方源与镜像站配置，如有需要可自行修改注释切换
        # The following security update software sources include both official and mirror configurations, modify comments to switch if needed
        # deb http://ports.ubuntu.com/ubuntu-ports/ noble-security main restricted universe multiverse
        # deb-src http://ports.ubuntu.com/ubuntu-ports/ noble-security main restricted universe multiverse
        # 预发布软件源，不建议启用
        # The following pre-release software sources are not recommended to be enabled
        # deb https://mirrors.tuna.tsinghua.edu.cn/ubuntu-ports/ noble-proposed main restricted universe multiverse
        # # deb-src https://mirrors.tuna.tsinghua.edu.cn/ubuntu-ports/ noble-proposed main restricted universe multiverse
        EOF
        }
        """.trimIndent()
        
        val installUbuntu = """
        install_ubuntu(){
          mkdir -p ${'$'}UBUNTU_PATH 2>/dev/null
          if [ -z "${'$'}(ls -A ${'$'}UBUNTU_PATH)" ]; then
            progress_echo "Ubuntu ${'$'}L_NOT_INSTALLED, ${'$'}L_INSTALLING..."
            ls ~/${'$'}UBUNTU
            progress_echo "Extracting Ubuntu rootfs..."
            busybox tar xf ~/${'$'}UBUNTU -C ${'$'}UBUNTU_PATH/ >/dev/null 2>&1
            echo "Extraction complete"
            mv ${'$'}UBUNTU_PATH/${'$'}UBUNTU_NAME/* ${'$'}UBUNTU_PATH/ 2>/dev/null
            rm -rf ${'$'}UBUNTU_PATH/${'$'}UBUNTU_NAME
            echo 'export ANDROID_DATA=/home/' >> ${'$'}UBUNTU_PATH/root/.bashrc
          else
            VERSION=`cat ${'$'}UBUNTU_PATH/etc/issue.net 2>/dev/null`
            progress_echo "Ubuntu ${'$'}L_INSTALLED -> ${'$'}VERSION"
          fi
          change_ubuntu_source
          echo 'nameserver 8.8.8.8' > ${'$'}UBUNTU_PATH/etc/resolv.conf
        }
        """.trimIndent()

        val installProotDistro = """
        install_proot_distro(){
          proot_distro_path=`which proot-distro`
          if [ -z "${'$'}proot_distro_path" ]; then
            progress_echo "proot-distro ${'$'}L_NOT_INSTALLED, ${'$'}L_INSTALLING..."
            cd ~
            busybox unzip proot-distro.zip -d proot-distro
            cd ~/proot-distro
            bash ./install.sh
          else
            progress_echo "proot-distro ${'$'}L_INSTALLED"
          fi
        }
        """.trimIndent()
        
        val loginUbuntu = """
        login_ubuntu(){
          exec ${'$'}BIN/bash ${'$'}BIN/proot-distro login --bind /storage/emulated/0:/sdcard/ ubuntu --isolated -- /bin/bash -c "echo LOGIN_SUCCESSFUL; exec /bin/bash -il"
        }
        """.trimIndent()

        return """
        $common
        $changeUbuntuNobleSource
        $installUbuntu
        $loginUbuntu
        $installProotDistro
        clear_lines
        start_shell(){
          install_proot_distro
          sleep 1
          bump_progress
          install_ubuntu
          sleep 1
          bump_progress
          login_ubuntu
        }
        """.trimIndent()
    }
    
    fun startTerminalSession(sessionId: String): TerminalSession {
        val bash = File(binDir, "bash").absolutePath
        val startScript = "source \$HOME/common.sh && start_shell"
        
        val command = arrayOf(bash, "-c", startScript)

        val env = mutableMapOf<String, String>()
        env["PATH"] = "${binDir.absolutePath}:${System.getenv("PATH")}"
        env["HOME"] = filesDir.absolutePath
        env["PREFIX"] = usrDir.absolutePath
        env["TERMUX_PREFIX"] = usrDir.absolutePath
        env["LD_LIBRARY_PATH"] = "${nativeLibDir}:${binDir.absolutePath}"
        env["PROOT_LOADER"] = File(binDir, "loader").absolutePath
        env["TMPDIR"] = File(filesDir, "tmp").absolutePath
        env["PROOT_TMP_DIR"] = File(filesDir, "tmp").absolutePath
        env["TERM"] = "xterm-256color"
        env["LANG"] = "en_US.UTF-8"

        Log.d(TAG, "Starting terminal session with command: ${command.joinToString(" ")}")
        Log.d(TAG, "Environment: $env")

        val pty = Pty.start(command, env, filesDir)

        val session = TerminalSession(
            process = pty.process,
            stdout = pty.stdout,
            stdin = pty.stdin
        )
        activeSessions[sessionId] = session
        return session
    }

    fun closeTerminalSession(sessionId: String) {
        activeSessions[sessionId]?.let { session ->
            session.process.destroy()
            activeSessions.remove(sessionId)
            Log.d(TAG, "Closed and removed session: $sessionId")
        }
    }

    private fun handleCommandLogic(command: String, session: com.ai.assistance.operit.terminal.data.TerminalSessionData, commandId: String) {
        if (session.isWaitingForInteractiveInput) {
            // Interactive mode: input is part of the previous command's output
            Log.d(TAG, "Handling interactive input: $command")
            sessionManager.updateSession(session.id) {
                it.copy(
                    isWaitingForInteractiveInput = false,
                    lastInteractivePrompt = "",
                    isInteractiveMode = false,
                    interactivePrompt = ""
                )
            }
        } else {
            // Normal command or input for a persistent interactive session (like node)
            if (session.isInteractiveMode) {
                // In persistent interactive mode, we don't create a new history item.
                // The input is just sent to the running process.
                // The output will be handled by the OutputProcessor.
                Log.d(TAG, "Sending input to interactive session: $command")
            } else if (command.trim() == "clear") {
                handleClearCommand(session)
            } else {
                handleRegularCommand(command, session, commandId)
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
    
    private fun handleRegularCommand(command: String, session: com.ai.assistance.operit.terminal.data.TerminalSessionData, commandId: String) {
        session.currentCommandOutputBuilder.clear()
        session.currentOutputLineCount = 0
        
        val newCommandItem = CommandHistoryItem(
            id = commandId,
            prompt = session.currentDirectory,
            command = command,
            output = "",
            isExecuting = true
        )
        
        // Set the current executing command reference for efficient access
        session.currentExecutingCommand = newCommandItem
        session.commandHistory.add(newCommandItem)
        
        // 发出命令开始执行事件
        coroutineScope.launch {
            _commandExecutionEvents.emit(CommandExecutionEvent(
                commandId = newCommandItem.id,
                sessionId = session.id,
                outputChunk = "",
                isCompleted = false
            ))
        }
    }
    
    private suspend fun appendOutputToHistory(sessionId: String, output: String) {
        withContext(Dispatchers.Main) {
            sessionManager.updateSession(sessionId) { session ->
                val currentHistory = session.commandHistory.toMutableList()
                val outputLines = output.split("\n")

                if (currentHistory.isEmpty()) {
                    currentHistory.addAll(outputLines.map { CommandHistoryItem(id = UUID.randomUUID().toString(), prompt = "", command = "", output = it, isExecuting = false) })
                } else {
                    val lastItem = currentHistory.last()
                    val firstNewLine = outputLines.first()

                    if (lastItem.output.endsWith("\u001B[?2004l")) {
                        lastItem.setOutput(lastItem.output + firstNewLine)
                        if (outputLines.size > 1) {
                            currentHistory.addAll(outputLines.drop(1).map { CommandHistoryItem(id = UUID.randomUUID().toString(), prompt = "", command = "", output = it, isExecuting = false) })
                        }
                    } else {
                        currentHistory.addAll(outputLines.map { CommandHistoryItem(id = UUID.randomUUID().toString(), prompt = "", command = "", output = it, isExecuting = false) })
                    }
                }
                
                session.copy(commandHistory = androidx.compose.runtime.mutableStateListOf<CommandHistoryItem>().apply { addAll(currentHistory) })
            }
        }
    }

    fun cleanup() {
        activeSessions.keys.forEach { sessionId ->
            closeTerminalSession(sessionId)
        }
        sessionManager.cleanup()
        coroutineScope.cancel()
        Log.d(TAG, "All active sessions cleaned up.")
    }
} 