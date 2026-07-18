package dev.ki.store

/**
 * One persisted checkpoint row. [json] is the opaque serialized koog
 * `AgentCheckpointData` — implementations never parse it (same discipline as
 * [StoredMessage.json]). [version] is koog's monotonic per-session checkpoint
 * version; [latest] orders on it.
 */
data class StoredCheckpoint(
    val checkpointId: String,
    val version: Long,
    val createdAt: Long,
    val json: String,
)

/**
 * Storage SPI for agent-persistence checkpoints — the crash-recovery counterpart to
 * [SessionStore]. koog's `Persistence` feature snapshots graph state after each node;
 * [StoreCheckpointProvider] adapts this SPI to koog's `PersistenceStorageProvider`, so
 * implementations deal only in [StoredCheckpoint] and never touch koog.
 *
 * Two deployments, one contract (mirrors [SessionStore]): the CLI backs it with the
 * same embedded SQLite connection, a host Spring app with Postgres via `JdbcTemplate`.
 * This is exactly the seam M10 fails over across nodes.
 *
 * Unlike [SessionStore.save] (replace semantics), [save] **appends** — koog writes one
 * new checkpoint per node and versions the next from [latest]; history is not rewritten.
 */
interface CheckpointStore {
    /** All checkpoints for [sessionId] (any order); empty if none. */
    fun load(sessionId: String): List<StoredCheckpoint>

    /** Append one checkpoint for [sessionId]. */
    fun save(sessionId: String, checkpoint: StoredCheckpoint)

    /**
     * The highest-[StoredCheckpoint.version] checkpoint for [sessionId], or null.
     *
     * **Must include tombstones** (koog's completion marker): koog calls this both to
     * version the next write (`latest.version + 1`) and, via `rollbackToLatestCheckpoint`,
     * to decide recovery — where a tombstone deserializes to a no-op restore. Filtering
     * tombstones here would break versioning; skipping them is koog's job, not the store's.
     */
    fun latest(sessionId: String): StoredCheckpoint?

    /** Drop all checkpoints for [sessionId] (pruning / after clean handoff). */
    fun delete(sessionId: String)
}
