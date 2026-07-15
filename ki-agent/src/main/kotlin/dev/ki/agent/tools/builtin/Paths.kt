package dev.ki.agent.tools.builtin

import java.nio.file.Path
import kotlin.io.path.Path

/**
 * Path resolution for the builtin file tools — a trimmed port of pi's
 * `resolveToCwd` (path-utils.ts). Expands a leading `~`, then resolves relative
 * paths against [cwd]; absolute paths pass through. The macOS screenshot-name
 * variants (NFD / narrow-space / curly-quote fallbacks) pi tries are deferred.
 */
fun resolveToCwd(filePath: String, cwd: Path): Path {
    val expanded = if (filePath == "~" || filePath.startsWith("~/")) {
        System.getProperty("user.home") + filePath.substring(1)
    } else {
        filePath
    }
    val p = Path(expanded)
    return (if (p.isAbsolute) p else cwd.resolve(p)).normalize()
}

/** The default working directory for tools: the JVM process cwd. */
fun processCwd(): Path = Path(System.getProperty("user.dir"))
