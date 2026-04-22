# Protege Desktop

[Protege](https://protege.stanford.edu) is a free, open-source ontology editor that supports the latest [OWL 2.0 standard](http://www.w3.org/TR/owl2-overview/). Protege has a pluggable architecture, and many [plugins](https://protegewiki.stanford.edu/wiki/Protege_Plugin_Library) for different functionalities are available.

To read more about **Protege's features**, please visit the Protege [home page](https://protege.stanford.edu).

The latest version of Protege can be [downloaded](https://protege.stanford.edu/software.php#desktop-protege) from the Protege website, or from [github](https://github.com/protegeproject/protege-distribution/releases).

If you would like to contribute to the Protege Project please see our [contributing guide](https://github.com/protegeproject/protege/blob/master/CONTRIBUTING.md)

The [Developer Documentation](https://github.com/protegeproject/protege/wiki/Developer-Documentation) may be found on the wiki.

An MCP server specification and implementation guide is available in [docs/mcp-server-spec.md](docs/mcp-server-spec.md).

## MCP Server (stdio, attached mode)

The `protege-mcp` module bundles an opt-in [Model Context Protocol](https://modelcontextprotocol.io) server that runs
**inside** a live Protege Desktop process over `stdio`.  It exposes open ontologies as MCP tools so that AI agents
and developer tooling can read and write ontologies through a standard JSON-RPC 2.0 interface.

### Enabling the server

The server is **disabled by default** and must be opted in explicitly before Protege starts.  Choose one of:

```bash
# Java system property (e.g. in Protege.l4j.ini or via -D flag)
-Dprotege.mcp.enabled=true

# Environment variable
export PROTEGE_MCP_ENABLED=true
```

When enabled, the server listens on `stdin`/`stdout` using Content-Length–framed JSON-RPC 2.0, matching the
[MCP stdio transport](https://modelcontextprotocol.io/docs/concepts/transports).

### Connecting a client

Add a server entry to your MCP client configuration (e.g. `.cursor/mcp.json` or Claude Desktop's config):

```json
{
  "mcpServers": {
    "protege": {
      "command": "/path/to/Protege",
      "env": {
        "PROTEGE_MCP_ENABLED": "true"
      }
    }
  }
}
```

### Available tools (v1)

| Tool | Mode | Description |
|---|---|---|
| `server_info` | read | Server version, transport, and supported capabilities. |
| `ontology_list` | read | List all ontologies open in the attached Protege workspace. |
| `ontology_open` | write | Load an ontology from a URI into the workspace. |
| `ontology_close` | write | Close an ontology workspace and discard its sandbox. |
| `ontology_info` | read | Axiom counts, format, and workspace state. |
| `ontology_capabilities` | read | Write support, reasoner, and export format availability. |
| `workspace_status` | read | Dirty state, lock state, and pending sandbox changes. |
| `entity_search` | read | Search entities by fragment or label. |
| `entity_get` | read | Structured details and annotations for a single entity. |
| `hierarchy_get` | read | Asserted parents and children for a class. |
| `axioms_list` | read | List axioms for the ontology or a specific entity. |
| `dl_query` | read | Manchester Syntax DL query (instances or subclasses). |
| `class_create` | write | Declare a new OWL class. Defaults to sandbox. |
| `property_create` | write | Declare a new object, data, or annotation property. Defaults to sandbox. |
| `individual_create` | write | Declare a named individual. Defaults to sandbox. |
| `individual_assert_type` | write | Add an `rdf:type` assertion. Defaults to sandbox. |
| `annotation_set` | write | Add an annotation assertion (e.g. `rdfs:label`). Defaults to sandbox. |
| `ontology_validate` | read | Structural validation checks. |
| `consistency_check` | read | Check ontology consistency with the active reasoner. |
| `reasoner_classify` | read | Run classification and report unsatisfiable classes. |
| `ontology_save` | write | Persist ontology to its current physical location. |
| `ontology_export` | read | Export ontology content as Turtle, RDF/XML, or Manchester. |
| `sandbox_commit` | write | Promote pending sandbox changes to the live ontology. |

### Sandbox model

Write tools accumulate changes in an in-memory **sandbox** (one per ontology) rather than immediately modifying the
live ontology.  Use `sandbox_commit` to promote changes.  Pass `"direct": true` to bypass the sandbox, or
`"dry_run": true` to validate without applying.

HTTP transport and SPARQL queries are not included in v1.

**Looking for support?** Please ask questions on the [protege-user](https://protege.stanford.edu/support.php) or [protege-dev](https://protege.stanford.edu/support.php) mailing lists. If you found a bug or would like to request a feature, you may also use [this issue tracker](https://github.com/protegeproject/protege/issues).

Protege is released under the [BSD 2-clause license](https://raw.githubusercontent.com/protegeproject/protege/master/license.txt).

Instructions for [building from source](https://github.com/protegeproject/protege/wiki/Building-from-Source) are available on the the wiki.
