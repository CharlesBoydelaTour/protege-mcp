package org.protege.mcp;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

final class ProtegeMcpServerController {

    private static final Logger logger = LoggerFactory.getLogger(ProtegeMcpServerController.class);

    private final ProtegeMcpServer server;
    private final InputStream in;
    private final OutputStream out;
    private Thread serverThread;

    ProtegeMcpServerController(ProtegeMcpServer server) {
        this(server, System.in, System.out);
    }

    ProtegeMcpServerController(ProtegeMcpServer server, InputStream in, OutputStream out) {
        this.server = server;
        this.in = in;
        this.out = out;
    }

    void start() {
        if (serverThread != null) {
            return;
        }
        serverThread = new Thread(() -> {
            try {
                server.serve(in, out);
            } catch (IOException e) {
                logger.warn("Protege MCP server stopped due to I/O failure: {}", e.getMessage(), e);
            }
        }, "protege-mcp-stdio");
        serverThread.setDaemon(true);
        serverThread.start();
    }

    void stop() {
        if (serverThread == null) {
            return;
        }
        serverThread.interrupt();
        serverThread = null;
    }
}
