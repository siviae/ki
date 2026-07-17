package dev.ki.agent.tools.builtin

import java.nio.file.Files
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BashExecTest {

    @Test fun `simple command returns output and exit code`() {
        val dir = Files.createTempDirectory("ki-bash")
        val r = BashExec.run("echo hello", dir)
        assertEquals(0, r.exitCode)
        assertFalse(r.timedOut)
        assertTrue(r.output.contains("hello"))
    }

    @Test fun `timeout reaps the whole process tree, not just the shell`() {
        val dir = Files.createTempDirectory("ki-bash-kill")
        val pidFile = dir.resolve("child.pid")

        // A backgrounded child records its own pid, then a foreground sleep keeps the
        // shell alive so the run actually times out. Without process-tree kill the
        // backgrounded sleep reparents to init and survives the shell's death.
        val r = BashExec.run(
            "sleep 30 & echo \$! > '${pidFile}'; sleep 30",
            dir,
            timeoutSeconds = 1,
        )
        assertTrue(r.timedOut, "command should have timed out")

        val childPid = pidFile.readText().trim().toLong()
        // Give the OS a moment to finish reaping after the kill signal.
        val alive = (1..20).firstNotNullOfOrNull {
            val a = ProcessHandle.of(childPid).map { h -> h.isAlive }.orElse(false)
            if (!a) false else { Thread.sleep(50); null }
        } ?: false

        assertFalse(alive, "backgrounded child (pid $childPid) survived the timeout kill")
    }
}
