package com.ai.assistance.operit.terminal.view

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ai.assistance.operit.terminal.TerminalViewModel
import kotlinx.coroutines.launch
import kotlin.math.max
import kotlin.math.min
import kotlin.math.abs

@Composable
fun TerminalScreen(terminalViewModel: TerminalViewModel = viewModel()) {
    // 会话相关状态
    val sessions by terminalViewModel.sessions.collectAsState()
    val currentSessionId by terminalViewModel.currentSessionId.collectAsState()
    
    // 当前会话的状态
    val commandHistory by terminalViewModel.commandHistory.collectAsState()
    val currentDirectory by terminalViewModel.currentDirectory.collectAsState()
    var command by remember { mutableStateOf("") }
    val listState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()
    
    // 缩放状态
    var scaleFactor by remember { mutableStateOf(1f) }
    
    // 删除确认弹窗状态
    var showDeleteConfirmDialog by remember { mutableStateOf(false) }
    var sessionToDelete by remember { mutableStateOf<String?>(null) }
    
    // 计算基于缩放因子的字体大小和间距
    val baseFontSize = 14.sp
    val fontSize = with(LocalDensity.current) { 
        (baseFontSize.toPx() * scaleFactor).toSp()
    }
    val baseLineHeight = 1.2f
    val lineHeight = baseLineHeight * scaleFactor
    val basePadding = 8.dp
    val padding = basePadding * scaleFactor

    // 自动滚动到底部
    LaunchedEffect(commandHistory.size, commandHistory.lastOrNull()?.output) {
        if (commandHistory.isNotEmpty()) {
            coroutineScope.launch {
                listState.animateScrollToItem(index = commandHistory.size)
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        // 会话标签页
        SessionTabBar(
            sessions = sessions,
            currentSessionId = currentSessionId,
            onSessionClick = { sessionId ->
                terminalViewModel.switchToSession(sessionId)
            },
            onNewSession = {
                terminalViewModel.createNewSession()
            },
            onCloseSession = { sessionId ->
                sessionToDelete = sessionId
                showDeleteConfirmDialog = true
            }
        )
        
        // 终端内容
        Box(
            modifier = Modifier
                .weight(1f)
                .pointerInput(Unit) {
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false)
                        do {
                            val event = awaitPointerEvent(pass = PointerEventPass.Initial)
                            
                            // 只在有多个手指时处理缩放
                            if (event.changes.size >= 2) {
                                val zoom = event.calculateZoom()
                                if (abs(zoom - 1f) > 0.01f) {
                                    scaleFactor = max(0.5f, min(3f, scaleFactor * zoom))
                                    // 消费事件以防止传递给LazyColumn
                                    event.changes.forEach { it.consume() }
                                }
                            }
                        } while (event.changes.any { it.pressed })
                    }
                }
        ) {
            Column(
                modifier = Modifier.fillMaxSize()
            ) {
                // 历史输出 - 占满全屏
                LazyColumn(
                    state = listState,
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(padding)
                ) {
                    items(commandHistory) { historyItem ->
                        // 显示命令
                        Text(
                            text = "${historyItem.prompt}${historyItem.command}",
                            color = Color.Green,
                            fontFamily = FontFamily.Monospace,
                            fontSize = fontSize,
                            lineHeight = fontSize * lineHeight,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = padding * 0.25f)
                        )
                        // 显示输出
                        if (historyItem.output.isNotEmpty()) {
                            Text(
                                text = historyItem.output,
                                color = Color.White,
                                fontFamily = FontFamily.Monospace,
                                fontSize = fontSize,
                                lineHeight = fontSize * lineHeight,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(bottom = padding * 0.5f)
                            )
                        }

                        // Show a progress indicator for executing commands
                        if (historyItem.isExecuting) {
                            Row(
                                modifier = Modifier.padding(top = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    strokeWidth = 2.dp
                                )
                                Text(
                                    text = "Executing...",
                                    color = Color.Gray,
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 12.sp,
                                    modifier = Modifier.padding(start = 8.dp)
                                )
                            }
                        }
                    }
                }
                
                // 当前输入行
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(padding)
                ) {
                    Text(
                        text = currentDirectory.ifEmpty { "$ " },
                        color = Color.Green,
                        fontFamily = FontFamily.Monospace,
                        fontSize = fontSize
                    )
                    BasicTextField(
                        value = command,
                        onValueChange = { command = it },
                        modifier = Modifier.weight(1f),
                        textStyle = TextStyle(
                            color = Color.Green,
                            fontFamily = FontFamily.Monospace,
                            fontSize = fontSize
                        ),
                        cursorBrush = SolidColor(Color.Green),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                        keyboardActions = KeyboardActions(onSend = {
                            if (command.isNotBlank()) {
                                terminalViewModel.sendCommand(command)
                                command = ""
                            }
                        })
                    )
                }
            }
        }
    }
    
    // 删除确认弹窗
    if (showDeleteConfirmDialog && sessionToDelete != null) {
        val sessionTitle = sessions.find { it.id == sessionToDelete }?.title ?: "未知会话"
        
        AlertDialog(
            onDismissRequest = { 
                showDeleteConfirmDialog = false
                sessionToDelete = null
            },
            title = {
                Text(
                    text = "确认删除会话",
                    color = Color.White
                )
            },
            text = {
                Text(
                    text = "确定要删除会话 \"$sessionTitle\" 吗？\n\n此操作不可撤销，会话中的所有数据将永久丢失。",
                    color = Color.Gray
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        sessionToDelete?.let { sessionId ->
                            terminalViewModel.closeSession(sessionId)
                        }
                        showDeleteConfirmDialog = false
                        sessionToDelete = null
                    }
                ) {
                    Text(
                        text = "删除",
                        color = Color.Red
                    )
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showDeleteConfirmDialog = false
                        sessionToDelete = null
                    }
                ) {
                    Text(
                        text = "取消",
                        color = Color.White
                    )
                }
            },
            containerColor = Color(0xFF2D2D2D),
            titleContentColor = Color.White,
            textContentColor = Color.Gray
        )
    }
}

@Composable
private fun SessionTabBar(
    sessions: List<com.ai.assistance.operit.terminal.TerminalSessionData>,
    currentSessionId: String?,
    onSessionClick: (String) -> Unit,
    onNewSession: () -> Unit,
    onCloseSession: (String) -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = Color(0xFF2D2D2D),
        shadowElevation = 4.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            // 会话标签页列表
            LazyRow(
                modifier = Modifier.weight(1f),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                items(sessions) { session ->
                    SessionTab(
                        session = session,
                        isActive = session.id == currentSessionId,
                        onClick = { onSessionClick(session.id) },
                        onClose = if (sessions.size > 1) {
                            { onCloseSession(session.id) }
                        } else null
                    )
                }
            }
            
            // 新建会话按钮
            IconButton(
                onClick = onNewSession,
                modifier = Modifier.size(32.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = "新建会话",
                    tint = Color.White,
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    }
}

@Composable
private fun SessionTab(
    session: com.ai.assistance.operit.terminal.TerminalSessionData,
    isActive: Boolean,
    onClick: () -> Unit,
    onClose: (() -> Unit)?
) {
    Surface(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable { onClick() },
        color = if (isActive) Color(0xFF4A4A4A) else Color(0xFF3A3A3A),
        shape = RoundedCornerShape(8.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = session.title,
                color = if (isActive) Color.White else Color.Gray,
                fontSize = 12.sp,
                fontWeight = if (isActive) FontWeight.Bold else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            
            // 关闭按钮（只有多个会话时才显示）
            onClose?.let { closeAction ->
                IconButton(
                    onClick = closeAction,
                    modifier = Modifier.size(16.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "关闭会话",
                        tint = Color.Gray,
                        modifier = Modifier.size(12.dp)
                    )
                }
            }
        }
    }
} 