# Protege MCP Server Specification

This document defines a draft [Model Context Protocol (MCP)](https://modelcontextprotocol.io) server surface for local ontology access built around Protege and OWLAPI. It is aligned with the MCP documentation model: a host runs an MCP client, the client connects to an MCP server, and the server exposes capabilities through `tools/list` and `tools/call` over JSON-RPC 2.0.

## 1. Goals

- Expose Protege and OWLAPI ontology operations as MCP tools.
- Support both read and write operations.
- Keep writes safe through validation, sandboxing, and explicit persistence tools.
- Remain compatible with standard MCP clients and hosts.

## 2. MCP framing

### 2.1 Roles and transport

This specification assumes the standard MCP roles:

- host: the desktop app, agent runtime, or integration environment
- client: the MCP protocol implementation used by the host
- server: the Protege ontology service described in this document

The server should support the MCP transports commonly used by clients:

- `stdio` for local desktop or agent-hosted execution
- streamable HTTP for remote or service-managed execution

### 2.2 Initialization

The server participates in standard MCP capability negotiation:

1. client sends `initialize`
2. server returns supported capabilities
3. client sends `notifications/initialized`
4. client discovers tools through `tools/list`
5. client invokes operations through `tools/call`

### 2.3 Server capabilities

For the first iteration, the server primarily exposes MCP tools:

```json
{
  "capabilities": {
    "tools": {}
  }
}
```

Future versions may also expose:

- resources for ontology snapshots, exports, and reports
- prompts for guided ontology editing workflows

### 2.4 Workspace and mutation model

- A client opens an ontology into a workspace.
- Each workspace has an `ontology_id` and may also have a writable `sandbox_id`.
- Read tools can target the live ontology or a sandbox.
- Write tools default to sandboxed changes unless explicitly configured otherwise.
- Persistent writes are finalized with `sandbox_commit` or `ontology_save`.

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

## 5. Summary table

| Tool | Mode | Description |
| --- | --- | --- |
| `ontology_open` | read/write | Open an ontology and create a workspace, optionally with a sandbox. |
| `ontology_close` | read/write | Close a workspace and optionally discard sandbox state. |
| `ontology_list` | read | List all open ontology workspaces. |
| `ontology_info` | read | Return ontology metadata, identifiers, counts, and workspace state. |
| `entity_search` | read | Search ontology entities by label, fragment, or IRI. |
| `entity_get` | read | Get the structured details of one ontology entity. |
| `hierarchy_get` | read | Inspect asserted or reasoned hierarchy relationships. |
| `axioms_list` | read | List axioms for the ontology or a specific entity. |
| `sparql_query` | read | Execute a SPARQL query over the ontology view. |
| `dl_query` | read | Execute a DL query with a reasoner. |
| `class_create` | write | Create a class with optional labels and parents. |
| `property_create` | write | Create an object, data, or annotation property. |
| `individual_create` | write | Create a named individual with optional types. |
| `individual_assert_type` | write | Assert an individual's rdf:type relationship. |
| `annotation_set` | write | Add or replace an annotation assertion. |
| `axiom_add` | write | Add a low-level OWL axiom. |
| `axiom_remove` | write | Remove a low-level OWL axiom. |
| `batch_apply` | write | Apply a set of mutations atomically. |
| `ontology_validate` | read | Run structural or policy validation checks. |
| `consistency_check` | read | Check ontology consistency with a reasoner. |
| `reasoner_classify` | read | Run classification and summarize inferred results. |
| `ontology_save` | write | Persist ontology state to disk. |
| `ontology_export` | read | Export ontology content to another serialization target. |
| `snapshot_create` | write | Capture a rollback snapshot. |
| `snapshot_restore` | write | Restore a prior snapshot into the active state. |
| `sandbox_commit` | write | Promote sandbox changes into the active workspace. |
| `diff_get` | read | Compare live, sandbox, or snapshot states. |
| `server_info` | read | Return server-level metadata and implementation limits. |
| `ontology_capabilities` | read | Describe ontology-specific supported features. |
| `workspace_status` | read | Inspect workspace and sandbox health. |

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

### 8.1 Local `stdio` server

```json
{
  "mcpServers": {
    "protege": {
      "command": "java",
      "args": [
        "-jar",
        "/opt/protege-mcp/protege-mcp-server.jar"
      ],
      "env": {
        "PROTEGE_MCP_MODE": "owlapi",
        "PROTEGE_MCP_SANDBOX_DEFAULT": "true"
      }
    }
  }
}
```

### 8.2 HTTP deployment

```json
{
  "name": "protege-mcp-server",
  "transport": "http",
  "listen": {
    "host": "127.0.0.1",
    "port": 8080
  },
  "backend": {
    "type": "owlapi",
    "sandbox_default": true,
    "validation_profile": "strict"
  }
}
```

## 9. Recommended implementation order

1. lifecycle tools
2. entity inspection and search
3. high-level semantic writes
4. low-level axiom operations
5. validation and reasoning
6. persistence, snapshots, and diff tools
7. resources and prompts if needed by the client ecosystem

## 10. Status

This is a draft server specification for a Protege-oriented MCP integration. It is intended to align the ontology tool surface with current MCP concepts and provide a stable base for implementation.
