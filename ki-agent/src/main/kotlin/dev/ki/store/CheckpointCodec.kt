package dev.ki.store

import ai.koog.agents.snapshot.feature.AgentCheckpointData
import ai.koog.agents.snapshot.providers.PersistenceUtils

/**
 * koog `AgentCheckpointData` ⇄ JSON string. The checkpoint counterpart to [MessageCodec]
 * — the **only** code that touches koog's checkpoint serialization.
 *
 * `AgentCheckpointData` is a kotlinx `@Serializable` self-contained snapshot (checkpoint
 * id, version, full `messageHistory`, graph `nodePath`, agent storage). It is serialized
 * with koog's own [PersistenceUtils.defaultCheckpointJson] (the same `Json` koog's file
 * provider uses) so the blob round-trips exactly; the store persists it opaquely and
 * never re-parses it. Because the blob is self-contained, it deserializes and resumes in
 * a **different process/node** — the property M10 failover relies on.
 */
object CheckpointCodec {
    private val json = PersistenceUtils.defaultCheckpointJson

    fun encode(data: AgentCheckpointData): String =
        json.encodeToString(AgentCheckpointData.serializer(), data)

    fun decode(text: String): AgentCheckpointData =
        json.decodeFromString(AgentCheckpointData.serializer(), text)
}
