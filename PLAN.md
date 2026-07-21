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

> **Reference by slug, not number.** The **slug** is a milestone's permanent identity —
> assigned once, never changed, never reused — and is the key used in prose (`[[slug]]`),
> commit scopes (`feat(...): … (extension-hooks)`), and todos. The **M-number** is a frozen
> historical alias only (shipped commits cite it); it is never renumbered and never the
> reference key. **Order is metadata, not identity:** what runs next is the `▶` status +
> `depends-on`, so reprioritizing changes a status, never an ID. See
> [Milestone identity & ordering](#milestone-identity--ordering-convention) below.

| Slug | # (legacy) | Milestone | Status · depends-on |
|------|-----------|-----------|---------------------|
| `vertical-slice` | M1 | End-to-end vertical slice | ✅ Done (commit `6b8a812`) |
| `native-tui` | M2 | Native TUI framework (ki-tui), drop casciian | ✅ Done (commit `e4d7b3a`) |
| `core-tools` | M3 | Core file & shell toolset | ✅ Done |
| `config-catalog` | M4 | Config, model catalog, sessions & context | ✅ Done |
| `tool-permissions` | M5 | Tool permissions & approval | ▢ Deferred (yolo mode for now) |
| `context-tokens` | M6 | Context & token management | ✅ Done |
| `tui-slash-commands` | M7 | TUI: slash commands, cancel, cost | ✅ Done (streaming deferred) |
| `robustness` | M8 | Robustness: retry, tool-errors, process-kill, logging | ✅ Done |
| `checkpoints` | M9 | Persistence: checkpoints, crash recovery & resume | ✅ Done |
| `streaming-reasoning` | M9.1 | Streaming model reasoning (thinking) blocks to the terminal | ✅ Done |
| `tool-call-lines` | M9.2 | Colored tool-call lines in the transcript (pi-style) | ✅ Done |
| `result-preview` | M9.3 | Tool-call result preview + Ctrl-O expand; koog double-encode fix | ✅ Done |
| `distributed-multinode` | M10 | Distributed multi-node ki (Spring/Postgres) | ◐ Primitives + loop done (Postgres-verified); LISTEN/NOTIFY + real-agent e2e left · dep `checkpoints` |
| `rocketchat-bot` | M11 | RocketChat bot reference implementation | ◐ Designed (verified surface) · dep `distributed-multinode` |
| `packaging` | M12 | Packaging & distribution | ▢ Planned |
| `interactive-tui` | M13 | Live streaming & interactive TUI | ▢ Planned |
| `tool-suite` | M14 | Tool suite completion | ▢ Planned |
| `rich-rendering` | M15 | Rich rendering & multi-provider | ▢ Planned |
| `integration-testing` | M16 | Integration & snapshot testing | ▢ Planned |
| `multifile-config` | M17 | Multi-file manifest config (no-override merge) | ✅ Done (commit `ce8bbd7`) |
| `extension-hooks` | M18 | Extension hooks (tool_call / tool_result / provider_request interceptors) | ◐ Hook surface shipped (block/modify/result/provider, koog-dispatch verified); porting pi's bash-guard/rules/env-mask is the follow-up |

New milestones from here get a **slug only** — no new M-number is minted (the number column
stops at M18). M8+ are the **most-reasonable reorganization of every deferred/backlog item** carried
out of M1–M7 (each milestone below cites the milestone it inherits work from). M5
(permissions) is intentionally skipped for now and picked up later.

**Renumber note (historical — why slugs exist):** the old **M8b** (checkpoints) became
**M9**, and two milestones were *inserted* (distributed multi-node, RocketChat bot), shifting
former M9–M13 **up by 3**. That shift is exactly the failure the slug scheme retires: numbers
that move break every commit/prose reference to them. The `(was M9)` parentheticals and this
note are the scars. Under slugs, an insert or reprioritization touches no existing identity.

---

## Milestone identity & ordering (convention)

The problem numbers caused: an M-number encoded **identity** *and* **execution order** in one
token, and that token leaked into immutable places — commit scopes (`feat(...): … (M18)`),
cross-references, and `todos/*`. Reprioritizing a milestone forced a renumber; a renumber
broke every reference; and reusing a freed number (the M10 case) made one number name two
different milestones. Order and identity must not share a token.

**The rules:**

1. **Slug = permanent identity.** Each milestone gets one kebab-case slug at creation
   (`extension-hooks`, `distributed-multinode`). It is assigned once, **never changed, never
   reused** even if the milestone is dropped. It is the only reference key.
2. **Order is metadata, never identity.** What runs next is the `▶` **status** plus an explicit
   **`depends-on: [slug]`** list — not a low number. Reprioritizing flips a status; inserting
   work adds a slug. No existing ID ever moves, so no reference ever breaks.
3. **Reference by slug everywhere.** Prose links `[[distributed-multinode]]`; commit scopes end
   `(extension-hooks)`; `todos/*` cite slugs. Never cite a milestone by number in new text.
4. **M-numbers are frozen aliases.** They stay in the table's `# (legacy)` column solely
   because shipped commits (`M10 orchestration loop`, `M17`) already reference them. No new
   M-number is minted; the sequence ends at M18. New milestones are slug-only.
5. **No global reordering pass.** The table is grouped roughly by theme/history for reading; it
   is *not* an execution queue. The queue is derived: runnable = status not-done **and** every
   `depends-on` slug done.

This is why the current work stays `extension-hooks` (`M18`) rather than being renumbered into
the M10 slot: the number was never the point — the `▶` status is what marks it next, and
`distributed-multinode` keeps its identity and its commit history intact.

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
- **Bash command allowlist** *(pi parity backlog #2)* — `[tools.bash.commands]` restricts
  `bash` to specific commands/subcommands (e.g. `git: [add, status, log, diff]`), or
  `unrestricted = true` to skip the allowlist. Unknown command → descriptive error
  listing what's allowed. Mirrors pi hats' `bash.commands` / `bash.unrestricted`.
- **File access restrictions** *(pi parity backlog #3)* — `[context].access.read` /
  `.edit` glob lists (ant-style, `**` recursive) checked by `read`/`write`/`edit`
  before the operation; denial names the allowed patterns. Mirrors pi hats' `files.read`
  / `files.edit`. Unit tests: allow/deny by pattern, root-escape attempts, symlink
  traversal.

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
(SQLite checkpoint store + `/resume` re-seed), ki-cluster (JdbcTemplate checkpoint
store; renamed from ki-store-spring during M10 prep), build.

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
  bean (ki-cluster). `[db].checkpoints = true` opts in via the manifest.
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
- Partial raw-transcript preservation for audit (M6 gap). **Honest scope:** checkpoints
  fire `interceptNodeExecutionCompleted` — *after every node, including the compression
  node* — so a checkpoint taken **after** compression captures the compacted history, not
  raw. The raw transcript survives only in the **earlier, pre-compression checkpoint rows**
  (append-only chain, one row per node), and only while those rows are unpruned. A full
  audit trail is therefore best-effort here, not guaranteed; a dedicated raw-log seam, if
  ever required, is a separate concern from crash-recovery.

**Acceptance**
- With checkpoints on, killing the process mid-run and restarting **recovers the run** —
  restarts at the last node, not the last turn. *(Met via a **two-instance in-process
  test** sharing one store + session id, which round-trips the blob through `CheckpointCodec`
  and so supports the cross-node claim; a true fork-a-process IT is not yet run.)*
- `/resume` re-seeds a live session without a restart.
- **Shipped config exercised:** checkpoints coexist with `compressHistory=true` (the
  `FromLastNMessages` strategy branch `require(uniqueNames)` runs against in production),
  not only the `NoCompression` test branch.

**Caveat (carried into M10):** checkpoints snapshot at node boundaries, so a
side-effecting tool (`bash`, `write`) that ran **after** the last checkpoint but before
the crash **re-runs** on recovery — recovery is at-least-once, not exactly-once. Flag
idempotency; do not imply clean exactly-once resume.

---

## M9.1 — Streaming model reasoning (thinking) blocks to the terminal  ✅ DONE

**Goal:** surface the model's reasoning/thinking as it is produced, streamed
token-by-token to the terminal, visually distinct from the final answer. Carved out of
M13's "Live token streaming" as a standalone increment because reasoning frames are a
separate `StreamFrame` variant from answer text and land end-to-end (agent → CLI render)
on their own. *(Slotted as M9.1 — the literal "M9" is the done Persistence milestone;
this rides directly on M9's single-node agent path.)* Streaming the *answer* body and
scrollback stay in M13.

### Delivered (as built — authoritative)
- **koog surface (verified against `prompt-model` / `agents-core` 1.0.0-preview7).**
  `StreamFrame.ReasoningDelta.text` carries the streamed thinking chunk;
  `StreamFrame.ReasoningComplete` / `TextComplete` / `ToolCallComplete` + `End` are folded
  back into a `Message.Assistant` by `Iterable<StreamFrame>.toMessageResponse()`.
- **Streaming strategy in `KiAgent` (`streaming` flag, default off).** Mirrors koog's
  `singleRunStrategyWithHistoryCompression` but swaps the two primary LLM calls (initial +
  post-tool) for a `streamFold()` node: it collects `requestLLMStreaming()`, pushes each
  `ReasoningDelta` to a per-run `reasoningSink`, folds the frames into the same
  `Message.Assistant` the blocking node would return, appends it, and records usage
  in-node (the streaming path does not fire `onLLMCallCompleted`). The compression branch
  stays on the blocking path — so the **tool loop and M6 compression are unchanged**.
- **Per-run reasoning sink.** `KiAgent.run(input, sessionId, onReasoning)` sets a volatile
  sink for the turn; `ki-cluster` and the blocking tests keep the proven non-streaming
  path (default `streaming = false`).
- **CLI render.** `AgentBridge` threads an `onReasoning` callback, marshaled to the UI
  thread via `uiPost`; `KiScreen` fills a dimmed, word-wrapped `ThinkingBlock` (`💭`
  marker) above the answer, updated token-by-token. One-shot mode streams reasoning to
  **stderr** so stdout stays the clean answer.

### Verification
- `StreamingReasoningTest` (real `KiAgent`, scripted `StreamFrame` executor): the fold
  drives the tool loop and delivers reasoning deltas **in order**; a second test proves M6
  compression still fires and chat-memory + system prompt survive under the streaming
  strategy. `AgentBridgeTest` covers reasoning-delta marshaling. `./gradlew build` green.

### Deferred (unchanged from plan)
- **Live *answer* streaming + scrollback** stay in **M13** (only reasoning streams today;
  the final answer still lands as one block on completion).
- **Cancel mid-reasoning-stream** (M7 Ctrl-C) and a **show/hide/collapse toggle** for the
  thinking region are not yet wired — folded into M13's streaming polish.
- **Provider caveat:** models that return reasoning only in the final message (not as
  deltas) render an empty thinking block — no error, just nothing to stream.

---

## M9.2 — Colored tool-call lines in the transcript (pi-style)  ✅ DONE

**Goal:** show each tool call as its own transcript row, colored like pi — so the user
can see what the agent is doing, not just a flickering status-bar tool name.

**Decision: match pi's model, not a hardcoded green.** Inspected upstream
(`packages/coding-agent/src/modes/interactive/components/tool-execution.ts` +
`theme/theme.ts` / `dark.json`). pi does **not** color the tool title green — the title is
`bold(toolName)` in the normal foreground; the visible color is a **full-width background
stripe** whose color tracks the call's lifecycle: `toolPendingBg` `#282832` while running →
`toolSuccessBg` `#283228` (the dark green seen in a dark terminal) on success →
`toolErrorBg` `#3c2828` on error. pi emits truecolor `48;2;r;g;b` (nearest-256 fallback when
the terminal lacks truecolor).

### Delivered (as built — authoritative)
- **Tool-call events from `KiAgent`.** koog's `EventHandler` `onToolCallStarting` /
  `onToolCallCompleted` / `onToolCallFailed` (each carries `toolCallId` + `toolArgs`) feed a
  per-run `toolSink` as a `ToolCallEvent(id, name, args, phase)` with phase
  `STARTING` / `OK` / `ERROR`. Fires on **both** the blocking and streaming (M9.1) paths.
- **`run(..., onTool)`** sets the sink for the turn; `AgentBridge` threads it and marshals
  each event onto the UI thread via `uiPost` (same pattern as the M9.1 reasoning sink).
- **`ToolCallLine` component (ki-cli).** A full-width row: `⏺ name(args)`, bold title
  (SGR-22 so it nests inside the stripe), on a truecolor background stripe. `KiScreen` keys
  live lines by `toolCallId` so completion **recolors the same row** pending → success/error.
- **`Ansi.bgRgb` / `Ansi.boldIn` (ki-tui)** — truecolor background (reset `49m`) and
  bold-without-full-reset primitives. Compact args via `KiAgent.argsPreview` (strips the
  outer JSON braces); the row pads/truncates to the viewport so the stripe fills the width.
- **One-shot mode** logs `⏺ name(args)` to stderr on tool start (stdout stays the answer).

### Verification
- `ToolCallLineTest` — full-width stripe (width contract, incl. narrow widths), the pi bg
  hexes per phase (`48;2;40;40;50` / `40;50;40` / `60;40;40`), bold title, no-arg omits
  parens. `StreamingReasoningTest` now also asserts the ls call surfaces `STARTING → OK`
  with a shared id. `./gradlew build` green (ki-tui 71, ki-agent 54, ki-cli 52).

### Deferred
- **256-color fallback + terminal-capability / theme (dark/light) detection** — pi picks
  truecolor vs nearest-256 from capabilities and auto-detects background via OSC 11; ki has
  neither yet, so M9.2 emits **truecolor only**. Folded into M15 (rich rendering / theme).
- Richer per-tool renderers (diffs, `⏺`/spinner states) stay in M13/M15. Output folding
  shipped early, see M9.3 below.

---

## M9.3 — Tool-call result preview + Ctrl-O expand; koog double-encode fix  ✅ DONE

**Trigger:** user-reported bug — the M9.2 tool-call row showed only the pass/fail color
stripe, never the tool's actual output, so there was no way to see what `ls`/`read`/`bash`
etc. actually returned without leaving the TUI. Separately, the *same* investigation
surfaced a real koog 1.0.0-preview7 bug that had been blocking real tool-call turns
end-to-end against the samokat LiteLLM proxy.

### Delivered (as built — authoritative)
- **`ToolCallEvent.result`** (ki-agent): koog's `onToolCallCompleted`/`onToolCallFailed`
  already carry the tool's return value (`ctx.toolResult`, a koog `JSONElement`) and the
  failure message (`ctx.message`) — previously discarded. Now forwarded: unwrapped via
  `resultPreview()` (string primitive → raw content, else `.toString()`).
- **`ToolCallLine` output preview** (ki-cli): once a call lands (OK/ERROR) with a
  non-blank result, up to 6 dimmed, wrapped lines render beneath the stripe. Longer output
  is capped with a `"N more lines (ctrl-o to expand)"` hint line instead of being silently
  dropped.
- **Ctrl-O expand, pi-style** (ki-tui + ki-cli): `Key.CTRL_O` (ASCII 15/SI) added to the
  key table. `KiScreen` tracks every `ToolCallLine` created this session (`allToolLines`,
  survives across turns, cleared by `/clear`) and a single `toolsExpanded` flag; Ctrl-O
  toggles all of them at once between the capped preview and the full result text. No
  per-row selection model exists in ki's scrollback TUI, so this is a session-wide toggle
  rather than pi's per-row expand — matches the simpler transcript model.
- **koog double-encode workaround** (ki-ai): `AbstractOpenAILLMClient.convertPromptToMessages`
  (koog 1.0.0-preview7) re-JSON-encodes an already-encoded `MessagePart.Tool.Call.args` when
  replaying a prior assistant tool call into request history, producing a doubly-quoted
  `arguments` string. Confirmed against the real samokat proxy: doubly-encoded → 500
  `litellm.InternalServerError ... 'str' object has no attribute 'items'` for
  `deepseek-v4-flash`; correctly single-encoded → 200. `DoubleEncodedArgsWorkaroundClient`
  subclasses `OpenAILLMClient`, overrides `serializeProviderChatRequest`, and unwraps the
  extra quoting from `messages[].tool_calls[].function.arguments` before the request goes
  over the wire. Drop this once koog fixes it upstream.
- **Live opt-in integration test** (`ki-cli/BootstrapIntegrationTest`, `KI_IT=1`): loads the
  real project `ki.toml` and drives a real turn — including a real tool call — against the
  real proxy, so this exact class of bug is caught by running a test, not by manually
  running `ki-cli` and reading a stack trace.

### Verification
- `ToolCallResultEventTest` (ki-agent): a real `KiAgent` run with a scripted executor
  proves a successful `ls` call's real output reaches `ToolCallEvent.result`, and a tool
  that throws surfaces its message on the `ERROR` event.
- `ToolCallLineTest` (ki-cli): result preview shape, blank-result no-op, 6-line cap +
  exact hint text, `setExpanded` shows the full result with no hint line, collapsing
  re-caps, a short result is unaffected by expand, width contract holds both collapsed and
  expanded.
- `KeysTest` (ki-tui): Ctrl-O (ASCII 15) parses to `Key.CTRL_O`.
- `DoubleEncodedArgsWorkaroundClientTest` (ki-ai): the double-encode unwrap is idempotent on
  correctly single-encoded `arguments` and a no-tool-calls body; unwraps the doubly-encoded
  shape captured verbatim from the real proxy failure.
- `BootstrapIntegrationTest` (ki-cli, `KI_IT=1`, real proxy): a real tool-call turn against
  the real `ki.toml` completes end-to-end — this is the regression test for the koog fix.
- `./gradlew build` green across ki-ai, ki-agent, ki-cli, ki-tui.

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
**`ki-cluster`** (the renamed `ki-store-spring` — now holds storage impls *and* all
coordination: advisory locks, Postgres queues, LISTEN/NOTIFY), a host Spring app (wiring).
**Module boundary is an acceptance criterion** (per M4): `:ki-cli:dependencies` must still
show **no spring / no postgres**. Coordination code lives only in `ki-cluster`; ki-agent
exposes interfaces, the Postgres module implements them — same SPI discipline as M4.

### Delivered (coordination primitives — as built)

- **Seams in ki-agent** (`dev.ki.store`): `SessionOwnership` (`tryClaim`/`release`/
  `isOwner`/`owned`) and `SteeringInbox` (`write` from any node, `drain` by the owner) +
  `SteeringMessage`. Pure interfaces, no Spring — the CLI never sees them.
- **`AdvisoryLockSessionOwnership`** (ki-cluster, `dev.ki.cluster`): session-level
  `pg_try_advisory_lock` on a **dedicated autocommit connection per owned session** (held in
  a map, released + returned on `release`/`close`); claims serialized so one node never
  double-connects a session. `AdvisoryKeys` derives a stable 64-bit lock key from the
  session id via SHA-256 (not 32-bit `hashCode`) so every node computes the same key.
- **`JdbcSteeringInbox`** (ki-cluster): `ki_steering` table; `drain` is an atomic
  take-and-mark via `UPDATE … WHERE consumed_at IS NULL RETURNING` (row-locks matched rows,
  so a racing drain gets nothing), results sorted by seq. Single-statement writes = short
  transactions. `SteeringInbox` registered as a Spring bean; `SessionOwnership` deliberately
  **not** auto-wired (needs a dedicated `DataSource`, documented on the config).
- **Verified — offline:** `AdvisoryKeysTest` (determinism + a pinned cross-node constant).
- **Verified — against real Postgres 16** (`CoordinationIT`, Testcontainers, `KI_IT=1`, all
  3 green): advisory-lock **mutual exclusion** (node B can't claim node A's session),
  **auto-release on a dropped owner connection** — the failover primitive: a raw connection
  takes the lock, is closed to simulate a node crash, and another instance's `tryClaim` then
  succeeds — and **steering drain-once-in-order** (second drain sees rows consumed, other
  sessions untouched). Self-skips without `KI_IT`+Docker. `./gradlew build` green.

### Still open (the larger half): the orchestration loop — contract (shaped by M11)

Designing M11 fixed the loop's shape. The contract, per node:

- **Ownership is per *turn*, not per session** *(user decision — Fork A over the earlier
  "same node" wording)*. A node holds the advisory lock only while a turn runs; between turns
  no lock is held and no connection is pinned. The next turn may land on a different node —
  invisible to correctness because checkpoint + history make the session portable, and it
  scales to many idle conversations (the chat-bot shape). Mid-turn messages route to the node
  holding the lock, which is exactly when the lock is held.
- **`SteeringInbox` is the per-session message queue for every post-first message** — not a
  separate "next-turn dispatch." Whether a drained message is consumed as *next turn* (session
  idle) or *mid-run steering* (session running) is only a question of **when** the owner drains.
- **Normal idle continuation is a plain `agent.run(nextInput, sessionId)`** — ChatMemory
  reloads history; the prior turn completed so Persistence sees a **tombstone → no rollback**.
  `runFromCheckpoint(input, checkpoint)` is **only** the interrupted-turn / failover path, not
  the normal path. Don't route normal turns through it.
- **The sweeper is the backbone, the webhook the fast path.** One periodic sweep:
  `SELECT session_id FROM inbox-with-unconsumed-work WHERE not-currently-owned ... FOR UPDATE
  SKIP LOCKED`, up to the node's concurrency cap → `tryClaim` (the atomic arbiter — no separate
  is-owned check, which races) → drain + run → release. This single loop is simultaneously
  **dead-owner failover**, **lost-wakeup recovery** (message lands in the gap between drain and
  release), and **unclaimed-new-work distribution**. LISTEN/NOTIFY only accelerates it.
- **Consume-ordering is at-least-once, reconciled with checkpoints.** A steering/inbox row is
  marked **consumed only after the turn completes** (or after the first checkpoint captures the
  message into history) — never at drain time. Otherwise a mid-turn crash consumes the message
  *and* loses it from every checkpoint. On takeover the checkpoint already carries it → the
  message replays at least once (same idempotency caveat as tool side effects, Decision B).
- **Deferred to v2: true mid-run interruption** (inject input while `agent.run` is in flight) —
  needs a custom koog strategy node or cancel + `runFromCheckpoint`. v1 is turn-based: steering
  is drained at the turn boundary. The Q&A-bot use-case doesn't need mid-run interruption.

**Built (this pass):** the `SessionWorker`/sweeper loop, unit-tested offline with in-memory
fakes (claim/run/reply/consume/release, crash-leaves-unconsumed, cross-node exclusion, cap)
and verified over **real Postgres** (`WorkerIT`: end-to-end run, two-node race = turn-once,
crashed-owner takeover). Remaining: LISTEN/NOTIFY and the monolithic real-agent e2e.

### Decision A — coordination via **session-level** advisory locks (not xact locks)

The user's constraint "avoid long-running transactions" is load-bearing. Use
**`pg_try_advisory_lock(session_key)`** on a **dedicated connection in autocommit**, held
**for the duration of a turn** (per-turn ownership, Fork A) — **not** `pg_advisory_xact_lock`,
which would require an open transaction for the whole turn = exactly the long transaction to
avoid. Session-level locks give both things needed:
- **single-owner mutual exclusion** — only one node holds a session's lock at a time;
- **automatic release on crash** — when the owning node's connection drops, Postgres
  releases the lock, so another node's `pg_try_advisory_lock` then **succeeds and takes
  over**. This is the failover primitive; no heartbeat table needed for liveness.

All DB writes (append messages, save checkpoints, consume steering) happen in **separate
short transactions** while the lock is held. The lock guards ownership; it never wraps a
model call or a tool run.

**Pooling tension (state it, don't hide it):** a session-level advisory lock **pins its
connection** while held — you can't return it to Hikari mid-turn. With per-turn ownership
(Fork A) this pins one connection per **actively running** turn (not per idle conversation),
so the bound is **concurrent turns per node** — a real capacity knob, and far cheaper than
pinning every live conversation. Size the dedicated ownership pool to the node's concurrency
cap, separate from the app's main pool.

### Decision B — failover replays from the last checkpoint (at-least-once)

On takeover, the new owner loads the session's latest M9 `AgentCheckpointData` and
resumes via `runFromCheckpoint`. Because `AgentCheckpointData` is a self-contained JSON
blob (M9), cross-node resume needs no shared in-memory state. **Inherit the M9 caveat:**
a tool side effect between the last checkpoint and the crash re-runs on the new node.
Tune checkpoint frequency (checkpoint after each tool node) to shrink the replay window;
document tool idempotency as the residual risk. Optionally: a "checkpoint-before-side-
effecting-tool" hook to make the replay window a no-op for already-applied writes.

### Decision C — steering via a DB inbox, drained at turn boundaries

- **Any node writes** a steering row: `ki_steering(session_id, seq, payload, consumed_at)`
  (built, verified). This inbox is the per-session message queue: a mid-session user
  follow-up and a RocketChat thread continuation are both just a steering write.
- **The node that runs the turn drains it.** For an **idle** session the drained payload is
  the next turn's input to a plain `agent.run(input, sessionId)`; for a **running** session
  it is applied at the next step boundary. Rows are marked consumed **only after the turn
  completes** (the reconciled at-least-once ordering above), never at drain time.
- **Optimization (optional):** Postgres **LISTEN/NOTIFY** to signal "new work for session X"
  instead of polling — a **signal, not a payload** (NOTIFY has size limits and no durability),
  on its **own dedicated connection**. The DB row stays the source of truth; the sweeper is
  the durable backstop, so a missed NOTIFY only adds latency, never loss.
- **Honest boundary:** in v1, steering applies at the **turn boundary**, not mid-token /
  mid-tool. True mid-run injection is deferred to v2 (see loop contract above).

**Deliverables**
- ✅ `SessionOwnership` seam (ki-agent) + `AdvisoryLockSessionOwnership` (ki-cluster,
  per-turn advisory lock on a dedicated connection). *(done, Postgres-verified)*
- ✅ `SteeringInbox` seam + `JdbcSteeringInbox` (atomic `UPDATE … RETURNING` drain). *(done)*
- ✅ **`SessionWorker` / sweeper loop** (ki-cluster): the per-node backbone —
  `pendingSessions` → `tryClaim` (atomic arbiter) → `peek` → `runTurn` → reply →
  `markConsumed` → `release`, under a concurrency cap. Handles new work, failover, and lost
  wakeups in one loop. `SessionTurnRunner` / `TurnReplySink` seams (ki-agent) let the host
  supply the actual agent turn, keeping the loop free of model config. Steering consume-order
  refactored to **peek → run → markConsumed** (was atomic take-and-mark) so a mid-turn crash
  leaves work unconsumed for retry. *(done, verified against Postgres)*
- ✅ **Real-`KiAgent`-through-worker failover** (`WorkerAgentFailoverTest`, offline): the
  `SessionWorker` drives a real checkpointing `KiAgent` across a crash + takeover — node A calls
  a tool then crashes (message left unconsumed), node B's worker resumes from the checkpoint and
  completes. Proves the `SessionTurnRunner` seam end to end **and** that re-feeding the peeked
  input on takeover does **not** double-add the user message (koog's rollback dedupes; plain
  `agent.run` + auto-rollback suffices — no `runFromCheckpoint` branch needed).
- ▢ LISTEN/NOTIFY accelerator (optional, on a dedicated connection).
- All coordination in `ki-cluster`; **CLI classpath stays spring/postgres-free**.

**Reply-delivery semantics (documented trade):** the worker replies **before** marking
consumed, so a crash in that window yields an **at-least-once reply** (another node re-runs and
replies again) rather than mark-then-reply's silent lost reply. Downstream (M11 RocketChat)
should tolerate/dedupe a repeated reply.

**Acceptance**
- Two nodes, one Postgres: a turn running on node A, `kill -9` node A mid-turn; node B's
  sweeper **claims the released session and completes the turn** from the last checkpoint
  (Testcontainers IT behind `KI_IT`). *(The primitive — auto-release + `tryClaim` takeover —
  is already Postgres-verified; this exercises it through the full loop.)*
- A steering/inbox row written **via node B** for a session later run by **any** node is
  drained and reaches the model, exactly-once-or-more, marked consumed only after the turn.
- No long-running transaction: coordination uses per-turn advisory locks + short write
  transactions (no open tx spans a model call). *(verified for the primitives)*
- `:ki-cli:dependencies` still shows **no spring, no postgres** (module-boundary gate).

---

## M11 — RocketChat bot reference implementation

**Goal:** a reference **RocketChat bot** over the distributed M10 layer — a user talks to
ki in a RocketChat thread, any node can receive the HTTP delivery, and the M10 loop runs the
turn and posts the answer back. This is the **real consumer that shaped M10's loop contract**:
a first message starts a session, every later message in the thread is an M10 steering write.

**Module:** new `ki-rocketchat` (Spring web; depends on `ki-cluster` + ki-agent). ki-agent/
ki-cli seams unchanged; the CLI stays spring/postgres-free (module-boundary gate holds).

### RocketChat surface (verified July 2026)

- **Ingestion = outgoing webhook behind a load balancer.** RocketChat POSTs each new message
  to one URL; the LB lands it on **one** node. Documented payload fields: `token`,
  `channel_id`, `channel_name`, `timestamp`, `user_id`, `user_name`, `text`, plus the message
  `_id` and, **when the message is in a thread, `tmid`** (thread-root message id — confirmed;
  RocketChat PR #17863 / docs). Realtime/bot-user WebSocket is rejected: N nodes on the same
  bot user each receive a **broadcast** of every message → N-fold duplicate processing.
- **Reply = REST `chat.postMessage`** with `roomId` + `text` + **`tmid`** to post *into the
  thread* (confirmed: `tmid` = "message id to reply to / create a thread on").
- **Echo-loop guard = `user_id` filter.** The bot's own replies re-fire the webhook; drop any
  message whose `user_id` equals the bot's own user id. *(A `bot` field on bot-authored
  messages is plausible but was **not** verifiable in the docs — do not rely on it; the
  `user_id` check is the robust guard.)* Also validate the shared `token`.

### Thread ↔ session mapping (the key detail)

A brand-new top-level message has **no `tmid`**; its `_id` becomes the thread root once the
bot replies with `tmid = _id`. Follow-ups then carry `tmid = root`. So **the thread root id
is the stable session key**:
- **No `tmid`** → new session; record `thread_root = message._id`; the turn's reply posts with
  `tmid = message._id` (opening the thread).
- **`tmid = T` present** → look up the session whose `thread_root = T`; it's a continuation.
  (Unmapped `T` — a user replying under some other message — starts a new session keyed by `T`.)

### The flow (all decoupled through Postgres — receiving node ≠ processing node)

1. **Webhook controller** (any node): validate token, drop self/echo (`user_id`), resolve
   `thread_root`, then **write to Postgres first, claim never here**:
   - new session → insert its first message as inbox work + upsert `rocketchat_thread`
     (`thread_root` ⇄ `session_id` ⇄ `room_id`);
   - continuation → `SteeringInbox.write(session_id, text)` (M10) — the per-session queue.
2. **M10 sweeper** (any node, the backbone): `SKIP LOCKED` pull under the concurrency cap →
   `tryClaim(session)` (atomic arbiter) → **drain the session's inbox/steering** → run the
   turn (`agent.run` idle, `runFromCheckpoint` on takeover) → post the reply via
   `chat.postMessage(roomId, text, tmid=thread_root)` → **mark consumed, then release**.
   `SKIP LOCKED` + per-node cap **is** the "least-busy" fair queue — a saturated node doesn't
   pull, so work flows to nodes with free slots. No global balancer.

M11 is thus **glue over M10**: a webhook controller, the `rocketchat_thread` map, a RocketChat
REST client, and reuse of the M10 sweeper + steering inbox. It adds no new coordination.

**Deliverables**
- `ki-rocketchat` module: webhook controller (token + echo guard), `RocketChatClient`
  (`chat.postMessage` with `tmid`), config for base URL / bot user id / auth token (by env).
- `rocketchat_thread` table (`thread_root` PK ⇄ `session_id` ⇄ `room_id`) + the resolve/upsert.
- Wire ingestion to the M10 inbox (new) / `SteeringInbox` (continuation); reply from the loop.

**Acceptance**
- First message in a thread → some node (respecting its cap) runs the turn and replies
  **in-thread** (`tmid` set); a `rocketchat_thread` row maps root ⇄ session.
- A follow-up reaches the **same session** even when a **different** node received the HTTP
  delivery (decoupling proven) — via the steering inbox.
- The bot's own reply does **not** trigger another turn (echo guard).
- Owner killed mid-turn → the M10 sweeper on another node finishes the turn and replies.
- Two nodes saturated, one idle → new threads flow to the idle node (`SKIP LOCKED`).
- IT behind `KI_IT` with a **stub RocketChat** (a local HTTP server capturing `chat.postMessage`),
  so no live RocketChat is needed offline.

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
- **Skills system** *(pi parity backlog #1)* — pi hats reference `skills: [name, ...]`;
  each skill is a directory (`.pi/all-skills/<name>/SKILL.md` + optional tool
  implementations) injected into the system prompt as `<available_skills>` and
  filtered per-hat. Ki equivalent: a `skills` field on the manifest, `Bootstrap`
  resolving skill paths and loading `SKILL.md` content into context, with per-session
  filtering. (Ki's single-manifest design means no per-hat filtering hook is needed —
  see pi backlog #9.)

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
- **Remote-Postgres IT for `ki-cluster`** *(M4 deferral)* — the `SessionStore`/`CheckpointStore`
  contracts against a real Postgres (Testcontainers), behind `KI_IT`. *(M10 already added
  Testcontainers-Postgres coverage for the coordination primitives.)*
- **Golden / snapshot tests for TUI rendering** *(backlog)* — lock the differential
  renderer against recorded line buffers.

**Acceptance**
- The full agent loop passes against a live endpoint under `KI_IT`.
- Postgres and SQLite stores pass the same `SessionStore` suite.

---

## M17 — Multi-file manifest config (no-override merge)

> **Sequencing:** pulled ahead as the immediate next work item (ahead of M10–M16 in
> execution order). Keeps the number M17 rather than renumbering into an M10 slot —
> M10/M11 are anchored in shipped commit messages (`M10 orchestration loop`, `M9.x`),
> so renumbering them would leave PLAN.md permanently disagreeing with `git log`.

**Goal:** let a project split one agent's config across multiple TOML files instead of
one growing `ki.toml`, without inventing override/precedence rules — the multi-provider
catalog need (pi's `kbconfig.yaml.llmProviders`: 4 providers, 11 models vs. ki's
single `baseUrl`/`apiKeyEnv`/`model`) becomes just the first user of this, not a
bespoke file format.

**Modules:** ki-cli (config loading).

**Deliverables**
- Config loader accepts N TOML files per agent instead of one `ki.toml` — default
  discovery loads `ki.toml` plus any sibling `ki.*.toml` files in the project root;
  an explicit file list (CLI flag) overrides discovery. Each file parsed with the
  existing Jackson TOML mapper.
- Files merge into one logical `Manifest` tree by **deep union, not override**: files
  are parsed to TOML trees and merged recursively. Disjoint keys union at every depth,
  so a shared *section* may be assembled from several files (e.g. `[tools.a]` in one
  file, `[tools.b]` in another; or `[llm].base_url` here and `[llm].model` there) —
  that is the point, and it carries no ambiguity.
- **No-duplication validation**: a merge conflict is only ever the **same concrete
  scalar/array key path set in two files** (e.g. both define `[llm].model`, or both
  define `context.files`). On conflict the loader throws, naming the dotted key path
  and *both* file paths. There is no last-file-wins or load-order-dependent behavior;
  a collision is always a config error the user fixes, never resolved silently. (Arrays
  don't deep-merge — an array-valued key defined in two files is a conflict, not a
  concatenation, keeping order deterministic.)
- Model/provider catalog is just an example split under this mechanism: e.g.
  `ki.providers.toml` carrying `[[providers]]` / `[[providers.models]]` (`id`,
  `modelId`, `displayName`, `reasoning`, `contextWindow`, `maxTokens`, `apiKeyEnv`),
  merged like any other file — no separate file format, precedence chain, or
  `defaultModel` fallback needed.
- `/model` slash command lists providers/models found across all loaded files.
- Unit tests: two disjoint files merge into one manifest; a shared section assembled
  from two files (disjoint keys) merges; the same scalar key in two files errors and
  names the dotted path + both files; an array key in two files errors; a
  single-`ki.toml` project behaves exactly as today.

**Acceptance**
- A project with just `ki.toml` boots unchanged — no other files required.
- A project split into `ki.toml` + `ki.providers.toml` (disjoint sections) merges
  cleanly into one `Manifest`.
- Two files setting the same key path fail fast with a "duplicate key `X` defined in
  `fileA` and `fileB`" error, not a silent override.

**Notes:** distinct from the per-manifest `[models]` alias catalog already in `ki.toml`
(M4) — that's a single-file alias table, this is the general multi-file split
mechanism. Supersedes an earlier single-purpose `ki-providers.toml`-with-precedence
design — union-merge + hard duplicate-key errors removes the override-ordering
ambiguity entirely.

---

## `extension-hooks` — Extension hooks (tool_call / tool_result / provider_request interceptors)

*Slug: `extension-hooks` · legacy alias M18 · depends-on: none (builds on shipped `core-tools`,
`result-preview`).*

> **Sequencing:** the **active** work item (`▶`) — implemented immediately. Its slug is its
> identity; the `▶` status is what marks it next, not its number (see
> [Milestone identity & ordering](#milestone-identity--ordering-convention)). Unblocks
> `todos/ki-interceptors-plan.md` (porting pi's `bash-guard`, `rules`, `env-mask` extensions),
> stalled purely on ki having no hook surface. Promotes the "Extension system" residual item
> (pi parity backlog #4) into a real milestone.

**Goal:** an extension can add **hooks** (behavioral interceptors), not just tools — so a
`.ki.kts` module can validate/block/modify a tool call, post-process a tool result, and
mask the outgoing LLM payload. Today an extension is degenerate: `ScriptToolLoader.load`
compiles a script whose final expression must be a single `ScriptToolSpec` (`tool(...) {}`),
returns exactly one tool, and there is no place to attach behavior around the loop.

**Modules:** ki-agent (hook types, `InterceptorChain`, tool + executor wrappers, script DSL),
ki-cli (Bootstrap wiring, `session_start`), ki-tui/ki-cli (blocked-call rendering).

### Investigation findings — where ki can actually intercept

Interception must be **strategy-independent**: `KiAgent` builds *two* koog graphs
(`streamingStrategy()` and koog's `singleRunStrategyWithHistoryCompression`), so anything
wired as a custom graph node has to be added to both (and would have to fabricate a
`ReceivedToolResults` to represent a block). The two seams that both graphs funnel through —
and that new graphs would inherit for free — are the **tool object** and the
**`PromptExecutor`**. That, not "EventHandler is observe-only," is the deciding reason to
wrap rather than add nodes. (koog's `EventHandler` `onToolCall*` callbacks are observe-only
anyway — they cannot block or rewrite — so they are unusable for interception regardless.)

| pi hook | ki seam (verified) | Mechanism |
|---------|--------------------|-----------|
| `tool_call` (permit/block/modify) | wrap each `Tool<JsonObject,String>` | koog runtime calls `ToolBase.executeUnsafe` (final) → `Tool.execute(args, metadata)` (final) → **abstract `execute(args)`**, which every ki tool (`ScriptTool`, `EditTool`) implements. A decorator subclassing `Tool<JsonObject,String>` and overriding `execute(args)` is guaranteed on the path — confirmed by decompiling `agents-tools-jvm-1.0.0-preview7`. |
| `tool_result` | same tool wrapper | post-process the `String` result after `delegate.execute`. |
| `before_provider_request` | wrap `KiLlm.executor` (`PromptExecutor`) | both graphs' LLM calls funnel through `llm.executor`; `RetryingPromptExecutor` + `DoubleEncodedArgsWorkaroundClient` are the existing precedent for delegating wrappers. Transform the `Prompt` before delegating. |
| `session_start` | `Bootstrap.build` | invoke `onSessionStart(root)` once after the session is assembled, before the first turn. |
| `turn_end` (auto-session-name) | `KiController` after `agent.run` returns | out of scope for M18 — deferred to the M15 rich-rendering/naming work; listed here only to map the full pi hook set. |

### Design

- **One `Extension` unit (not a separate `[hooks]` table).** pi unifies tools + hooks in one
  extension, and the ask is exactly "extensions can also add hooks." A `.ki.kts` script's
  result becomes an `Extension { tools: List<ScriptToolSpec>, hooks: List<Hook> }` instead of
  a bare `ScriptToolSpec`. **Contract change point:** `ScriptToolLoader.valueOrThrow` currently
  *requires* the final expression be a `ScriptToolSpec`; it must accept an `Extension`. A
  script that ends in a lone `tool(...) {}` stays valid — the loader lifts a bare
  `ScriptToolSpec` into a single-tool, zero-hook `Extension`, so every existing `[tools.*]`
  script keeps working untouched.
- **Script DSL** gains hook registrars alongside `tool { }`, collected into the returned
  `Extension`: `onToolCall("bash") { args -> Permit | Block(reason) | Modify(newArgs) }`,
  `onToolResult("read") { text -> newText }`, `onProviderRequest { prompt -> newPrompt }`,
  `onSessionStart { root -> }`. A hook names the tool(s) it targets (or `*`); the chain only
  wraps tools that at least one hook targets.
- **`InterceptorChain`** (ki-agent) collects every loaded extension's hooks and exposes:
  wrap a `Tool` in an `InterceptingTool` (runs `onToolCall` chain → on `Block` short-circuits
  without calling the delegate; on `Modify` feeds new args in; runs `onToolResult` on the way
  out), and wrap the `PromptExecutor` in an `InterceptingPromptExecutor` (runs the
  `onProviderRequest` chain over `execute`/`executeStreaming`/`executeMultipleChoices`).
  Ordering is load order (deterministic from the merged manifest); multiple `Modify`s compose.
- **Wiring (Bootstrap):** after building tools + `KiLlm`, build the chain from loaded
  extensions, map tools through `chain.wrap(tool)`, and replace `llm.executor` with
  `chain.wrap(executor)` before constructing `KiAgent`. `KiAgent` itself is unchanged — it
  receives already-wrapped tools and an already-wrapped executor.
- **Two correctness constraints called out so they aren't found during implementation:**
  - **Blocked call must be a distinct signal, not a fake-success.** A wrapper that returns a
    `String` for a block makes koog's `EventHandler` fire `onToolCallCompleted`, so the UI
    would paint a *blocked* call as **OK** (green). The `ToolCallEvent`/`ToolPhase` surface
    (M9.2) needs a `BLOCKED` phase (or the wrapper throws a typed block so `onToolCallFailed`
    fires) so the transcript distinguishes "model was denied" from "tool succeeded."
  - **`onProviderRequest` must produce a *new* `Prompt`,** never mutate the one backing
    persisted chat-memory — env-mask replacing secrets in-place would otherwise corrupt the
    stored transcript. The wrapper builds a masked copy for the wire only.

**Config**

```toml
[extensions.guards]
script = "tools/guards.ki.kts"   # registers onToolCall/onToolResult hooks (+ optional tools)
```

`[tools.<name>]` stays the single-tool shorthand; `[extensions.<name>]` is the hook-bearing
form. Both compile through `ScriptToolLoader`; the only difference is which manifest table
lists them and that an extension may contribute hooks. (Open sub-decision: whether to fold
`[tools]` entirely into `[extensions]` as pi does, or keep the shorthand — leaning keep, since
the tool-only case is the common one and `[tools.bash]`-style bare builtins read cleanly.)

**Deliverables**
- `Extension`, `Hook` (`ToolCallHook`/`ToolResultHook`/`ProviderRequestHook`/`SessionStartHook`),
  and `InterceptionResult = Permit | Block(reason) | Modify(args)` types in ki-agent.
- Script DSL: `onToolCall`/`onToolResult`/`onProviderRequest`/`onSessionStart` registrars +
  `Extension` return contract; `ScriptToolLoader` accepts `Extension` or lifts a bare
  `ScriptToolSpec`.
- `InterceptingTool` + `InterceptingPromptExecutor` + `InterceptorChain`.
- `ToolPhase.BLOCKED` (or typed block exception) + renderer support for a blocked line.
- Bootstrap: parse `[extensions.*]`, build the chain, wrap tools + executor, fire
  `onSessionStart(root)`.
- Unit tests: `onToolCall` block short-circuits (delegate never runs) and surfaces the reason;
  `Modify` rewrites args seen by the delegate; two hooks on one tool compose in load order;
  `onToolResult` transforms output; `onProviderRequest` masks a wire `Prompt` while the
  persisted history keeps the raw value; a bare `tool(...) {}` script still loads as a
  zero-hook extension.

**Acceptance**
- A `guards.ki.kts` extension registering `onToolCall("bash")` blocks a disallowed command:
  the delegate never executes and the model receives the block reason as the tool result.
- An `env-mask.ki.kts` extension registering `onProviderRequest` replaces a secret in the
  outgoing payload; the same secret is still present verbatim in the persisted session store.
- A project with only `[tools.*]` (no `[extensions]`) boots and behaves exactly as today.

**Notes:** this milestone delivers only the *hook surface*; porting the three concrete pi
extensions (`bash-guard`, `rules`, `env-mask`) is the follow-up tracked in
`todos/ki-interceptors-plan.md` — that todo's "verify what hooks ki exposes" dependency is
answered by the findings table above (Option C — hybrid Kotlin logic + thin `.ki.kts` glue —
is the fit, since `InterceptorChain`/wrappers are compiled ki-agent code and the extension
scripts are the glue). `commands` and `providers` from pi's extension model (backlog #4) stay
out of scope; M18 is tools + hooks only.

---

## Residual / opportunistic (not milestoned)

Low-priority items that don't warrant their own milestone; fold into a nearby one if
convenient:

- Chat-memory **preprocessor ordering** tuning (trim/filter) — passthrough today (M4/M6);
  M6's compression strategy already meets the window-budget goal.
- **HikariCP-pooled local backend** (M4) — unneeded for a single-user CLI; add only if a
  local multi-connection use case appears.
- **Extension system** *(pi parity backlog #4)* — pi auto-discovers `.pi/extensions/*.ts`
  as a runtime plugin mechanism (tools, commands, event handlers, providers), selectable
  per-hat. **Tools + hooks promoted to M18** (extensions can add interceptors, not just
  tools). Residual remainder: `commands` and `providers` as extension-contributed units, and
  auto-discovery of an `extensions/` dir (M18 lists each extension in `[extensions.*]`).
- **Submodules / repo pulling** *(pi parity backlog #5)* — pi hats can list
  `submodules: all | [paths]`; on activation, `git pull --ff-only` runs in each repo in
  the background to keep multi-repo sessions current. Ki assumes a single repo.
  Priority: low, post-M14.
- **Confluence cache config** *(pi parity backlog #6)* — pi's `kbconfig.yaml` has a
  `confluence` section (cache dirs, page roots, depth, exclusions) backing a
  `confluence_push` tool. Project-specific, not a general ki feature — leave to pi/scripts
  unless ki specifically needs it; if so, a generic `[cache]` section for remote-doc
  syncing would be the shape.
- **Telemetry** *(pi parity backlog #7)* — pi logs sessions to JSONL served by a Flask
  UI. Ki already has structured logging (logback, `.ki/logs/`) plus its session store;
  likely unneeded, but event hooks + JSONL export could be added if a UI is wanted later.
- **Hat/role switching with context reset** *(pi parity backlog #8)* — pi's `/hat`
  selects a role (tools/skills/includes/model), compacting and resetting context on
  switch. Ki is single-manifest-per-session; would need a `manifest` field referencing
  other manifests + a `/role` command + compaction-on-switch. Priority: low, nice-to-have,
  post-M14.
- ~~`before_provider_request` skill filtering~~ *(pi parity backlog #9)* — **won't
  implement**, moot under ki's single-manifest design (no per-hat skill set to filter).

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

**M1–M4, M6–M9, M9.1, M9.2 are done** (M5 deferred); **M10/M11** are in progress. Each
milestone is independently shippable; keep the integration-test-behind-`KI_IT` discipline
so `gradle build` stays green offline.
