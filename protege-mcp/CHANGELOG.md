# Changelog

All notable changes to the `protege-mcp` module are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).
The MCP server is versioned independently from the host Protégé Desktop assembly.

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
