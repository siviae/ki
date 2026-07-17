package dev.ki.cli.ui

/** Read-only view the dispatcher needs to render status-style commands. */
interface SlashContext {
    fun model(): String
    fun tools(): List<String>
    fun modelCatalog(): List<String>
    fun configSummary(): String
}

/** The outcome of interpreting a line of input. Executed by the UI / controller. */
sealed interface SlashAction {
    /** Print [text] to the transcript. */
    data class Show(val text: String) : SlashAction
    /** Clear the transcript. */
    data object Clear : SlashAction
    /** Quit the app. */
    data object Quit : SlashAction
    /** Switch the active model to [name]. */
    data class SwitchModel(val name: String) : SlashAction
    /** Not a slash command — send [text] to the agent as a normal prompt. */
    data class NotACommand(val text: String) : SlashAction
    /** An unrecognized `/name`. */
    data class Unknown(val name: String) : SlashAction
}

/**
 * Pure slash-command interpreter — dispatched before a line reaches the agent. Kept
 * side-effect-free (returns a [SlashAction]) so it is trivially unit-testable; the UI
 * performs the effect.
 */
object SlashCommands {
    val HELP = """
        Commands:
          /help            show this help
          /model [name]    show the current model (+ catalog), or switch to <name>
          /tools           list the loaded tools
          /config          show effective configuration
          /resume          how to resume a session
          /clear           clear the transcript
          /quit            exit ki
    """.trimIndent()

    fun dispatch(input: String, ctx: SlashContext): SlashAction {
        if (!input.startsWith("/")) return SlashAction.NotACommand(input)
        val body = input.trim().removePrefix("/")
        if (body.isEmpty()) return SlashAction.Unknown("")
        val space = body.indexOf(' ')
        val cmd = if (space < 0) body else body.substring(0, space)
        val arg = if (space < 0) "" else body.substring(space + 1).trim()

        return when (cmd.lowercase()) {
            "help", "h", "?" -> SlashAction.Show(HELP)
            "clear" -> SlashAction.Clear
            "quit", "exit", "q" -> SlashAction.Quit
            "tools" -> SlashAction.Show("Tools: " + ctx.tools().joinToString(", ").ifEmpty { "(none)" })
            "config" -> SlashAction.Show(ctx.configSummary())
            "model" -> if (arg.isEmpty())
                SlashAction.Show("Model: ${ctx.model()}\nCatalog: " + ctx.modelCatalog().joinToString(", ").ifEmpty { "(none)" })
            else SlashAction.SwitchModel(arg)
            "resume" -> SlashAction.Show("Resume a session by restarting: ki --resume <id>  (or --continue).")
            else -> SlashAction.Unknown(cmd)
        }
    }
}
