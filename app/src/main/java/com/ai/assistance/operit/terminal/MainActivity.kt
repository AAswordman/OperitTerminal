package com.ai.assistance.operit.terminal

import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.annotation.RequiresApi
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.ai.assistance.operit.terminal.view.TerminalScreen
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.platform.LocalContext
import com.ai.assistance.operit.terminal.data.CommandHistoryItem

class MainActivity : ComponentActivity() {
    @RequiresApi(Build.VERSION_CODES.O)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // 启用边到边显示
        enableEdgeToEdge()
        
        // 隐藏系统UI以实现全屏
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val windowInsetsController = WindowCompat.getInsetsController(window, window.decorView)
        windowInsetsController.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        windowInsetsController.hide(WindowInsetsCompat.Type.systemBars())
        
        setContent {
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = Color.Black
            ) {
                val context = LocalContext.current
                val terminalManager = remember { TerminalManager.getInstance(context) }
                
                val sessions by terminalManager.sessions.collectAsState(initial = emptyList())
                val currentSessionId by terminalManager.currentSessionId.collectAsState(initial = null)
                val commandHistory by terminalManager.commandHistory.collectAsState(initial = SnapshotStateList<CommandHistoryItem>())
                val currentDirectory by terminalManager.currentDirectory.collectAsState(initial = "$ ")
                var command by remember { mutableStateOf("") }
                val isFullscreen by terminalManager.isFullscreen.collectAsState(initial = false)
                val screenContent by terminalManager.screenContent.collectAsState(initial = "")

                TerminalScreen(
                    sessions = sessions,
                    currentSessionId = currentSessionId,
                    commandHistory = commandHistory,
                    currentDirectory = currentDirectory,
                    isFullscreen = isFullscreen,
                    screenContent = screenContent,
                    command = command,
                    onCommandChange = { command = it },
                    onSendInput = { inputText, isCommand ->
                        if (inputText.isNotBlank()) {
                            if (isCommand) {
                                terminalManager.sendCommand(inputText)
                                command = ""
                            } else {
                                terminalManager.sendInput(inputText)
                            }
                        }
                    },
                    onInterrupt = { terminalManager.sendInterruptSignal() },
                    onNewSession = { terminalManager.createNewSession() },
                    onSwitchSession = { sessionId -> terminalManager.switchToSession(sessionId) },
                    onCloseSession = { sessionId -> terminalManager.closeSession(sessionId) }
                )
            }
        }
    }
}