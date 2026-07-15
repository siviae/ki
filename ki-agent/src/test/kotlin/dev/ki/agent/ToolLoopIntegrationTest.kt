package dev.ki.agent

import dev.ki.agent.tools.ScriptToolLoader
import dev.ki.ai.KiConfig
import dev.ki.ai.KiLlm
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Opt-in end-to-end check of the full tool-calling loop through the ScriptTool
 * adapter: koog generates the tool's JSON schema from our [ToolDescriptor], the
 * model (mock) returns a tool_call, koog decodes the arguments into our
 * `Tool<JsonObject, String>`, the script body runs, the result is fed back, and
 * the second turn produces the final answer.
 *
 *   KI_IT=1 KI_IT_BASE_URL=http://127.0.0.1:4011 gradle :ki-agent:test
 */
class ToolLoopIntegrationTest {

    @Test
    fun `model tool_call runs the script tool and the loop completes`() {
        if (System.getenv("KI_IT") != "1") return
        val baseUrl = System.getenv("KI_IT_BASE_URL") ?: "http://127.0.0.1:4011"

        val tmp = File.createTempFile("ki-loop", "").apply { delete(); mkdirs() }
        val marker = File(tmp, "fired.txt")
        val scriptsDir = File(tmp, "tools").apply { mkdirs() }
        File(scriptsDir, "grep.ki.kts").writeText(
            """
            tool("grep") {
                description = "Search for a pattern."
                param("pattern", "the pattern", ParamType.STRING, required = true)
                execute { args ->
                    java.io.File(${"\"" + marker.absolutePath.replace("\\", "\\\\") + "\""}).writeText("fired:" + args.string("pattern"))
                    "matched 1 line"
                }
            }
            """.trimIndent(),
        )

        val tools = ScriptToolLoader(File(tmp, "cache")).loadAll(scriptsDir)
        val llm = KiLlm(KiConfig(baseUrl = baseUrl, apiKey = "sk-test", defaultModelId = "gpt-4o"))
        val agent = KiAgent(llm, systemPrompt = "Use tools.", tools = tools)

        val reply = runBlocking { agent.run("find the needle") }

        assertTrue(marker.exists(), "script tool never executed")
        assertTrue(marker.readText().contains("needle"), "tool got wrong args: ${marker.readText()}")
        assertTrue(reply.contains("friend", ignoreCase = true), "loop did not complete; reply=$reply")
        tmp.deleteRecursively()
    }
}
