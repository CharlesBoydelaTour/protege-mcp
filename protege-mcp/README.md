# protege-mcp

## Overview

`protege-mcp` is an OSGi bundle that attaches a [Model Context Protocol](https://modelcontextprotocol.io)
(MCP) server to a running Protégé Desktop instance. The server speaks JSON-RPC 2.0 over `stdio`
or a localhost TCP socket (Content-Length framing) and exposes the open OWL ontologies of the
host Protégé workspace as MCP tools. V1 supports read introspection, high-level semantic writes with a sandbox-first model,
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

**Enabled by default.** Launch Protégé normally and the MCP server starts on stdio as soon as
the bundle is activated by Felix (see
[`ProtegeMcpBundleActivator`](src/main/java/org/protege/mcp/ProtegeMcpBundleActivator.java)).

To disable it, set either of the following to a falsy value
(`false`, `0`, `no`, `off`, `disabled`, case-insensitive):

1. **JVM system property** – pass `-Dprotege.mcp.enabled=false` to Protégé. Add it to:
   - macOS: `Protege.app/Contents/Info.plist` under `Eclipse/JVMOptions` (or the launcher
     `Protege.cfg` used by your build).
   - Linux: the `Protege.cfg` next to the `Protege` launcher script.
2. **Environment variable** – export `PROTEGE_MCP_ENABLED=false` in the shell before launching
   Protégé.

Setting the system property explicitly to `true` or leaving both unset both result in the
server starting normally.

## Transports

The bundle exposes **two transports**, both enabled by default and both speaking
the same `Content-Length`-framed JSON-RPC 2.0 dialect:

- **Stdio** — JSON-RPC over `stdin`/`stdout` of the host Protégé process. Useful
  when an MCP host launches Protégé as a subprocess (or when Protégé is started
  from a terminal). Disable with `-Dprotege.mcp.enabled=false` or
  `PROTEGE_MCP_ENABLED=false`.
- **Localhost TCP** — `127.0.0.1` only, default port **47800**. Lets an MCP
  client attach to a Protégé instance you launched yourself (Dock icon, Start
  menu, double-clicked `.app`, …) where stdio is not available. Disable with
  `-Dprotege.mcp.tcp.enabled=false` or `PROTEGE_MCP_TCP_ENABLED=false`. Change
  the port with `-Dprotege.mcp.tcp.port=<n>` or `PROTEGE_MCP_TCP_PORT=<n>`.

The TCP listener is bound exclusively to the loopback interface; it is not
reachable from other hosts. There is no HTTP/SSE transport in this release.

The built `protege-mcp-<version>.jar` is **dual-purpose**: it is the OSGi
bundle that Felix loads inside Protégé Desktop **and** an executable
stdio↔TCP relay (`Main-Class: org.protege.mcp.ProtegeMcpRelay`, zero non-JDK
dependencies). MCP hosts that only speak stdio (such as VS Code) use
`java -jar protege-mcp.jar` to bridge to a Protégé instance you launched
yourself — see *Attach mode (recommended)* below.

## Transport (stdio detail)

The framed payload format, identical on both stdio and TCP, follows the
[MCP stdio spec](https://modelcontextprotocol.io/docs/concepts/transports):

```
Content-Length: <byte-count>\r\n
\r\n
<utf-8 json body>
```

Because Protégé Desktop is a GUI process, the stdio transport requires that
Protégé be launched from a terminal (so its `stdin`/`stdout` are connected) or
that an MCP host launches Protégé as a subprocess and pipes JSON-RPC over the
inherited streams. For the much more common case of an already-running Protégé
GUI, use the localhost TCP transport (and the relay) described above.

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

### V1.5 additions

V1.5 introduces direct-mode entity CRUD (no sandbox, no `dry_run`),
multilingual annotation editing with REPLACE semantics, Manchester-syntax
axiom-level write tools, and an explicit on-disk reload. Every V1.5 write
applies its changes via the Swing EDT (see *EDT wrapping* below) so that
mutations are safe to issue against a live Protégé workspace. V1 direct-mode
writes (`direct: true`) are now also EDT-wrapped.

**Class CRUD**

| Tool | Description |
| --- | --- |
| `class_delete` | Remove all axioms referencing a class (direct mode). |
| `class_rename` | Rename a class IRI across the active ontology via `OWLEntityRenamer`. |

**Object Property CRUD**

| Tool | Description |
| --- | --- |
| `object_property_create` | Declare a new object property, optionally as sub-property of a parent. |
| `object_property_delete` | Remove all axioms referencing an object property. |
| `object_property_rename` | Rename an object property IRI across the active ontology. |

**Data Property CRUD**

| Tool | Description |
| --- | --- |
| `data_property_create` | Declare a new data property, optionally as sub-property of a parent. |
| `data_property_delete` | Remove all axioms referencing a data property. |
| `data_property_rename` | Rename a data property IRI across the active ontology. |

**Individual CRUD**

| Tool | Description |
| --- | --- |
| `individual_delete` | Remove all axioms referencing a named individual. |
| `individual_rename` | Rename a named individual IRI across the active ontology. |

**Annotations (label / comment)**

| Tool | Description |
| --- | --- |
| `entity_annotate_set` | Set `rdfs:label` or `rdfs:comment` on an entity. REPLACE semantics: existing annotations on the same `(entity, property, lang)` tuple are removed before the new one is added. Supports a `lang` tag. |
| `entity_annotate_remove` | Remove `rdfs:label` or `rdfs:comment` annotations from an entity. With `lang`, removes only the matching language; without, removes all annotations of that property. |

**Axiom-level (Manchester)**

| Tool | Description |
| --- | --- |
| `axiom_add` | Parse a Manchester-syntax axiom string and add it to the ontology. Errors with `-32602` on parse failure. |
| `axiom_remove` | Parse a Manchester-syntax axiom string and remove it. Idempotent — removing a missing axiom still returns success. |

**Reload**

| Tool | Description |
| --- | --- |
| `ontology_reload` | Force-reload an ontology from its physical URI through the workspace `OWLModelManager`. Discards in-memory edits and the per-ontology sandbox, and invalidates Protégé caches (entity finder, short-form provider). |

**EDT wrapping.** All V1.5 mutation tools — and now V1 direct-mode writes
plus `sandbox_commit` — apply their changes on the Swing Event Dispatch
Thread via an internal `runOnEdt(Runnable)` helper. This avoids deadlocks
between the MCP I/O thread and Protégé's UI listeners, which expect to be
notified on the EDT. In headless mode (unit tests, no `GraphicsEnvironment`)
the runnable executes inline. If the call already runs on the EDT, the
helper short-circuits.

There are **38 tools** currently registered (23 V1 + 15 V1.5). The spec also
describes `batch_apply`, `snapshot_create`, `snapshot_restore`, `diff_get`,
and `sparql_query` as deferred to V2 (see *Limits / out of scope* below
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
- No batch / snapshot / diff tools (`batch_apply`, `snapshot_create`,
  `snapshot_restore`, `diff_get`) — still deferred to V2.
- V1.5 axiom-level write tools (`axiom_add`, `axiom_remove`) cover the
  `axiom_add` / `axiom_remove` slots from section 4.5 of the spec.
- No HTTP transport — `stdio` and localhost TCP only (added in 0.3.0).
- No multi-sandbox support per ontology — at most one sandbox per workspace.
  V1.5 mutation tools bypass the sandbox and write directly to the live
  ontology.

## Integrated launch

The bundle is wired into the Protégé Desktop assembly (see
[protege-desktop/src/main/assembly/dependency-sets.xml](../protege-desktop/src/main/assembly/dependency-sets.xml)),
so a full desktop build ships `protege-mcp-<version>.jar` under `bundles/` automatically.

Build the full desktop distribution:

```bash
mvn -DskipTests -Dmaven.compiler.proc=full -pl protege-desktop -am package
```

The platform-independent archive lands at:
:

```bash
unzip protege-desktop/target/protege-5.6.10-SNAPSHOT-platform-independent.zip
cd Protege-5.6.10-SNAPSHOT
./run.sh
```

The MCP server starts automatically on stdio. Append `-Dprotege.mcp.enabled=false` to the
`run.sh` invocation if you want to launch Protégé without it.
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

## Java relay (stdio ↔ TCP bridge)

The same `protege-mcp-<version>.jar` doubles as a standalone executable
relay — `Main-Class: org.protege.mcp.ProtegeMcpRelay`, zero non-JDK
dependencies. It pumps Content-Length-framed JSON-RPC bytes between its own
`stdin`/`stdout` and a TCP socket on the running Protégé bundle, with a 30 s
connect retry loop so VS Code can be started before Protégé.

```bash
java -jar protege-mcp-5.6.10-SNAPSHOT.jar [--host=127.0.0.1] [--port=47800]
```

Configuration precedence (highest first): CLI flag → system property → env
var → default.

| Setting | CLI flag | System property | Env var | Default |
| --- | --- | --- | --- | --- |
| Host | `--host=…` | `-Dprotege.mcp.host=…` | `PROTEGE_MCP_HOST` | `127.0.0.1` |
| Port | `--port=…` | `-Dprotege.mcp.tcp.port=…` | `PROTEGE_MCP_TCP_PORT` | `47800` |

## Attach mode (recommended)

Launch Protégé Desktop the way you normally would (Dock, Start menu,
double-click `Protégé.app`, `run.sh`, …). The bundle binds its TCP transport
automatically on `127.0.0.1:47800`. Then point an MCP host at the Java relay,
which connects to that port:

```
VS Code ──stdio──▶ java -jar protege-mcp.jar ──TCP 127.0.0.1:47800──▶ Protégé
```

Nothing else needs to be running between MCP host launches; if Protégé is
offline the relay retries the connection for up to 30 s.

## Stdio mode

For headless / one-shot use cases an MCP host can spawn Protégé itself and
speak JSON-RPC over the inherited streams (`run.sh` / `run.bat` from the
platform-independent distribution). The bundle's stdio transport is
active whenever `protege.mcp.enabled` is not falsy. This bypasses the relay
entirely but ties the lifetime of Protégé to the MCP host process.

## VS Code (GitHub Copilot Chat)

VS Code's native MCP support reads server definitions from an `mcp.json` file:

- **Workspace-scoped:** `.vscode/mcp.json` at the project root.
- **User-scoped:** the profile-level `mcp.json` opened via *MCP: Open User
  Configuration*.

A ready-to-paste example lives at [examples/vscode-mcp.json](examples/vscode-mcp.json),
which matches the workspace-level [`.vscode/mcp.json`](../.vscode/mcp.json)
used in this repository:

```json
{
  "inputs": [
    {
      "id": "protegeBundleJar",
      "type": "promptString",
      "description": "Absolute path to protege-mcp-<version>.jar (also acts as the stdio<->TCP relay)."
    },
    {
      "id": "javaBin",
      "type": "promptString",
      "description": "Path to a Java 11+ binary used to run the relay.",
      "default": "java"
    },
    {
      "id": "protegeMcpPort",
      "type": "promptString",
      "description": "TCP port exposed by the Protege MCP bundle (default 47800).",
      "default": "47800"
    }
  ],
  "servers": {
    "protege-mcp": {
      "type": "stdio",
      "command": "${input:javaBin}",
      "args": [
        "-jar",
        "${input:protegeBundleJar}",
        "--host=127.0.0.1",
        "--port=${input:protegeMcpPort}"
      ]
    }
  }
}
```

Step-by-step:

1. **Build the bundle** once: `mvn -pl protege-mcp -am package`. The resulting
   `protege-mcp/target/protege-mcp-5.6.10-SNAPSHOT.jar` is both the OSGi
   bundle dropped into `bundles/` and the executable relay used below.
2. **Launch Protégé** (Dock icon / `Protégé.app` / `run.sh`). The TCP
   transport binds `127.0.0.1:47800` automatically; override with
   `-Dprotege.mcp.tcp.port=<n>` / `PROTEGE_MCP_TCP_PORT=<n>` or disable with
   `-Dprotege.mcp.tcp.enabled=false` / `PROTEGE_MCP_TCP_ENABLED=false`.
3. **Install the config** (copy `examples/vscode-mcp.json` to
   `.vscode/mcp.json`, or merge into your user-level file).
4. **Reload VS Code**, click *Start* on the `protege-mcp` entry, paste the
   absolute path to the bundle JAR at the `protegeBundleJar` prompt, accept
   the default port unless overridden.
5. **Use Copilot Chat in agent mode** and reference tools by name, e.g.
   `#protege-mcp/ontology_list` or `#protege-mcp/dl_query`.

The full tool catalogue and JSON-RPC surface are documented in
[docs/mcp-server-spec.md](../docs/mcp-server-spec.md).
