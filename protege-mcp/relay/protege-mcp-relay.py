#!/usr/bin/env python3
"""Stdio <-> TCP relay for the Protege MCP server.

Reads MCP frames from stdin and forwards them byte-for-byte to a TCP socket
(the bundle's localhost transport), and copies the socket's bytes back to
stdout. Framing is identical on both sides; this script never parses JSON.

Configuration via environment variables:
    PROTEGE_MCP_HOST   default 127.0.0.1
    PROTEGE_MCP_PORT   default 47800

Designed to be spawned by an MCP host (e.g. VS Code's `protege-mcp-attach`
server entry) so that an externally-launched Protégé Desktop instance can
be reached without sharing stdio with the GUI process.

See ../examples/vscode-mcp.README.md for usage.
"""

from __future__ import annotations

import os
import signal
import socket
import sys
import threading
import time

DEFAULT_HOST = "127.0.0.1"
DEFAULT_PORT = 47800
RETRY_INTERVAL_S = 0.5
RETRY_TIMEOUT_S = 30.0
COPY_CHUNK = 65536


def _resolve_host() -> str:
    value = os.environ.get("PROTEGE_MCP_HOST", "").strip()
    return value or DEFAULT_HOST


def _resolve_port() -> int:
    raw = os.environ.get("PROTEGE_MCP_PORT", "").strip()
    if not raw:
        return DEFAULT_PORT
    try:
        port = int(raw)
    except ValueError:
        print(
            f"protege-mcp-relay: invalid PROTEGE_MCP_PORT={raw!r}, falling back to {DEFAULT_PORT}",
            file=sys.stderr,
        )
        return DEFAULT_PORT
    if not (0 < port < 65536):
        print(
            f"protege-mcp-relay: PROTEGE_MCP_PORT={port} out of range, falling back to {DEFAULT_PORT}",
            file=sys.stderr,
        )
        return DEFAULT_PORT
    return port


def _connect(host: str, port: int) -> socket.socket:
    deadline = time.monotonic() + RETRY_TIMEOUT_S
    last_err: Exception | None = None
    while time.monotonic() < deadline:
        try:
            sock = socket.create_connection((host, port), timeout=5.0)
            sock.settimeout(None)
            return sock
        except OSError as exc:
            last_err = exc
            time.sleep(RETRY_INTERVAL_S)
    print(
        f"protege-mcp-relay: could not connect to {host}:{port} after {RETRY_TIMEOUT_S:.0f}s: {last_err}",
        file=sys.stderr,
    )
    sys.exit(1)


def _pump(src, dst, on_eof) -> None:
    try:
        while True:
            chunk = src.read(COPY_CHUNK)
            if not chunk:
                break
            dst.write(chunk)
            dst.flush()
    except (OSError, ValueError):
        pass
    finally:
        on_eof()


def main() -> int:
    host = _resolve_host()
    port = _resolve_port()
    sock = _connect(host, port)

    stdin_in = sys.stdin.buffer
    stdout_out = sys.stdout.buffer
    sock_in = sock.makefile("rb", buffering=0)
    sock_out = sock.makefile("wb", buffering=0)
    stop_event = threading.Event()

    def _shutdown(*_args) -> None:
        stop_event.set()
        try:
            sock.shutdown(socket.SHUT_RDWR)
        except OSError:
            pass
        try:
            sock.close()
        except OSError:
            pass

    signal.signal(signal.SIGTERM, lambda *_: _shutdown())
    signal.signal(signal.SIGINT, lambda *_: _shutdown())

    t_in = threading.Thread(
        target=_pump, args=(stdin_in, sock_out, _shutdown),
        name="relay-stdin-to-tcp", daemon=True,
    )
    t_out = threading.Thread(
        target=_pump, args=(sock_in, stdout_out, _shutdown),
        name="relay-tcp-to-stdout", daemon=True,
    )
    t_in.start()
    t_out.start()

    # Wait until either pump exits.
    while not stop_event.is_set() and t_in.is_alive() and t_out.is_alive():
        stop_event.wait(0.5)

    _shutdown()
    return 0


if __name__ == "__main__":
    sys.exit(main())
