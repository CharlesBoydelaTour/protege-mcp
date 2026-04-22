package org.protege.mcp;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;

final class ProtegeMcpServerController {

    private static final Logger logger = LoggerFactory.getLogger(ProtegeMcpServerController.class);

    private final ProtegeMcpServer server;
    private Thread serverThread;

    ProtegeMcpServerController(ProtegeMcpServer server) {
        this.server = server;
    }

    void start() {
        if (serverThread != null) {
            return;
        }
        serverThread = new Thread(() -> {
            try {
                server.serve(System.in, System.out);
            }
            catch (IOException e) {
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
