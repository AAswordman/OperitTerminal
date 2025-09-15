package com.ai.assistance.operit.terminal

import android.util.Log
import java.io.File
import java.io.FileDescriptor
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

class Pty(
    val process: Process,
    val masterFd: FileDescriptor,
    private val ptyMaster: Int
) {
    val stdout: InputStream = FileInputStream(masterFd)
    val stdin: OutputStream = FileOutputStream(masterFd)

    fun waitFor(): Int {
        return process.waitFor()
    }

    fun destroy() {
        process.destroy()
        try {
            // It's important to close the master FD to signal EOF to the process
            stdout.close()
            stdin.close()
        } catch (e: IOException) {
            Log.e("Pty", "Error closing PTY streams", e)
        }
    }

    companion object {
        private const val TAG = "Pty"

        init {
            try {
                System.loadLibrary("pty")
            } catch (e: UnsatisfiedLinkError) {
                Log.e(TAG, "Failed to load libpty.so", e)
                // Handle error appropriately, maybe disable PTY functionality
            }
        }

        @Throws(IOException::class)
        fun start(command: Array<String>, environment: Map<String, String>, workingDir: File): Pty {
            val envArray = environment.map { "${it.key}=${it.value}" }.toTypedArray()

            // This will return an array of two integers: { pid, masterFd }
            val processInfo = createSubprocess(command, envArray, workingDir.absolutePath)
            val pid = processInfo[0]
            val masterFdInt = processInfo[1]

            if (pid <= 0 || masterFdInt <= 0) {
                throw IOException("Failed to create subprocess with PTY. pid=$pid, fd=$masterFdInt")
            }

            val fileDescriptor = Reflect.getFileDescriptor(masterFdInt)
            
            // We need a Process object to manage the subprocess lifetime
            val dummyProcess = object : Process() {
                override fun destroy() {
                    // Send SIGHUP to the process group to ensure all child processes are terminated
                    android.os.Process.sendSignal(pid, 1) // SIGHUP
                }

                override fun exitValue(): Int {
                    // We can't get the actual exit value without a blocking waitpid call,
                    // which we do in waitFor(). The contract of exitValue() is to throw
                    // an exception if the process is still running.
                    try {
                        // sendSignal(pid, 0) checks if the process exists.
                        // If it doesn't throw, the process is still alive.
                        android.os.Process.sendSignal(pid, 0)
                        throw IllegalThreadStateException("Process hasn't exited")
                    } catch (e: Exception) {
                        // The process is dead. We don't have the exit code without waiting,
                        // so we can't fulfill the contract perfectly. Returning 0 is a
                        // reasonable fallback for a terminated process where the specific
                        // exit code isn't available.
                        return 0
                    }
                }

                override fun getErrorStream(): InputStream? = null
                override fun getInputStream(): InputStream? = null
                override fun getOutputStream(): OutputStream? = null

                override fun waitFor(): Int {
                    return Companion.waitFor(pid)
                }
            }
            
            return Pty(dummyProcess, fileDescriptor, masterFdInt)
        }

        private external fun createSubprocess(cmdArray: Array<String>, envArray: Array<String>, workingDir: String): IntArray

        private external fun waitFor(pid: Int): Int
    }
}

// Reflection helper to create FileDescriptor from an int fd.
object Reflect {
    fun getFileDescriptor(fd: Int): FileDescriptor {
        val fileDescriptor = FileDescriptor()
        try {
            val field = FileDescriptor::class.java.getDeclaredField("descriptor")
            field.isAccessible = true
            field.set(fileDescriptor, fd)
        } catch (e: Exception) {
            throw IOException("Failed to create FileDescriptor from integer fd", e)
        }
        return fileDescriptor
    }
} 