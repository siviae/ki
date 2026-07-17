package dev.ki.store.spring

import dev.ki.store.SessionStore
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.jdbc.core.JdbcTemplate

/**
 * Drop-in wiring for a host Spring app: import this configuration (or component-scan
 * `dev.ki.store.spring`) and the agent gets a Postgres-backed [SessionStore] over the
 * application's existing `JdbcTemplate` — no separate database or service.
 */
@Configuration
open class KiStoreSpringConfiguration {
    @Bean
    open fun kiSessionStore(jdbcTemplate: JdbcTemplate): SessionStore =
        JdbcTemplateSessionStore(jdbcTemplate)
}
