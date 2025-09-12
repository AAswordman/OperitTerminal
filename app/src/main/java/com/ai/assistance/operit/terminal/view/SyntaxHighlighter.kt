package com.ai.assistance.operit.terminal.view

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.withStyle
import java.util.regex.Pattern

object SyntaxColors {
    val keyword = Color(0xFF569CD6)       // 淡蓝色，用于if, for等关键字
    val command = Color(0xFF4EC9B0)       // 蓝绿色，用于ls, cd等常见命令
    val string = Color(0xFFD69D85)        // 橙色，用于字符串
    val comment = Color(0xFF6A9955)       // 绿色，用于注释
    val operator = Color(0xFFB5CEA8)      // 灰绿色，用于管道符、重定向等
    val warning = Color(0xFFFBC02D)       // 黄色，用于警告
    val error = Color(0xFFE53935)         // 红色，用于错误
    val default = Color.White             // 默认颜色
    val commandDefault = Color(0xFF66BB6A) // 命令输入的默认颜色 (淡绿色)
}

private val commandPattern = Pattern.compile(
    "(?<KEYWORD>\\b(if|then|else|fi|for|in|do|done|while|case|esac|function|export|unset|alias)\\b)|" +
    "(?<COMMAND>\\b(ls|cd|echo|pwd|mkdir|rm|cp|mv|cat|grep|find|sudo|apt|pkg|git|docker|chmod|chown)\\b)|" +
    "(?<STRING>\"(?:\\\\.|[^\"])*\"|'(?:\\\\.|[^'])*')|" +
    "(?<COMMENT>#.*)|" +
    "(?<OPERATOR>[|&><=;!\\-/$])"
)

private val outputPattern = Pattern.compile(
    "(?<WARNING>\\b(warning|Warning|WARNING)\\b.*)|" +
    "(?<ERROR>\\b(error|Error|ERROR|failed|Failed|FAILED)\\b.*)"
)

private fun highlightWithPattern(code: String, pattern: Pattern, defaultColor: Color): AnnotatedString {
    return buildAnnotatedString {
        val matcher = pattern.matcher(code)
        var lastEnd = 0

        while (matcher.find()) {
            val start = matcher.start()
            val end = matcher.end()

            if (start > lastEnd) {
                withStyle(style = SpanStyle(color = defaultColor)) {
                    append(code.substring(lastEnd, start))
                }
            }

            val color = when {
                runCatching { matcher.group("KEYWORD") }.getOrNull() != null -> SyntaxColors.keyword
                runCatching { matcher.group("COMMAND") }.getOrNull() != null -> SyntaxColors.command
                runCatching { matcher.group("STRING") }.getOrNull() != null -> SyntaxColors.string
                runCatching { matcher.group("COMMENT") }.getOrNull() != null -> SyntaxColors.comment
                runCatching { matcher.group("OPERATOR") }.getOrNull() != null -> SyntaxColors.operator
                runCatching { matcher.group("WARNING") }.getOrNull() != null -> SyntaxColors.warning
                runCatching { matcher.group("ERROR") }.getOrNull() != null -> SyntaxColors.error
                else -> defaultColor
            }

            withStyle(style = SpanStyle(color = color)) {
                append(code.substring(start, end))
            }

            lastEnd = end
        }

        if (lastEnd < code.length) {
            withStyle(style = SpanStyle(color = defaultColor)) {
                append(code.substring(lastEnd))
            }
        }
    }
}


fun highlight(code: String, isCommand: Boolean = false): AnnotatedString {
    return if (isCommand) {
        highlightWithPattern(code, commandPattern, SyntaxColors.commandDefault)
    } else {
        // 逐行处理输出
        val lines = code.split('\n')
        buildAnnotatedString {
            lines.forEachIndexed { index, line ->
                append(highlightWithPattern(line, outputPattern, SyntaxColors.default))
                if (index < lines.size - 1) {
                    append('\n')
                }
            }
        }
    }
}


class SyntaxHighlightingVisualTransformation : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        return TransformedText(
            text = highlight(text.text, isCommand = true),
            offsetMapping = OffsetMapping.Identity
        )
    }
} 