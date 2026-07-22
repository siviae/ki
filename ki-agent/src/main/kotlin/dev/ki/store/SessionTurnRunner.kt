package dev.ki.store

/**
 * Runs one agent turn for a session — the seam between M10's coordination loop and the actual
 * agent. The `ki-spring` `SessionWorker` owns *when* and *where* a turn runs (claim, drain,
 * release); the **host** supplies *how* by implementing this — building a `KiAgent` with the
 * session's history + checkpoint providers and calling `run`. This keeps the coordination
 * module free of model/tool/prompt configuration (the host owns that), mirroring how the CLI
 * and a Spring app both construct the agent themselves.
 *
 * The runner is called **only by the node that owns [sessionId]** (advisory lock held), so it
 * need not re-check ownership. It should run with checkpoints enabled so a mid-turn crash is
 * recoverable by another node (M9). [input] is the concatenated peeked steering for this turn.
 */
fun interface SessionTurnRunner {
    /** Run one turn for [sessionId] with [input]; return the assistant's reply text. */
    suspend fun runTurn(sessionId: String, input: String): String
}

/** Receives the reply a turn produced (e.g. post it back to a RocketChat thread in M11). */
fun interface TurnReplySink {
    suspend fun onReply(sessionId: String, reply: String)
}
