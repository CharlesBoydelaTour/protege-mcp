package org.protege.mcp;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * Stdio &lt;-&gt; TCP relay for the Protege MCP server.
 *
 * <p>
 * Designed to be spawned by an MCP host (e.g. VS Code) so that an externally-
 * launched Protégé Desktop instance — which exposes the MCP server on a
 * localhost TCP port via {@link ProtegeMcpTcpTransport} — can be reached
 * without sharing stdio with the GUI process.
 *
 * <p>
 * The relay performs a two-phase startup:
 * <ol>
 * <li><b>Phase 1</b> — handles the MCP {@code initialize} / {@code
 * notifications/initialized} handshake locally so the MCP host (VS Code) does
 * not time out while Protégé is still loading.</li>
 * <li><b>Phase 2</b> — connects to Protégé's TCP port (retrying indefinitely),
 * replays the handshake with Protégé, then switches to a pure byte relay.</li>
 * </ol>
 *
 * <h2>Configuration</h2>
 * Defaults match {@link ProtegeMcpTcpTransport#DEFAULT_PORT}. Overrides
 * (in priority order: CLI flag, system property, environment variable):
 * <ul>
 * <li>Host: {@code --host=…} / {@code -Dprotege.mcp.host=…} /
 * {@code PROTEGE_MCP_HOST}
 * <li>Port: {@code --port=…} / {@code -Dprotege.mcp.tcp.port=…} /
 * {@code PROTEGE_MCP_TCP_PORT}
 * </ul>
 *
 * <p>
 * Has zero non-JDK dependencies so the protege-mcp JAR can act as both an
 * OSGi bundle (loaded by Felix inside Protégé) and a standalone executable
 * (when launched via {@code java -jar protege-mcp-<version>.jar}).
 */
public final class ProtegeMcpRelay {

    private static final String DEFAULT_HOST = "127.0.0.1";
    // 47800 inlined to avoid triggering ProtegeMcpTcpTransport class-loading at
    // runtime
    private static final int DEFAULT_PORT = 47800;
    private static final long RETRY_INTERVAL_MS = 500L;
    private static final long LOG_INTERVAL_MS = 5_000L;
    private static final int CONNECT_TIMEOUT_MS = 5_000;
    private static final String SERVER_VERSION = "5.6.10-SNAPSHOT";

    private ProtegeMcpRelay() {
        // utility / main class
    }

    public static void main(String[] args) {
        String host = resolveHost(args);
        int port = resolvePort(args);

        // MCP stdio transport uses newline-delimited JSON (NDJSON) on the host
        // side (per the MCP spec). The TCP transport on Protégé's side uses
        // LSP-style Content-Length framing. So we read/write lines on stdio
        // and translate to/from framed messages on the TCP socket.
        BufferedReader stdin = new BufferedReader(
                new InputStreamReader(new FileInputStream(FileDescriptor.in),
                        StandardCharsets.UTF_8));
        OutputStream stdout = new FileOutputStream(FileDescriptor.out);

        // ── Phase 1: local MCP handshake ────────────────────────────────────
        // Respond to `initialize` immediately so the MCP host (VS Code) does
        // not time out while Protégé is still loading.
        String initRequest;
        String initializedNotification;
        try {
            initRequest = readLine(stdin);
            writeLine(stdout, buildInitializeResponse(initRequest));
            initializedNotification = readLine(stdin); // notifications/initialized
        } catch (IOException e) {
            System.err.println("protege-mcp-relay: handshake error: " + e.getMessage());
            System.exit(1);
            return;
        }

        // ── Phase 2: connect to Protégé TCP transport (retry indefinitely) ──
        System.err.printf("protege-mcp-relay: waiting for Protégé MCP server on %s:%d ...%n",
                host, port);
        Socket socket;
        try {
            socket = connectWithRetry(host, port);
        } catch (IOException e) {
            System.err.printf("protege-mcp-relay: could not connect to %s:%d: %s%n",
                    host, port, e.getMessage());
            System.exit(1);
            return;
        }
        System.err.printf("protege-mcp-relay: connected to Protégé MCP server on %s:%d%n",
                host, port);

        InputStream sockIn;
        OutputStream sockOut;
        try {
            sockIn = socket.getInputStream();
            sockOut = socket.getOutputStream();
        } catch (IOException e) {
            System.err.println("protege-mcp-relay: socket stream error: " + e.getMessage());
            closeQuietly(socket);
            System.exit(1);
            return;
        }

        // ── Phase 3: replay handshake with Protégé, then translate ──────────
        try {
            // Replay initialize; discard Protégé's response (already sent ours).
            writeFrame(sockOut, initRequest.getBytes(StandardCharsets.UTF_8));
            readFrame(sockIn);
            // Forward notifications/initialized.
            writeFrame(sockOut, initializedNotification.getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            System.err.println("protege-mcp-relay: Protégé handshake error: " + e.getMessage());
            closeQuietly(socket);
            System.exit(1);
            return;
        }

        // Bidirectional translation: NDJSON ↔ Content-Length framing.
        Runnable shutdown = () -> closeQuietly(socket);
        Runtime.getRuntime().addShutdownHook(new Thread(shutdown, "relay-shutdown"));

        Thread inToTcp = new Thread(() -> pumpLinesToFrames(stdin, sockOut, shutdown),
                "relay-stdin-to-tcp");
        Thread tcpToOut = new Thread(() -> pumpFramesToLines(sockIn, stdout, shutdown),
                "relay-tcp-to-stdout");
        inToTcp.setDaemon(true);
        tcpToOut.setDaemon(true);
        inToTcp.start();
        tcpToOut.start();

        try {
            // Exit as soon as either pump terminates.
            while (inToTcp.isAlive() && tcpToOut.isAlive()) {
                inToTcp.join(500);
            }
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        } finally {
            shutdown.run();
        }
    }

    // ── MCP Content-Length framing ───────────────────────────────────────────

    static byte[] readFrame(InputStream in) throws IOException {
        ByteArrayOutputStream hdr = new ByteArrayOutputStream(128);
        int b, p1 = 0, p2 = 0, p3 = 0;
        while ((b = in.read()) != -1) {
            hdr.write(b);
            if (p3 == '\r' && p2 == '\n' && p1 == '\r' && b == '\n') {
                break;
            }
            p3 = p2;
            p2 = p1;
            p1 = b;
        }
        if (b == -1)
            throw new EOFException("stdin closed before end of headers");
        String headers = hdr.toString(StandardCharsets.UTF_8.name());
        int length = 0;
        for (String line : headers.split("\r\n")) {
            if (line.toLowerCase(Locale.ROOT).startsWith("content-length:")) {
                length = Integer.parseInt(line.substring(line.indexOf(':') + 1).trim());
                break;
            }
        }
        return in.readNBytes(length);
    }

    static void writeFrame(OutputStream out, byte[] body) throws IOException {
        byte[] header = ("Content-Length: " + body.length + "\r\n\r\n")
                .getBytes(StandardCharsets.UTF_8);
        out.write(header);
        out.write(body);
        out.flush();
    }

    static String buildInitializeResponse(String requestBody) {
        String id = extractJsonId(requestBody);
        return "{\"jsonrpc\":\"2.0\",\"id\":" + id
                + ",\"result\":{\"protocolVersion\":\"2025-03-26\","
                + "\"capabilities\":{\"tools\":{}},"
                + "\"serverInfo\":{\"name\":\"protege-mcp\","
                + "\"version\":\"" + SERVER_VERSION + "\"}}}";
    }

    // ── NDJSON line I/O (MCP stdio side) ─────────────────────────────────────

    static String readLine(BufferedReader in) throws IOException {
        String line;
        // Skip blank lines (some clients send keepalive newlines).
        while ((line = in.readLine()) != null) {
            if (!line.isEmpty())
                return line;
        }
        throw new EOFException("stdin closed");
    }

    static void writeLine(OutputStream out, String json) throws IOException {
        byte[] body = json.getBytes(StandardCharsets.UTF_8);
        out.write(body);
        out.write('\n');
        out.flush();
    }

    /** Extracts the top-level {@code "id"} value from a JSON-RPC message. */
    static String extractJsonId(String json) {
        int idx = json.indexOf("\"id\"");
        if (idx == -1)
            return "null";
        idx = json.indexOf(':', idx) + 1;
        while (idx < json.length() && Character.isWhitespace(json.charAt(idx)))
            idx++;
        if (idx >= json.length())
            return "null";
        char first = json.charAt(idx);
        if (first == '"') {
            int end = json.indexOf('"', idx + 1);
            return end == -1 ? "null" : "\"" + json.substring(idx + 1, end) + "\"";
        }
        int end = idx;
        while (end < json.length() && ",}]".indexOf(json.charAt(end)) == -1
                && !Character.isWhitespace(json.charAt(end))) {
            end++;
        }
        return json.substring(idx, end);
    }

    // ── Translating relays (NDJSON ↔ Content-Length) ────────────────────────

    private static void pumpLinesToFrames(BufferedReader src, OutputStream dst,
            Runnable onEof) {
        try {
            String line;
            while ((line = src.readLine()) != null) {
                if (line.isEmpty())
                    continue;
                writeFrame(dst, line.getBytes(StandardCharsets.UTF_8));
            }
        } catch (IOException ignored) {
            // peer closed or stdio EOF; fall through to shutdown
        } finally {
            onEof.run();
        }
    }

    private static void pumpFramesToLines(InputStream src, OutputStream dst,
            Runnable onEof) {
        try {
            while (true) {
                byte[] body = readFrame(src);
                dst.write(body);
                dst.write('\n');
                dst.flush();
            }
        } catch (IOException ignored) {
            // peer closed or stdio EOF; fall through to shutdown
        } finally {
            onEof.run();
        }
    }

    // ── TCP connection with retry ────────────────────────────────────────────

    private static Socket connectWithRetry(String host, int port) throws IOException {
        SocketAddress addr = new InetSocketAddress(host, port);
        long nextLog = System.currentTimeMillis() + LOG_INTERVAL_MS;
        while (true) {
            Socket s = new Socket();
            try {
                s.connect(addr, CONNECT_TIMEOUT_MS);
                return s;
            } catch (IOException e) {
                closeQuietly(s);
                long now = System.currentTimeMillis();
                if (now >= nextLog) {
                    System.err.printf(
                            "protege-mcp-relay: still waiting for Protégé on %s:%d ...%n",
                            host, port);
                    nextLog = now + LOG_INTERVAL_MS;
                }
                try {
                    Thread.sleep(RETRY_INTERVAL_MS);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new IOException("interrupted while connecting", ie);
                }
            }
        }
    }

    private static void closeQuietly(Socket s) {
        try {
            s.close();
        } catch (IOException ignored) {
            /* best effort */ }
    }

    // ── Configuration resolution ─────────────────────────────────────────────

    private static String resolveHost(String[] args) {
        String v = parseFlag(args, "--host=");
        if (v != null && !v.isEmpty())
            return v;
        v = System.getProperty("protege.mcp.host");
        if (v != null && !v.isEmpty())
            return v;
        v = System.getenv("PROTEGE_MCP_HOST");
        if (v != null && !v.isEmpty())
            return v;
        return DEFAULT_HOST;
    }

    private static int resolvePort(String[] args) {
        Integer p = parsePort(parseFlag(args, "--port="));
        if (p != null)
            return p;
        p = parsePort(System.getProperty("protege.mcp.tcp.port"));
        if (p != null)
            return p;
        p = parsePort(System.getenv("PROTEGE_MCP_TCP_PORT"));
        if (p != null)
            return p;
        return DEFAULT_PORT;
    }

    private static Integer parsePort(String raw) {
        if (raw == null || raw.trim().isEmpty())
            return null;
        try {
            int p = Integer.parseInt(raw.trim());
            if (p < 1 || p > 65535) {
                System.err.println("protege-mcp-relay: port out of range: " + raw);
                return null;
            }
            return p;
        } catch (NumberFormatException e) {
            System.err.println("protege-mcp-relay: invalid port: " + raw);
            return null;
        }
    }

    private static String parseFlag(String[] args, String prefix) {
        if (args == null)
            return null;
        for (String a : args) {
            if (a != null && a.startsWith(prefix))
                return a.substring(prefix.length());
        }
        return null;
    }
}
