# VS Code MCP example config

`vscode-mcp.json` is a ready-to-paste server definition for VS Code's native MCP
support (copy to `.vscode/mcp.json` or merge into the user-scoped `mcp.json`).
It declares a single stdio server, **`protege-mcp`**, that runs the bundle JAR
in its dual role as a `stdio ↔ TCP` relay and forwards MCP frames to a
Protégé Desktop instance you launched yourself.

## Recommended workflow (attach mode)

1. Launch Protégé Desktop normally (Dock icon, Start menu, double-click
   `Protégé.app`, or `./run.sh`). The bundle starts the localhost TCP
   transport automatically on `127.0.0.1:47800`.
2. In VS Code, select the `protege-mcp` server and click *Start*. VS Code will
   prompt for:
   - `protegeBundleJar` — absolute path to `protege-mcp-<version>.jar` (the
     same JAR that Felix loads under `bundles/`; it is also the executable
     relay via `Main-Class: org.protege.mcp.ProtegeMcpRelay`).
   - `javaBin` — path to a Java 11+ binary (defaults to `java` on `$PATH`).
   - `protegeMcpPort` — the bundle's TCP port (defaults to `47800`).
3. The relay connects to the running Protégé instance and forwards MCP frames
   between VS Code and the bundle. You can stop / restart VS Code's server
   entry without restarting Protégé. The relay retries the TCP connect for
   up to 30 s, so VS Code can be started before Protégé.

If you changed the bundle's TCP port via `-Dprotege.mcp.tcp.port=<n>` (or
`PROTEGE_MCP_TCP_PORT=<n>`), enter the same value at the `protegeMcpPort`
prompt.

## Direct CLI usage of the relay

The relay is also runnable on its own:

```bash
java -jar protege-mcp-5.6.10-SNAPSHOT.jar [--host=127.0.0.1] [--port=47800]
```

CLI flags take precedence over `-Dprotege.mcp.host` / `-Dprotege.mcp.tcp.port`,
which take precedence over `PROTEGE_MCP_HOST` / `PROTEGE_MCP_TCP_PORT`.

## Notes

- The MCP server is **enabled by default** as soon as the `protege-mcp` bundle
  is activated by Felix — no opt-in env var or system property is needed.
- The TCP transport binds to `127.0.0.1` only; it is not reachable from other
  hosts. To disable it set `-Dprotege.mcp.tcp.enabled=false` /
  `PROTEGE_MCP_TCP_ENABLED=false` (stdio remains available).
- Stdio mode (VS Code spawns Protégé itself via `run.sh`) is still supported
  but is no longer the recommended flow; see *Stdio mode* in
  [../README.md](../README.md).

For the full setup (build, install, launch, troubleshooting) see
[../README.md](../README.md), section *VS Code (GitHub Copilot Chat)*.

