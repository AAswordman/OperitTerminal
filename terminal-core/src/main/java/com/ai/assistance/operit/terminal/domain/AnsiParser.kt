package com.ai.assistance.operit.terminal.domain

import android.graphics.Color
import android.util.Log
import java.util.LinkedList
import java.util.Queue
import java.util.regex.Pattern

data class TerminalChar(
    val char: Char = ' ',
    val fgColor: Int = Color.WHITE,
    val bgColor: Int = Color.BLACK,
    val isBold: Boolean = false,
    val isDim: Boolean = false,
    val isItalic: Boolean = false,
    val isUnderline: Boolean = false,
    val isBlinking: Boolean = false,
    val isInverse: Boolean = false,
    val isHidden: Boolean = false,
    val isStrikethrough: Boolean = false
)

class AnsiParser(
    private var screenWidth: Int = 80,
    private var screenHeight: Int = 24
) {
    private var screenBuffer: Array<Array<TerminalChar>> = Array(screenHeight) { Array(screenWidth) { TerminalChar() } }
    private var cursorX: Int = 0
    private var cursorY: Int = 0
    
    private var currentFgColor: Int = Color.WHITE
    private var currentBgColor: Int = Color.BLACK
    private var isBold: Boolean = false
    private var isDim: Boolean = false
    private var isItalic: Boolean = false
    private var isUnderline: Boolean = false
    private var isBlinking: Boolean = false
    private var isInverse: Boolean = false
    private var isHidden: Boolean = false
    private var isStrikethrough: Boolean = false

    // Saved state
    private var savedCursorX: Int = 0
    private var savedCursorY: Int = 0
    private var savedFgColor: Int = Color.WHITE
    private var savedBgColor: Int = Color.BLACK
    private var savedIsBold: Boolean = false
    private var savedIsDim: Boolean = false
    private var savedIsItalic: Boolean = false
    private var savedIsUnderline: Boolean = false
    private var savedIsBlinking: Boolean = false
    private var savedIsInverse: Boolean = false
    private var savedIsHidden: Boolean = false
    private var savedIsStrikethrough: Boolean = false


    // Regex to capture CSI (Control Sequence Introducer) sequences
    private val csiPattern = Pattern.compile("\u001B\\[([?0-9;]*)([A-Za-z])")
    private val oscPattern = Pattern.compile("\u001B\\]([0-9]+);([^\u0007]*)\u0007")

    fun parse(text: String) {
        var i = 0
        while (i < text.length) {
            val char = text[i]
            when (char) {
                '\u001B' -> { // ESC
                    val csiMatcher = csiPattern.matcher(text)
                    val oscMatcher = oscPattern.matcher(text)

                    if (i + 1 < text.length && text[i+1] == ']') {
                        if (oscMatcher.find(i)) {
                            // handleOscSequence(oscMatcher.group(1), oscMatcher.group(2))
                            i = oscMatcher.end() - 1
                        }
                    } else if (csiMatcher.find(i)) {
                        val params = csiMatcher.group(1)
                        val command = csiMatcher.group(2)
                        handleCsiSequence(params, command)
                        i = csiMatcher.end() - 1
                    } else if (i + 1 < text.length) {
                         when(text[i+1]) {
                            'c' -> resetTerminal() // RIS - Reset to Initial State
                            '7' -> saveCursorAndAttrs() // DECSC - Save Cursor Position and Attributes
                            '8' -> restoreCursorAndAttrs() // DECRC - Restore Cursor Position and Attributes
                            else -> Log.w("AnsiParser", "Unsupported single char escape sequence: ${text[i+1]}")
                        }
                        i++ // Consume the character after ESC
                    }
                }
                '\n' -> { // Newline
                    cursorY++
                    if (cursorY >= screenHeight) {
                        cursorY = screenHeight - 1
                        scrollUp()
                    }
                }
                '\r' -> { // Carriage return
                    cursorX = 0
                }
                '\b' -> { // Backspace
                    cursorX = (cursorX - 1).coerceAtLeast(0)
                }
                '\t' -> { // Tab
                    val nextTabStop = (cursorX / 8 + 1) * 8
                    cursorX = nextTabStop.coerceAtMost(screenWidth -1)
                }
                else -> {
                    // Handle normal character
                    if (cursorX >= screenWidth) {
                        cursorX = 0
                        cursorY++
                        if (cursorY >= screenHeight) {
                            cursorY = screenHeight - 1
                            scrollUp()
                        }
                    }

                    if (cursorY < screenHeight) {
                         screenBuffer[cursorY][cursorX] = TerminalChar(
                             char, currentFgColor, currentBgColor, isBold,
                             isDim, isItalic, isUnderline, isBlinking,
                             isInverse, isHidden, isStrikethrough
                         )
                    }
                    cursorX++
                }
            }
            i++
        }
    }

    private fun handleCsiSequence(paramsStr: String?, command: String?) {
        val params = paramsStr?.split(';')?.mapNotNull { it.toIntOrNull() } ?: emptyList()
        val p1 = params.firstOrNull() ?: 0

        when (command) {
            "H", "f" -> { // Cursor Position
                val row = if (params.isNotEmpty()) params[0] - 1 else 0
                val col = if (params.size > 1) params[1] - 1 else 0
                cursorY = row.coerceIn(0, screenHeight - 1)
                cursorX = col.coerceIn(0, screenWidth - 1)
            }
            "A" -> cursorY = (cursorY - p1.coerceAtLeast(1)).coerceAtLeast(0) // CUU - Cursor Up
            "B" -> cursorY = (cursorY + p1.coerceAtLeast(1)).coerceAtMost(screenHeight - 1) // CUD - Cursor Down
            "C" -> cursorX = (cursorX + p1.coerceAtLeast(1)).coerceAtMost(screenWidth - 1) // CUF - Cursor Forward
            "D" -> cursorX = (cursorX - p1.coerceAtLeast(1)).coerceAtLeast(0) // CUB - Cursor Back
            "E" -> { // CNL - Cursor Next Line
                cursorY = (cursorY + p1.coerceAtLeast(1)).coerceAtMost(screenHeight - 1)
                cursorX = 0
            }
            "F" -> { // CPL - Cursor Previous Line
                cursorY = (cursorY - p1.coerceAtLeast(1)).coerceAtLeast(0)
                cursorX = 0
            }
            "G" -> cursorX = (p1 - 1).coerceIn(0, screenWidth - 1) // CHA - Cursor Character Absolute
            "J" -> { // Erase in Display
                when (p1) {
                    0 -> eraseFromCursorToEnd()
                    1 -> eraseFromStartToCursor()
                    2, 3 -> clearScreen()
                }
            }
            "K" -> { // Erase in Line
                 when (p1) {
                    0 -> for (i in cursorX until screenWidth) screenBuffer[cursorY][i] = TerminalChar(bgColor = currentBgColor)
                    1 -> for (i in 0..cursorX) screenBuffer[cursorY][i] = TerminalChar(bgColor = currentBgColor)
                    2 -> for (i in 0 until screenWidth) screenBuffer[cursorY][i] = TerminalChar(bgColor = currentBgColor)
                }
            }
            "S" -> scrollUp(p1.coerceAtLeast(1)) // SU - Scroll Up
            "T" -> scrollDown(p1.coerceAtLeast(1)) // SD - Scroll Down
            "m" -> { // Select Graphic Rendition (SGR)
                handleSgr(params)
            }
            "h", "l" -> { // Set/Reset Mode (DECSET/DECRST)
                // For now, we can just log this.
                // Important ones are ?25 (cursor visibility) and ?1049 (alternate screen buffer)
                if (paramsStr?.startsWith("?") == true) {
                    Log.d("AnsiParser", "DEC Private Mode: params=$paramsStr command=$command")
                } else {
                    Log.d("AnsiParser", "Set/Reset Mode: params=$paramsStr command=$command")
                }
            }
            "s" -> saveCursorAndAttrs() // Save cursor position (ANSI.SYS)
            "u" -> restoreCursorAndAttrs() // Restore cursor position (ANSI.SYS)
            // Add more command handlers as needed
            else -> {
                Log.w("AnsiParser", "Unsupported CSI command: '$command' with params: $params")
            }
        }
    }

    private fun handleSgr(params: List<Int>) {
        if (params.isEmpty()) {
            resetSgr()
            return
        }

        val queue: Queue<Int> = LinkedList(params)
        while (queue.isNotEmpty()) {
            when (val p = queue.poll() ?: 0) {
                0 -> resetSgr()
                1 -> isBold = true
                2 -> isDim = true
                3 -> isItalic = true
                4 -> isUnderline = true
                5 -> isBlinking = true
                7 -> isInverse = true
                8 -> isHidden = true
                9 -> isStrikethrough = true
                21, 22 -> { isBold = false; isDim = false }
                23 -> isItalic = false
                24 -> isUnderline = false
                25 -> isBlinking = false
                27 -> isInverse = false
                28 -> isHidden = false
                29 -> isStrikethrough = false
                in 30..37 -> currentFgColor = ansiColor(p - 30) // Set foreground color
                38 -> currentFgColor = parseExtendedColor(queue)
                39 -> currentFgColor = Color.WHITE // Default foreground color
                in 40..47 -> currentBgColor = ansiColor(p - 40) // Set background color
                48 -> currentBgColor = parseExtendedColor(queue)
                49 -> currentBgColor = Color.BLACK // Default background color
                in 90..97 -> currentFgColor = ansiBrightColor(p - 90) // Set bright foreground color
                in 100..107 -> currentBgColor = ansiBrightColor(p - 100) // Set bright background color
                else -> Log.w("AnsiParser", "Unsupported SGR parameter: $p")
            }
        }
    }

    private fun parseExtendedColor(queue: Queue<Int>): Int {
        if (queue.isEmpty()) return Color.WHITE // Invalid sequence
        val type = queue.poll()
        return when (type) {
            5 -> { // 256-color mode
                if (queue.isEmpty()) Color.WHITE
                else {
                    val colorIndex = queue.poll()
                    if (colorIndex in 0..255) xterm256Color(colorIndex) else Color.WHITE
                }
            }
            2 -> { // 24-bit color mode
                if (queue.size < 3) Color.WHITE
                else {
                    val r = queue.poll()
                    val g = queue.poll()
                    val b = queue.poll()
                    Color.rgb(r, g, b)
                }
            }
            else -> Color.WHITE
        }
    }
    
    private fun resetSgr() {
        currentFgColor = Color.WHITE
        currentBgColor = Color.BLACK
        isBold = false
        isDim = false
        isItalic = false
        isUnderline = false
        isBlinking = false
        isInverse = false
        isHidden = false
        isStrikethrough = false
    }
    
    private fun resetTerminal() {
        clearScreen()
        resetSgr()
        cursorX = 0
        cursorY = 0
    }
    
    private fun saveCursorAndAttrs() {
        savedCursorX = cursorX
        savedCursorY = cursorY
        savedFgColor = currentFgColor
        savedBgColor = currentBgColor
        savedIsBold = isBold
        savedIsDim = isDim
        savedIsItalic = isItalic
        savedIsUnderline = isUnderline
        savedIsBlinking = isBlinking
        savedIsInverse = isInverse
        savedIsHidden = isHidden
        savedIsStrikethrough = isStrikethrough
    }

    private fun restoreCursorAndAttrs() {
        cursorX = savedCursorX
        cursorY = savedCursorY
        currentFgColor = savedFgColor
        currentBgColor = savedBgColor
        isBold = savedIsBold
        isDim = savedIsDim
        isItalic = savedIsItalic
        isUnderline = savedIsUnderline
        isBlinking = savedIsBlinking
        isInverse = savedIsInverse
        isHidden = savedIsHidden
        isStrikethrough = savedIsStrikethrough
    }
    
    private fun scrollUp(lines: Int = 1) {
        for (i in 0 until lines) {
            // Shift content up
            for (y in 0 until screenHeight - 1) {
                screenBuffer[y] = screenBuffer[y + 1]
            }
            // Clear the last line
            screenBuffer[screenHeight - 1] = Array(screenWidth) { TerminalChar(bgColor = currentBgColor) }
        }
    }

    private fun scrollDown(lines: Int = 1) {
        for (i in 0 until lines) {
            // Shift content down
            for (y in screenHeight - 1 downTo 1) {
                screenBuffer[y] = screenBuffer[y - 1]
            }
            // Clear the first line
            screenBuffer[0] = Array(screenWidth) { TerminalChar(bgColor = currentBgColor) }
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
    
    fun getCursorX(): Int = cursorX
    fun getCursorY(): Int = cursorY

    fun getScreenContent(): Array<Array<TerminalChar>> {
        return screenBuffer
    }
    
    fun resize(newWidth: Int, newHeight: Int) {
        // A more sophisticated implementation would copy over the old buffer content
        val oldBuffer = screenBuffer
        val oldHeight = screenHeight
        val oldWidth = screenWidth

        screenWidth = newWidth
        screenHeight = newHeight
        screenBuffer = Array(screenHeight) { Array(screenWidth) { TerminalChar() } }
        
        // Copy old content
        val heightToCopy = oldHeight.coerceAtMost(newHeight)
        val widthToCopy = oldWidth.coerceAtMost(newWidth)

        for(y in 0 until heightToCopy) {
            System.arraycopy(oldBuffer[y], 0, screenBuffer[y], 0, widthToCopy)
        }

        cursorX = cursorX.coerceIn(0, screenWidth - 1)
        cursorY = cursorY.coerceIn(0, screenHeight - 1)
    }

    // Standard ANSI colors
    private fun ansiColor(index: Int) = when(index) {
        0 -> Color.BLACK
        1 -> Color.RED
        2 -> Color.GREEN
        3 -> Color.YELLOW
        4 -> Color.BLUE
        5 -> Color.MAGENTA
        6 -> Color.CYAN
        7 -> Color.WHITE
        else -> Color.WHITE
    }

    // Bright ANSI colors
    private fun ansiBrightColor(index: Int) = when(index) {
        0 -> Color.DKGRAY
        1 -> Color.rgb(255, 85, 85)
        2 -> Color.rgb(85, 255, 85)
        3 -> Color.rgb(255, 255, 85)
        4 -> Color.rgb(85, 85, 255)
        5 -> Color.rgb(255, 85, 255)
        6 -> Color.rgb(85, 255, 255)
        7 -> Color.LTGRAY
        else -> Color.WHITE
    }
    
    private fun xterm256Color(colorIndex: Int): Int {
        if (colorIndex < 0 || colorIndex > 255) return Color.WHITE

        // 16 basic colors
        if (colorIndex < 16) {
            val baseColors = intArrayOf(
                0x000000, 0x800000, 0x008000, 0x808000, 0x000080, 0x800080, 0x008080, 0xc0c0c0,
                0x808080, 0xff0000, 0x00ff00, 0xffff00, 0x0000ff, 0xff00ff, 0x00ffff, 0xffffff
            )
            return baseColors[colorIndex] or 0xFF000000.toInt()
        }

        // 216 colors cube
        if (colorIndex < 232) {
            val i = colorIndex - 16
            val r = (i / 36) * 51
            val g = ((i % 36) / 6) * 51
            val b = (i % 6) * 51
            return Color.rgb(r, g, b)
        }

        // 24 grayscale colors
        val gray = (colorIndex - 232) * 10 + 8
        return Color.rgb(gray, gray, gray)
    }
}
