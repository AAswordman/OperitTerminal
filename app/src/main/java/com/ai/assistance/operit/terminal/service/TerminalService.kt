package com.ai.assistance.operit.terminal.service

import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.RemoteCallbackList
import android.util.Log
import androidx.annotation.RequiresApi
import com.ai.assistance.operit.terminal.TerminalViewModel
import com.ai.assistance.operit.terminal.aidl.ITerminalCallback
import com.ai.assistance.operit.terminal.aidl.ITerminalService
import com.ai.assistance.operit.terminal.data.TerminalState
import com.ai.assistance.operit.terminal.data.TerminalSessionData
import com.ai.assistance.operit.terminal.data.CommandHistoryItem
import com.ai.assistance.operit.terminal.aidl.CommandHistoryItemParcelable
import com.ai.assistance.operit.terminal.aidl.TerminalSessionDataParcelable
import com.ai.assistance.operit.terminal.aidl.TerminalStateParcelable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach

@RequiresApi(Build.VERSION_CODES.O)
class TerminalService : Service() {

    private val job = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.Main + job)

    private lateinit var viewModel: TerminalViewModel
    private val callbacks = RemoteCallbackList<ITerminalCallback>()

    private val binder = object : ITerminalService.Stub() {
        override fun createSession(): String {
            val newSession = viewModel.createNewSession()
            return newSession.id
        }

        override fun switchToSession(sessionId: String) {
            viewModel.switchToSession(sessionId)
        }

        override fun closeSession(sessionId: String) {
            viewModel.closeSession(sessionId)
        }

        override fun sendCommand(command: String) {
            viewModel.sendCommand(command)
        }

        override fun sendInterruptSignal() {
            viewModel.sendInterruptSignal()
        }

        override fun registerCallback(callback: ITerminalCallback?) {
            callback?.let { callbacks.register(it) }
        }

        override fun unregisterCallback(callback: ITerminalCallback?) {
            callback?.let { callbacks.unregister(it) }
        }

        override fun requestStateUpdate() {
            val currentState = viewModel.terminalState.value
            val parcelableState = mapTerminalStateToParcelable(currentState)
            val n = callbacks.beginBroadcast()
            for (i in 0 until n) {
                try {
                    callbacks.getBroadcastItem(i).onStateUpdated(parcelableState)
                } catch (e: Exception) {
                    Log.e("TerminalService", "Error sending state update", e)
                }
            }
            callbacks.finishBroadcast()
        }
    }

    override fun onCreate() {
        super.onCreate()
        viewModel = TerminalViewModel(application)
        viewModel.terminalState
            .onEach { state ->
                val parcelableState = mapTerminalStateToParcelable(state)
                val n = callbacks.beginBroadcast()
                for (i in 0 until n) {
                    try {
                        callbacks.getBroadcastItem(i).onStateUpdated(parcelableState)
                    } catch (e: Exception) {
                       Log.e("TerminalService", "Error broadcasting state", e)
                    }
                }
                callbacks.finishBroadcast()
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
    
    // Mapper functions
    private fun mapTerminalStateToParcelable(state: TerminalState): TerminalStateParcelable {
        return TerminalStateParcelable(
            sessions = state.sessions.map { mapSessionToParcelable(it) },
            currentSessionId = state.currentSessionId,
            isLoading = state.isLoading,
            error = state.error
        )
    }

    private fun mapSessionToParcelable(session: TerminalSessionData): TerminalSessionDataParcelable {
        return TerminalSessionDataParcelable(
            id = session.id,
            title = session.title,
            currentDirectory = session.currentDirectory,
            commandHistory = session.commandHistory.map { mapCommandHistoryItemToParcelable(it) },
            isInteractiveMode = session.isInteractiveMode,
            interactivePrompt = session.interactivePrompt,
            isInitializing = session.isInitializing
        )
    }

    private fun mapCommandHistoryItemToParcelable(item: CommandHistoryItem): CommandHistoryItemParcelable {
        return CommandHistoryItemParcelable(
            id = item.id,
            prompt = item.prompt,
            command = item.command,
            output = item.output,
            isExecuting = item.isExecuting
        )
    }
} 