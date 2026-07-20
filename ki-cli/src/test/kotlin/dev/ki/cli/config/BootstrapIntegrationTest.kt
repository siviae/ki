package dev.ki.cli.config

import dev.ki.agent.KiAgent
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * Opt-in end-to-end check: loads the real project `ki.toml` (model/base_url/api_key_env),
 * builds a session against the configured LiteLLM proxy, and drives one real turn against
 * the real model. Catches wiring/proxy problems (bad model routing, malformed tool schemas,
 * ...) that unit tests with a synthetic manifest can't see. Enable with:
 *
 *   KI_IT=1 gradle :ki-cli:test --tests "*BootstrapIntegrationTest*"
 *
 * Requires the manifest's `[llm].api_key_env` (see repo-root ki.toml) to be set in the
 * environment.
 */
class BootstrapIntegrationTest {

    @Test
    fun `real ki toml drives a real LLM reply`() {
        if (System.getenv("KI_IT") != "1") return // skipped unless explicitly enabled

        val manifestPath = Path.of("../ki.toml")
        assertTrue(manifestPath.exists(), "expected repo-root ki.toml at $manifestPath")

        val session = Bootstrap.build(CliArgs(configPath = manifestPath), "You are terse.")
        session.store.use {
            val agent = KiAgent(session.llm, session.systemPrompt, session.tools)
            val reply = runBlocking { agent.run("Reply with exactly one word: hi") }
            assertTrue(
                reply.isNotBlank(),
                "expected a non-empty reply from model '${session.config.defaultModelId}' " +
                    "via ${session.config.baseUrl}",
            )
        }
    }

    @Test
    fun `real ki toml drives a real LLM tool call`() {
        if (System.getenv("KI_IT") != "1") return // skipped unless explicitly enabled

        val manifestPath = Path.of("../ki.toml")
        assertTrue(manifestPath.exists(), "expected repo-root ki.toml at $manifestPath")

        val session = Bootstrap.build(CliArgs(configPath = manifestPath), "You are terse.")
        session.store.use {
            val agent = KiAgent(session.llm, session.systemPrompt, session.tools)
            // Sends every configured tool's schema to the proxy and forces at least one
            // call (bash) — the shape that tripped the 500 from deepseek-v4-flash.
            val reply = runBlocking { agent.run("Use the bash tool to run `echo hi` and report the output.") }
            assertTrue(
                reply.isNotBlank(),
                "expected a non-empty reply from model '${session.config.defaultModelId}' " +
                    "via ${session.config.baseUrl}",
            )
        }
    }
}
