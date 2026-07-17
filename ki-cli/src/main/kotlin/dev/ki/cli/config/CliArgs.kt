package dev.ki.cli.config

import java.nio.file.Path

/**
 * Parsed command-line arguments. Precedence for anything also settable elsewhere is
 * CLI flag > env > manifest > default (applied in [Bootstrap]).
 */
data class CliArgs(
    val configPath: Path = Path.of("./ki.toml"),
    val model: String? = null,
    val dbPath: String? = null,
    /** Resume a specific session id. */
    val resume: String? = null,
    /** Resume the most recently updated session. */
    val continueLatest: Boolean = false,
    /** One-shot prompt; when null the CLI runs interactively. */
    val prompt: String? = null,
    /** INFO-level logging to `.ki/logs/`. */
    val verbose: Boolean = false,
    /** DEBUG-level logging to `.ki/logs/`. */
    val debug: Boolean = false,
) {
    companion object {
        fun parse(argv: Array<String>): CliArgs {
            var args = CliArgs()
            val rest = ArrayList<String>()
            var i = 0
            fun next(flag: String): String {
                if (i + 1 >= argv.size) error("Missing value for $flag")
                return argv[++i]
            }
            while (i < argv.size) {
                when (val a = argv[i]) {
                    "--config", "-c" -> args = args.copy(configPath = Path.of(next(a)))
                    "--model", "-m" -> args = args.copy(model = next(a))
                    "--db" -> args = args.copy(dbPath = next(a))
                    "--resume", "-r" -> args = args.copy(resume = next(a))
                    "--continue" -> args = args.copy(continueLatest = true)
                    "--verbose", "-v" -> args = args.copy(verbose = true)
                    "--debug" -> args = args.copy(debug = true)
                    else -> rest.add(a)
                }
                i++
            }
            if (rest.isNotEmpty()) args = args.copy(prompt = rest.joinToString(" "))
            return args
        }
    }
}
