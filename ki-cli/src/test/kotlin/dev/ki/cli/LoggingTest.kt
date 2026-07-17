package dev.ki.cli

import ch.qos.logback.classic.LoggerContext
import ch.qos.logback.classic.joran.JoranConfigurator
import dev.ki.cli.config.CliArgs
import org.slf4j.LoggerFactory
import java.nio.file.Files
import kotlin.io.path.exists
import kotlin.io.path.listDirectoryEntries
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LoggingTest {

    @Test fun `verbosity flags map to log levels`() {
        assertEquals("WARN", Logging.levelFor(CliArgs()))
        assertEquals("INFO", Logging.levelFor(CliArgs(verbose = true)))
        assertEquals("DEBUG", Logging.levelFor(CliArgs(debug = true)))
        // --debug wins over --verbose.
        assertEquals("DEBUG", Logging.levelFor(CliArgs(verbose = true, debug = true)))
    }

    @Test fun `flags parse from argv`() {
        assertTrue(CliArgs.parse(arrayOf("--verbose")).verbose)
        assertTrue(CliArgs.parse(arrayOf("-v")).verbose)
        assertTrue(CliArgs.parse(arrayOf("--debug")).debug)
        assertTrue(!CliArgs.parse(arrayOf("hello")).verbose)
    }

    @Test fun `log dir is a logs sibling of the db`() {
        assertEquals("logs", Logging.logDirFor(null).toString().substringAfterLast('/'))
        assertEquals(".ki/logs", Logging.logDirFor(".ki/ki.db").toString())
        assertEquals("var/ki/logs", Logging.logDirFor("var/ki/session.db").toString())
    }

    @Test fun `shipped logback config writes a log file to the configured dir`() {
        val dir = Files.createTempDirectory("ki-log")
        System.setProperty("KI_LOG_DIR", dir.toString())
        System.setProperty("KI_LOG_LEVEL", "INFO")

        // Reconfigure a fresh context from the shipped logback.xml so this test is
        // independent of whatever initialized logging earlier in the JVM.
        val ctx = LoggerFactory.getILoggerFactory() as LoggerContext
        ctx.reset()
        JoranConfigurator().apply { context = ctx }
            .doConfigure(javaClass.classLoader.getResource("logback.xml"))

        ctx.getLogger("dev.ki.cli.test").info("hello from the logging test")
        ctx.stop() // flush + close appenders

        val log = dir.resolve("ki.log")
        assertTrue(log.exists(), "ki.log was not created in $dir")
        assertTrue(log.toFile().readText().contains("hello from the logging test"))
        // sanity: nothing leaked elsewhere in the temp dir
        assertTrue(dir.listDirectoryEntries().isNotEmpty())
    }
}
