package dev.ki.agent.skills

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SkillsTest {
    private fun dir(name: String, frontmatter: String): Path {
        val d = Files.createTempDirectory("ki-skill").resolve(name)
        Files.createDirectories(d)
        d.resolve("SKILL.md").writeText("---\n$frontmatter\n---\n\nBody text.\n")
        return d
    }

    // --- frontmatter parsing ---------------------------------------------------

    @Test fun `plain single-line scalars`() {
        val (fm, _) = Frontmatter.parse("---\nname: foo\ndescription: Does things\n---\n\nBody\n")
        assertEquals("foo", fm["name"])
        assertEquals("Does things", fm["description"])
    }

    @Test fun `double quoted scalar unescapes`() {
        val (fm, _) = Frontmatter.parse("---\ndescription: \"a \\\"quoted\\\" thing\"\n---\nB\n")
        assertEquals("a \"quoted\" thing", fm["description"])
    }

    @Test fun `single quoted scalar keeps special chars`() {
        val (fm, _) = Frontmatter.parse("---\ndescription: 'it''s a {test} & more'\n---\nB\n")
        assertEquals("it's a {test} & more", fm["description"])
    }

    @Test fun `folded scalar joins lines with spaces and clips one newline`() {
        val (fm, _) = Frontmatter.parse(
            "---\ndescription: >\n  First part here\n  second part continues\n  third part done.\n---\nB\n",
        )
        assertEquals("First part here second part continues third part done.\n", fm["description"])
    }

    @Test fun `folded scalar strip chomping drops the trailing newline`() {
        val (fm, _) = Frontmatter.parse("---\ndescription: >-\n  One two\n  three.\n---\nB\n")
        assertEquals("One two three.", fm["description"])
    }

    @Test fun `literal scalar keeps newlines`() {
        val (fm, _) = Frontmatter.parse("---\ndescription: |\n  line one\n  line two\n---\nB\n")
        assertEquals("line one\nline two\n", fm["description"])
    }

    @Test fun `no frontmatter yields empty map and full body`() {
        val (fm, body) = Frontmatter.parse("Just text\n")
        assertTrue(fm.isEmpty())
        assertEquals("Just text\n", body)
    }

    // --- skill loading -----------------------------------------------------------

    @Test fun `loads name description and paths`() {
        val d = dir("my-skill", "name: my-skill\ndescription: Does X")
        val s = Skills.loadOne(d)!!
        assertEquals("my-skill", s.name)
        assertEquals("Does X", s.description)
        assertEquals(d.resolve("SKILL.md"), s.filePath)
        assertEquals(d, s.baseDir)
    }

    @Test fun `name falls back to directory name`() {
        val s = Skills.loadOne(dir("dir-name", "description: Has no name key"))!!
        assertEquals("dir-name", s.name)
    }

    @Test fun `missing description skips the skill`() {
        assertNull(Skills.loadOne(dir("broken", "name: broken")))
    }

    @Test fun `missing SKILL_md returns null`() {
        assertNull(Skills.loadOne(Files.createTempDirectory("empty")))
    }

    @Test fun `disable-model-invocation is detected`() {
        val s = Skills.loadOne(dir("hidden", "name: hidden\ndescription: X\ndisable-model-invocation: true"))!!
        assertTrue(s.disableModelInvocation)
    }

    // --- prompt formatting (byte-compatible with pi's formatSkillsForPrompt) -------

    @Test fun `format matches pi byte for byte`() {
        val skills = listOf(
            Skill("alpha", "Does <a> & \"b\" 'c'", Path.of("/tmp/alpha/SKILL.md"), Path.of("/tmp/alpha"), false),
            Skill("beta", "Hidden one", Path.of("/tmp/beta/SKILL.md"), Path.of("/tmp/beta"), true),
            Skill("gamma", "Plain one", Path.of("/tmp/gamma/SKILL.md"), Path.of("/tmp/gamma"), false),
        )
        val expected = listOf(
            "",
            "",
            "The following skills provide specialized instructions for specific tasks.",
            "Use the read tool to load a skill's file when the task matches its description.",
            "When a skill file references a relative path, resolve it against the skill directory " +
                "(parent of SKILL.md / dirname of the path) and use that absolute path in tool commands.",
            "",
            "<available_skills>",
            "  <skill>",
            "    <name>alpha</name>",
            "    <description>Does &lt;a&gt; &amp; &quot;b&quot; &apos;c&apos;</description>",
            "    <location>/tmp/alpha/SKILL.md</location>",
            "  </skill>",
            "  <skill>",
            "    <name>gamma</name>",
            "    <description>Plain one</description>",
            "    <location>/tmp/gamma/SKILL.md</location>",
            "  </skill>",
            "</available_skills>",
        ).joinToString("\n")
        assertEquals(expected, Skills.formatForPrompt(skills))
    }

    @Test fun `empty visible set formats to empty string`() {
        assertEquals("", Skills.formatForPrompt(emptyList()))
        val hidden = Skill("x", "d", Path.of("/x"), Path.of("/x"), true)
        assertEquals("", Skills.formatForPrompt(listOf(hidden)))
    }

    // --- real repo skill (folded description with an em dash) ----------------------

    @Test fun `real shifts-schema skill parses its folded description`() {
        val kb = System.getenv("KB_ROOT") ?: return // run manually: KB_ROOT=... gradlew test
        val s = Skills.loadOne(Path.of(kb, ".pi/all-skills/shifts-schema")) ?: return
        assertEquals("shifts-schema", s.name)
        assertTrue(s.description.startsWith("Full schema of all shifts Postgres databases"))
        assertTrue(s.description.endsWith("\n"))
    }
}
