package dev.ki.cli.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class SlashCommandsTest {
    private val ctx = object : SlashContext {
        override fun model() = "gpt-4o"
        override fun tools() = listOf("bash", "read", "edit")
        override fun modelCatalog() = listOf("fast", "big")
        override fun configSummary() = "model: gpt-4o"
    }

    private fun dispatch(s: String) = SlashCommands.dispatch(s, ctx)

    @Test fun `plain text is not a command`() {
        val a = assertIs<SlashAction.NotACommand>(dispatch("hello world"))
        assertEquals("hello world", a.text)
    }

    @Test fun `help clear quit`() {
        assertTrue(assertIs<SlashAction.Show>(dispatch("/help")).text.contains("Commands"))
        assertEquals(SlashAction.Clear, dispatch("/clear"))
        assertEquals(SlashAction.Quit, dispatch("/quit"))
        assertEquals(SlashAction.Quit, dispatch("/q"))
    }

    @Test fun `tools lists registered tools`() {
        assertTrue(assertIs<SlashAction.Show>(dispatch("/tools")).text.contains("bash"))
    }

    @Test fun `model without arg shows current and catalog`() {
        val t = assertIs<SlashAction.Show>(dispatch("/model")).text
        assertTrue(t.contains("gpt-4o"))
        assertTrue(t.contains("fast"))
    }

    @Test fun `model with arg switches`() {
        assertEquals("fast", assertIs<SlashAction.SwitchModel>(dispatch("/model fast")).name)
    }

    @Test fun `unknown command`() {
        assertEquals("frobnicate", assertIs<SlashAction.Unknown>(dispatch("/frobnicate")).name)
    }

    @Test fun `commands are case-insensitive and trim`() {
        assertEquals(SlashAction.Quit, dispatch("/QUIT"))
        assertEquals("m", assertIs<SlashAction.SwitchModel>(dispatch("/model   m")).name)
    }
}
