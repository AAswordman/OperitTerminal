package com.ai.assistance.operit.terminal

import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.annotation.RequiresApi
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.ai.assistance.operit.terminal.view.TerminalScreen
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import android.util.Log


class MainActivity : ComponentActivity() {
    @RequiresApi(Build.VERSION_CODES.O)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 1. 设置应用内容以在系统栏后面绘制，实现边到边效果
        WindowCompat.setDecorFitsSystemWindows(window, false)

        // 2. 隐藏状态栏和导航栏，并设置滑动行为
        val windowInsetsController = WindowCompat.getInsetsController(window, window.decorView)
        windowInsetsController.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        windowInsetsController.hide(WindowInsetsCompat.Type.systemBars())

        // 3. 创建TerminalManager实例
        val terminalManager = TerminalManager.getInstance(this)
        
        // 4. 创建初始会话
        lifecycleScope.launch {
            try {
                terminalManager.createNewSession("Default Session")
                Log.d("MainActivity", "Initial session created successfully")
            } catch (e: Exception) {
                Log.e("MainActivity", "Failed to create initial session", e)
            }
        }

        setContent {
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = Color.Black
            ) {
                val context = LocalContext.current
                val terminalManagerInCompose = remember { TerminalManager.getInstance(context) }
                val terminalEnv = rememberTerminalEnv(terminalManagerInCompose)

                TerminalScreen(
                    env = terminalEnv
                )
            }
        }
    }
}