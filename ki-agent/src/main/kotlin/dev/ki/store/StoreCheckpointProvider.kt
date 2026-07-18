package dev.ki.store

import ai.koog.agents.snapshot.feature.AgentCheckpointData
import ai.koog.agents.snapshot.providers.PersistenceStorageProvider
import ai.koog.agents.snapshot.providers.filters.AgentCheckpointPredicateFilter

/**
 * Bridges koog's `PersistenceStorageProvider` to ki's [CheckpointStore] SPI — the
 * checkpoint counterpart to [StoreChatHistoryProvider], and the single place koog
 * `AgentCheckpointData` crosses into storage.
 *
 * koog keys checkpoints by `sessionId` (the run's session id, == ki's session id), so a
 * process that dies mid-run resumes by running the agent with the same session id: on the
 * next strategy start koog calls [getLatestCheckpoint] and rolls the graph back to it.
 *
 * The generic `Filter` is koog's [AgentCheckpointPredicateFilter] (matching the in-memory
 * and file providers); ki never issues filtered queries, but koog's default overloads
 * route through the filtered ones, so both are implemented.
 */
class StoreCheckpointProvider(
    private val store: CheckpointStore,
) : PersistenceStorageProvider<AgentCheckpointPredicateFilter> {

    override suspend fun saveCheckpoint(sessionId: String, agentCheckpointData: AgentCheckpointData) {
        store.save(
            sessionId,
            StoredCheckpoint(
                checkpointId = agentCheckpointData.checkpointId,
                version = agentCheckpointData.version,
                createdAt = agentCheckpointData.createdAt.toEpochMilliseconds(),
                json = CheckpointCodec.encode(agentCheckpointData),
            ),
        )
    }

    override suspend fun getCheckpoints(
        sessionId: String,
        filter: AgentCheckpointPredicateFilter?,
    ): List<AgentCheckpointData> {
        val all = store.load(sessionId).map { CheckpointCodec.decode(it.json) }
        return if (filter != null) all.filter { filter.check(it) } else all
    }

    override suspend fun getLatestCheckpoint(
        sessionId: String,
        filter: AgentCheckpointPredicateFilter?,
    ): AgentCheckpointData? {
        // No filter: the store's version-ordered latest (must include tombstones — koog
        // versions the next write and no-ops the restore for a tombstone). Filtered: koog
        // wants newest-by-createdAt among matches, so fall back to a scan.
        if (filter == null) return store.latest(sessionId)?.let { CheckpointCodec.decode(it.json) }
        return getCheckpoints(sessionId, filter).maxByOrNull { it.createdAt }
    }
}
