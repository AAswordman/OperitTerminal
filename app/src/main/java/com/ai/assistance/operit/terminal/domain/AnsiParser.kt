package com.ai.assistance.operit.terminal.domain

import android.graphics.Color
import android.util.Log
import java.util.regex.Pattern

data class TerminalChar(
    val char: Char = ' ',
    val fgColor: Int = Color.WHITE,
    val bgColor: Int = Color.BLACK,
    val isBold: Boolean = false
)

class AnsiParser(
    private var screenWidth: Int = 80,
    private var screenHeight: Int = 24
) {
    private val screenBuffer: Array<Array<TerminalChar>> = Array(screenHeight) { Array(screenWidth) { TerminalChar() } }
    private var cursorX: Int = 0
    private var cursorY: Int = 0
    
    private var currentFgColor: Int = Color.WHITE
    private var currentBgColor: Int = Color.BLACK
    private var isBold: Boolean = false

    // Regex to capture CSI (Control Sequence Introducer) sequences
    private val csiPattern = Pattern.compile("\u001B\\[([?0-9;]*)([A-Za-z])")

    fun parse(text: String): String {
        var i = 0
        while (i < text.length) {
            val char = text[i]
            if (char == '\u001B') { // ESC
                val matcher = csiPattern.matcher(text)
                if (matcher.find(i)) {
                    val params = matcher.group(1)
                    val command = matcher.group(2)
                    handleCsiSequence(params, command)
                    i = matcher.end() - 1
                }
            } else {
                // Handle normal character
                if (cursorX < screenWidth && cursorY < screenHeight) {
                     screenBuffer[cursorY][cursorX] = TerminalChar(char, currentFgColor, currentBgColor, isBold)
                }
                cursorX++
                if (cursorX >= screenWidth) {
                    cursorX = 0
                    cursorY++
                    if (cursorY >= screenHeight) {
                        cursorY = screenHeight - 1
                        //scrollUp()
                    }
                }
            }
            i++
        }
        return renderScreenToString()
    }

    private fun handleCsiSequence(paramsStr: String?, command: String?) {
        val params = paramsStr?.split(';')?.mapNotNull { it.toIntOrNull() } ?: emptyList()

        when (command) {
            "H", "f" -> { // Cursor Position
                val row = if (params.isNotEmpty()) params[0] - 1 else 0
                val col = if (params.size > 1) params[1] - 1 else 0
                cursorY = row.coerceIn(0, screenHeight - 1)
                cursorX = col.coerceIn(0, screenWidth - 1)
            }
            "J" -> { // Erase in Display
                when (params.firstOrNull() ?: 0) {
                    0 -> eraseFromCursorToEnd()
                    1 -> eraseFromStartToCursor()
                    2, 3 -> clearScreen()
                }
            }
            "K" -> { // Erase in Line
                 when (params.firstOrNull() ?: 0) {
                    0 -> for (i in cursorX until screenWidth) screenBuffer[cursorY][i] = TerminalChar(bgColor = currentBgColor)
                    1 -> for (i in 0..cursorX) screenBuffer[cursorY][i] = TerminalChar(bgColor = currentBgColor)
                    2 -> for (i in 0 until screenWidth) screenBuffer[cursorY][i] = TerminalChar(bgColor = currentBgColor)
                }
            }
            "m" -> { // Select Graphic Rendition (SGR)
                // This is a complex one, for now we just reset
                if (params.isEmpty() || params[0] == 0) {
                    currentFgColor = Color.WHITE
                    currentBgColor = Color.BLACK
                    isBold = false
                }
                // TODO: Handle actual colors and attributes
            }
            "h", "l" -> { // Set/Reset Mode (DECSET/DECRST)
                // For now, we can just log this.
                // Important ones are ?25 (cursor visibility) and ?1049 (alternate screen buffer)
                Log.d("AnsiParser", "Set/Reset Mode: params=$paramsStr command=$command")
            }
            // Add more command handlers as needed
            else -> {
                Log.w("AnsiParser", "Unsupported CSI command: '$command' with params: $params")
            }
        }
    }
    
    private fun clearScreen() {
        for (y in 0 until screenHeight) {
            for (x in 0 until screenWidth) {
                screenBuffer[y][x] = TerminalChar(bgColor = currentBgColor)
            }
        }
        cursorX = 0
        cursorY = 0
    }

    private fun eraseFromCursorToEnd() {
        for (x in cursorX until screenWidth) {
            screenBuffer[cursorY][x] = TerminalChar(bgColor = currentBgColor)
        }
        for (y in cursorY + 1 until screenHeight) {
            for (x in 0 until screenWidth) {
                screenBuffer[y][x] = TerminalChar(bgColor = currentBgColor)
            }
        }
    }

    private fun eraseFromStartToCursor() {
        for (y in 0 until cursorY) {
            for (x in 0 until screenWidth) {
                screenBuffer[y][x] = TerminalChar(bgColor = currentBgColor)
            }
        }
        for (x in 0..cursorX) {
            screenBuffer[cursorY][x] = TerminalChar(bgColor = currentBgColor)
        }
    }

    fun renderScreenToString(): String {
        val builder = StringBuilder()
        for (y in 0 until screenHeight) {
            for (x in 0 until screenWidth) {
                builder.append(screenBuffer[y][x].char)
            }
            if (y < screenHeight - 1) {
                builder.append('\n')
            }
        }
        return builder.toString()
    }
    
    fun resize(newWidth: Int, newHeight: Int) {
        // A more sophisticated implementation would copy over the old buffer content
        screenWidth = newWidth
        screenHeight = newHeight
        // Re-initialize buffer
        // screenBuffer = Array(screenHeight) { Array(screenWidth) { TerminalChar() } }
        clearScreen()
    }
}
