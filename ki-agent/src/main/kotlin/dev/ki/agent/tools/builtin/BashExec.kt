package dev.ki.agent.tools.builtin

import com.zaxxer.nuprocess.NuAbstractProcessHandler
import com.zaxxer.nuprocess.NuProcess
import com.zaxxer.nuprocess.NuProcessBuilder
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/** Outcome of a bash run. [exitCode] is null when the process was killed (timeout). */
data class BashResult(val exitCode: Int?, val output: String, val timedOut: Boolean)

/**
 * Runs a shell command via NuProcess. stdout and stderr are merged into one byte
 * stream (matching pi's single `onData`) and decoded as UTF-8 once at the end —
 * so a multi-byte character split across read chunks can never corrupt, without a
 * streaming decoder. NuProcess delivers callbacks on its own pump thread and
 * guarantees the closed stdout/stderr callbacks precede onExit, which unblocks
 * waitFor; the accumulated buffer is therefore complete once waitFor returns.
 *
 * Deferred vs. pi: live streaming updates, full-output-to-temp-file persistence on
 * truncation, and process-tree kill (destroy(true) kills the shell; detached
 * grandchildren may survive).
 */
object BashExec {
    private val SHELL = if (System.getProperty("os.name").startsWith("Windows")) "cmd" else "/bin/bash"
    private val SHELL_ARGS = if (SHELL == "cmd") listOf("/c") else listOf("-c")

    /** @param timeoutSeconds null → wait indefinitely (pi has no default timeout). */
    fun run(command: String, cwd: Path, timeoutSeconds: Long? = null): BashResult {
        if (!Files.isDirectory(cwd)) {
            throw IllegalArgumentException("Working directory does not exist: $cwd\nCannot execute bash commands.")
        }

        val buffer = ByteArrayOutputStream()
        var exit = 0
        val handler = object : NuAbstractProcessHandler() {
            override fun onStdout(b: ByteBuffer, closed: Boolean) = drain(b)
            override fun onStderr(b: ByteBuffer, closed: Boolean) = drain(b)
            override fun onExit(statusCode: Int) { exit = statusCode }
            private fun drain(b: ByteBuffer) {
                val bytes = ByteArray(b.remaining())
                b.get(bytes)
                synchronized(buffer) { buffer.write(bytes) }
            }
        }

        val builder = NuProcessBuilder(handler, listOf(SHELL) + SHELL_ARGS + command)
        builder.setCwd(cwd)
        builder.environment().putAll(System.getenv())
        val process: NuProcess = builder.start()

        val code = if (timeoutSeconds == null) {
            process.waitFor(0, TimeUnit.SECONDS)
        } else {
            process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
        }

        // NuProcess returns Integer.MIN_VALUE when the timeout elapses before exit.
        val timedOut = code == Integer.MIN_VALUE || process.isRunning
        if (timedOut) {
            process.destroy(true)
            process.waitFor(0, TimeUnit.SECONDS) // reap
        }

        val output = synchronized(buffer) { buffer.toByteArray() }.toString(Charsets.UTF_8)
        return BashResult(
            exitCode = if (timedOut) null else exit,
            output = output,
            timedOut = timedOut,
        )
    }
}
