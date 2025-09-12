package com.ai.assistance.operit.terminal.view

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ai.assistance.operit.terminal.TerminalViewModel
import kotlinx.coroutines.launch

@Composable
fun TerminalScreen(terminalViewModel: TerminalViewModel = viewModel()) {
    val commandHistory by terminalViewModel.commandHistory.collectAsState()
    val currentDirectory by terminalViewModel.currentDirectory.collectAsState()
    var command by remember { mutableStateOf("") }
    val listState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()

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
    ) {
        Column(
            modifier = Modifier.fillMaxSize()
        ) {
            // 历史输出
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(8.dp)
            ) {
                items(commandHistory) { historyItem ->
                    // 显示命令
                    Text(
                        text = "${historyItem.prompt}${historyItem.command}",
                        color = Color.Green,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 14.sp,
                        modifier = Modifier.fillMaxWidth()
                    )
                    // 显示输出
                    if (historyItem.output.isNotEmpty()) {
                        Text(
                            text = historyItem.output,
                            color = Color.White,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 14.sp,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }
            
            // 当前输入行
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(8.dp)
            ) {
                Text(
                    text = currentDirectory.ifEmpty { "$ " },
                    color = Color.Green,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 14.sp
                )
                BasicTextField(
                    value = command,
                    onValueChange = { command = it },
                    modifier = Modifier.weight(1f),
                    textStyle = TextStyle(
                        color = Color.Green,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 14.sp
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