# Protege MCP Server Specification

This document defines the **v1** [Model Context Protocol (MCP)](https://modelcontextprotocol.io) server surface for
Protege Desktop, implemented in the `protege-mcp` bundle.  The server runs **attached** to a live Protege process
and is reached through `stdio` only.  HTTP transport and SPARQL queries are out of scope for v1.

## 1. Goals

- Expose the open OWL ontologies in a running Protege Desktop instance as MCP tools.
- Support read introspection, high-level semantic writes, validation, and persistence.
- Keep writes safe through sandboxing, `dry_run`, and explicit `sandbox_commit`.
- Remain compatible with standard MCP clients and hosts.
- Opt-in only: the server must be explicitly enabled; it does not auto-start for normal desktop users.

## 2. MCP framing

### 2.1 Roles and transport

Standard MCP roles:

- **host**: the desktop app, agent runtime, or integration environment
- **client**: the MCP protocol implementation used by the host
- **server**: the Protege ontology service described in this document

**v1 transport: `stdio` only.**  The server reads Content-Length–framed JSON-RPC 2.0 messages from `stdin` and
writes responses to `stdout`.  Each message is preceded by a header of the form:

```
Content-Length: <byte-count>\r\n\r\n
```

followed by the UTF-8 JSON body.  This matches the [MCP stdio transport spec](https://modelcontextprotocol.io/docs/concepts/transports).

HTTP transport is deferred to a future version.

### 2.2 Opt-in startup

The server is **disabled by default**.  To enable it, set one of the following before Protege starts:

| Mechanism | Value |
|---|---|
| Java system property `protege.mcp.enabled` | `true` |
| Environment variable `PROTEGE_MCP_ENABLED` | `true` |

Example (Protege launch script):
```bash
-Dprotege.mcp.enabled=true
```

Example (shell):
```bash
export PROTEGE_MCP_ENABLED=true
```

### 2.3 Initialization

The server participates in standard MCP capability negotiation:

1. client sends `initialize`
2. server returns supported capabilities (protocol version `2025-03-26`)
3. client sends `notifications/initialized`
4. client discovers tools through `tools/list`
5. client invokes operations through `tools/call`

### 2.4 Server capabilities

```json
{
  "capabilities": {
    "tools": {}
  }
}
```

### 2.5 Workspace and mutation model

- The server **discovers** workspaces from the currently open OWL editor kits in the running Protege instance.
- Each workspace is identified by its ontology IRI string (`ontology_id`).
- Each workspace supports at most **one sandbox** – an ordered list of pending axiom additions.
- Write tools default to the sandbox (`direct: false`).
- `sandbox_commit` promotes pending sandbox axioms to the live ontology.
- `ontology_save` persists the live ontology to disk.
- Pass `dry_run: true` to validate a write call without applying it.
- Pass `direct: true` to bypass the sandbox and apply immediately.

## 3. Common conventions

### 3.1 Tool naming

Tool names use stable snake_case verbs and nouns, for example:

- `ontology_open`
- `entity_search`
- `class_create`
- `axiom_add`
- `sandbox_commit`

### 3.2 Shared input fields

Many tools reuse these fields:

- `ontology_id`: target ontology handle returned by `ontology_open`
- `sandbox_id`: optional sandbox handle for isolated writes
- `dry_run`: validate and describe the change without applying it
- `reasoner`: optional reasoner identifier
- `include_imports`: whether imported ontologies are included

### 3.3 Write safety

Write-capable tools should support the following guardrails where applicable:

- schema validation
- sandbox-first execution
- `dry_run`
- change-set previews
- optional approval or policy enforcement

## 4. Tool catalog

The following tools are described in MCP-style JSON definitions. Each definition focuses on `name`, `description`, and `inputSchema`, which matches standard MCP tool discovery. Return values are specified after each definition.

### 4.1 Lifecycle and workspace tools

#### `ontology_open`

```json
{
  "name": "ontology_open",
  "description": "Open an ontology from a local file path or IRI and create a workspace handle.",
  "inputSchema": {
    "type": "object",
    "properties": {
      "source": { "type": "string", "description": "Local file path or ontology IRI." },
      "format_hint": { "type": "string", "enum": ["owl", "rdfxml", "ttl", "omn", "jsonld"] },
      "imports_mode": { "type": "string", "enum": ["include", "exclude", "lazy"], "default": "include" },
      "workspace": { "type": "string", "description": "Optional caller-defined workspace name." },
      "create_sandbox": { "type": "boolean", "default": true }
    },
    "required": ["source"]
  }
}
```

Returns: `ontology_id`, `sandbox_id`, `document_iri`, `ontology_iri`, `version_iri`, `imports`, `format`, `loaded`.

#### `ontology_close`

```json
{
  "name": "ontology_close",
  "description": "Close a previously opened ontology workspace.",
  "inputSchema": {
    "type": "object",
    "properties": {
      "ontology_id": { "type": "string" },
      "discard_sandbox": { "type": "boolean", "default": true }
    },
    "required": ["ontology_id"]
  }
}
```

Returns: `closed`, `discarded_sandbox`.

#### `ontology_list`

```json
{
  "name": "ontology_list",
  "description": "List currently opened ontologies and workspaces.",
  "inputSchema": {
    "type": "object",
    "properties": {}
  }
}
```

Returns: `ontologies[]`.

#### `ontology_info`

```json
{
  "name": "ontology_info",
  "description": "Return metadata for an ontology, including IRIs, imports, format, and workspace status.",
  "inputSchema": {
    "type": "object",
    "properties": {
      "ontology_id": { "type": "string" }
    },
    "required": ["ontology_id"]
  }
}
```

Returns: `ontology_id`, `document_iri`, `ontology_iri`, `version_iri`, `imports`, `axiom_count`, `entity_counts`, `dirty`, `sandbox_id`.

### 4.2 Inspection and navigation tools

#### `entity_search`

```json
{
  "name": "entity_search",
  "description": "Search classes, individuals, properties, and annotation properties by label, IRI fragment, or exact IRI.",
  "inputSchema": {
    "type": "object",
    "properties": {
      "ontology_id": { "type": "string" },
      "query": { "type": "string" },
      "entity_types": {
        "type": "array",
        "items": {
          "type": "string",
          "enum": ["class", "individual", "object_property", "data_property", "annotation_property", "datatype"]
        }
      },
      "match_mode": { "type": "string", "enum": ["contains", "prefix", "exact"], "default": "contains" },
      "limit": { "type": "integer", "minimum": 1, "maximum": 500, "default": 50 },
      "include_imports": { "type": "boolean", "default": true }
    },
    "required": ["ontology_id", "query"]
  }
}
```

Returns: `results[]`.

#### `entity_get`

```json
{
  "name": "entity_get",
  "description": "Get a structured description of an ontology entity.",
  "inputSchema": {
    "type": "object",
    "properties": {
      "ontology_id": { "type": "string" },
      "entity_iri": { "type": "string" },
      "include_axioms": { "type": "boolean", "default": true },
      "include_annotations": { "type": "boolean", "default": true },
      "include_imports": { "type": "boolean", "default": true }
    },
    "required": ["ontology_id", "entity_iri"]
  }
}
```

Returns: `entity`, `annotations`, `axioms`, `usage`.

#### `hierarchy_get`

```json
{
  "name": "hierarchy_get",
  "description": "Return parent and child hierarchy information for an entity.",
  "inputSchema": {
    "type": "object",
    "properties": {
      "ontology_id": { "type": "string" },
      "entity_iri": { "type": "string" },
      "reasoned": { "type": "boolean", "default": false },
      "depth": { "type": "integer", "minimum": 1, "maximum": 10, "default": 1 }
    },
    "required": ["ontology_id", "entity_iri"]
  }
}
```

Returns: `parents`, `children`, `equivalents`.

#### `axioms_list`

```json
{
  "name": "axioms_list",
  "description": "List axioms for an ontology or for a specific entity.",
  "inputSchema": {
    "type": "object",
    "properties": {
      "ontology_id": { "type": "string" },
      "entity_iri": { "type": "string" },
      "axiom_types": {
        "type": "array",
        "items": { "type": "string" }
      },
      "limit": { "type": "integer", "minimum": 1, "maximum": 1000, "default": 200 }
    },
    "required": ["ontology_id"]
  }
}
```

Returns: `axioms[]`.

### 4.3 Query tools

#### `sparql_query`

> **Deferred to V2** — described here for forward reference. Not registered in
> `tools/list` for V1 (see section 5).

```json
{
  "name": "sparql_query",
  "description": "Run a SPARQL query against the ontology or an adapter-backed graph view.",
  "inputSchema": {
    "type": "object",
    "properties": {
      "ontology_id": { "type": "string" },
      "query": { "type": "string" },
      "timeout_ms": { "type": "integer", "minimum": 1, "default": 30000 }
    },
    "required": ["ontology_id", "query"]
  }
}
```

Returns: `head`, `rows`, `boolean_result`, `execution_time_ms`.

#### `dl_query`

```json
{
  "name": "dl_query",
  "description": "Evaluate a DL query using the selected reasoner.",
  "inputSchema": {
    "type": "object",
    "properties": {
      "ontology_id": { "type": "string" },
      "expression": { "type": "string" },
      "reasoner": { "type": "string" },
      "limit": { "type": "integer", "minimum": 1, "maximum": 1000, "default": 200 }
    },
    "required": ["ontology_id", "expression"]
  }
}
```

Returns: `results[]`, `reasoner`, `execution_time_ms`.

### 4.4 High-level semantic write tools

#### `class_create`

```json
{
  "name": "class_create",
  "description": "Create a new OWL class and optionally attach labels, comments, and parent classes.",
  "inputSchema": {
    "type": "object",
    "properties": {
      "ontology_id": { "type": "string" },
      "sandbox_id": { "type": "string" },
      "class_iri": { "type": "string" },
      "label": { "type": "string" },
      "comment": { "type": "string" },
      "parent_class_iris": {
        "type": "array",
        "items": { "type": "string" }
      },
      "dry_run": { "type": "boolean", "default": false }
    },
    "required": ["ontology_id", "class_iri"]
  }
}
```

Returns: `created`, `change_set`, `validation`.

#### `property_create`

```json
{
  "name": "property_create",
  "description": "Create an object, data, or annotation property.",
  "inputSchema": {
    "type": "object",
    "properties": {
      "ontology_id": { "type": "string" },
      "sandbox_id": { "type": "string" },
      "property_iri": { "type": "string" },
      "property_kind": { "type": "string", "enum": ["object", "data", "annotation"] },
      "label": { "type": "string" },
      "domain_iris": { "type": "array", "items": { "type": "string" } },
      "range_iris": { "type": "array", "items": { "type": "string" } },
      "dry_run": { "type": "boolean", "default": false }
    },
    "required": ["ontology_id", "property_iri", "property_kind"]
  }
}
```

Returns: `created`, `change_set`, `validation`.

#### `individual_create`

```json
{
  "name": "individual_create",
  "description": "Create a named individual and optionally assert one or more types.",
  "inputSchema": {
    "type": "object",
    "properties": {
      "ontology_id": { "type": "string" },
      "sandbox_id": { "type": "string" },
      "individual_iri": { "type": "string" },
      "type_iris": { "type": "array", "items": { "type": "string" } },
      "label": { "type": "string" },
      "dry_run": { "type": "boolean", "default": false }
    },
    "required": ["ontology_id", "individual_iri"]
  }
}
```

Returns: `created`, `change_set`, `validation`.

#### `individual_assert_type`

```json
{
  "name": "individual_assert_type",
  "description": "Assert that an individual is an instance of a class.",
  "inputSchema": {
    "type": "object",
    "properties": {
      "ontology_id": { "type": "string" },
      "sandbox_id": { "type": "string" },
      "individual_iri": { "type": "string" },
      "class_iri": { "type": "string" },
      "dry_run": { "type": "boolean", "default": false }
    },
    "required": ["ontology_id", "individual_iri", "class_iri"]
  }
}
```

Returns: `applied`, `change_set`, `validation`.

#### `annotation_set`

```json
{
  "name": "annotation_set",
  "description": "Add or replace an annotation assertion on an entity or ontology.",
  "inputSchema": {
    "type": "object",
    "properties": {
      "ontology_id": { "type": "string" },
      "sandbox_id": { "type": "string" },
      "target_iri": { "type": "string" },
      "property_iri": { "type": "string" },
      "value": {},
      "language": { "type": "string" },
      "replace_existing": { "type": "boolean", "default": false },
      "dry_run": { "type": "boolean", "default": false }
    },
    "required": ["ontology_id", "target_iri", "property_iri", "value"]
  }
}
```

Returns: `applied`, `change_set`, `validation`.

### 4.5 Low-level axiom write tools

#### `axiom_add`

```json
{
  "name": "axiom_add",
  "description": "Add a low-level OWL axiom represented as structured JSON or a supported syntax string.",
  "inputSchema": {
    "type": "object",
    "properties": {
      "ontology_id": { "type": "string" },
      "sandbox_id": { "type": "string" },
      "axiom": {
        "description": "Axiom payload in structured form or syntax string."
      },
      "axiom_format": { "type": "string", "enum": ["json", "manchester", "functional"], "default": "json" },
      "dry_run": { "type": "boolean", "default": false }
    },
    "required": ["ontology_id", "axiom"]
  }
}
```

Returns: `applied`, `normalized_axiom`, `change_set`, `validation`.

#### `axiom_remove`

```json
{
  "name": "axiom_remove",
  "description": "Remove an existing axiom by structured match or identifier.",
  "inputSchema": {
    "type": "object",
    "properties": {
      "ontology_id": { "type": "string" },
      "sandbox_id": { "type": "string" },
      "axiom": {},
      "axiom_id": { "type": "string" },
      "dry_run": { "type": "boolean", "default": false }
    },
    "required": ["ontology_id"]
  }
}
```

Returns: `applied`, `removed`, `change_set`, `validation`.

#### `batch_apply`

```json
{
  "name": "batch_apply",
  "description": "Apply multiple ontology mutations atomically inside a sandbox.",
  "inputSchema": {
    "type": "object",
    "properties": {
      "ontology_id": { "type": "string" },
      "sandbox_id": { "type": "string" },
      "operations": {
        "type": "array",
        "items": { "type": "object" },
        "minItems": 1
      },
      "dry_run": { "type": "boolean", "default": false },
      "stop_on_error": { "type": "boolean", "default": true }
    },
    "required": ["ontology_id", "operations"]
  }
}
```

Returns: `applied`, `results[]`, `change_set`, `validation`.

### 4.6 Validation and reasoning tools

#### `ontology_validate`

```json
{
  "name": "ontology_validate",
  "description": "Run structural and policy validation on an ontology or sandbox state.",
  "inputSchema": {
    "type": "object",
    "properties": {
      "ontology_id": { "type": "string" },
      "sandbox_id": { "type": "string" },
      "validation_profile": { "type": "string" }
    },
    "required": ["ontology_id"]
  }
}
```

Returns: `valid`, `errors[]`, `warnings[]`.

#### `consistency_check`

```json
{
  "name": "consistency_check",
  "description": "Check ontology consistency with the configured reasoner.",
  "inputSchema": {
    "type": "object",
    "properties": {
      "ontology_id": { "type": "string" },
      "sandbox_id": { "type": "string" },
      "reasoner": { "type": "string" }
    },
    "required": ["ontology_id"]
  }
}
```

Returns: `consistent`, `unsatisfiable_classes`, `reasoner`, `execution_time_ms`.

#### `reasoner_classify`

```json
{
  "name": "reasoner_classify",
  "description": "Run classification and materialize inferred hierarchy results for inspection.",
  "inputSchema": {
    "type": "object",
    "properties": {
      "ontology_id": { "type": "string" },
      "sandbox_id": { "type": "string" },
      "reasoner": { "type": "string" }
    },
    "required": ["ontology_id"]
  }
}
```

Returns: `classified`, `summary`, `reasoner`, `execution_time_ms`.

### 4.7 Persistence, versioning, and recovery tools

#### `ontology_save`

```json
{
  "name": "ontology_save",
  "description": "Persist the current ontology or sandbox state to its document location or to a new output target.",
  "inputSchema": {
    "type": "object",
    "properties": {
      "ontology_id": { "type": "string" },
      "sandbox_id": { "type": "string" },
      "destination": { "type": "string" },
      "format": { "type": "string", "enum": ["rdfxml", "ttl", "omn", "functional", "jsonld"] }
    },
    "required": ["ontology_id"]
  }
}
```

Returns: `saved`, `destination`, `format`, `bytes_written`.

#### `ontology_export`

```json
{
  "name": "ontology_export",
  "description": "Export the ontology to a requested serialization without changing the primary document.",
  "inputSchema": {
    "type": "object",
    "properties": {
      "ontology_id": { "type": "string" },
      "sandbox_id": { "type": "string" },
      "destination": { "type": "string" },
      "format": { "type": "string", "enum": ["rdfxml", "ttl", "omn", "functional", "jsonld"] }
    },
    "required": ["ontology_id", "destination", "format"]
  }
}
```

Returns: `exported`, `destination`, `format`.

#### `snapshot_create`

```json
{
  "name": "snapshot_create",
  "description": "Create a named snapshot of the current ontology or sandbox state for rollback or comparison.",
  "inputSchema": {
    "type": "object",
    "properties": {
      "ontology_id": { "type": "string" },
      "sandbox_id": { "type": "string" },
      "label": { "type": "string" }
    },
    "required": ["ontology_id"]
  }
}
```

Returns: `snapshot_id`, `created_at`, `label`.

#### `snapshot_restore`

```json
{
  "name": "snapshot_restore",
  "description": "Restore a previous snapshot into the active sandbox or workspace state.",
  "inputSchema": {
    "type": "object",
    "properties": {
      "ontology_id": { "type": "string" },
      "sandbox_id": { "type": "string" },
      "snapshot_id": { "type": "string" },
      "dry_run": { "type": "boolean", "default": false }
    },
    "required": ["ontology_id", "snapshot_id"]
  }
}
```

Returns: `restored`, `change_set`, `validation`.

#### `sandbox_commit`

```json
{
  "name": "sandbox_commit",
  "description": "Commit sandboxed changes into the active ontology workspace after validation.",
  "inputSchema": {
    "type": "object",
    "properties": {
      "ontology_id": { "type": "string" },
      "sandbox_id": { "type": "string" },
      "message": { "type": "string" },
      "run_validation": { "type": "boolean", "default": true }
    },
    "required": ["ontology_id", "sandbox_id"]
  }
}
```

Returns: `committed`, `change_set`, `validation`, `snapshot_id`.

#### `diff_get`

```json
{
  "name": "diff_get",
  "description": "Describe ontology differences between the live state, a sandbox, or two snapshots.",
  "inputSchema": {
    "type": "object",
    "properties": {
      "ontology_id": { "type": "string" },
      "left_snapshot_id": { "type": "string" },
      "right_snapshot_id": { "type": "string" },
      "sandbox_id": { "type": "string" }
    },
    "required": ["ontology_id"]
  }
}
```

Returns: `added_axioms`, `removed_axioms`, `summary`.

### 4.8 Metadata and introspection tools

#### `server_info`

```json
{
  "name": "server_info",
  "description": "Return MCP server metadata, implementation details, and supported backend features.",
  "inputSchema": {
    "type": "object",
    "properties": {}
  }
}
```

Returns: `server_name`, `server_version`, `mcp_version`, `backend`, `supported_tools`, `limits`.

#### `ontology_capabilities`

```json
{
  "name": "ontology_capabilities",
  "description": "Return ontology-specific capabilities such as available reasoners, supported formats, and enabled write policies.",
  "inputSchema": {
    "type": "object",
    "properties": {
      "ontology_id": { "type": "string" }
    },
    "required": ["ontology_id"]
  }
}
```

Returns: `reasoners`, `formats`, `write_policies`, `query_features`.

#### `workspace_status`

```json
{
  "name": "workspace_status",
  "description": "Inspect dirty state, lock state, sandbox health, and pending validation issues for a workspace.",
  "inputSchema": {
    "type": "object",
    "properties": {
      "ontology_id": { "type": "string" },
      "sandbox_id": { "type": "string" }
    },
    "required": ["ontology_id"]
  }
}
```

Returns: `dirty`, `locked`, `sandbox_present`, `pending_changes`, `last_validation`.

## 5. Summary table — v1 implemented tools

The following tools are implemented in the `protege-mcp` v1 bundle.  Tools marked **deferred** are described in
section 4 but are not registered in v1.

| Tool | Mode | Status | Description |
| --- | --- | --- | --- |
| `server_info` | read | ✅ v1 | Server version, transport, and supported capabilities. |
| `ontology_list` | read | ✅ v1 | List all ontologies open in the attached Protege workspace. |
| `ontology_open` | write | ✅ v1 | Load an ontology from a URI into the workspace. |
| `ontology_close` | write | ✅ v1 | Close an ontology workspace and discard its sandbox. |
| `ontology_info` | read | ✅ v1 | Axiom counts, format, and workspace state. |
| `ontology_capabilities` | read | ✅ v1 | Write support, reasoner availability, and export formats. |
| `workspace_status` | read | ✅ v1 | Dirty state, lock state, and pending sandbox changes. |
| `entity_search` | read | ✅ v1 | Search entities by fragment or label. |
| `entity_get` | read | ✅ v1 | Structured details and annotations for a single entity. |
| `hierarchy_get` | read | ✅ v1 | Asserted parents and children for a class. |
| `axioms_list` | read | ✅ v1 | List axioms for the ontology or a specific entity. |
| `dl_query` | read | ✅ v1 | Manchester Syntax DL query (instances or subclasses). |
| `class_create` | write | ✅ v1 | Declare a new OWL class. Defaults to sandbox. |
| `property_create` | write | ✅ v1 | Declare a new property (object/data/annotation). Defaults to sandbox. |
| `individual_create` | write | ✅ v1 | Declare a named individual. Defaults to sandbox. |
| `individual_assert_type` | write | ✅ v1 | Add an `rdf:type` assertion. Defaults to sandbox. |
| `annotation_set` | write | ✅ v1 | Add an annotation assertion. Defaults to sandbox. |
| `ontology_validate` | read | ✅ v1 | Structural validation checks. |
| `consistency_check` | read | ✅ v1 | Check consistency with the active reasoner. |
| `reasoner_classify` | read | ✅ v1 | Run classification and report unsatisfiable classes. |
| `ontology_save` | write | ✅ v1 | Persist ontology to its physical location. |
| `ontology_export` | read | ✅ v1 | Export as Turtle, RDF/XML, or Manchester. |
| `sandbox_commit` | write | ✅ v1 | Promote sandbox changes to the live ontology. |
| `sparql_query` | read | ❌ out of scope | SPARQL queries are not included in v1. |
| `axiom_add` | write | ⏭ deferred | Low-level axiom mutation. |
| `axiom_remove` | write | ⏭ deferred | Low-level axiom removal. |
| `batch_apply` | write | ⏭ deferred | Atomic mutation batch. |
| `snapshot_create` | write | ⏭ deferred | Rollback snapshot. |
| `snapshot_restore` | write | ⏭ deferred | Restore snapshot. |
| `diff_get` | read | ⏭ deferred | Compare live, sandbox, or snapshot states. |

## 6. Error response convention

MCP request failures should use standard JSON-RPC error objects. Tool-specific details should be returned in `error.data`.

### 6.1 Generic shape

```json
{
  "jsonrpc": "2.0",
  "id": "42",
  "error": {
    "code": -32602,
    "message": "Invalid params",
    "data": {
      "tool": "class_create",
      "field": "class_iri",
      "reason": "IRI must be absolute"
    }
  }
}
```

### 6.2 Recommended usage

- `-32601`: unknown method or missing MCP primitive
- `-32602`: invalid tool input
- `-32603`: internal server error
- application-specific negative codes: ontology-domain failures

### 6.3 Example domain errors

Ontology not found:

```json
{
  "code": -32010,
  "message": "Ontology workspace not found",
  "data": {
    "ontology_id": "ont_123"
  }
}
```

Validation failed:

```json
{
  "code": -32020,
  "message": "Mutation rejected by validation policy",
  "data": {
    "tool": "sandbox_commit",
    "errors": [
      {
        "type": "consistency_error",
        "message": "Ontology becomes inconsistent after proposed changes"
      }
    ]
  }
}
```

Snapshot restore conflict:

```json
{
  "code": -32030,
  "message": "Snapshot restore requires a clean sandbox",
  "data": {
    "tool": "snapshot_restore",
    "sandbox_id": "sbx_77",
    "pending_changes": 4
  }
}
```

## 7. Extensibility

This tool surface is intended to be backend-neutral even if the first implementation is OWLAPI-first.

Planned extension points:

- alternate ontology backends such as triple stores or repository adapters
- Protege-specific integration for editor state and UI-aware operations
- MCP resources for ontology exports, reports, and reasoning artifacts
- MCP prompts for guided ontology editing, review, and repair flows
- additional policy tools for approval workflows and access control
- async or long-running operation handling for heavy reasoning tasks

To preserve compatibility, new tools should:

- keep existing tool names stable
- add optional fields rather than changing required ones
- use new tool names for incompatible behavior
- continue returning structured error data

## 8. Example MCP configuration

### 8.1 Cursor / Claude Desktop (stdio, attached mode)

Point your MCP client at the Protege executable and set `PROTEGE_MCP_ENABLED=true`:

```json
{
  "mcpServers": {
    "protege": {
      "command": "/Applications/Protégé.app/Contents/MacOS/Protege",
      "env": {
        "PROTEGE_MCP_ENABLED": "true"
      }
    }
  }
}
```

On Linux:

```json
{
  "mcpServers": {
    "protege": {
      "command": "/opt/protege/run.sh",
      "env": {
        "PROTEGE_MCP_ENABLED": "true"
      }
    }
  }
}
```

HTTP transport is not supported in v1.

## 9. Recommended next steps

1. ✅ lifecycle tools (v1)
2. ✅ entity inspection and search (v1)
3. ✅ high-level semantic writes (v1)
4. ✅ validation and reasoning (v1)
5. ✅ persistence and export (v1)
6. ⏭ low-level axiom operations (`axiom_add`, `axiom_remove`)
7. ⏭ snapshot and diff tools
8. ⏭ MCP resources and prompts
9. ⏭ HTTP transport (opt-in, separate from attached stdio mode)

## 10. Status

v1 is implemented in the `protege-mcp` OSGi bundle.  It is opt-in, stdio-only, attached to a live Protege Desktop
process, and has no SPARQL support.  The 23 tools listed as ✅ in section 5 are registered and functional.
Deferred tools are described in section 4 but not yet registered.
