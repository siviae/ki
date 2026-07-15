# ki — Build Plan

JVM port of [pi](https://github.com/earendil-works/pi), an interactive coding agent,
reimplemented in Kotlin on [koog](https://github.com/JetBrains/koog). Three layered
modules:

- **ki-ai** — unified LLM API over providers (LiteLLM first), model catalog.
- **ki-agent** — tool-calling agent runtime; tools authored as Kotlin scripts,
  compiled on startup.
- **ki-cli** — interactive TUI coding agent built on
  [casciian](https://github.com/crramirez/casciian), driving ki-agent.

This document is the roadmap. **M1 is complete**; M2+ are the plan for continuing
the build locally. Each milestone lists a goal, the concrete deliverables, the
modules touched, and acceptance criteria you can check against.

---

## Conventions

- **Package root:** `dev.ki.*` (`dev.ki.ai`, `dev.ki.agent`, `dev.ki.cli`).
- **Build:** Gradle (Kotlin DSL), version catalog in `gradle/libs.versions.toml`.
  Built and tested with Gradle 8.14.3.
- **Tests:** JUnit. Live-wire integration tests self-skip unless `KI_IT=1` is set
  and a LiteLLM endpoint is reachable (see each test's guard).
- **Tool scripts:** `*.ki.kts`, compiled on startup via `ScriptToolLoader`
  (`@KotlinScript` + on-disk `CompiledScriptJarsCache` keyed on source hash).

---

## Milestone status

| # | Milestone | Status |
|---|-----------|--------|
| M1 | End-to-end vertical slice | ✅ Done (commit `6b8a812`) |
| M2 | Core file & shell toolset | ▢ Planned |
| M3 | Config, model catalog & sessions | ▢ Planned |
| M4 | Tool permissions & approval | ▢ Planned |
| M5 | Context & token management | ▢ Planned |
| M6 | TUI: slash commands, cancel, cost | ▢ Planned |
| M7 | Robustness: retries, errors, logging | ▢ Planned |
| M8 | Packaging & distribution | ▢ Planned |

---

## M1 — End-to-end vertical slice  ✅ DONE

**Goal:** prove the whole stack talks end to end — TUI → agent → LLM → tool call →
back — through one real tool.

**Delivered**
- `ki-ai`: `KiLlm` wraps koog `OpenAILLMClient(OpenAIClientSettings(baseUrl))` as a
  `MultiLLMPromptExecutor` pointed at LiteLLM's OpenAI-compatible proxy. `KiModel`
  maps to koog `LLModel` with the `OpenAIEndpoint.Completions` capability so
  arbitrary LiteLLM model ids work. `KiConfig` for base URL / key. `Main.kt` smoke entry.
- `ki-agent`: `KiAgent` on koog `AIAgent`. `ScriptToolLoader` compiles `*.ki.kts`
  on startup with a compiled-jar cache. `ScriptTool` adapts a script to a koog
  `Tool<JsonObject, String>`; `ScriptToolSpec` is the script-facing declaration.
- `ki-cli`: casciian TUI (`KiScreen` — transcript / input / status panes).
  `AgentBridge` marshals the async agent onto the UI thread. `BuiltinTools` wires
  koog `ReadFileTool`. Bundled `grep.ki.kts` script tool.

**Verified:** `gradle build` green; unit tests for the script loader (compile +
cache reuse) and the UI bridge; integration tests (self-skip w/o `KI_IT=1`)
covering streaming, an agent reply, and the full tool-calling loop via a
script-defined tool.

**Known gaps carried forward**
- No committed Gradle wrapper (`gradlew`) — generation was blocked in the build
  environment. **First local step:** run `gradle wrapper --gradle-version 8.14.3`
  and commit `gradlew`, `gradlew.bat`, `gradle/wrapper/`.
- Single builtin tool (read file) + one script tool (grep). Expanded in M2.
- No config file, no persistence, no permissions. Addressed M3/M4.

---

## M2 — Core file & shell toolset

**Goal:** give the agent the tools a coding agent actually needs to edit a repo.

**Modules:** ki-agent (tool impls), ki-cli (wiring + bundled scripts).

**Deliverables**
- Builtin tools (koog `Tool`s in ki-agent or ki-cli `BuiltinTools`):
  - `read_file` (have it), `list_dir` / `glob`, `write_file`, `edit_file`
    (exact-string replace, must-read-before-edit semantics like pi/Claude Code),
    `grep` (promote from script to builtin or keep as script — decide), `bash`
    (run a shell command, capture stdout/stderr/exit, timeout).
- `bash` tool: working-dir aware, configurable timeout, output truncation, never
  interactive (`GIT_TERMINAL_PROMPT=0`-style guards).
- `edit_file` safety: fail if `old_string` not unique / not found; `replace_all` flag.
- Decide the split: which tools ship as compiled Kotlin (`BuiltinTools`) vs. as
  `*.ki.kts` scripts under `ki-cli/src/main/resources/tools/`.

**Acceptance**
- Integration test (KI_IT): agent creates a file, greps it, edits it, reads back
  the edit — full loop through the new tools.
- Unit tests for `edit_file` uniqueness/not-found and `bash` timeout/truncation.

---

## M3 — Config, model catalog & sessions

**Goal:** real configuration and persistence so runs are reproducible and resumable.

**Modules:** ki-ai (model catalog, config), ki-cli (session store, CLI args).

**Deliverables**
- Config file (e.g. `~/.config/ki/config.toml` or `.ki/config.*`): LiteLLM base
  URL, API key (env-override, never logged), default model, request timeouts.
  Precedence: CLI flag > env > config file > default.
- Model catalog in `KiModel`: named entries → LiteLLM model id + capabilities +
  context window; list/select at runtime.
- CLI args (ki-cli `Main`): `--model`, `--config`, working dir, one-shot prompt
  vs. interactive.
- Session persistence: write transcript + tool calls to `.ki/sessions/<id>.jsonl`;
  `--resume <id>` / `--continue` to reload history into the agent.

**Acceptance**
- Start a session, exit, resume — prior turns are in context.
- Switching `--model` changes the model actually sent to LiteLLM (assert in an
  IT or via a stub executor).
- Missing/invalid config yields a clear actionable error, not a stack trace.

---

## M4 — Tool permissions & approval

**Goal:** don't let the agent mutate the machine without oversight — mirror pi /
Claude Code permission modes.

**Modules:** ki-agent (permission gate around tool dispatch), ki-cli (approval UI).

**Deliverables**
- Permission model: per-tool classification (read-only vs. mutating vs. shell),
  modes: `ask` (default), `auto-approve read-only`, `yolo` (approve all), `deny`.
- Approval hook in the agent's tool-dispatch path — a tool call can be paused
  pending a decision.
- ki-cli approval prompt in the TUI (approve once / always-for-this-tool / deny),
  wired through `AgentBridge` without blocking the UI thread.
- Allowlist persisted per session/project (e.g. "always allow `bash: git status`").

**Acceptance**
- With default mode, a `write_file`/`bash` call blocks for approval; deny → the
  tool returns a refusal to the model and the loop continues.
- Auto-approve mode runs read-only tools without prompting.
- Unit test the permission classifier + decision flow with a fake approver.

---

## M5 — Context & token management

**Goal:** long sessions don't blow the context window.

**Modules:** ki-ai (token counting), ki-agent (history strategy).

**Deliverables**
- Token accounting per message/turn (use model context window from the catalog).
- History strategy: trim/summarize older turns when approaching the window;
  koog history-compression strategy if available, else a summarize-to-note step.
- Preserve system prompt + recent turns + tool results within budget.
- Surface usage (tokens in/out, % of window) to the status pane.

**Acceptance**
- A scripted long conversation stays under the window and keeps answering
  coherently (IT).
- Compression preserves the task thread (assert a fact from an early turn is
  still recalled after compaction).

---

## M6 — TUI polish: slash commands, cancel, cost

**Goal:** make the CLI pleasant and interruptible.

**Modules:** ki-cli.

**Deliverables**
- Slash commands: `/help`, `/model`, `/clear`, `/tools`, `/config`, `/resume`,
  `/quit` — dispatched before the prompt reaches the agent.
- Cancellation: interrupt an in-flight agent turn (cancel the coroutine / koog
  run) from the UI without corrupting session state.
- Live streaming render into the transcript pane (token-by-token).
- Status line: model, tokens/window, running cost (from LiteLLM usage or a local
  price table), current tool.
- Keybindings + scrollback in the transcript pane.

**Acceptance**
- Ctrl-C (or bound key) stops generation, returns to prompt, history intact.
- `/model <name>` switches mid-session; `/tools` lists loaded builtins + scripts.

---

## M7 — Robustness: retries, errors, logging

**Goal:** survive flaky networks and bad inputs without dying.

**Modules:** all.

**Deliverables**
- LLM call retries with exponential backoff on transient (5xx / network) errors;
  surface rate-limit (429) with backoff; fail fast on auth (401/403).
- Tool errors are captured and returned to the model as tool results, never crash
  the loop.
- Structured logging (levels, to file under `.ki/logs/`), `--verbose` / `--debug`.
- Graceful handling of malformed tool-call args from the model (validation +
  a corrective message back to the model).

**Acceptance**
- Injected transient failure → retried then succeeds (unit test with a failing-
  then-ok stub executor).
- A tool that throws yields an error tool-result and the agent recovers.

---

## M8 — Packaging & distribution

**Goal:** a runnable, distributable `ki`.

**Modules:** build, ki-cli.

**Deliverables**
- Commit the Gradle wrapper (see M1 gap).
- `application` plugin on ki-cli → `installDist` / runnable scripts; a `ki`
  launcher.
- Optional: shadow/fat jar; GraalVM native-image experiment for fast startup.
- CI (GitHub Actions): `gradle build` + tests on push; cache Gradle.
- README: install, configure (LiteLLM URL/key), run, author a script tool.

**Acceptance**
- Fresh clone → `./gradlew :ki-cli:installDist` → launcher starts a session.
- CI green on a PR.

---

## Cross-cutting backlog (pick up any time)

- More bundled script tools (`*.ki.kts`): web fetch, apply-patch, run-tests.
- Multi-provider in ki-ai beyond LiteLLM (direct OpenAI/Anthropic) via the same
  `MultiLLMPromptExecutor` seam.
- Tool-call parallelism where safe.
- Prompt/system-instruction templating and per-project `KI.md`-style context file.
- Golden/snapshot tests for TUI rendering.

---

## Getting started locally

```bash
# 1. Generate & commit the wrapper (one-time; blocked in the build env that made M1)
gradle wrapper --gradle-version 8.14.3

# 2. Build & test
./gradlew build

# 3. Run live integration tests against a LiteLLM endpoint
export KI_IT=1
export KI_BASE_URL=http://localhost:4000   # your LiteLLM proxy
export KI_API_KEY=sk-...                    # if your proxy requires one
./gradlew test

# 4. Launch the TUI (once M8 wiring lands; until then use ki-cli's Main)
./gradlew :ki-cli:run
```

Start at **M2**. Each milestone is independently shippable; keep the
integration-test-behind-`KI_IT` discipline so `gradle build` stays green offline.
