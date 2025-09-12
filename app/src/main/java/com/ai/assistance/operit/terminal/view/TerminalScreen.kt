package com.ai.assistance.operit.terminal.view

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
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
    val commandHistory by terminalViewModel.commandHistory.collectAsState()
    val currentDirectory by terminalViewModel.currentDirectory.collectAsState()
    var command by remember { mutableStateOf("") }
    val listState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()
    
    // 缩放状态
    var scaleFactor by remember { mutableStateOf(1f) }
    
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

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
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