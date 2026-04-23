package org.protege.mcp;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Localhost-only TCP transport for the Protege MCP server. Speaks the same
 * {@code Content-Length}-framed JSON-RPC 2.0 dialect as the stdio path,
 * by reusing {@link ProtegeMcpServer} and {@link McpContentLengthCodec} on
 * each accepted connection.
 *
 * <p>
 * Bound exclusively to {@code 127.0.0.1}; never to a wildcard address.
 * Each connection runs on its own daemon thread with a fresh
 * {@code ProtegeMcpServer} instance; the underlying tool executor and the
 * Protege workspace state it observes are shared across connections.
 */
final class ProtegeMcpTcpTransport implements AutoCloseable {

    static final String ENABLED_PROPERTY = "protege.mcp.tcp.enabled";
    static final String ENABLED_ENVIRONMENT_VARIABLE = "PROTEGE_MCP_TCP_ENABLED";
    static final String PORT_PROPERTY = "protege.mcp.tcp.port";
    static final String PORT_ENVIRONMENT_VARIABLE = "PROTEGE_MCP_TCP_PORT";
    static final int DEFAULT_PORT = 47800;

    private static final Logger logger = LoggerFactory.getLogger(ProtegeMcpTcpTransport.class);

    private final int requestedPort;
    private final Supplier<ProtegeMcpServer> serverFactory;
    private final Set<Socket> liveSockets = ConcurrentHashMap.newKeySet();

    private ServerSocket serverSocket;
    private Thread acceptThread;
    private volatile boolean closed;

    ProtegeMcpTcpTransport(int port) {
        this(port, () -> new ProtegeMcpServer(new ProtegeMcpToolExecutor()));
    }

    ProtegeMcpTcpTransport(int port, Supplier<ProtegeMcpServer> serverFactory) {
        this.requestedPort = port;
        this.serverFactory = serverFactory;
    }

    /** @return bound port (useful when 0 was requested), or -1 if not started. */
    int boundPort() {
        return serverSocket == null ? -1 : serverSocket.getLocalPort();
    }

    void start() throws IOException {
        InetAddress loopback = InetAddress.getByName("127.0.0.1");
        serverSocket = new ServerSocket(requestedPort, 50, loopback);
        int port = serverSocket.getLocalPort();
        logger.info("Protege MCP TCP transport listening on 127.0.0.1:{}", port);
        acceptThread = new Thread(this::acceptLoop, "protege-mcp-tcp-accept");
        acceptThread.setDaemon(true);
        acceptThread.start();
    }

    private void acceptLoop() {
        while (!closed) {
            Socket socket;
            try {
                socket = serverSocket.accept();
            } catch (IOException e) {
                if (closed) {
                    return;
                }
                logger.debug("Protege MCP TCP accept failed: {}", e.toString());
                continue;
            }
            liveSockets.add(socket);
            Thread t = new Thread(() -> handle(socket), "protege-mcp-tcp-conn");
            t.setDaemon(true);
            t.start();
        }
    }

    private void handle(Socket socket) {
        try {
            ProtegeMcpServer server = serverFactory.get();
            server.serve(socket.getInputStream(), socket.getOutputStream());
        } catch (IOException e) {
            if (!closed) {
                logger.debug("Protege MCP TCP connection ended: {}", e.toString());
            }
        } finally {
            liveSockets.remove(socket);
            try {
                socket.close();
            } catch (IOException ignored) {
                // best effort
            }
        }
    }

    @Override
    public void close() {
        closed = true;
        if (serverSocket != null) {
            try {
                serverSocket.close();
            } catch (IOException ignored) {
                // best effort
            }
        }
        for (Socket s : liveSockets) {
            try {
                s.close();
            } catch (IOException ignored) {
                // best effort
            }
        }
        liveSockets.clear();
        if (acceptThread != null) {
            acceptThread.interrupt();
            acceptThread = null;
        }
    }

    /**
     * Whether the TCP transport should start. Reuses the same falsy-value
     * parsing as the stdio activator flag via
     * {@link ProtegeMcpBundleActivator#enabledByDefault(String, String)}.
     */
    static boolean tcpEnabled() {
        return ProtegeMcpBundleActivator.enabledByDefault(
                System.getProperty(ENABLED_PROPERTY),
                System.getenv(ENABLED_ENVIRONMENT_VARIABLE));
    }

    static int resolvePort() {
        return resolvePort(System.getProperty(PORT_PROPERTY),
                System.getenv(PORT_ENVIRONMENT_VARIABLE));
    }

    static int resolvePort(String propValue, String envValue) {
        String raw = propValue != null ? propValue : envValue;
        if (raw == null || raw.trim().isEmpty()) {
            return DEFAULT_PORT;
        }
        try {
            int p = Integer.parseInt(raw.trim());
            if (p < 0 || p > 65535) {
                logger.warn("Invalid Protege MCP TCP port {}; falling back to default {}", raw, DEFAULT_PORT);
                return DEFAULT_PORT;
            }
            return p;
        } catch (NumberFormatException e) {
            logger.warn("Invalid Protege MCP TCP port '{}'; falling back to default {}", raw, DEFAULT_PORT);
            return DEFAULT_PORT;
        }
    }
}
