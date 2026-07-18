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
| M8 | Robustness: retry, tool-errors, process-kill, logging | ✅ Done (checkpoints split to M8b) |
| M8b | Agent-persistence checkpoints & crash recovery | ▢ Deferred (own pass) |
| M9 | Packaging & distribution | ▢ Planned |
| M10 | Live streaming & interactive TUI | ▢ Planned |
| M11 | Tool suite completion | ▢ Planned |
| M12 | Rich rendering & multi-provider | ▢ Planned |
| M13 | Integration & snapshot testing | ▢ Planned |

M8+ are the **most-reasonable reorganization of every deferred/backlog item** carried
out of M1–M7 (each milestone below cites the milestone it inherits work from). M5
(permissions) is intentionally skipped for now and picked up later.

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
  **default off**, revisit alongside M8 robustness. Do not conflate with history.

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

- koog `Persistence` checkpoints / rollback (Decision A) — wire in M8.
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
to the M8 checkpoint seam. Coherent-long-conversation + early-fact-recall remain live
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
Agent-persistence checkpoints split out to M8b as their own pass.)*

**Delivered**
- **LLM-call retry with exponential backoff** (`ki-ai` `RetryingPromptExecutor`,
  commit `3101705`). Wraps the `PromptExecutor`; retries transient failures — 5xx,
  408/425, rate-limit (429), and bare network I/O — with full-jitter backoff; fails
  fast on auth/bad-request (401/403/400/404/422). Classifier walks the cause chain for
  koog's `KoogHttpClientException(statusCode)`. Only the non-streaming `execute`
  paths retry (a streaming `Flow` has no safe restart point → deferred to M10).
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
  `BashExec`; only the timeout path is wired/tested here (cancel → M10).

<details><summary>Original plan (superseded)</summary>

Deliverables: retry/backoff; tool-error capture + malformed-args→corrective message;
process-tree kill (proposed `setsid`); agent-persistence checkpoints; structured
logging. Acceptance included "with checkpoints on, killing the process mid-run and
restarting recovers the run" — moved to M8b.
</details>

---

## M8b — Agent-persistence checkpoints & crash recovery

**Goal:** opt-in mid-run snapshots so a killed process can resume, and the **raw
transcript** that M6 compaction overwrites in history is preserved. *(M4 Decision A
"secondary role"; M6 flagged the gap. Split from M8: koog ships the machinery
(`agents-features-snapshot`: `Persistence` feature, `PersistenceStorageProvider`,
`AgentCheckpointData`), but wiring + a crash-recovery IT is a milestone on its own.)*

**Modules:** ki-agent (install `Persistence`, checkpoint SPI), ki-cli + ki-store-spring
(checkpoint storage), build.

**Deliverables**
- A `PersistenceStorageProvider` backed by a new checkpoint table (SQLite locally,
  JdbcTemplate remotely) alongside the existing message store; koog `AgentCheckpointData`
  serialized with koog Json (same opaque-blob discipline as `MessageCodec`).
- `install(Persistence)` in `KiAgent`, default off; config for continuous vs. on-demand
  snapshots.
- Recovery: on restart with checkpoints on, load the latest checkpoint and roll the
  agent context back to it.
- Preserve the pre-compaction raw transcript for audit/rollback.

**Acceptance**
- With checkpoints on, killing the process mid-run and restarting recovers the run
  (fork-a-process integration test).
- Raw transcript survives an M6 compaction.

---

## M9 — Packaging & distribution

**Goal:** a runnable, distributable `ki` — the first "real v0.1" gate. *(Existing M9;
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

## M10 — Live streaming & interactive TUI

**Goal:** the interactive polish deferred from M7 (and M2) — the agent feels live.

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

## M11 — Tool suite completion

**Goal:** finish the toolset to pi parity and make script-tool config real.

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

## M12 — Rich rendering & multi-provider

**Goal:** richer UI surfaces and provider reach beyond LiteLLM.

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

## M13 — Integration & snapshot testing

**Goal:** close the live/`KI_IT` and golden-test gaps left deferred across milestones.

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

**M1–M4, M6, M7 are done** (M5 deferred). Next up is **M8** (robustness, errors &
recovery). Each milestone is independently shippable; keep the
integration-test-behind-`KI_IT` discipline so `gradle build` stays green offline.
