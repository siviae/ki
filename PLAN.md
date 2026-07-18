# ki — Build Plan

JVM port of [pi](https://github.com/earendil-works/pi), an interactive coding agent,
reimplemented in Kotlin on [koog](https://github.com/JetBrains/koog). Layered modules:

- **ki-ai** — unified LLM API over providers (LiteLLM first), model catalog.
- **ki-agent** — tool-calling agent runtime; tools authored as Kotlin scripts,
  compiled on startup.
- **ki-tui** — native terminal UI framework (differential rendering, no external
  TUI dependency), a Kotlin port of pi's [`packages/tui`](https://github.com/earendil-works/pi/tree/main/packages/tui).
- **ki-cli** — interactive TUI coding agent built on **ki-tui**, driving ki-agent.

This document is the roadmap. **M1–M3 are complete**; M4+ are the plan for
continuing the build locally. Each milestone lists a goal, the concrete
deliverables, the modules touched, and acceptance criteria you can check against.

---

## Conventions

- **Package root:** `dev.ki.*` (`dev.ki.ai`, `dev.ki.agent`, `dev.ki.tui`,
  `dev.ki.cli`; M4 added `dev.ki.store` (SPI, in ki-agent), `dev.ki.cli.store`
  (SQLite impl), `dev.ki.store.spring` (JdbcTemplate impl)).
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
| M2 | Native TUI framework (ki-tui), drop casciian | ✅ Done (commit `e4d7b3a`) |
| M3 | Core file & shell toolset | ✅ Done |
| M4 | Config, model catalog, sessions & context | ✅ Done |
| M5 | Tool permissions & approval | ▢ Deferred (yolo mode for now) |
| M6 | Context & token management | ✅ Done |
| M7 | TUI: slash commands, cancel, cost | ✅ Done (streaming deferred) |
| M8 | Robustness: retry, tool-errors, process-kill, logging | ✅ Done (checkpoints split to M9) |
| M9 | Persistence: checkpoints, crash recovery & resume | ✅ Done |
| M10 | Distributed multi-node ki (Spring/Postgres) | ▢ Planned |
| M11 | RocketChat bot reference implementation | ▢ Planned |
| M12 | Packaging & distribution | ▢ Planned (was M9) |
| M13 | Live streaming & interactive TUI | ▢ Planned (was M10) |
| M14 | Tool suite completion | ▢ Planned (was M11) |
| M15 | Rich rendering & multi-provider | ▢ Planned (was M12) |
| M16 | Integration & snapshot testing | ▢ Planned (was M13) |

M8+ are the **most-reasonable reorganization of every deferred/backlog item** carried
out of M1–M7 (each milestone below cites the milestone it inherits work from). M5
(permissions) is intentionally skipped for now and picked up later.

**Renumber note:** the old **M8b** (agent-persistence checkpoints) is now **M9**, and
**two new milestones were inserted** — **M10** (distributed multi-node) and **M11**
(RocketChat bot). So former M9–M13 each shift **up by 3** to M12–M16. The insert order
follows the dependency chain: **resume/checkpoints (M9) → distributed failover (M10) →
RocketChat bot (M11)** — the bot is a consumer of the distributed session layer.

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
- ~~casciian TUI is being replaced by our own **ki-tui**~~ — **done in M2.**
- Single builtin tool (read file) + one script tool (grep). Expanded in M3.
- No config file, no persistence, no permissions. Addressed M4/M5.

---

## M2 — Native TUI framework (ki-tui), drop casciian  ✅ DONE

**Goal:** replace the casciian dependency with our own terminal UI framework — a
Kotlin port of pi's `packages/tui`. A differential-rendering, flicker-free TUI we
fully control, then reimplement ki-cli's `KiScreen` on it and remove casciian.

### Delivered (commit `e4d7b3a`)

- New **`ki-tui`** module (`dev.ki.tui`, JDK 21 toolchain, `kotlin("test")` +
  JUnit Platform). Zero runtime deps.
- `Ansi` (CSI/SGR/OSC-8 constants incl. `SEGMENT_RESET`, sync-output CSI 2026),
  `Width` (wcwidth table — CJK/emoji width 2, combining 0, tab 3; ANSI-aware
  `visibleWidth`, `truncateToWidth` with pi reset-wrapping + pad, `wrapText`),
  `Keys` (`enum Key`, `parse`/`matchesKey`/`isPrintable`, `splitInput`,
  bracketed-paste), `Terminal`/`ProcessTerminal` (raw mode via `stty` subprocess
  against `/dev/tty`, resize poll, shutdown-hook restore), `Component`/`Container`/
  `Text`/`Spacer`, `Editor` (cursor + word nav, single-slot kill ring, undo,
  char-wrap render with inverted cursor cell), and `Tui` — the differential
  renderer (single UI thread, coalesced `requestRender`, first/full/append/in-place
  strategies, per-line `applyLineResets`, viewport scroll tracking).
- **ki-cli migrated:** `KiScreen` (transcript / editor / inverted status line) and
  `AgentBridge` reimplemented on `Tui`; Ctrl-C/Ctrl-Q quit. casciian removed from
  `libs.versions.toml` and `ki-cli/build.gradle.kts`.

**Verified:** 71 ki-tui tests green (faithfully **ported from pi's own suite** —
`Width`, `Keys`, `StdinBuffer`, `Editor`, TUI render via a ported Kotlin
`VirtualTerminal` VT-grid harness — plus ki-specific + `DispatchTest` regressions);
`./gradlew build` green; live pty submit round-trip confirmed. Ports exposed and
fixed real ki bugs (tab width 0→3, `truncateToWidth` semantics, missing per-line
resets, `splitInput` per-codepoint, `clearOnShrink` default).

**Deferred to backlog (as planned):** inline images, full Kitty keyboard protocol,
autocomplete/fuzzy, `Markdown`/`SelectList`, overlays/modals, IME cursor. One pi
test (484, "umlauts across line breaks") relies on active Kitty protocol
(LF→shift+enter); adapted to Shift+Enter since Kitty is deferred — a bare LF is
Enter/submit, matching pi's kitty-inactive behaviour.

<details><summary>Original M2 plan (kept for the record)</summary>

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

</details>

---

## M3 — Core file & shell toolset  ✅ DONE

**Goal:** give the agent the tools a coding agent actually needs to edit a repo —
ported from pi's `packages/coding-agent/src/core/tools` (bash, read, write, edit, ls,
truncate), so tool behaviour and error strings match pi.

**Modules:** ki-agent (`dev.ki.agent.tools.builtin` — tool impls + tests), ki-cli
(wiring), build (NuProcess dep).

### Decision: subprocess management + tool authoring

- **NuProcess** (`com.zaxxer:nuprocess`) drives `bash` — async, no reactor-thread
  per process, callbacks on a pump thread. **Pinned to 3.0.0**: 4.0.0 ships Java 25
  bytecode (major 69) and won't load on the JDK 21 toolchain; 3.0.0 (Java 7) has the
  identical API surface we use.
- **Tool split:** `bash`/`read`/`write`/`ls` are authored with the same declarative
  `tool { }` DSL the `.kts` scripts use and wrapped as `ScriptTool`. `edit` is the
  one structured tool (its `edits[]` is an array of `{oldText,newText}` the scalar
  DSL can't express) — a dedicated `EditTool : Tool<JsonObject,String>` with an
  explicit `ToolParameterType.List(Object(...))` descriptor, decoding args by hand.
  `grep` stays a bundled `*.ki.kts` script; `find` is deferred.

### Delivered

- `BashExec` (NuProcess): merges stdout+stderr, decodes UTF-8 once at the end (so a
  multi-byte char split across read chunks can't corrupt), cwd-aware, optional
  timeout (`waitFor` → `Integer.MIN_VALUE` sentinel → `destroy(true)`), non-zero exit
  and timeout folded into the returned text.
- `read` (offset/limit, `truncateHead` 2000 lines / 50KB, actionable continuation
  notices), `write` (mkdir -p + byte count), `ls` (sorted, dotfiles, `/` suffix,
  500-entry + byte cap), `edit` (exact-text `edits[]`: BOM strip, CRLF
  normalize/restore, per-edit not-found / duplicate / empty / overlap / no-change
  validation, reverse-order apply, all-or-nothing), `Truncate` (head/tail).
- ki-cli wired to `dev.ki.agent.tools.builtin.BuiltinTools.all()` (the old
  koog-`ReadFileTool` stub `BuiltinTools` removed); system prompt updated.

**Verified:** 36 ki-agent tests green (`Truncate`, `FileTools` read/write/ls/bash,
`EditTool` incl. CRLF, wiring) — ported from pi's `tools.test.ts`, adapted since ki
tools return a single string and fold failures into it rather than throwing +
returning a details/diff object. `./gradlew build` green.

**Correction to the original plan:** the earlier draft said `edit` should have
"must-read-before-edit semantics" and a "`replace_all` flag." pi's actual `edit` has
neither — it's an `edits[]` array, each matched against the original, required to be
unique and non-overlapping. Implemented pi's real contract (pi source is
authoritative).

**Deferred to backlog:** fuzzy matching in `edit` (NFKC + smart-quote/dash/space
normalization — ki matches exact text only), image reads, streaming tool-output
updates + full-output-to-temp-file persistence on truncation, process-tree kill
(`destroy` kills the shell; detached grandchildren may survive), shellPath/WSL stdin
transport, the `find` tool, and the KI_IT live agent loop (create→grep→edit→read).

---

## M4 — Config, model catalog, sessions & context loading  ✅ DONE

### Delivered (as built — authoritative; supersedes the planning notes below)

Storage compatibility layer, both deployments, one contract:
- **`SessionStore` SPI lives in `ki-agent`** (`dev.ki.store`) — the agent owns the
  contract. `StoredMessage(seq, role, json)` / `SessionInfo`; `save` is replace
  semantics (koog hands the full history each store). `StoreChatHistoryProvider`
  adapts it to koog's chat-memory `ChatHistoryProvider`; `MessageCodec` is the **only**
  koog-serialization touch point (koog's kotlinx `Json` for the `Message` blob).
- **Local impl `SqliteSessionStore` in `ki-cli`** (`dev.ki.cli.store`) — raw
  `sqlite-jdbc`, two tables (`ki_message`, `ki_session`), `CREATE TABLE IF NOT EXISTS`,
  no ORM/migration framework. **No standalone `ki-store` module** (the earlier plan's
  module was folded away — SPI→agent, impl→cli).
- **Remote reference impl `JdbcTemplateSessionStore` in new `ki-store-spring`**
  (`dev.ki.store.spring`) — same portable DDL over Spring `JdbcTemplate` + a
  `@Configuration` bean; idempotent schema init for drop-in Spring/Postgres use.
- **`KiAgent`** installs koog chat-memory when given a provider; `run(input, sessionId)`
  keys persistence — koog derives `runId` from `sessionId`, so resume = same id.

Config / manifest / context:
- **`ki.toml` manifest (mandatory for CLI)** parsed with **Jackson** (`--config`,
  default `./ki.toml`; missing ⇒ clear error, exit 2). It is the **explicit tool
  allowlist** — builtins enabled by name, script tools by `script` path, per-tool
  config in the entry; nothing auto-loads. `[context].files` prepend to the system
  prompt; `[llm]`, `[db]`, `[models]` catalog. `Bootstrap` resolves effective config
  (CLI > env > manifest > default) and assembles llm + tools + store + provider.
- **CLI args:** `--config/-c`, `--model/-m`, `--db`, `--resume/-r <id>`, `--continue`
  (latest session via `ki_session.updated_at`), one-shot prompt vs. interactive.
- Serializer split (decided): **koog kotlinx Json for the `Message` blob** (koog's
  native contract, opaque `TEXT`), **Jackson for our own types** (manifest/config/
  catalog) to match prod.

**Verified:** `./gradlew build` green **offline**, 136 tests, 0 failures. Highlights —
(a) **resume integration test** (two runs, one `SessionStore`, same `sessionId`)
proves koog's chat-memory merge reloads history, does **not** drop the new turn, and
does **not** duplicate the system prompt; (b) **codec gate** round-trips a full
tool-loop transcript (`Tool.Call` + `Tool.Result` parts survive); (c) SQLite +
JdbcTemplate stores both pass replace/list-ordering; (d) shipped `ki.toml` boots
end-to-end (compiles the grep script, opens the store, 6 tools); (e) **isolation as
acceptance** — `:ki-cli:dependencies` runtimeClasspath has **no `spring*`, no
`org.postgresql`** (sqlite + jackson only).

**Corrections vs. the planning notes:** SPI moved from a `ki-store` module into
`ki-agent`; SQLite impl into `ki-cli`; the pgmicro/`tech.turso`/Liquibase gate stack
is gone (moot — schema is one portable table, not a shared driver); added the
`ki_session` table so `--continue` has recency ordering; Jackson chosen for our types.

**Deferred:** per-tool `settings` from the manifest are parsed and carried but **not
yet injected** into script tools (scripts still read env) — wire an injected `config`
handle when the first API-wrapping tool needs it; koog `Persistence` checkpoints
(M8); real preprocessor ordering (M6); live remote-Postgres IT behind `KI_IT`.

<details><summary>Original M4 plan (kept for the record)</summary>

**Goal:** real configuration, persistence, and context so runs are reproducible and
resumable — and so ki fits **both** of its target deployments from one codebase:

- **Local / lightweight** — a single binary, no external services. Sessions persist
  to an **embedded SQLite file** (`.ki/ki.db`) via the minimal `sqlite-jdbc` driver.
  **Zero Spring, zero Postgres on the classpath.**
- **Remote / embedded-in-Spring** — ki runs *inside* a host Spring application and
  persists to that app's **Postgres** via **`JdbcTemplate`**, sharing its
  connection pool and transactions.

The unifying design is a **narrow storage SPI** with two hand-written
implementations — *not* a shared JDBC abstraction layer. Both back koog's history
feature identically; the agent core never knows which backend it's on.

**Modules:** ki-ai (model catalog, config); ki-agent (koog history provider over the
SPI; takes tools + context as constructor inputs — stays config-format-agnostic);
new **`ki-store`** (the SPI + the default SQLite/`sqlite-jdbc` impl — no Spring, no
Postgres); new **`ki-store-spring`** (the `JdbcTemplate`/Postgres impl, pulled in
only by a host Spring app); ki-cli (`ki.toml` manifest parsing → tools + context,
CLI args, resume wiring); build (`org.xerial:sqlite-jdbc`; Spring deps confined to
`ki-store-spring`).

### Decision A — persist via koog's history feature, not JSON files

Unchanged by the storage pivot. Use koog's built-in conversation persistence rather
than writing `.jsonl`.

- **`ChatHistoryProvider` is the session store** (koog *chat-memory* feature):
  `suspend load(conversationId): List<Message>` seeds the next `agent.run()`, and
  `suspend store(conversationId, messages)` saves on successful completion —
  `conversationId` = ki session id. This *is* resume-with-history. Implement a
  single custom `ChatHistoryProvider` that delegates to the storage SPI (below), so
  swapping backends never touches agent code.
  - **How it persists:** koog `Message`s are `@Serializable`; tool interactions ride
    inside them as `MessagePart.Tool.Call` / `MessagePart.Tool.Result` parts (they
    are message *parts*, not separate roles — verified against
    `prompt-model-jvm`). So persistence is: serialize each `Message` with koog's
    JSON and store one row per message — `(conversation_id, seq, role, message_json)`
    — which preserves the full tool-calling transcript without a bespoke schema.
- **Chat-memory preprocessor order is an M6 seam, not M4.** koog runs
  `ChatMemoryPreProcessor`s in sequence and order changes the result (docs' example:
  `windowSize(10)` then filter ≠ filter then `windowSize(10)` — each receives the
  prior's output). M4 wires the pipeline with a **passthrough / large-window**
  default and records the ordering contract; M6 (context/token management) owns the
  real trimming/summarization order.
- **Agent Persistence (checkpoints) is a *secondary* role, not the session store.**
  koog's `Persistence` feature (`PersistenceStorageProvider`,
  `createCheckpointAfterNode` / `rollbackToCheckpoint`) snapshots mid-execution graph
  state for crash-recovery / undo — a different concern from the transcript. Plan it
  as an optional second SPI-backed `PersistenceStorageProvider` with its own tables;
  **default off**, revisit alongside M9 persistence. Do not conflate with history.

### Decision B — storage SPI + two impls (SQLite local / Postgres-Spring remote)

**This replaces the old JDBI + pgmicro plan entirely.** pgmicro is dropped: no
embedded PG-dialect DB, no `tech.turso` native driver, no shared-driver dialect
problem. The user chose *two different, deliberately minimal* access mechanisms, so
the compatibility layer is a **domain interface**, not a mini-ORM.

- **SPI (in `ki-store`, zero DB-framework deps):**
  ```
  interface SessionStore {
      fun load(conversationId: String): List<StoredMessage>      // ordered by seq
      fun append(conversationId: String, messages: List<StoredMessage>)
      fun listSessions(): List<SessionInfo>
  }
  ```
  `StoredMessage` = `(seq, role, messageJson)`. The koog `ChatHistoryProvider`
  adapter maps koog `Message` ⇄ `StoredMessage` and is the only code that touches
  koog serialization — both impls stay ignorant of koog.
- **Local impl — `SqliteSessionStore` (default, in `ki-store`):** raw SQL over
  `org.xerial:sqlite-jdbc`, one `Connection` to `jdbc:sqlite:.ki/ki.db`. No JDBI, no
  Spring, no pool. Schema bootstrapped with `CREATE TABLE IF NOT EXISTS` on first
  open — **no migration framework** for one table.
- **Remote impl — `JdbcTemplateSessionStore` (in `ki-store-spring`):** the same raw
  SQL issued through Spring `JdbcTemplate`, reusing the host app's `DataSource` /
  transaction manager. Exposed as a `@Configuration` / auto-config bean so a host app
  gets it by adding the module. Idempotent `CREATE TABLE IF NOT EXISTS` on init so
  integration is genuinely "drop the dependency in and go"; if a host prefers to own
  the DDL via its own Liquibase/Flyway, ki ships the table contract and the
  create-if-not-exists is a harmless no-op.
- **The schema is one trivial, portable table** — `conversation_id TEXT`,
  `seq INTEGER`, `role TEXT`, `message_json TEXT`, PK `(conversation_id, seq)`. This
  DDL is valid on **both** SQLite and Postgres verbatim; that portability is *why*
  the pivot removes the whole dialect-gate stack.

**Decided:** `ki-store-spring` **ships in-repo** as the separate optional module — a
concrete `JdbcTemplateSessionStore` a host app gets by adding the dependency, so
"seamless Spring integration" is real, not just an SPI contract. The CLI never
depends on it.

### ⚠ Verification gates (only one survives the pivot)

The old gates 1–3 (does `tech.turso` speak PG dialect / native-jar distribution /
does Liquibase know the driver) are **gone** — no shared driver, no native jar, no
migration framework on the local path. Remaining:

1. **History round-trips the full tool loop.** Backend-independent koog-serialization
   risk. Before building on it, serialize a `List<Message>` from a real tool-calling
   turn (Assistant + `Tool.Call` + `Tool.Result`) through the `ChatHistoryProvider`
   adapter and assert every part survives `load`. If koog's history drops tool parts,
   add custom serialization or fall back to the Persistence checkpoint payload for
   the transcript. Verify against `SqliteSessionStore` (the default) first.

### Decision C — a single explicit `ki.toml` manifest (CLI); programmatic config (Spring)

**Decided.** Two configuration paths, deliberately different, meeting at the same
agent constructor:

- **CLI — one mandatory `ki.toml` manifest.** Located via `--config <path>`, default
  `./ki.toml`. **The manifest is required** — no manifest ⇒ a clear startup error
  (not a silent zero-tool run). The earlier `CONTEXT_PATH` env-scan / `PATH`-style
  auto-discovery idea is **dropped** in favor of this one explicit file.
- **Spring — programmatic only.** Inside a host Spring app the agent is built in code:
  tools and their configuration are constructed and passed manually. **No manifest,
  no TOML/YAML loading on the Spring path.** All configuration of the Spring-app
  deployment is **out of scope** for this project — ki-agent exposes the constructor
  seam; the host owns wiring.

**The manifest is an explicit allowlist — every tool the agent may use is listed in
it, with its configuration. Nothing auto-loads.** No directory scan, no implicit
`.ki/tools`, no bundled-tool auto-extract. A tool the agent can call ⇔ a `[tools.*]`
entry in the manifest. This is the M5 permission model's foundation (the allowlist
already lives here) and makes a run fully reproducible from one file.

```toml
[context]
files = ["KI.md", "docs/conventions.md"]   # instruction context, prepended in order

[tools.bash]                               # builtin: listed by name = enabled
[tools.read]
[tools.edit]

[tools.jira]                               # script tool: has a script path + config
script = "tools/jira.ki.kts"
base_url = "https://acme.atlassian.net"
token_env = "JIRA_TOKEN"                   # secret by env reference, never inline
```

- **Builtin tools** (`bash`/`read`/`write`/`edit`/`ls`, `grep` script): available
  only when listed by name; a bare `[tools.<name>]` enables, an optional config block
  tunes it. Not listed ⇒ not registered.
- **Script tools**: a `[tools.<name>]` with a `script = "…"` path (resolved relative
  to the manifest). Its remaining keys are the tool's config, read via an injected
  `config` handle. `ScriptToolLoader` compiles exactly the scripts the manifest
  names — it no longer scans a directory.
- **Secrets never inline.** Config references secrets by env var
  (`token_env = "JIRA_TOKEN"`), never literal keys — the manifest is a committable
  file. Extends the existing "API key via env, never logged" rule.

**Boundary:** manifest parsing lives in **ki-cli** (or a small `dev.ki.config`
piece), which reads `ki.toml` → builds the tool list + context → constructs the
agent. **ki-agent stays config-format-agnostic**: it takes tools and context as
constructor inputs, exactly as the Spring path supplies them programmatically. Same
seam, two producers.

### Other deliverables

- Config file (`~/.config/ki/config.toml` or `.ki/config.*`): LiteLLM base URL, API
  key (env-override, never logged), default model, request timeouts, **storage
  backend** (`sqlite` default + file path | `postgres` handled by the host Spring
  app). Precedence: CLI flag > env > file > default.
- Model catalog in `KiModel`: named entries → LiteLLM model id + capabilities +
  context window; list/select at runtime.
- CLI args (ki-cli `Main`): `--model`, `--config` (path to `ki.toml`, default
  `./ki.toml`, **required to exist**), `--db` (SQLite file path), working dir,
  one-shot prompt vs. interactive, `--resume <id>` / `--continue`.

### Acceptance

- Start a session, exit, resume (`--resume`/`--continue`) — prior turns **and tool
  results** are back in context, read from the SQLite DB.
- **Dependency isolation is verifiable, not just asserted:**
  `./gradlew :ki-cli:dependencies` shows **no `spring*` and no `org.postgresql`** on
  the CLI's runtime classpath. The Spring/Postgres impl lives only in
  `ki-store-spring`, which the CLI never depends on.
- The same `SessionStore` SPI + koog `ChatHistoryProvider` adapter works against both
  impls: `SqliteSessionStore` (unit-tested, default) and `JdbcTemplateSessionStore`
  (integration-tested behind `KI_IT` against a real/embedded Postgres).
- `./gradlew build` stays green **offline** — no test requires a running Postgres; the
  SQLite `.db` is self-contained.
- A `ki.toml` manifest is the sole tool allowlist: a tool listed in it is callable,
  one omitted is not registered; a per-tool config value (incl. an env-referenced
  secret) reaches the tool. Missing manifest ⇒ clear startup error. The Spring path
  builds the identical agent programmatically with no manifest.
- Switching `--model` changes the model actually sent to LiteLLM (stub executor).
- Missing/invalid config or an unreachable DB yields a clear actionable error.

### Deferred / backlog

- koog `Persistence` checkpoints / rollback (Decision A) — wire in M9.
- Real preprocessor ordering (trim/summarize) — M6.
- A pooled local backend (HikariCP) — unneeded for a single-user CLI; add only if a
  local multi-connection use case appears.

</details>

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

## M6 — Context & token management  ✅ DONE

**Goal:** long sessions don't blow the context window.

### Delivered (as built — authoritative)

- **History compression via koog's native strategy.** `KiAgent` builds on
  `singleRunStrategyWithHistoryCompression(HistoryCompressionConfig(isHistoryTooBig,
  FromLastNMessages(keepLastMessages)))`. After a tool step, if the prompt exceeds the
  budget the older turns are summarized into a TL;DR while the **system prompt + first
  user message + the last N messages** are preserved (koog's `composeMessageHistory`).
  `compressHistory=false` ⇒ `NoCompression` (same graph, trigger disabled) so there is
  one construction path.
- **Token accounting.** `KiTokenizer` wraps koog's regex `PromptTokenizer` for the
  **budget trigger** only (it undercounts BPE → biased up by a `SAFETY=1.25` factor);
  `budget = max(256, contextWindow * contextBudgetRatio)` (ratio default 0.7).
- **Context window plumbed end to end.** `KiConfig` carries `contextWindow` /
  `maxOutputTokens`; `Bootstrap` fills them from the `[models.<alias>]` catalog entry;
  `KiLlm.defaultModel` uses them — so the budget reflects the *actual* configured
  model, not a hardcoded 128k. (`KiLlm` gained an `of(executor, model)` factory for
  embedding/tests.)
- **Real usage surfaced.** An `EventHandler` `onLLMCallCompleted` hook reads the LLM's
  own `ResponseMetaInfo` token counts into `KiAgent.lastUsage` (`ContextUsage`:
  tokens / window / percent / reported-vs-estimated); the CLI status line shows
  `— <tok>/<window> tok (<pct>%)` after each turn (`~` prefix when estimated). Trigger
  uses the estimator; display uses reported counts — the two are kept separate.

**Verified:** `./gradlew build` green, 139 tests. The key guard is
`ContextCompressionTest` — a **coexistence** test through the real `KiAgent`: forces
the context over budget (large system prompt), drives a tool call to reach the
compression edge, and asserts (a) the summarize turn fires (3 LLM calls; the
"comprehensive summary of this conversation" prompt is seen), (b) **chat-memory still
persists** the session under the custom strategy, (c) the **system prompt survives**
compaction, (d) usage is captured. Plus `KiTokenizerTest` and a Bootstrap test that
the catalog window reaches `KiLlm`.

**Note / deferred:** compression is **destructive to the persisted transcript** —
`interceptStrategyCompleted` stores the (now compressed) prompt, so resume reloads the
compacted history, not the raw one. That satisfies the acceptance criterion (early
context survives via the TL;DR) but means a full audit trail, if ever needed, belongs
to the M9 checkpoint seam. Coherent-long-conversation + early-fact-recall remain live
IT (behind `KI_IT`) since they're LLM-quality, not deterministic. Chat-memory
preprocessor ordering intentionally left passthrough (redundant once compression
persists the compacted prompt).

<details><summary>Original M6 plan (kept for the record)</summary>

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

</details>

---

## M7 — TUI polish: slash commands, cancel, cost  ✅ DONE (core; streaming/scrollback deferred)

**Goal:** make the CLI pleasant and interruptible.

### Delivered (as built — authoritative)

- **Slash commands** — pure, testable `SlashCommands.dispatch(input, ctx): SlashAction`
  dispatched before a line reaches the agent: `/help`, `/model [name]`, `/tools`,
  `/config`, `/resume`, `/clear`, `/quit` (+ aliases `/q`, `/h`). Case-insensitive.
  Unknown `/x` reported, not sent to the LLM. `/config` never prints the API key.
- **`KiController`** (new) — owns live session state and implements `SlashContext`.
  `/model <name>` **rebuilds** the `KiAgent` against the new model while keeping the
  same `sessionId`, history provider, and usage accumulator, so **history (M4) and the
  running cost total survive the switch**. `/resume` is show-hint only (live re-seed
  deferred — restart with `--resume`/`--continue`).
- **Cancellation** — `AgentBridge` tracks the per-turn `Job`; Esc / Ctrl-C cancel an
  in-flight turn (Ctrl-C also quits when idle; Ctrl-Q always quits). Fixed a real bug:
  the old `catch (Throwable)` swallowed `CancellationException` and posted a spurious
  error — now rethrown; cancel runs UI cleanup itself (`onCancelled`) since the result
  callback won't fire on a dead coroutine. The store stays clean (koog's
  `interceptStrategyCompleted` never fires on cancel).
- **Status line** — model · context usage (`~tokens/window (pct%)`, from M6) · running
  cost (`$0.0000`) · current tool. Running cost = `Pricing` (small local per-1M-token
  table, labeled an estimate; unknown model ⇒ cost omitted, not faked) over the shared
  `UsageAccumulator` (cumulative input/output tokens captured in
  `EventHandler.onLLMCallCompleted`). Current tool via `onToolCallStarting/Completed`.

**Verified:** `./gradlew build` green, 152 tests. New: `SlashCommandsTest` (dispatch
matrix), `AgentBridgeTest` cancel cases (result callback does **not** fire on cancel;
no-op when idle), `PricingTest`, `KiControllerTest` (model switch rebuilds + keeps
tools; `/config` omits the key). Existing `AgentBridgeTest` marshaling tests still
green (change was additive).

**Deferred (called out, not buried):**
- **Live token streaming** into the transcript — needs a *streaming* strategy node that
  must also carry the tool loop **and** the M6 compression, which would re-open the
  chat-memory/compression coexistence just settled. A focused pass on its own; the
  transcript updates per-turn today.
- **Scrollback** — `Tui` auto-scrolls to the bottom with no user-scroll API yet; needs
  a small ki-tui viewport-offset addition + PageUp/PageDown binding.
- **Autocomplete / fuzzy** for slash commands & file paths (polish on top of dispatch).

<details><summary>Original M7 plan (kept for the record)</summary>

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

</details>

---

## M8 — Robustness: retry, tool-errors, process-kill, logging ✅ DONE

**Goal:** survive flaky networks, bad inputs, and runaway subprocesses without dying
or corrupting a session. *(Existing M8 + process-tree kill carried from M3.
Agent-persistence checkpoints split out to M9 as their own pass.)*

**Delivered**
- **LLM-call retry with exponential backoff** (`ki-ai` `RetryingPromptExecutor`,
  commit `3101705`). Wraps the `PromptExecutor`; retries transient failures — 5xx,
  408/425, rate-limit (429), and bare network I/O — with full-jitter backoff; fails
  fast on auth/bad-request (401/403/400/404/422). Classifier walks the cause chain for
  koog's `KoogHttpClientException(statusCode)`. Only the non-streaming `execute`
  paths retry (a streaming `Flow` has no safe restart point → deferred to M13).
- **Tool-error recovery** (test-pinned, commit `d7a683d`). Verified koog's
  `GenericAgentEnvironment` already converts a thrown tool exception, an unknown tool,
  and bad-args into an error tool-result instead of crashing the loop — so no
  redundant try/catch was added; `ToolErrorRecoveryTest` pins the contract.
- **Process-tree kill for `bash`** *(M3 deferral)* (`ki-agent` `BashExec.killTree`,
  commit `d56bc22`). `NuProcess.destroy` targets only the shell pid, so a `cmd &`
  grandchild reparents to init and survives; `killTree` snapshots the descendants
  *before* destroying the shell and force-kills each. `ProcessHandle` over `setsid`
  (portable on darwin, no external tool).
- **Structured file logging** (`ki-cli`, commit `240bdcc`). kotlin-logging facade +
  logback backend (ki-cli only); rolling file under `.ki/logs/`, never the console
  (the TUI owns the screen). `--debug` → DEBUG, `--verbose` → INFO, else WARN, via
  `KI_LOG_LEVEL`/`KI_LOG_DIR` system properties set before logback init.

**Verified**
- Injected 500 retried then succeeds; 401 fails fast without retry; retries bounded by
  `maxAttempts`; network `IOException` treated transient (`RetryingPromptExecutorTest`).
- A tool that throws yields an error tool-result on the next-turn prompt and the agent
  recovers to a final answer (`ToolErrorRecoveryTest`).
- A `bash` command holding a backgrounded child is fully reaped on timeout — the
  child's pid is dead (`BashExecTest`; survives without the fix).
- The shipped `logback.xml` writes a file to the configured dir; flag→level mapping and
  logs-sibling-of-db resolution (`LoggingTest`).

**Corrections vs. the original plan**
- Retry decorator assumed exceptions propagate — *verified*: koog throws
  `KoogHttpClientException` carrying `statusCode`; no `Retry-After` header survives, so
  429 backs off exponentially rather than by server hint.
- Tool-error handling turned out to be framework-native (koog), so this became a
  test-pin, not new code.
- Cancel-path reaping (vs. timeout) runs through AgentBridge coroutine-cancel, not
  `BashExec`; only the timeout path is wired/tested here (cancel → M13).

<details><summary>Original plan (superseded)</summary>

Deliverables: retry/backoff; tool-error capture + malformed-args→corrective message;
process-tree kill (proposed `setsid`); agent-persistence checkpoints; structured
logging. Acceptance included "with checkpoints on, killing the process mid-run and
restarting recovers the run" — moved to M9.
</details>

---

## M9 — Persistence: checkpoints, crash recovery & resume

**Goal:** opt-in mid-run snapshots so a killed process can resume from where it left
off, a **live in-session resume** the M4 transcript-reload didn't cover, and the **raw
transcript** that M6 compaction overwrites in history preserved for audit. *(Was M8b.
M4 Decision A "secondary role"; M6 flagged the compaction gap; M7 left `/resume`
hint-only.)* **This milestone is the local/single-node foundation the distributed M10
builds on** — the checkpoint SPI it lands is exactly what M10 fails over across nodes.

**Modules:** ki-agent (install `Persistence`, checkpoint SPI seam), ki-cli
(SQLite checkpoint store + `/resume` re-seed), ki-store-spring (JdbcTemplate checkpoint
store), build.

### Delivered (checkpoint spine — as built)

- **`CheckpointStore` SPI + `StoredCheckpoint`** in ki-agent (`dev.ki.store`), mirroring
  the M4 `SessionStore` pattern; **`CheckpointCodec`** (koog `AgentCheckpointData` ⇄ JSON
  via koog's `PersistenceUtils.defaultCheckpointJson`) is the checkpoint counterpart to
  `MessageCodec` — the only koog-checkpoint-serialization touch point; **`StoreCheckpointProvider`**
  adapts the SPI to koog's `PersistenceStorageProvider<AgentCheckpointPredicateFilter>`.
- **`KiAgent`** installs `Persistence` (`enableAutomaticPersistence`) when a
  `checkpointProvider` is supplied, **default off**. Recovery is automatic: koog's
  `rollbackToLatestCheckpoint` on strategy start restores an interrupted run; a clean run
  writes a **tombstone** whose `toAgentContextData()` is null → no-op restore, so the
  happy path is untouched and chat-memory handles history.
- **`SqliteCheckpointStore`** (ki-cli) over the **same** SQLite connection as
  `SqliteSessionStore` (shared write monitor + `busy_timeout=5000` — a 2nd connection
  would contend, since checkpoints write per node); **`JdbcTemplateCheckpointStore`** +
  bean (ki-store-spring). `[db].checkpoints = true` opts in via the manifest.
- **Verified:** `CheckpointRecoveryTest` — **two agent instances** sharing one store +
  session id (offline stand-in for kill-and-restart, and the M10 takeover primitive):
  instance A calls a tool then crashes; instance B resumes and finishes; asserts the
  pre-crash tool call + result appear in B's prompt **exactly once** (no ChatMemory ↔
  checkpoint duplication), and the non-tombstone→tombstone lifecycle. Plus
  `SqliteCheckpointStoreTest` (round-trip / version-ordering / tombstone-in-latest /
  upsert / delete / shared-connection coexistence). `./gradlew build` green, 171 tests.

### Delivered (live `/resume` re-seed — second increment)

- **`/resume [id]`** switches the active session **in place**: `KiController` holds a
  mutable `activeSessionId` (the key handed to `agent.run`); swapping it makes koog's
  chat-memory reload that conversation's history on the next turn — **no agent rebuild**,
  no restart. `/resume` with no id lists resumable sessions (newest first, msg counts,
  current marked); an unknown id is rejected without switching. `SlashAction.Resume(id?)`
  keeps the dispatcher pure; `configSummary` reflects the active id.
- **Verified:** `KiControllerTest` (list / switch-in-place / unknown-id-no-switch) +
  `SlashCommandsTest` resume dispatch. `./gradlew build` green, **175 tests**.

**M9 complete.** Transcript catch-up on resume (re-rendering prior turns into the TUI
scrollback, distinct from the model-context re-seed done here) is a small M13 UI follow-up.

### What M4 already ships vs. the delta here

M4's `--resume`/`--continue` already **reload the transcript** from the message store
into a *fresh* run. Don't re-describe that. The delta M9 owns:
- **Checkpoint-based resume of an *interrupted* run** — M4 resumes a *completed*
  session's history; a session killed **mid-turn** (mid tool-loop) has no clean final
  store write. Checkpoints capture graph state at node boundaries so an interrupted run
  restarts at the last node, not the last completed turn.
- **Live in-session `/resume`** — M7 shipped `/resume` as a hint only (restart with the
  flag). Wire it to actually re-seed the live `KiController` session in place.

### koog machinery (verified against `agents-features-snapshot` 1.0.0-preview7)

- `Persistence` feature; `PersistenceFeatureConfig.enableAutomaticPersistence` (default
  **true**) checkpoints **after each graph node** via `createCheckpointAfterNode`.
- `AgentCheckpointData` is a plain `@Serializable` (kotlinx) data class — `checkpointId`,
  `createdAt`, `messageHistory: List<Message>`, `nodePath` (graph position),
  `storage` (`AIAgentStorage` as JSON), `agentIterations`, `llmModel`/`tools`. It is a
  **self-contained JSON blob**, so persisting it is the same opaque-blob discipline as
  `MessageCodec` — and, because it carries the full graph position + history, it can be
  deserialized and resumed in a **different process** (the crux M10 depends on; see M10).
- **SPI is `PersistenceStorageProvider<Filter>`** — suspend `saveCheckpoint(sessionId,
  data)`, `getLatestCheckpoint(sessionId)`, `getCheckpoints(sessionId)`. Implement it
  over the store, keyed by `sessionId` — mirrors the M4 `SessionStore` two-impl pattern.
- Resume entry points: `persistence.rollbackToLatestCheckpoint(ctx)` and
  `runFromCheckpoint(session, input, checkpoint, rollbackStrategy)` — the latter resumes
  a checkpoint **with new input**, which is also the M10 steering seam (noted there).

**Deliverables**
- A `PersistenceStorageProvider` backed by a new `ki_checkpoint` table (SQLite locally,
  JdbcTemplate remotely), sibling to the M4 message store; blob serialized with koog Json.
- `install(Persistence)` in `KiAgent`, **default off** (opt-in via config); choose
  continuous (`enableAutomaticPersistence`) vs. on-demand snapshots.
- Recovery: on restart with checkpoints on, load the latest checkpoint and roll the
  agent context back to it, then continue.
- Live `/resume` re-seed in `KiController` (finish the M7 deferral).
- Preserve the pre-compaction raw transcript for audit/rollback (M6 gap) — the checkpoint
  `messageHistory` is the raw record even after history-store compaction overwrites the
  session rows.

**Acceptance**
- With checkpoints on, killing the process mid-run and restarting **recovers the run**
  (fork-a-process integration test) — restarts at the last node, not the last turn.
- Raw transcript survives an M6 compaction (assert an early message recoverable from the
  checkpoint blob after the session store was compacted).
- `/resume` re-seeds a live session without a restart.

**Caveat (carried into M10):** checkpoints snapshot at node boundaries, so a
side-effecting tool (`bash`, `write`) that ran **after** the last checkpoint but before
the crash **re-runs** on recovery — recovery is at-least-once, not exactly-once. Flag
idempotency; do not imply clean exactly-once resume.

---

## M10 — Distributed multi-node ki (Spring/Postgres)

**Goal:** run ki as a **distributed, multi-node service** where each node runs its own
ki instance against one shared Postgres. A session runs to completion on the node that
owns it; if that node **fails mid-session**, another node picks it up from the last M9
checkpoint. **Steering** messages can be written to the DB by *any* node and are picked
up and fed to the model by the node currently working the session. *(This is the
"bigger" persistence feature; distributed deployment is **Spring + Postgres only** —
never the local SQLite/CLI path.)*

**Modules:** ki-agent (session-ownership + steering-input **seams** only — no Spring),
**new `ki-cluster` or into `ki-store-spring`** (all coordination: advisory locks,
Postgres queues, LISTEN/NOTIFY), a host Spring app (wiring). **Module boundary is an
acceptance criterion** (per M4): `:ki-cli:dependencies` must still show **no spring /
no postgres**. Coordination code lives only in the Spring-side module; ki-agent exposes
interfaces, the Postgres module implements them — same SPI discipline as M4.

### Decision A — coordination via **session-level** advisory locks (not xact locks)

The user's constraint "avoid long-running transactions" is load-bearing. Use
**`pg_try_advisory_lock(session_key)`** on a **dedicated connection in autocommit**, held
for the lifetime of session ownership — **not** `pg_advisory_xact_lock`, which would
require an open transaction for the whole session = exactly the long transaction to
avoid. Session-level locks give both things needed:
- **single-owner mutual exclusion** — only one node holds a session's lock at a time;
- **automatic release on crash** — when the owning node's connection drops, Postgres
  releases the lock, so another node's `pg_try_advisory_lock` then **succeeds and takes
  over**. This is the failover primitive; no heartbeat table needed for liveness.

All DB writes (append messages, save checkpoints, consume steering) happen in **separate
short transactions** while the lock is held. The lock guards ownership; it never wraps a
model call or a tool run.

**Pooling tension (state it, don't hide it):** a session-level advisory lock **pins its
connection** — you cannot return that connection to Hikari while the lock is held. So
owning N concurrent sessions needs N dedicated connections (a small dedicated
ownership-pool, separate from the app's main pool). This **bounds sessions-per-node** and
is a real capacity knob, not a bug.

### Decision B — failover replays from the last checkpoint (at-least-once)

On takeover, the new owner loads the session's latest M9 `AgentCheckpointData` and
resumes via `runFromCheckpoint`. Because `AgentCheckpointData` is a self-contained JSON
blob (M9), cross-node resume needs no shared in-memory state. **Inherit the M9 caveat:**
a tool side effect between the last checkpoint and the crash re-runs on the new node.
Tune checkpoint frequency (checkpoint after each tool node) to shrink the replay window;
document tool idempotency as the residual risk. Optionally: a "checkpoint-before-side-
effecting-tool" hook to make the replay window a no-op for already-applied writes.

### Decision C — steering via a DB inbox, applied at step boundaries

- **Any node writes** a steering row: `ki_steering(session_id, seq, payload,
  consumed_at)`. The **owning node** polls (baseline) its own sessions' unconsumed
  steering rows and, at the **next node/step boundary**, feeds the payload into the run
  via `runFromCheckpoint(session, input=steering, checkpoint)` (the M9 seam), marking the
  row consumed in a short transaction.
- **Optimization (optional):** Postgres **LISTEN/NOTIFY** to signal "new steering for
  session X" instead of polling — but as a **signal, not a payload** (NOTIFY has size
  limits and no durability), on its **own dedicated connection**. The DB row remains the
  source of truth.
- **Honest boundary:** steering applies at the **next step boundary**, not mid-token /
  mid-tool. koog has no seam to inject input into an already-running node.

**Deliverables**
- `SessionOwnership` seam in ki-agent (claim / renew / release / is-owner), Postgres
  advisory-lock impl in the Spring module over a dedicated ownership connection.
- Failover: unowned sessions with pending work are **claimable**; on claim, resume from
  the latest checkpoint. A scan/poll (or NOTIFY) finds work whose owner died.
- `SteeringInbox` seam (write from any node; drain for owned sessions), Postgres impl
  (short-tx insert + `... consumed_at IS NULL` drain), applied via the M9 checkpoint-run
  seam at step boundaries.
- All coordination in the Spring-side module; **CLI classpath stays spring/postgres-free**.

**Acceptance**
- Two nodes, one Postgres: start a long session on node A, `kill -9` node A mid-run, node
  B **detects the released lock, claims the session, and completes it** from the last
  checkpoint (integration test, Testcontainers Postgres, behind `KI_IT`).
- A steering row written **via node B** for a session owned by **node A** is picked up and
  reaches the model on node A at the next step boundary.
- No long-running transaction: assert coordination uses session-level advisory locks +
  short write transactions (no open tx spans a model call).
- `:ki-cli:dependencies` still shows **no spring, no postgres** (module-boundary gate).

---

## M11 — RocketChat bot reference implementation

**Goal:** a reference **RocketChat bot** that lets a user talk to a distributed ki
deployment (M10) from a RocketChat thread — the bot receives a user message, runs it
through an agent, and posts the answer back. **A new thread starts a new session; further
messages in that thread are steering into the existing session.** *(Reference impl per
the user's real use case; a later pass builds it out.)*

**Modules:** a new bot module (Spring, depends on `ki-store-spring` + M10 `ki-cluster`);
no change to ki-agent/ki-cli seams.

### The delivery question, answered straight

The user asked whether "each node runs its own bot instance, and the node that receives a
message starts the session." **It depends on the RocketChat ingestion mode, and the clean
answer is to decouple ingestion from processing regardless:**
- **Outgoing webhook behind a load balancer** — RocketChat POSTs each new message to one
  URL; the LB routes it to **one** node. This matches "receiving node handles it" cleanly
  and is the recommended ingestion path. *(Verify the webhook payload carries the thread
  id — `tmid` — so continuations can be routed as steering.)*
- **Realtime / bot-user WebSocket** — if N nodes all connect as the **same bot user**,
  RocketChat **broadcasts every message to all subscribers** → N-fold duplicate
  processing. So this mode *requires* a DB claim step anyway. **Don't** rely on
  "receiving node = owner" here.

Either way, **decouple**: ingestion writes the incoming message to a Postgres **inbox**;
processing is pulled from the DB. This unifies with M10 and removes the ingestion-mode
dependency.

### Decision — new thread = fair pull; continuation = steering (reuse M10)

- **New thread (no `tmid`)** → insert an inbox row; **any node** pulls it under its
  concurrency cap via `SELECT … FOR UPDATE SKIP LOCKED` in a short transaction. That
  **is** the "least-busy" fair queue — a node at capacity simply doesn't pull, so work
  flows to nodes with spare slots. **Do not build a global least-busy balancer**;
  `SKIP LOCKED` + per-node cap is the right primitive and premature to over-engineer.
  The pulling node claims the session (M10 `SessionOwnership`) and starts the run.
- **Continuation (has `tmid` of an existing session)** → **this is an M10 steering
  message.** Write it to the steering inbox keyed by the session that owns the thread; the
  node currently working that session drains and applies it at the next step boundary
  (M10 Decision C). No separate mechanism.
- **Reply** posted back to the thread by the node that produced it (via RocketChat REST).

This makes M11 mostly **glue over M10** — thread↔session mapping, RocketChat REST in/out,
and the inbox pull loop — confirming the dependency: **M11 needs M10**.

**Deliverables**
- Ingestion (webhook endpoint recommended; realtime adapter optional) → Postgres inbox.
- Thread↔session mapping table (`rocketchat_thread`, `tmid` ⇄ `session_id`).
- New-thread pull loop (`FOR UPDATE SKIP LOCKED`, per-node concurrency cap) → claim +
  run.
- Continuation → M10 steering inbox; reply posted to the thread via RocketChat REST.

**Acceptance**
- Open a thread with a first message → some node picks it up (respecting its cap) and
  replies in-thread.
- A follow-up in the same thread reaches the **same session** (via steering) even if a
  *different* node received the HTTP delivery.
- If the owning node dies mid-answer, M10 failover lets another node finish and reply.
- Two nodes at capacity vs. one idle: new threads flow to the idle node (`SKIP LOCKED`).

---

## M12 — Packaging & distribution

**Goal:** a runnable, distributable `ki` — the first "real v0.1" gate. *(Was M9;
includes committing the Gradle wrapper carried from M1.)*

**Modules:** build, ki-cli.

**Deliverables**
- Commit the Gradle wrapper (generated locally under JDK 21; see M1 note).
- `application` plugin on ki-cli → `installDist` / runnable scripts; a `ki` launcher.
- Optional: shadow/fat jar; GraalVM native-image experiment for fast startup.
- CI (GitHub Actions): `gradle build` + tests on push (JDK 21); cache Gradle.
- README: install, configure (LiteLLM URL/key, `ki.toml`), run, author a script tool.

**Acceptance**
- Fresh clone → `./gradlew :ki-cli:installDist` → launcher starts a session.
- CI green on a PR.

---

## M13 — Live streaming & interactive TUI

**Goal:** the interactive polish deferred from M7 (and M2) — the agent feels live. *(Was M10.)*

**Modules:** ki-agent (streaming strategy), ki-cli (transcript/streaming render),
ki-tui (viewport, input).

**Deliverables**
- **Live token streaming** into the transcript *(M7 deferral)* — a streaming strategy
  node that still carries the tool loop **and** M6 compression (guard the coexistence
  with a test, as M6 did). Render token-by-token via `EventHandler` streaming frames.
- **Streaming tool-output updates** *(M3 deferral)* — long-running `bash` output
  streamed to the transcript as it arrives, not only at completion.
- **Transcript scrollback** *(M7 deferral)* — a small `Tui` viewport-offset API +
  PageUp/PageDown (today the renderer auto-pins to the bottom).
- **Autocomplete / fuzzy matching** for slash commands and file paths *(deferred from
  M2, reaffirmed in M7)*.

**Acceptance**
- A long reply renders incrementally; Ctrl-C still cancels cleanly (M7) mid-stream.
- PageUp scrolls back through history without corrupting the live region.

---

## M14 — Tool suite completion

**Goal:** finish the toolset to pi parity and make script-tool config real. *(Was M11.)*

**Modules:** ki-agent (tools), ki-cli (manifest → tool config), bundled scripts.

**Deliverables**
- **`find` tool** *(M3 deferral)*.
- **`edit` fuzzy matching** *(M3 deferral)* — NFKC + smart-quote / dash / space
  normalization (ki matches exact text only today).
- **Per-tool config injection** *(M4 deferral)* — the manifest's `[tools.<name>]`
  settings (already parsed) reach a script tool through an injected `config` handle,
  completing the `ki.toml` convention.
- **Image reads** in `read` *(M3 deferral)* — MIME detection / BMP-PNG handling.
- **Full-output-to-temp-file on truncation** *(M3 deferral)* — persist the untruncated
  tool output to a temp file and reference it in the truncation notice.
- **`shellPath` / WSL stdin transport** for `bash` *(M3 deferral)*.
- **More bundled script tools** *(backlog)* — web fetch, apply-patch, run-tests.
- **Tool-call parallelism where safe** *(backlog)*.

**Acceptance**
- `find` + fuzzy `edit` match pi's behavior on the ported test cases.
- A script tool reads a value from its `[tools.<name>]` manifest block at runtime.

---

## M15 — Rich rendering & multi-provider

**Goal:** richer UI surfaces and provider reach beyond LiteLLM. *(Was M12.)*

**Modules:** ki-tui (components), ki-ai (providers), ki-agent (templating).

**Deliverables**
- **ki-tui components deferred from M2** — `Markdown` (render assistant replies),
  `SelectList`, overlays / modals, inline images (Kitty / iTerm2 graphics), full Kitty
  keyboard protocol, IME hardware-cursor positioning.
- **Multi-provider in ki-ai** *(backlog)* — direct OpenAI / Anthropic clients beyond
  the LiteLLM proxy, via the same `MultiLLMPromptExecutor` seam.
- **Prompt / system-instruction templating** *(backlog)* — per-project instruction
  composition (the `[context].files` mechanism landed in M4; this generalizes it to
  templated / conditional context).

**Acceptance**
- Assistant Markdown renders (headings/code/lists) in the transcript.
- ki talks to a non-LiteLLM provider through the same agent path (behind `KI_IT`).

---

## M16 — Integration & snapshot testing

**Goal:** close the live/`KI_IT` and golden-test gaps left deferred across milestones. *(Was M13.)*

**Modules:** all (tests).

**Deliverables**
- **KI_IT live agent loop** *(M3 deferral)* — create → grep → edit → read end to end
  against a real LiteLLM endpoint.
- **Long-conversation / compaction recall IT** *(M6 deferral)* — a scripted long
  session stays under the window and still recalls an early-turn fact after compaction.
- **Remote-Postgres IT for `ki-store-spring`** *(M4 deferral)* — the `SessionStore`
  contract against a real Postgres (Testcontainers), behind `KI_IT`.
- **Golden / snapshot tests for TUI rendering** *(backlog)* — lock the differential
  renderer against recorded line buffers.

**Acceptance**
- The full agent loop passes against a live endpoint under `KI_IT`.
- Postgres and SQLite stores pass the same `SessionStore` suite.

---

## Residual / opportunistic (not milestoned)

Low-priority items that don't warrant their own milestone; fold into a nearby one if
convenient:

- Chat-memory **preprocessor ordering** tuning (trim/filter) — passthrough today (M4/M6);
  M6's compression strategy already meets the window-budget goal.
- **HikariCP-pooled local backend** (M4) — unneeded for a single-user CLI; add only if a
  local multi-connection use case appears.

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

**M1–M4, M6, M7, M8 are done** (M5 deferred). Next up is **M9** (persistence:
checkpoints, crash recovery & resume), the foundation for **M10** (distributed
multi-node) and **M11** (RocketChat bot). Each milestone is independently shippable; keep the
integration-test-behind-`KI_IT` discipline so `gradle build` stays green offline.
