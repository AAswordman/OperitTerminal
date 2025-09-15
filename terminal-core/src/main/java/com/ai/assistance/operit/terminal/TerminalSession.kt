package com.ai.assistance.operit.terminal

import java.io.InputStream
import java.io.OutputStream

/**
 * Represents an active terminal session, encapsulating the process and its I/O streams.
 *
 * @property process The underlying [Process] of the terminal session.
 * @property stdout The standard output stream from the terminal process, which the terminal view will read from.
 * @property stdin The standard input stream to the terminal process, which the terminal view will write to.
 */
data class TerminalSession(
    val process: Process,
    val stdout: InputStream,
    val stdin: OutputStream
) 