tool("grep") {
    description = "Search files under a path for a regular-expression pattern. " +
        "Returns matching lines prefixed with file:line."
    param("pattern", "the regex to search for", ParamType.STRING, required = true)
    param("path", "file or directory to search (defaults to '.')", ParamType.STRING, required = false)
    execute { args ->
        val regex = Regex(args.string("pattern"))
        val root = java.io.File(args.stringOrNull("path") ?: ".")
        val files = if (root.isFile) sequenceOf(root) else root.walkTopDown().filter { it.isFile }
        val out = StringBuilder()
        var count = 0
        for (f in files) {
            if (count >= 200) break
            runCatching {
                f.readLines().forEachIndexed { i, line ->
                    if (regex.containsMatchIn(line)) {
                        out.append("${f.path}:${i + 1}: ${line.trim()}\n")
                        count++
                    }
                }
            }
        }
        if (out.isEmpty()) "no matches" else out.toString()
    }
}
