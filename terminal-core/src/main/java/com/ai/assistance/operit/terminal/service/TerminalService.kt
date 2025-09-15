package com.ai.assistance.operit.terminal.service

import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.RemoteCallbackList
import android.util.Log
import androidx.annotation.RequiresApi
import com.ai.assistance.operit.terminal.ITerminalCallback
import com.ai.assistance.operit.terminal.ITerminalService
import com.ai.assistance.operit.terminal.CommandExecutionEvent
import com.ai.assistance.operit.terminal.SessionDirectoryEvent
import com.ai.assistance.operit.terminal.TerminalManager
import com.ai.assistance.operit.terminal.data.SessionInitState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

class TerminalService : Service() {

    private val job = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.Main + job)

    private lateinit var terminalManager: TerminalManager
    private val callbacks = RemoteCallbackList<ITerminalCallback>()

    private val binder = object : ITerminalService.Stub() {
        override fun createSession(): String {
            val newSession = terminalManager.createNewSession()
            
            // 阻塞等待会话初始化完成
            runBlocking {
                withTimeoutOrNull(30000) { // 30秒超时
                    terminalManager.terminalState.first { state ->
                        val session = state.sessions.find { it.id == newSession.id }
                        session?.initState == SessionInitState.READY
                    }
                } ?: run {
                    Log.e("TerminalService", "Session initialization timeout for session: ${newSession.id}")
                }
            }
            
            return newSession.id
        }

        override fun switchToSession(sessionId: String) {
            terminalManager.switchToSession(sessionId)
        }

        override fun closeSession(sessionId: String) {
            terminalManager.closeSession(sessionId)
        }

        override fun sendCommand(command: String): String {
            return terminalManager.sendCommand(command)
        }

        override fun sendInterruptSignal() {
            terminalManager.sendInterruptSignal()
        }

        override fun registerCallback(callback: ITerminalCallback?) {
            callback?.let { callbacks.register(it) }
        }

        override fun unregisterCallback(callback: ITerminalCallback?) {
            callback?.let { callbacks.unregister(it) }
        }

        override fun requestStateUpdate() {
            // 新架构下不需要请求完整状态更新
        }
    }

    override fun onCreate() {
        super.onCreate()
        terminalManager = TerminalManager.getInstance(applicationContext)
        
        // 监听命令执行事件
        terminalManager.commandExecutionEvents
            .onEach { event ->
                Log.d("TerminalService", "Received command execution event: $event")
                broadcastCommandExecutionEvent(event)
            }
            .launchIn(scope)
            
        // 监听目录变化事件
        terminalManager.directoryChangeEvents
            .onEach { event ->
                Log.d("TerminalService", "Received directory change event: $event")
                broadcastDirectoryChangeEvent(event)
            }
            .launchIn(scope)
    }

    override fun onBind(intent: Intent?): IBinder {
        return binder
    }

    override fun onDestroy() {
        super.onDestroy()
        job.cancel()
        callbacks.kill()
    }
    
    // 事件广播方法
    private fun broadcastCommandExecutionEvent(event: CommandExecutionEvent) {
        val n = callbacks.beginBroadcast()
        Log.d("TerminalService", "Broadcasting command execution event to $n callbacks: $event")
        for (i in 0 until n) {
            try {
                callbacks.getBroadcastItem(i).onCommandExecutionUpdate(event)
                Log.d("TerminalService", "Successfully sent command execution event to callback $i")
            } catch (e: Exception) {
                Log.e("TerminalService", "Error broadcasting command execution event to callback $i", e)
            }
        }
        callbacks.finishBroadcast()
        Log.d("TerminalService", "Finished broadcasting command execution event")
    }
    
    private fun broadcastDirectoryChangeEvent(event: SessionDirectoryEvent) {
        val n = callbacks.beginBroadcast()
        for (i in 0 until n) {
            try {
                callbacks.getBroadcastItem(i).onSessionDirectoryChanged(event)
            } catch (e: Exception) {
                Log.e("TerminalService", "Error broadcasting directory change event", e)
            }
        }
        callbacks.finishBroadcast()
    }
} 