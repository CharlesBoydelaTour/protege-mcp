# VS Code MCP example config

`vscode-mcp.json` is a ready-to-paste server definition for VS Code's native MCP
support (copy to `.vscode/mcp.json` or merge into the user-scoped `mcp.json`).
It declares three server entries:

- **`protege-mcp-attach`** *(recommended)* — spawns the small Python relay
  ([`../relay/protege-mcp-relay.py`](../relay/protege-mcp-relay.py)) which
  forwards JSON-RPC frames over a localhost TCP socket to a Protégé Desktop
  instance you launched yourself (Dock icon, `run.sh` from a terminal, etc.).
  This is the only mode that works when Protégé was started by double-clicking
  the macOS `.app` bundle (no controlling terminal).
- **`protege-mcp-spawn`** — legacy mode: VS Code spawns `run.sh` itself and
  speaks stdio with the subprocess. Useful when you do not have a Protégé
  instance already running.
- **`protege-mcp-spawn-windows`** — same as `protege-mcp-spawn` but invokes
  `run.bat` for Windows hosts.

## Recommended workflow (`protege-mcp-attach`)

1. Launch Protégé Desktop normally (Dock icon, Start menu, or `./run.sh`). The
   bundle starts both transports automatically: stdio (only useful if launched
   from a terminal) **and** localhost TCP on `127.0.0.1:47800` (default).
2. In VS Code, select the `protege-mcp-attach` server and click *Start*. VS Code
   will prompt for `relayScript` (the absolute path to
   `protege-mcp-relay.py`) and an optional `port`. Leave `port` blank to use
   the default `47800`.
3. The relay connects to the running Protégé instance and forwards MCP frames
   between VS Code and the bundle. You can stop / restart VS Code's server
   entry without restarting Protégé.

If you changed the bundle's TCP port via `-Dprotege.mcp.tcp.port=<n>` (or
`PROTEGE_MCP_TCP_PORT=<n>`), enter the same value at the `port` prompt.

## Spawn mode (`protege-mcp-spawn` / `protege-mcp-spawn-windows`)

- Pass the absolute path of the extracted Protégé directory at the
  `protegeHome` prompt (the folder containing `run.sh` / `run.bat`).
- The Unix entry forwards `-Dapple.awt.UIElement=true` via `CMD_OPTIONS` so
  Protégé stays out of the macOS Dock when launched as a subprocess.
- `JAVA_HOME` is **optional**; leave the prompt blank to fall back to the
  system JDK or Protégé's bundled JRE.

## Notes

- The MCP server is **enabled by default** as soon as the `protege-mcp` bundle
  is activated by Felix — no opt-in env var or system property is needed.
- The TCP transport binds to `127.0.0.1` only; it is not reachable from other
  hosts. To disable it set `-Dprotege.mcp.tcp.enabled=false` (stdio remains
  available).

For the full setup (build, install, launch, troubleshooting) see
[../README.md](../README.md), section *VS Code (GitHub Copilot Chat)*.

