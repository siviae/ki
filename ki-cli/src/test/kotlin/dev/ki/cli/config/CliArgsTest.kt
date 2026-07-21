package dev.ki.cli.config

import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals

class CliArgsTest {
    @Test fun `single --config sets the primary and no extras`() {
        val args = CliArgs.parse(arrayOf("--config", "a.toml"))
        assertEquals(Path.of("a.toml"), args.configPath)
        assertEquals(emptyList(), args.additionalConfigs)
    }

    @Test fun `repeated --config makes the first primary and the rest additional`() {
        val args = CliArgs.parse(arrayOf("-c", "a.toml", "--config", "b.toml", "-c", "c.toml"))
        assertEquals(Path.of("a.toml"), args.configPath)
        assertEquals(listOf(Path.of("b.toml"), Path.of("c.toml")), args.additionalConfigs)
    }
}
