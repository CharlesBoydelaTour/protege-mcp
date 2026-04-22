# protege-mcp

## Overview

`protege-mcp` is an OSGi bundle that attaches a [Model Context Protocol](https://modelcontextprotocol.io)
(MCP) server to a running Protégé Desktop instance. The server speaks JSON-RPC 2.0 over `stdio`
(Content-Length framing) and exposes the open OWL ontologies of the host Protégé workspace as MCP
tools. V1 supports read introspection, high-level semantic writes with a sandbox-first model,
reasoning operations (consistency check, classification, DL queries), and on-disk persistence.
The full surface is described in [docs/mcp-server-spec.md](../docs/mcp-server-spec.md).

## Requirements

- JDK 11+ at runtime (the bundle targets the same baseline as Protégé Desktop 5.6.x).
- Maven 3.6+ to build from source. When building under JDK 23+ the annotation processor switch
  `-Dmaven.compiler.proc=full` is required for upstream modules; pass it on the command line.
- Protégé Desktop 5.6.x install (5.6.10-SNAPSHOT in this workspace) into which the bundle is dropped.

## Build

From the workspace root:

```bash
mvn -pl protege-mcp -am package
```

If you are rebuilding the upstream modules from a clean state on JDK 23+:

```bash
mvn -pl protege-mcp -am -Dmaven.compiler.proc=full package
```

The resulting bundle JAR is written to:

```
protege-mcp/target/protege-mcp-<version>.jar
```

For the current parent version (`5.6.10-SNAPSHOT`):

```
protege-mcp/target/protege-mcp-5.6.10-SNAPSHOT.jar
```

## Install into Protégé Desktop

The Protégé Desktop assembly (see [protege-desktop/src/main/assembly/dependency-sets.xml](../protege-desktop/src/main/assembly/dependency-sets.xml)
and [protege-os-x.xml](../protege-desktop/src/main/assembly/protege-os-x.xml)) places OSGi
bundles in a `bundles/` directory. Copy the built JAR there:

- macOS:
  ```bash
  cp protege-mcp/target/protege-mcp-5.6.10-SNAPSHOT.jar \
     "/Applications/Protege-5.6.x/Protege.app/Contents/bundles/"
  ```
- Linux:
  ```bash
  cp protege-mcp/target/protege-mcp-5.6.10-SNAPSHOT.jar \
     /opt/Protege-5.6.x/bundles/
  ```

Adjust the install root to wherever Protégé Desktop is installed on your machine.

## Enable the server

The bundle is opt-in. On startup the activator logs a disabled message unless one of the
following is set (see [`ProtegeMcpBundleActivator`](src/main/java/org/protege/mcp/ProtegeMcpBundleActivator.java)):

1. **JVM system property** – pass `-Dprotege.mcp.enabled=true` to Protégé. Add it to:
   - macOS: `Protege.app/Contents/Info.plist` under `Eclipse/JVMOptions` (or the launcher
     `Protege.cfg` used by your build).
   - Linux: the `Protege.cfg` next to the `Protege` launcher script.
2. **Environment variable** – export `PROTEGE_MCP_ENABLED=true` in the shell before launching
   Protégé.

Either mechanism enables the server. If both are absent, the bundle starts inert.

## Transport

V1 uses `stdio` only. The server reads JSON-RPC 2.0 messages from `stdin` and writes responses
to `stdout`, each prefixed with a `Content-Length` header per the MCP stdio spec:

```
Content-Length: <byte-count>\r\n
\r\n
<utf-8 json body>
```

Because Protégé Desktop is a GUI process, a usable `stdio` transport requires that Protégé be
launched from a terminal (so its `stdin`/`stdout` are connected) or, more commonly, that an MCP
host launches Protégé as a subprocess and pipes JSON-RPC over the inherited streams. There is
no HTTP transport in V1.

## MCP handshake example

Client → server (`initialize`):

```
Content-Length: 124\r\n
\r\n
{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-03-26","capabilities":{},"clientInfo":{"name":"demo","version":"0"}}}
```

Server → client (response shape):

```json
{
  "jsonrpc": "2.0",
  "id": 1,
  "result": {
    "protocolVersion": "2025-03-26",
    "capabilities": { "tools": {} },
    "serverInfo": { "name": "protege-mcp", "version": "5.6.10-SNAPSHOT" }
  }
}
```

The client then sends `notifications/initialized`, followed by `tools/list` and `tools/call`.

## Tool catalogue

Sourced from [`ProtegeMcpToolExecutor.buildToolDefs()`](src/main/java/org/protege/mcp/ProtegeMcpToolExecutor.java).

| Tool | Description |
| --- | --- |
| `server_info` | Return server-level metadata: version, transport, and capabilities. |
| `ontology_list` | List all ontologies currently open in the attached Protégé workspace. |
| `ontology_open` | Load an ontology from a URI into the attached workspace. |
| `ontology_close` | Close an ontology workspace and discard its sandbox. |
| `ontology_info` | Return axiom counts, format, and workspace state for an open ontology. |
| `ontology_capabilities` | Describe write support, reasoner availability, and export formats. |
| `workspace_status` | Inspect dirty state, sandbox size, and lock state. |
| `entity_search` | Search entities by fragment or label fragment; returns IRI, rendering, and type. |
| `entity_get` | Get structured details and annotations for a single entity by IRI. |
| `hierarchy_get` | Return asserted parents and children for a class in the class hierarchy. |
| `axioms_list` | List axioms for the ontology or for a specific entity. Supports `limit` and `entity_iri`. |
| `dl_query` | Execute a Manchester Syntax DL expression; returns instances or subclasses. |
| `class_create` | Declare a new OWL class, optionally with a parent. Defaults to sandbox. |
| `property_create` | Declare a new property (`object`/`data`/`annotation`). Defaults to sandbox. |
| `individual_create` | Declare a named individual, optionally with an `rdf:type`. Defaults to sandbox. |
| `individual_assert_type` | Add an `rdf:type` assertion for an existing individual. Defaults to sandbox. |
| `annotation_set` | Add an annotation assertion axiom (e.g. `rdfs:label`). Defaults to sandbox. |
| `ontology_validate` | Run structural validation checks and report issues. |
| `consistency_check` | Check ontology consistency with the active reasoner. |
| `reasoner_classify` | Run classification and return unsatisfiable class count. |
| `ontology_save` | Persist the ontology to its current physical location. |
| `ontology_export` | Export ontology content as a string in the specified format (`Turtle`, `RDF/XML`, `Manchester`). |
| `sandbox_commit` | Promote pending sandbox axioms to the live ontology. |

There are 23 tools currently registered. The spec also describes `sparql_query` and the
low-level axiom / snapshot / diff tools as deferred to V2 (see *Limits / out of scope* below
and section 5 of the spec).

The `version` reported in `serverInfo` is sourced at runtime from the bundle's OSGi
`Bundle-Version` (or, outside OSGi, from the Maven-generated
`META-INF/maven/edu.stanford.protege/protege-mcp/pom.properties`), so it tracks the parent
project version automatically.

## Sandbox model

Write tools default to per-ontology sandboxing. Common arguments:

- *(default)* — the change is appended to the workspace's sandbox (an ordered list of pending
  axiom additions). The live ontology is untouched until promoted.
- `dry_run: true` — the change is validated and the resulting change-set is reported, but
  nothing is queued in the sandbox or applied to the live ontology.
- `direct: true` — the sandbox is bypassed and the change is applied straight to the live
  ontology. Use only when you do not need staged review.
- `sandbox_commit` — promotes all pending sandbox axioms for the target `ontology_id` to the
  live ontology in one batch.

`ontology_save` is a separate step that flushes the live ontology to its physical document.

## Errors

Error responses follow JSON-RPC 2.0:

```json
{
  "jsonrpc": "2.0",
  "id": 1,
  "error": { "code": -32601, "message": "...", "data": { } }
}
```

| Code | Meaning |
| --- | --- |
| `-32600` | Invalid request (missing `method`). |
| `-32601` | Unknown method or unknown tool name. |
| `-32602` | Invalid arguments (missing or malformed required field). |
| `-32010` | Domain "not found" (ontology, entity, ...). |
| `-32000` | Generic server error (save/export failure, no active reasoner, no workspace, etc.). |

`data` is an optional JSON object carrying diagnostic detail (offending field name, exception
class and message, ontology id, etc.).

## Testing

```bash
mvn -pl protege-mcp test
```

The module ships 15 tests across three suites:

- [`McpContentLengthCodecTest`](src/test/java/org/protege/mcp/McpContentLengthCodecTest.java) — Content-Length framing read/write paths.
- [`ProtegeMcpServerTest`](src/test/java/org/protege/mcp/ProtegeMcpServerTest.java) — JSON-RPC dispatch, `initialize`, `tools/list`, `tools/call`, error mapping.
- [`ProtegeMcpStdioSmokeTest`](src/test/java/org/protege/mcp/ProtegeMcpStdioSmokeTest.java) — end-to-end stdio smoke test driving the server through pipes.

## Limits / out of scope for V1

Per [docs/mcp-server-spec.md](../docs/mcp-server-spec.md):

- No SPARQL query tool. The spec lists `sparql_query` as deferred to V2; it is not registered
  in the current `buildToolDefs()`.
- No low-level axiom add/remove tools. Writes are exposed only through high-level semantic
  helpers (`class_create`, `property_create`, `individual_create`, `individual_assert_type`,
  `annotation_set`).
- No snapshot or diff tools.
- No HTTP transport — `stdio` only.
- No multi-sandbox support per ontology — at most one sandbox per workspace.

## Integrated launch

The bundle is wired into the Protégé Desktop assembly (see
[protege-desktop/src/main/assembly/dependency-sets.xml](../protege-desktop/src/main/assembly/dependency-sets.xml)),
so a full desktop build ships `protege-mcp-<version>.jar` under `bundles/` automatically.

Build the full desktop distribution:

```bash
mvn -DskipTests -Dmaven.compiler.proc=full -pl protege-desktop -am package
```

The platform-independent archive lands at:

```
protege-desktop/target/protege-${project.version}-platform-independent.zip
```

Extract and launch with the opt-in flag:

```bash
unzip protege-desktop/target/protege-5.6.10-SNAPSHOT-platform-independent.zip
cd Protege-5.6.10-SNAPSHOT
./run.sh -Dprotege.mcp.enabled=true
```

`run.sh` is the launcher emitted by the assembly; it boots Felix via `protege-launcher`
and loads every JAR in `bundles/`, including `protege-mcp.jar`. Because the V1 transport
is `stdio` and consumes the process's `stdout`, the intended use case is for an MCP host
to spawn Protégé as a subprocess and pipe JSON-RPC over the inherited streams (or to run
`run.sh` directly from a terminal whose `stdin`/`stdout` are wired to the host).

The macOS `Protégé.app` bundle produced by the `protege-os-x` assembly contains the same
`bundles/` layout, but `stdio` is awkward to use from a double-click launch — prefer the
platform-independent zip when wiring an MCP host.

## VS Code (GitHub Copilot Chat)

VS Code's native MCP support reads server definitions from an `mcp.json` file. There are two
locations:

- **Workspace-scoped:** `.vscode/mcp.json` at the project root.
- **User-scoped:** the profile-level `mcp.json` opened via the command palette entry
  *MCP: Open User Configuration*.

A ready-to-paste example lives at [examples/vscode-mcp.json](examples/vscode-mcp.json). It
declares two stdio servers — `protege-mcp` (macOS / Linux, spawning `run.sh`) and
`protege-mcp-windows` (spawning `run.bat`) — and uses VS Code's `inputs` mechanism to prompt
for the extracted Protégé directory and an optional `JAVA_HOME`.

Step-by-step:

1. **Build & extract Protégé Desktop** (once):
   ```bash
   mvn -DskipTests -Dmaven.compiler.proc=full -pl protege-desktop -am package
   unzip protege-desktop/target/protege-5.6.10-SNAPSHOT-platform-independent.zip -d ~/tools
   ```
   Note the absolute path of the extracted folder (the one containing `run.sh`); you will
   paste it into the `protegeHome` prompt.
2. **Install the config.** Copy `examples/vscode-mcp.json` to `.vscode/mcp.json` for a
   workspace-scoped install, or open *MCP: Open User Configuration* and merge the
   `inputs` / `servers` entries into the user file. Delete whichever of the
   `protege-mcp` / `protege-mcp-windows` entries does not match your OS.
3. **Reload VS Code.** Run *MCP: List Servers*; `protege-mcp` should appear. Click *Start* —
   VS Code spawns Protégé as a stdio child process, prompting you for `protegeHome` (and
   `javaHome`) on first launch. The opt-in flag is forwarded via the `CMD_OPTIONS`
   environment variable that `run.sh` appends to the JVM command line.
4. **Use Copilot Chat in agent mode** and reference tools by name, e.g.
   `#protege-mcp/ontology_list` or `#protege-mcp/dl_query`.

A Swing window will pop up when Protégé starts (the editor kit needs an AWT environment, so
headless mode is **not** supported). Send the window to the background — do **not** quit
Protégé from its menu, as that would terminate the MCP server subprocess. VS Code's *Stop*
button on the server entry is the correct way to shut it down.

The full tool catalogue and JSON-RPC surface are documented in
[docs/mcp-server-spec.md](../docs/mcp-server-spec.md).
