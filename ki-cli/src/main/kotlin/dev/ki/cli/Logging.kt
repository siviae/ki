package dev.ki.cli

import dev.ki.cli.config.CliArgs
import java.nio.file.Path

/**
 * Wires the logging backend before it initializes. logback reads `KI_LOG_LEVEL` and
 * `KI_LOG_DIR` from system properties (see `logback.xml`), so [configure] must run
 * *before* the first logger is touched — hence it is the first thing `main` does.
 *
 * Levels map from the CLI verbosity flags: `--debug` → DEBUG, `--verbose` → INFO,
 * otherwise WARN (quiet by default, since the sink is a file the user rarely reads).
 */
object Logging {
    fun levelFor(args: CliArgs): String = when {
        args.debug -> "DEBUG"
        args.verbose -> "INFO"
        else -> "WARN"
    }

    /** Default log directory: a `logs/` sibling of the session db (both under `.ki/`). */
    fun logDirFor(dbPath: String?): Path {
        val db = dbPath?.let { Path.of(it) }
        val parent = db?.parent ?: Path.of(".ki")
        return parent.resolve("logs")
    }

    fun configure(args: CliArgs, dbPath: String?) {
        System.setProperty("KI_LOG_LEVEL", levelFor(args))
        System.setProperty("KI_LOG_DIR", logDirFor(dbPath).toString())
    }
}
