# ki — Build Plan

JVM port of [pi](https://github.com/earendil-works/pi), an interactive coding agent,
reimplemented in Kotlin on [koog](https://github.com/JetBrains/koog). Layered modules:

- **ki-ai** — unified LLM API over providers (LiteLLM first), model catalog.
- **ki-agent** — tool-calling agent runtime; tools authored as Kotlin scripts,
  compiled on startup.
- **ki-tui** — native terminal UI framework (differential rendering, no external
  TUI dependency), a Kotlin port of pi's [`packages/tui`](https://github.com/earendil-works/pi/tree/main/packages/tui).
- **ki-cli** — interactive TUI coding agent built on **ki-tui**, driving ki-agent.

This document is the roadmap. **M1 is complete**; M2+ are the plan for continuing
the build locally. Each milestone lists a goal, the concrete deliverables, the
modules touched, and acceptance criteria you can check against.

---

## Conventions

- **Package root:** `dev.ki.*` (`dev.ki.ai`, `dev.ki.agent`, `dev.ki.tui`, `dev.ki.cli`).
- **Build:** Gradle (Kotlin DSL), version catalog in `gradle/libs.versions.toml`.
  Built and tested with Gradle 8.14.3. **Toolchain: JDK 21** (Gradle 8.14.3 does
  not support running on JDK 25 — use JDK 21 for the wrapper and all builds).
- **Tests:** JUnit. Live-wire integration tests self-skip unless `KI_IT=1` is set
  and a LiteLLM endpoint is reachable (see each test's guard).
- **Tool scripts:** `*.ki.kts`, compiled on startup via `ScriptToolLoader`
  (`@KotlinScript` + on-disk `CompiledScriptJarsCache` keyed on source hash).

---

## Milestone status

| # | Milestone | Status |
|---|-----------|--------|
| M1 | End-to-end vertical slice | ✅ Done (commit `6b8a812`) |
| M2 | Native TUI framework (ki-tui), drop casciian | ▢ Planned |
| M3 | Core file & shell toolset | ▢ Planned |
| M4 | Config, model catalog & sessions | ▢ Planned |
| M5 | Tool permissions & approval | ▢ Planned |
| M6 | Context & token management | ▢ Planned |
| M7 | TUI: slash commands, cancel, cost | ▢ Planned |
| M8 | Robustness: retries, errors, logging | ▢ Planned |
| M9 | Packaging & distribution | ▢ Planned |

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
cache reuse) and the UI bridge; integration tests (self-skip w/o `KI_IT`)
covering streaming, an agent reply, and the full tool-calling loop via a
script-defined tool.

**Known gaps carried forward**
- ~~No committed Gradle wrapper~~ — **resolved locally.** Wrapper generated with
  `gradle wrapper --gradle-version 8.14.3` (under JDK 21); still needs committing
  (see M9). `./gradlew build` is green.
- casciian TUI is being replaced by our own **ki-tui** — see M2.
- Single builtin tool (read file) + one script tool (grep). Expanded in M3.
- No config file, no persistence, no permissions. Addressed M4/M5.

---

## M2 — Native TUI framework (ki-tui), drop casciian

**Goal:** replace the casciian dependency with our own terminal UI framework — a
Kotlin port of pi's `packages/tui`. A differential-rendering, flicker-free TUI we
fully control, then reimplement ki-cli's `KiScreen` on it and remove casciian.

**Why:** casciian is a heavyweight full-screen widget toolkit that doesn't match
pi's model (inline, scrollback-friendly, differential redraw). Owning the layer
lets M7 (streaming, cancel, cost) and the transcript UX match pi exactly.

**Modules:** new `ki-tui` module (package `dev.ki.tui`); `ki-cli` (migrate
`KiScreen`/`AgentBridge` onto ki-tui); build (`settings.gradle.kts`,
`libs.versions.toml`, `ki-cli/build.gradle.kts`).

### Decision: terminal driver (record it, don't hand-wave)

The JVM has no built-in raw-mode API. Default to the **`stty` subprocess** path
(`stty raw -echo` against `/dev/tty` via `ProcessBuilder`, restore on exit) — it
adds **zero new dependencies** and is already proven in this stack (casciian
itself shelled to `stty` in the M1 run log). Read raw bytes from `System.in`,
write ANSI to `System.out`. Keep it behind a `Terminal` interface so JLine or a
JNA/JNI raw-mode backend can be swapped in later without touching components.
Explicitly **not** adopting JLine now — trading casciian for JLine would not be
"our own TUI."

### Deliverables

- **`Terminal` interface + `ProcessTerminal`** (port of `terminal.ts`): raw mode
  on/off, `columns`/`rows` (+ SIGWINCH-equivalent resize via a size poll or JNI
  `ioctl`), `write`, cursor `moveBy`/`hide`/`show`, `clearLine`/`clearFromCursor`/
  `clearScreen`, bracketed-paste enable (`\x1b[?2004h`), `setTitle`. Restore
  terminal state on stop and on JVM shutdown hook.
- **Differential renderer — the spine** (`TUI` class, port of `tui.ts`): component
  tree → `List<String>` lines per width; the three-strategy diff (full redraw /
  append-only / in-place line update), all writes wrapped in **synchronized output
  CSI 2026** (`\x1b[?2026h` … `\x1b[?2026l`) for atomic, flicker-free frames.
  Scrollback-aware viewport tracking; coalesced `requestRender()` on a frame timer.
- **`Component` interface:** `render(width: Int): List<String>`, optional
  `handleInput(data: String)`, `invalidate()`. Contract: each returned line's
  visible width must not exceed `width`; TUI resets SGR + OSC-8 per line.
- **Core components** (port `components/`): `Container`, `Text`, `TruncatedText`,
  `Spacer`, `Box`, `Input` (single-line), `Editor` (multi-line submit), `Loader`
  (spinner) — enough to rebuild the transcript / input / status layout.
- **Input pipeline:** `StdinBuffer` (split batched input into single sequences,
  detect bracketed paste), `keys` (`parseKey`/`matchesKey`, modifier decoding),
  configurable `Keybindings`.
- **Text/width utilities** (port `utils.ts`): ANSI-aware `visibleWidth` backed by
  a **wcwidth** table (CJK / emoji / combining marks), grapheme segmentation via
  `java.text.BreakIterator` (ICU4J only if BreakIterator proves insufficient),
  `truncateToWidth`, `wrapTextWithAnsi`, `sliceByColumn`.
- **Editor essentials:** cursor + word navigation, `UndoStack`, emacs-style
  `KillRing` — the minimum for a usable multi-line prompt editor.
- **Migrate ki-cli:** reimplement `KiScreen` (transcript / input / status panes)
  and `AgentBridge` on ki-tui; delete the casciian usage. **Remove casciian from
  `libs.versions.toml` and `ki-cli/build.gradle.kts`.**

### Deferred / backlog (explicitly out of scope for M2 to keep it shippable)

- Inline images (Kitty / iTerm2 graphics protocols), `Image` component.
- Full Kitty keyboard-protocol negotiation + key-release/repeat events
  (fall back to standard escape-sequence parsing).
- Autocomplete + fuzzy matching (file paths / slash commands) — overlaps M7.
- `Markdown`, `SelectList`, `SettingsList` components.
- Overlays / modals, IME hardware-cursor positioning (`CURSOR_MARKER`).
- Native modifier bindings (pi's `.node` C shims) — Kotlin has no equivalent need.

### Acceptance

- **casciian is gone:** `grep -ri casciian .` returns only history/plan; it is
  absent from `libs.versions.toml` and `ki-cli/build.gradle.kts`. `./gradlew build`
  green.
- **ki-cli boots on ki-tui:** launcher starts, renders transcript / input / status
  panes, accepts a typed prompt and Ctrl-Q to quit — verified the same way the M1
  casciian boot was (run the launcher under a pty, confirm the rendered UI).
- **Unit tests:** width/`truncateToWidth`/`wrapTextWithAnsi` (incl. CJK/emoji), the
  diff renderer against golden line buffers (full vs. append vs. in-place update),
  key parsing / `matchesKey`, and the editor ops (insert / word-move / undo / kill).

---

## M3 — Core file & shell toolset

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

## M4 — Config, model catalog & sessions

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

## M5 — Tool permissions & approval

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

## M6 — Context & token management

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

## M7 — TUI polish: slash commands, cancel, cost

**Goal:** make the CLI pleasant and interruptible.

**Modules:** ki-cli, ki-tui.

**Deliverables**
- Slash commands: `/help`, `/model`, `/clear`, `/tools`, `/config`, `/resume`,
  `/quit` — dispatched before the prompt reaches the agent. (Autocomplete + fuzzy
  matching for slash commands / file paths, deferred from M2, lands here.)
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

## M8 — Robustness: retries, errors, logging

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

## M9 — Packaging & distribution

**Goal:** a runnable, distributable `ki`.

**Modules:** build, ki-cli.

**Deliverables**
- Commit the Gradle wrapper (generated locally under JDK 21; see M1 note).
- `application` plugin on ki-cli → `installDist` / runnable scripts; a `ki`
  launcher.
- Optional: shadow/fat jar; GraalVM native-image experiment for fast startup.
- CI (GitHub Actions): `gradle build` + tests on push (JDK 21); cache Gradle.
- README: install, configure (LiteLLM URL/key), run, author a script tool.

**Acceptance**
- Fresh clone → `./gradlew :ki-cli:installDist` → launcher starts a session.
- CI green on a PR.

---

## Cross-cutting backlog (pick up any time)

- More bundled script tools (`*.ki.kts`): web fetch, apply-patch, run-tests.
- ki-tui components deferred from M2: inline images (Kitty/iTerm2), `Markdown`,
  `SelectList`, overlays/modals, full Kitty keyboard protocol, IME cursor.
- Multi-provider in ki-ai beyond LiteLLM (direct OpenAI/Anthropic) via the same
  `MultiLLMPromptExecutor` seam.
- Tool-call parallelism where safe.
- Prompt/system-instruction templating and per-project `KI.md`-style context file.
- Golden/snapshot tests for TUI rendering.

---

## Getting started locally

```bash
# 1. Wrapper is generated (JDK 21). If starting fresh:
#    gradle wrapper --gradle-version 8.14.3   # run under JDK 21
export JAVA_HOME="$HOME/.sdkman/candidates/java/21.0.3-jbr"   # Gradle 8.14.3 needs JDK <= 24

# 2. Build & test
./gradlew build

# 3. Run live integration tests against a LiteLLM endpoint
export KI_IT=1
export LITELLM_BASE_URL=http://localhost:4000   # your LiteLLM proxy
export LITELLM_API_KEY=sk-...                    # if your proxy requires one
./gradlew test

# 4. Launch the TUI
./gradlew :ki-cli:run
# ...or via the installed launcher:
./gradlew :ki-cli:installDist && ki-cli/build/install/ki-cli/bin/ki-cli
```

Start at **M2** (native TUI). Each milestone is independently shippable; keep the
integration-test-behind-`KI_IT` discipline so `gradle build` stays green offline.
