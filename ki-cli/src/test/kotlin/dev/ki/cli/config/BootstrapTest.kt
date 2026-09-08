package dev.ki.cli.config

import ai.koog.agents.core.tools.Tool
import ai.koog.prompt.message.Message
import ai.koog.prompt.message.MessagePart
import dev.ki.agent.config.ManifestException
import dev.ki.store.MessageCodec
import dev.ki.agent.hooks.ToolBlockedException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.test.assertFalse

class BootstrapTest {
    private fun writeManifest(toml: String): Path {
        val dir = Files.createTempDirectory("ki-boot")
        return dir.resolve("ki.toml").also { it.writeText(toml) }
    }

    @Test fun `builds only the tools listed in the manifest`() {
        val cfg = writeManifest(
            """
            [llm]
            base_url = "http://localhost:4000"
            api_key_env = "LITELLM_API_KEY"
            model = "gpt-4o"
            [db]
            path = "sessions"
            [tools.bash]
            [tools.read]
            """.trimIndent()
        )
        val session = Bootstrap.build(CliArgs(configPath = cfg), "SYS")
        session.store.let {
            assertEquals(2, session.tools.size)
        }
    }

    @Test fun `an unlisted builtin is simply absent`() {
        val cfg = writeManifest("[llm]\nbase_url = \"http://localhost:4000\"\napi_key_env = \"LITELLM_API_KEY\"\nmodel = \"gpt-4o\"\n[tools.bash]\n")
        val session = Bootstrap.build(CliArgs(configPath = cfg), "SYS")
        session.store.let { assertEquals(1, session.tools.size) }
    }

    @Test fun `unknown non-builtin tool without a script errors`() {
        val cfg = writeManifest("[llm]\nbase_url = \"http://localhost:4000\"\napi_key_env = \"LITELLM_API_KEY\"\nmodel = \"gpt-4o\"\n[tools.frobnicate]\n")
        val e = assertFailsWith<ManifestException> { Bootstrap.build(CliArgs(configPath = cfg), "SYS") }
        assertTrue(e.message!!.contains("frobnicate"), e.message)
    }

    @Test fun `context files are appended to the system prompt`() {
        val dir = Files.createTempDirectory("ki-ctx")
        dir.resolve("KI.md").writeText("Project rule: be terse.")
        val cfg = dir.resolve("ki.toml")
        cfg.writeText("[llm]\nbase_url = \"http://localhost:4000\"\napi_key_env = \"LITELLM_API_KEY\"\nmodel = \"gpt-4o\"\n[context]\nfiles = [\"KI.md\"]\n[tools.bash]\n")
        val session = Bootstrap.build(CliArgs(configPath = cfg), "SYS")
        session.store.let {
            assertTrue(session.systemPrompt.startsWith("SYS"))
            assertTrue(session.systemPrompt.contains("Project rule: be terse."))
        }
    }

    @Test fun `model catalog context window flows into the llm (M6 budget)`() {
        val cfg = writeManifest(
            """
            [llm]
            base_url = "http://localhost:4000"
            api_key_env = "LITELLM_API_KEY"
            model = "small"
            [db]
            path = "sessions"
            [tools.bash]
            [models.small]
            id = "gpt-4o-mini"
            context_window = 8000
            max_output_tokens = 4096
            """.trimIndent()
        )
        val session = Bootstrap.build(CliArgs(configPath = cfg), "SYS")
        session.store.let {
            assertEquals("gpt-4o-mini", session.llm.defaultModel.id)
            assertEquals(8000, session.llm.defaultModel.contextWindow)
        }
    }

    @Test fun `sibling ki-star-toml files are auto-discovered and merged`() {
        val dir = Files.createTempDirectory("ki-boot")
        val cfg = dir.resolve("ki.toml")
        cfg.writeText("[llm]\nbase_url = \"http://localhost:4000\"\napi_key_env = \"LITELLM_API_KEY\"\nmodel = \"gpt-4o\"\n[tools.bash]\n")
        // A sibling adds another tool; no --config for it, discovery must pick it up.
        dir.resolve("ki.extra.toml").writeText("[tools.read]\n")
        val session = Bootstrap.build(CliArgs(configPath = cfg), "SYS")
        session.store.let { assertEquals(2, session.tools.size) }
    }

    @Test fun `a duplicate key across primary and sibling fails the build`() {
        val dir = Files.createTempDirectory("ki-boot")
        val cfg = dir.resolve("ki.toml")
        cfg.writeText("[llm]\nbase_url = \"http://localhost:4000\"\napi_key_env = \"LITELLM_API_KEY\"\nmodel = \"gpt-4o\"\n[tools.bash]\n")
        dir.resolve("ki.dup.toml").writeText("[llm]\nmodel = \"other\"\n")
        val e = assertFailsWith<ManifestException> { Bootstrap.build(CliArgs(configPath = cfg), "SYS") }
        assertTrue(e.message!!.contains("llm.model"), e.message)
    }

    @Test fun `an extension's config is filled from the manifest and enforced on the wrapped tool`() {
        // Drives the real Bootstrap wiring: load extension -> fill config<GuardConfig>() from the
        // merged tree -> wrap the bash tool. Then execute a disallowed command through the wrapped
        // tool (production path) and assert the block. A blocked call never runs the delegate, so
        // real bash never fires.
        val dir = Files.createTempDirectory("ki-boot-extcfg")
        dir.resolve("guards.ki.kts").writeText(
            """
            data class BashRules(val unrestricted: Boolean = false, val commands: Map<String, Any?> = emptyMap())
            data class GuardConfig(val bash: BashRules = BashRules())
            extension {
                val cfg = config<GuardConfig>()
                onToolCall("bash") { _, args ->
                    val name = args.string("command").trim().substringBefore(' ')
                    val bash = cfg().bash
                    if (!bash.unrestricted && name !in bash.commands.keys)
                        InterceptionResult.Block("blocked: " + name)
                    else InterceptionResult.Permit
                }
            }
            """.trimIndent()
        )
        val cfg = dir.resolve("ki.toml")
        cfg.writeText(
            """
            [llm]
            base_url = "http://localhost:4000"
            api_key_env = "LITELLM_API_KEY"
            model = "gpt-4o"
            [tools.bash]
            [bash]
            unrestricted = false
              [bash.commands.git]
            [extensions.guards]
            script = "guards.ki.kts"
            """.trimIndent()
        )

        val session = Bootstrap.build(CliArgs(configPath = cfg), "SYS")
        session.store.let {
            @Suppress("UNCHECKED_CAST")
            val bash = session.tools.first { it.descriptor.name == "bash" } as Tool<JsonObject, String>
            assertFailsWith<ToolBlockedException> {
                runBlocking { bash.execute(buildJsonObject { put("command", "rm -rf /") }) }
            }
        }
    }

    @Test fun `--continue resumes the most recent session`() {
        val cfg = writeManifest("[llm]\nbase_url = \"http://localhost:4000\"\napi_key_env = \"LITELLM_API_KEY\"\nmodel = \"gpt-4o\"\n[db]\npath = \"sessions\"\n[tools.bash]\n")
        // Seed two sessions directly in the store the bootstrap will open.
        val first = Bootstrap.build(CliArgs(configPath = cfg), "SYS")
        first.store.let { s ->
            val msg = { id: String ->
                dev.ki.store.StoredMessage(
                    0, "User",
                    MessageCodec.encode(
                        Message.User(listOf(MessagePart.Text(id)), ai.koog.prompt.message.RequestMetaInfo(kotlin.time.Instant.fromEpochMilliseconds(0)))
                    ),
                )
            }
            s.save("old", listOf(msg("old")))
            Thread.sleep(5)
            s.save("recent", listOf(msg("recent")))
        }
        val resumed = Bootstrap.build(CliArgs(configPath = cfg, continueLatest = true), "SYS")
        resumed.store.let { assertEquals("recent", resumed.sessionId) }
    }

    @Test fun `token limits not set in config are pulled from the proxy, config wins`() {
        val dir = Files.createTempDirectory("ki-boot")
        val cfg = dir.resolve("ki.toml")
        cfg.writeText(
            """
            [llm]
            base_url = "http://localhost:4000/v1"
            api_key_env = "LITELLM_API_KEY"
            model = "qwen3.8-27b"
            [tools.bash]
            [models."qwen3.8-27b"]
            id = "qwen3.8-27b"
            vision = false
            """.trimIndent()
        )
        var fetched = false
        val fake: (String, String?, String) -> ProxyModels.Info? = { url, key, model ->
            fetched = true
            assertEquals("http://localhost:4000/v1", url)
            assertEquals("qwen3.8-27b", model)
            ProxyModels.Info(maxInputTokens = 262_144, maxOutputTokens = 32_768)
        }
        val session = Bootstrap.build(CliArgs(configPath = cfg), "SYS", fetchProxyModels = fake)
        session.llm.defaultModel.let {
            // proxy filled the unset limits
            assertEquals(262_144, it.contextWindow)
            assertEquals(32_768, it.maxOutputTokens)
            // vision came from ki.toml (false → no Vision capability in koog terms)
            assertFalse(it.vision)
            assertFalse(it.toLLModel().capabilities!!.any { c -> c.toString().contains("Vision") })
        }
        assertTrue(fetched)
    }

    @Test fun `explicit config limits override the proxy and skip the fetch`() {
        val dir = Files.createTempDirectory("ki-boot")
        val cfg = dir.resolve("ki.toml")
        cfg.writeText(
            """
            [llm]
            base_url = "http://localhost:4000"
            api_key_env = "LITELLM_API_KEY"
            model = "small"
            [tools.bash]
            [models.small]
            id = "gpt-4o-mini"
            context_window = 8000
            max_output_tokens = 4096
            """.trimIndent()
        )
        val session = Bootstrap.build(CliArgs(configPath = cfg), "SYS", fetchProxyModels = { _, _, _ ->
            throw IllegalStateException("proxy fetch must not run when limits are explicit")
        })
        assertEquals(8000, session.llm.defaultModel.contextWindow)
        // vision defaults to true when not configured
        assertTrue(session.llm.defaultModel.vision)
    }
}
