package dev.ki.cli.config

import java.nio.file.Path

/**
 * Parsed command-line arguments. Precedence for anything also settable elsewhere is
 * CLI flag > env > manifest > default (applied in [Bootstrap]).
 */
data class CliArgs(
    /** Primary manifest; its directory is the project root. */
    val configPath: Path = Path.of("./ki.toml"),
    /**
     * Extra manifest files, deep-union-merged with [configPath] (see [Manifest.load]).
     * When empty, [Bootstrap] auto-discovers sibling `ki.*.toml` files next to
     * [configPath]; when non-empty, only the explicit set is loaded (no discovery).
     */
    val additionalConfigs: List<Path> = emptyList(),
    val model: String? = null,
    val dbPath: String? = null,
    /** Resume a specific session id. */
    val resume: String? = null,
    /** Resume the most recently updated session. */
    val continueLatest: Boolean = false,
    /** One-shot prompt; when null the CLI runs interactively. */
    val prompt: String? = null,
    /**
     * Activate a named hat: load `.pi/hats/<name>/ki.toml` over the project manifest with
     * hat-wins resolution (pi semantics — see [ManifestLoader.loadHat]). The hat's
     * `[agent].systemPrompt`, when present, becomes the base system prompt.
     */
    val hat: String? = null,
    /** Print the resolved configuration as JSON to stdout and exit (parity/debug aid). */
    val printResolved: Boolean = false,
    /** INFO-level logging to `.ki/logs/`. */
    val verbose: Boolean = false,
    /** DEBUG-level logging to `.ki/logs/`. */
    val debug: Boolean = false,
) {
    companion object {
        fun parse(argv: Array<String>): CliArgs {
            var args = CliArgs()
            val rest = ArrayList<String>()
            var configSeen = false
            var i = 0
            fun next(flag: String): String {
                if (i + 1 >= argv.size) error("Missing value for $flag")
                return argv[++i]
            }
            while (i < argv.size) {
                when (val a = argv[i]) {
                    // First --config is the primary (drives the root); further ones add files.
                    "--config", "-c" -> {
                        val p = Path.of(next(a))
                        args = if (!configSeen) args.copy(configPath = p)
                        else args.copy(additionalConfigs = args.additionalConfigs + p)
                        configSeen = true
                    }
                    "--model", "-m" -> args = args.copy(model = next(a))
                    "--db" -> args = args.copy(dbPath = next(a))
                    "--resume", "-r" -> args = args.copy(resume = next(a))
                    "--continue" -> args = args.copy(continueLatest = true)
                    "--verbose", "-v" -> args = args.copy(verbose = true)
                    "--debug" -> args = args.copy(debug = true)
                    "--hat" -> args = args.copy(hat = next(a))
                    "--print-resolved" -> args = args.copy(printResolved = true)
                    else -> rest.add(a)
                }
                i++
            }
            if (rest.isNotEmpty()) args = args.copy(prompt = rest.joinToString(" "))
            return args
        }
    }
}
