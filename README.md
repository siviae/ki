# ki

JVM agent harness, heavily inspired by [pi.dev](https://github.com/earendil-works/pi), based on the [Koog](https://github.com/JetBrains/koog) framework and Kotlin Scripting.

## Goal

Reimplement pi's architecture on the JVM in Kotlin, as three layered components:

1. **Unified LLM API** (`ki-ai`) — single abstraction over LLM providers. Initially targets [LiteLLM](https://github.com/BerriAI/litellm) only.
2. **Agent runtime** (`ki-agent`) — tool calling and state management, built on the LLM API. Tools are authored as Kotlin scripts, compiled automatically on startup.
3. **Interactive coding agent CLI** (`ki-cli`) — TUI front-end built on [casciian](https://github.com/crramirez/casciian), using the agent runtime.

## Stack

- Kotlin, built with [Koog](https://github.com/JetBrains/koog)
- Kotlin scripting for tools (compiled on startup)
- casciian for the TUI
- LiteLLM as the LLM backend
