package dev.ki.store.spring

import dev.ki.cluster.JdbcSteeringInbox
import dev.ki.store.CheckpointStore
import dev.ki.store.SessionStore
import dev.ki.store.SteeringInbox
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.jdbc.core.JdbcTemplate

/**
 * Drop-in wiring for a host Spring app: import this configuration (or component-scan
 * `dev.ki.store.spring`) and the agent gets a Postgres-backed [SessionStore],
 * [CheckpointStore], and [SteeringInbox] over the application's existing `JdbcTemplate` —
 * no separate database or service. Together they are the M9/M10 substrate: checkpoints are
 * the crash-recovery state M10 fails over, the steering inbox delivers out-of-band input to
 * the owning node.
 *
 * **Not auto-wired: [dev.ki.store.SessionOwnership].** Its advisory-lock impl pins one
 * connection per owned session, so it needs a **dedicated** `DataSource` sized to the node's
 * target sessions-per-node — distinct from the app's main pool. The host constructs
 * `AdvisoryLockSessionOwnership(dedicatedDataSource)` itself; wiring it here off the main pool
 * would silently cap and starve the app's connections.
 */
@Configuration
open class KiStoreSpringConfiguration {
    @Bean
    open fun kiSessionStore(jdbcTemplate: JdbcTemplate): SessionStore =
        JdbcTemplateSessionStore(jdbcTemplate)

    @Bean
    open fun kiCheckpointStore(jdbcTemplate: JdbcTemplate): CheckpointStore =
        JdbcTemplateCheckpointStore(jdbcTemplate)

    @Bean
    open fun kiSteeringInbox(jdbcTemplate: JdbcTemplate): SteeringInbox =
        JdbcSteeringInbox(jdbcTemplate)
}
