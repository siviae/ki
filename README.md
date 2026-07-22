# ki

JVM agent harness, heavily inspired by [pi.dev](https://github.com/earendil-works/pi), based on the [Koog](https://github.com/JetBrains/koog) framework and Kotlin Scripting.

## Goal

Reimplement pi's architecture on the JVM in Kotlin, as three layered components:

1. **Agent runtime** (`ki-agent`) — the unified LLM API (`dev.ki.ai`: single abstraction over providers, LiteLLM first) plus tool calling and state management on top of it. Tools are authored as Kotlin scripts, compiled automatically on startup.
2. **Native TUI framework** (`ki-tui`) — a dependency-free terminal UI with differential rendering and synchronized output, a Kotlin port of pi's [`packages/tui`](https://github.com/earendil-works/pi/tree/main/packages/tui).
3. **Interactive coding agent CLI** (`ki-cli`) — TUI front-end built on `ki-tui`, using the agent runtime.

A fourth module, **`ki-spring`**, is the optional Spring/Postgres reference implementation of the distributed session store + coordination seams (not on the CLI's classpath).

## Stack

- Kotlin, built with [Koog](https://github.com/JetBrains/koog)
- Kotlin scripting for tools (compiled on startup)
- Own `ki-tui` framework for the TUI (differential rendering, raw mode via `stty`)
- LiteLLM as the LLM backend

## Requirements

- **JDK 21.** The build uses Gradle 8.14.3, which does not support running on JDK 25.
  Point `JAVA_HOME` at a JDK ≤ 24 for every Gradle invocation:

  ```bash
  export JAVA_HOME="$HOME/.sdkman/candidates/java/21.0.3-jbr"   # adjust to your JDK 21
  ```

- A **LiteLLM** proxy (OpenAI-compatible) for real model calls. Without one, the
  agent still runs but every prompt returns a connection error.

## Build & test

```bash
./gradlew build     # compiles all modules, runs unit tests
```

Live integration tests self-skip unless `KI_IT=1` and a reachable LiteLLM endpoint
are set, so the offline build stays green.

## Run

The CLI is an interactive TUI; it needs a real terminal (raw mode via `stty`).
Enter sends a prompt, **Ctrl-Q** quits.

Configure the backend (all optional; sensible localhost defaults apply):

```bash
export LITELLM_BASE_URL=http://localhost:4000   # your LiteLLM proxy (default)
export LITELLM_API_KEY=sk-...                    # if the proxy requires a key
export KI_MODEL=gpt-4o                            # default model id
```

Then launch via the installed launcher (recommended — a clean TTY):

```bash
./gradlew :ki-cli:installDist
./ki-cli/build/install/ki-cli/bin/ki-cli
```

`./gradlew :ki-cli:run` also works, but Gradle's output pipe can garble the TUI
redraw; prefer the launcher above if it looks wrong.

Tool scripts (`*.ki.kts`) are extracted to `.ki/tools/` and compiled on first run.
