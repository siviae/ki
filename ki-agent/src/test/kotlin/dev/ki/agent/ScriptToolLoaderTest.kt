package dev.ki.agent

import dev.ki.agent.tools.ScriptToolLoader
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ScriptToolLoaderTest {

    private val tmp = File.createTempFile("ki-tool-test", "").let {
        it.delete(); it.mkdirs(); it
    }
    private val cacheDir = File(tmp, "cache")
    private val scriptsDir = File(tmp, "tools").apply { mkdirs() }

    @AfterTest
    fun cleanup() {
        tmp.deleteRecursively()
    }

    private fun writeSampleScript(): File {
        val f = File(scriptsDir, "echo.ki.kts")
        f.writeText(
            """
            tool("echo") {
                description = "Echoes the input back, optionally upper-cased."
                param("text", "the text to echo", ParamType.STRING, required = true)
                param("shout", "upper-case the output", ParamType.BOOLEAN, required = false)
                execute { args ->
                    val t = args.string("text")
                    if (args.boolOrNull("shout") == true) t.uppercase() else t
                }
            }
            """.trimIndent(),
        )
        return f
    }

    @Test
    fun `compiles a script into a koog tool with correct descriptor`() {
        val loader = ScriptToolLoader(cacheDir)
        val tool = loader.load(writeSampleScript())

        assertEquals("echo", tool.descriptor.name)
        assertTrue(tool.descriptor.description.contains("Echoes"))
        assertEquals(listOf("text"), tool.descriptor.requiredParameters.map { it.name })
        assertEquals(listOf("shout"), tool.descriptor.optionalParameters.map { it.name })
    }

    @Test
    fun `executes the script body`() = runBlocking {
        val loader = ScriptToolLoader(cacheDir)
        val tool = loader.load(writeSampleScript())

        val out = tool.execute(
            buildJsonObject {
                put("text", JsonPrimitive("hi"))
                put("shout", JsonPrimitive(true))
            },
        )
        assertEquals("HI", out)
    }

    @Test
    fun `second load hits the on-disk compiled cache`() {
        val script = writeSampleScript()
        ScriptToolLoader(cacheDir).load(script)
        val jars = cacheDir.listFiles { f -> f.name.endsWith(".jar") }.orEmpty()
        assertTrue(jars.isNotEmpty(), "expected a cached compiled jar after first load")

        val mtime = jars.first().lastModified()
        // Fresh loader, same source -> cache hit, jar not rewritten.
        ScriptToolLoader(cacheDir).load(script)
        assertEquals(mtime, jars.first().lastModified(), "cached jar should be reused, not recompiled")
    }
}
