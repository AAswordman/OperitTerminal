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
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.runtime.collectAsState

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
                val terminalViewModel: TerminalViewModel = viewModel()
                val sessions by terminalViewModel.sessions.collectAsState(initial = emptyList())
                val currentSessionId by terminalViewModel.currentSessionId.collectAsState(initial = null)
                val commandHistory by terminalViewModel.commandHistory.collectAsState(initial = emptyList())
                val currentDirectory by terminalViewModel.currentDirectory.collectAsState(initial = "$ ")
                var command by remember { mutableStateOf("") }

                TerminalScreen(
                    sessions = sessions,
                    currentSessionId = currentSessionId,
                    commandHistory = commandHistory,
                    currentDirectory = currentDirectory,
                    command = command,
                    onCommandChange = { command = it },
                    onSendCommand = { sentCommand ->
                        if (sentCommand.isNotBlank()) {
                            terminalViewModel.sendCommand(sentCommand)
                            command = ""
                        }
                    },
                    onInterrupt = { terminalViewModel.sendInterruptSignal() },
                    onNewSession = { terminalViewModel.createNewSession() },
                    onSwitchSession = { sessionId -> terminalViewModel.switchToSession(sessionId) },
                    onCloseSession = { sessionId -> terminalViewModel.closeSession(sessionId) }
                )
            }
        }
    }
}