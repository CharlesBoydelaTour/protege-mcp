# Changelog

All notable changes to the `protege-mcp` module are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).
The MCP server is versioned independently from the host Protégé Desktop assembly.

## [Unreleased]

### Added

- `org.protege.mcp.ProtegeMcpRelay`: a pure-JDK stdio↔TCP relay packaged
  into the same `protege-mcp-<version>.jar`. The JAR is now dual-purpose
  — Felix loads it as an OSGi bundle inside Protégé, and `java -jar` runs
  it as an executable relay (`Main-Class: org.protege.mcp.ProtegeMcpRelay`,
  zero non-JDK dependencies). Accepts `--host=` / `--port=` CLI flags,
  `-Dprotege.mcp.host` / `-Dprotege.mcp.tcp.port` system properties, and
  `PROTEGE_MCP_HOST` / `PROTEGE_MCP_TCP_PORT` env vars; defaults to
  `127.0.0.1:47800`. Includes a 30 s connect retry loop so MCP hosts
  may be started before Protégé.
- New "Attach mode (recommended)" workflow documented in the README:
  launch Protégé normally → bundle auto-binds `127.0.0.1:47800` → VS Code
  spawns the Java relay via `.vscode/mcp.json` → MCP requests flow into
  the running Protégé.

### Changed

- `examples/vscode-mcp.json` rewritten to declare a single `protege-mcp`
  stdio server invoking `java -jar protege-mcp.jar` with `--host` /
  `--port` flags. Matches the workspace-level `.vscode/mcp.json`.

### Removed

- `protege-mcp/relay/protege-mcp-relay.py` (Python stdio↔TCP relay) and
  every README / example reference to it. The Java relay shipped inside
  the bundle JAR replaces it.

## [0.4.0] - 2026-04-23

### Added

- V1.5 entity CRUD tools: `class_delete`, `class_rename`, `object_property_create`,
  `object_property_delete`, `object_property_rename`, `data_property_create`,
  `data_property_delete`, `data_property_rename`, `individual_delete`, `individual_rename`.
- V1.5 annotation tools: `entity_annotate_set`, `entity_annotate_remove`
  (`rdfs:label`, `rdfs:comment` with multilingual support, REPLACE semantics).
- V1.5 axiom-level tools: `axiom_add`, `axiom_remove` (Manchester syntax). The
  parser is built via `OWLManager.createManchesterParser()` and resolves
  short forms against a `BidirectionalShortFormProviderAdapter` over the
  active ontology's signature.
- `ontology_reload`: force fresh load from disk through the workspace
  `OWLModelManager`, so Protégé caches (entity finder, short-form provider,
  hierarchy provider) get invalidated.
- `runOnEdt` helper: all V1.5 mutation tools and V1 direct-mode writes now
  run `OWLModelManager.applyChanges` via the Swing EDT, fixing a deadlock
  against running Protégé workspaces. In headless mode (unit tests) the
  runnable executes inline.

### Fixed

- `class_rename` / `*_property_rename` no longer deadlock against the live
  Protégé EDT (real-Protégé E2E confirmed in ~10ms vs `>30s` timeout
  previously).
- `ontology_reload` now properly invalidates Protégé workspace caches
  (entity finder, short-form provider) by routing the close+reload through
  `OWLModelManager` instead of the underlying `OWLOntologyManager`.

### Notes

- All V1.5 mutation tools are **direct-write only** — no sandbox, no
  `dry_run`. V1 sandbox semantics for `class_create` / `property_create` /
  `individual_create` / `individual_assert_type` / `annotation_set` are
  unchanged.
- `entity_annotate_set` REPLACE semantics: removes existing annotations on
  the same `(entity, property, lang)` tuple before adding the new one.
- `axiom_remove` is idempotent: removing an axiom that is not present in the
  ontology still returns `{"removed": true}`.

## [0.3.0] - 2026-04-23

### Added

- **Localhost TCP transport** for the MCP server, enabled by default and bound
  exclusively to `127.0.0.1:47800`. Speaks the same Content-Length–framed
  JSON-RPC 2.0 dialect as the existing stdio path; each accepted connection
  runs on its own daemon thread with a fresh `ProtegeMcpServer` instance.
  Configurable via `-Dprotege.mcp.tcp.port=<n>` / `PROTEGE_MCP_TCP_PORT=<n>`;
  disable with `-Dprotege.mcp.tcp.enabled=false` /
  `PROTEGE_MCP_TCP_ENABLED=false`. Stdio remains available regardless.
- `protege-mcp/relay/protege-mcp-relay.py` — a standalone, dependency-free
  Python 3 stdio↔TCP relay that lets an MCP host (e.g. VS Code) attach to
  an externally-launched Protégé Desktop instance.
- New VS Code MCP server entry **`protege-mcp-attach`** that spawns the relay
  so users can attach to a Protégé GUI launched from the Dock or `.app`
  bundle (where stdio is not available).
- `ProtegeMcpTcpTransportTest` covering framed initialize / `tools/call`
  over TCP, two concurrent clients, port-release on close, and port /
  enable-flag parsing.

### Changed

- VS Code example config (`examples/vscode-mcp.json`) now defaults to the
  attach flow via the relay; the previous spawn flow is preserved as
  `protege-mcp-spawn` / `protege-mcp-spawn-windows` for users who want VS Code
  to launch Protégé as a dedicated subprocess.

## [0.2.0] - 2026-04-23

The "0.2.0" tag refers to the MCP server's user-facing identity. The Maven artifact
version remains `5.6.10-SNAPSHOT`, aligned with the parent Protégé Desktop reactor;
`ProtegeMcpVersion` continues to read it from the bundle manifest, so
`serverInfo.version` is unchanged.

### Changed

- MCP server is now **enabled by default** whenever the `protege-mcp` bundle is
  present in the Felix `bundles/` directory. Previously the activator only started
  the JSON-RPC dispatcher when `protege.mcp.enabled=true` (or the equivalent
  environment variable) was supplied.
- Disable behaviour is preserved as an explicit opt-out: set
  `-Dprotege.mcp.enabled=false` or `PROTEGE_MCP_ENABLED=false` (also accepts
  `0`, `no`, `off`, `disabled`, case-insensitive) to keep the bundle inert.
  The system property takes precedence over the environment variable.
- Activator startup log now distinguishes the default-on path from an
  explicit enable, and the disabled-state log message points at the new
  opt-out flag.

## [0.1.0] - 2026-04-22

First tagged release of the MCP server bundle for Protégé Desktop.

### Added

- OSGi bundle `org.protege.mcp` that hosts an MCP server over stdio inside a
  running Protégé Desktop process. Activation is opt-in via the
  `protege.mcp.enabled` system property or the `PROTEGE_MCP_ENABLED`
  environment variable; the bundle is inert otherwise.
- JSON-RPC 2.0 transport with `Content-Length`-framed messages on
  `System.in` / `System.out`.
- MCP protocol version `2025-03-26` advertised during `initialize`.
- 23 V1 tools covering lifecycle and inspection, entity queries,
  high-level semantic writes against a per-ontology sandbox,
  validation and reasoning, persistence and export, and explicit
  sandbox commit. The full catalogue is enumerated in the
  [README](README.md) and [docs/mcp-server-spec.md](../docs/mcp-server-spec.md).
- Sandbox-first write model: every mutating tool accepts `dry_run`
  (preview only) and `direct` (bypass sandbox, write straight to the
  active ontology) flags, with the sandbox as the default target.
- Dynamic version reporting via `ProtegeMcpVersion`, which resolves the
  reported server version from the OSGi `Bundle-Version` header and
  falls back to Maven `pom.properties` when running outside an OSGi
  framework (e.g. unit tests).
- Stdout sanitization when MCP is enabled: `System.out` is redirected
  to `System.err` at startup, and every Logback `ConsoleAppender` is
  retargeted to `System.err` both at runtime and in
  `logback.xml` / `logback-win.xml`, so stray log lines cannot corrupt
  the JSON-RPC stream even when `scan="true"` reloads the config.
- JDK 17+ launch fix in all four launcher scripts (platform-independent,
  macOS, Windows, Linux): `--add-opens=java.desktop/com.apple.laf=ALL-UNNAMED`
  is added so `EditorKitManager.addEditorKit` no longer fails on the
  reflective access into Apple LAF internals.
- Activation of the bundle in the `protege-desktop` assemblies for all
  four distributions; the JAR is shipped automatically under `bundles/`.
- Example MCP host configuration for VS Code at
  [examples/vscode-mcp.json](examples/vscode-mcp.json).
- Test suite of 27 tests covering the framing codec, the server
  dispatcher, an end-to-end stdio smoke test, and write-tool sandbox
  semantics.

### Changed

- `serverInfo.version` is no longer hardcoded; it is resolved at
  runtime from the bundle manifest (or the Maven fallback) via
  `ProtegeMcpVersion`.

### Fixed

- `axiomsList`: replaced the unsupported
  `OWLOntology.getAxioms(OWLEntity)` call with
  `getReferencingAxioms(OWLEntity)` for OWLAPI 4.5.29 compatibility.
- `requireModelManager`: no longer leaks `NullPointerException` or
  OSGi internals into JSON-RPC errors when the workspace is
  unavailable; returns the canonical `"No active Protege workspace"`
  message with a structured diagnostic `data` payload.
- `ontology_open`: now invokes `setActiveOntology` after a successful
  load, so subsequent `entity_search` and `hierarchy_get` calls
  resolve against the freshly opened ontology.
- Empty editor-kit list: surfaces a clearer error message that points
  the user to `~/.Protege/logs/protege.log` for root-cause diagnosis.

### Build

- Java baseline: 11. The module has been built up to JDK 25; under
  JDK 23+ the upstream AutoValue annotation processor requires
  `-Dmaven.compiler.proc=full` on the Maven command line.
- Mockito pinned to `5.14.2` (test scope) for JDK 25 compatibility.
- bnd warnings cleaned up: dropped the unused `javax.annotation.*`
  import directive, and `org.protege.mcp` is marked as a private
  package (no `Export-Package`).

### Deferred to V2

- `sparql_query` tool (described in spec section 4.3 with a
  "Deferred to V2" note).
- Low-level axiom add/remove operations; the V1 sandbox model is
  semantic-write-only.
- Snapshots, diffs, and undo across sandbox transactions.
- HTTP and SSE transports.
- Eliminating the residual ~84-byte JVM `CompileCommand` notice that
  is emitted on stdout before the first JSON-RPC frame.

### Known limitations

- Protégé Desktop must be launched in an environment with AWT; there
  is no truly headless mode, and the editor kit refuses to register
  otherwise.
- The bundle exposes the live workspace's active ontology only;
  multi-workspace concurrency is not modelled beyond per-ontology
  sandboxes.
- Reasoner availability depends on the user installing a reasoner
  plugin. Tools that require one (`dl_query`, `consistency_check`,
  `reasoner_classify`) return JSON-RPC error `-32000`
  `"No active reasoner"` when none is registered.

### Released alongside

- Protégé Desktop assembly version `5.6.10-SNAPSHOT`.

[0.1.0]: https://semver.org/spec/v2.0.0.html
