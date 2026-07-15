package dev.ki.tui

import java.io.File
import java.nio.charset.StandardCharsets

/**
 * Minimal terminal abstraction the renderer writes through. Kept small and
 * behind an interface so the raw-mode backend (currently an `stty` subprocess,
 * see [ProcessTerminal]) can be swapped for JLine/JNA later without touching
 * components or the renderer.
 */
interface Terminal {
    /** Enter raw mode and begin delivering input. [onInput] gets each decoded
     *  input chunk; [onResize] fires when the terminal size changes. */
    fun start(onInput: (String) -> Unit, onResize: () -> Unit)

    /** Restore the terminal to its prior state. Safe to call more than once. */
    fun stop()

    fun write(s: String)

    val columns: Int
    val rows: Int

    /** Move the cursor by [lines] rows (negative = up, positive = down). */
    fun moveBy(lines: Int)
    fun hideCursor()
    fun showCursor()
    fun clearLine()
    fun clearScreen()
    fun setTitle(title: String)
}

/**
 * Real terminal backed by `System.in`/`System.out` with raw mode toggled through
 * the `stty` utility against `/dev/tty`. Zero external dependencies. Reads input
 * on a daemon thread and polls for size changes on another (no SIGWINCH on the JVM).
 */
class ProcessTerminal : Terminal {
    private val tty = File("/dev/tty")
    private val out = System.out
    private var savedSttyState: String? = null
    @Volatile private var running = false
    private var reader: Thread? = null
    private var resizeWatcher: Thread? = null

    @Volatile private var cachedCols = 80
    @Volatile private var cachedRows = 24

    override val columns: Int get() = cachedCols
    override val rows: Int get() = cachedRows

    override fun start(onInput: (String) -> Unit, onResize: () -> Unit) {
        savedSttyState = runStty("-g")?.trim()
        // Raw mode, no echo: we handle every byte and paint the screen ourselves.
        runStty("raw", "-echo")
        querySize()
        write(Ansi.BRACKETED_PASTE_ON)
        write(Ansi.HIDE_CURSOR)

        // Restore the terminal even on abnormal exit.
        Runtime.getRuntime().addShutdownHook(Thread { restore() })

        running = true
        reader = Thread({ readLoop(onInput) }, "ki-tui-input").apply { isDaemon = true; start() }
        resizeWatcher = Thread({ resizeLoop(onResize) }, "ki-tui-resize").apply { isDaemon = true; start() }
    }

    private fun readLoop(onInput: (String) -> Unit) {
        val buf = ByteArray(4096)
        val stdin = System.`in`
        try {
            while (running) {
                val n = stdin.read(buf)
                if (n < 0) break
                if (n == 0) continue
                onInput(String(buf, 0, n, StandardCharsets.UTF_8))
            }
        } catch (_: Exception) {
            // Terminal closed / interrupted — stop quietly.
        }
    }

    private fun resizeLoop(onResize: () -> Unit) {
        while (running) {
            try {
                Thread.sleep(250)
            } catch (_: InterruptedException) {
                break
            }
            val (c, r) = readSize() ?: continue
            if (c != cachedCols || r != cachedRows) {
                cachedCols = c
                cachedRows = r
                onResize()
            }
        }
    }

    override fun stop() {
        running = false
        reader?.interrupt()
        resizeWatcher?.interrupt()
        restore()
    }

    private var restored = false
    @Synchronized private fun restore() {
        if (restored) return
        restored = true
        write(Ansi.BRACKETED_PASTE_OFF)
        write(Ansi.SHOW_CURSOR)
        write(Ansi.RESET)
        out.flush()
        savedSttyState?.let { runStty(it) } ?: runStty("sane")
    }

    override fun write(s: String) {
        out.print(s)
        out.flush()
    }

    override fun moveBy(lines: Int) {
        if (lines < 0) write(Ansi.moveUp(-lines))
        else if (lines > 0) write(Ansi.moveDown(lines))
    }

    override fun hideCursor() = write(Ansi.HIDE_CURSOR)
    override fun showCursor() = write(Ansi.SHOW_CURSOR)
    override fun clearLine() = write(Ansi.CLEAR_LINE)
    override fun clearScreen() = write(Ansi.CLEAR_SCREEN)
    override fun setTitle(title: String) = write(Ansi.title(title))

    private fun querySize() {
        readSize()?.let { (c, r) -> cachedCols = c; cachedRows = r }
    }

    /** Read `rows cols` via `stty size`. Returns (cols, rows) or null on failure. */
    private fun readSize(): Pair<Int, Int>? {
        val line = runStty("size")?.trim() ?: return null
        val parts = line.split(" ")
        if (parts.size != 2) return null
        val r = parts[0].toIntOrNull() ?: return null
        val c = parts[1].toIntOrNull() ?: return null
        if (c <= 0 || r <= 0) return null
        return c to r
    }

    /**
     * Run `stty <args>` with the controlling terminal (`/dev/tty`) as stdin so it
     * reads/writes real terminal settings. Returns captured stdout, or null.
     * A saved `-g` state string is passed as a single space-joined arg list.
     */
    private fun runStty(vararg args: String): String? = try {
        val cmd = mutableListOf("stty")
        // A restored -g state is one opaque string; split it back into args.
        if (args.size == 1 && args[0].contains(":")) cmd.addAll(args[0].split(" "))
        else cmd.addAll(args)
        val proc = ProcessBuilder(cmd)
            .redirectInput(tty)
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .start()
        val stdout = proc.inputStream.readBytes().toString(StandardCharsets.UTF_8)
        proc.waitFor()
        stdout
    } catch (_: Exception) {
        null
    }
}
