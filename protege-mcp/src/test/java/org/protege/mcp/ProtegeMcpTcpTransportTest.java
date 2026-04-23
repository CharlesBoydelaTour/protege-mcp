package org.protege.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.After;
import org.junit.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * End-to-end tests for {@link ProtegeMcpTcpTransport}: framing parity with the
 * stdio path, concurrent client handling, and clean port release on close.
 */
public class ProtegeMcpTcpTransportTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ProtegeMcpTcpTransport transport;

    @After
    public void tearDown() {
        if (transport != null) {
            transport.close();
            transport = null;
        }
    }

    @Test
    public void initializeAndServerInfoOverTcp() throws Exception {
        transport = new ProtegeMcpTcpTransport(0);
        transport.start();
        int port = transport.boundPort();
        assertThat(port, greaterThan(0));

        try (Socket socket = new Socket(InetAddress.getByName("127.0.0.1"), port)) {
            OutputStream out = socket.getOutputStream();
            InputStream in = socket.getInputStream();
            McpContentLengthCodec codec = new McpContentLengthCodec(MAPPER);

            writeFrame(out, "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{}}");
            JsonNode initResp = codec.read(in);
            assertNotNull("initialize response", initResp);
            assertThat(initResp.path("id").asInt(), equalTo(1));
            assertThat(initResp.path("result").path("protocolVersion").asText(),
                    equalTo("2025-03-26"));
            assertThat(initResp.path("result").path("serverInfo").path("name").asText(),
                    equalTo("protege-mcp"));

            writeFrame(out,
                    "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/call\","
                            + "\"params\":{\"name\":\"server_info\",\"arguments\":{}}}");
            JsonNode infoResp = codec.read(in);
            assertNotNull("server_info response", infoResp);
            assertThat(infoResp.path("id").asInt(), equalTo(2));
            JsonNode structured = infoResp.path("result").path("structuredContent");
            assertThat(structured.path("name").asText(), equalTo("protege-mcp"));
        }
    }

    @Test
    public void twoConcurrentClientsBothSucceed() throws Exception {
        transport = new ProtegeMcpTcpTransport(0);
        transport.start();
        int port = transport.boundPort();

        try (Socket a = new Socket(InetAddress.getByName("127.0.0.1"), port);
                Socket b = new Socket(InetAddress.getByName("127.0.0.1"), port)) {

            McpContentLengthCodec codec = new McpContentLengthCodec(MAPPER);

            writeFrame(a.getOutputStream(),
                    "{\"jsonrpc\":\"2.0\",\"id\":11,\"method\":\"initialize\",\"params\":{}}");
            writeFrame(b.getOutputStream(),
                    "{\"jsonrpc\":\"2.0\",\"id\":22,\"method\":\"initialize\",\"params\":{}}");

            JsonNode respA = codec.read(a.getInputStream());
            JsonNode respB = codec.read(b.getInputStream());

            assertNotNull(respA);
            assertNotNull(respB);
            assertThat(respA.path("id").asInt(), equalTo(11));
            assertThat(respB.path("id").asInt(), equalTo(22));
            assertThat(respA.path("result").path("serverInfo").path("name").asText(),
                    equalTo("protege-mcp"));
            assertThat(respB.path("result").path("serverInfo").path("name").asText(),
                    equalTo("protege-mcp"));
        }
    }

    @Test
    public void closeReleasesThePort() throws Exception {
        // Discover a free port, then bind the transport explicitly to it.
        int port;
        try (ServerSocket probe = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            port = probe.getLocalPort();
        }

        ProtegeMcpTcpTransport first = new ProtegeMcpTcpTransport(port);
        first.start();
        assertThat(first.boundPort(), equalTo(port));
        first.close();

        // Re-binding to the same port must succeed once close() returns.
        ProtegeMcpTcpTransport second = new ProtegeMcpTcpTransport(port);
        try {
            second.start();
            assertThat(second.boundPort(), equalTo(port));
        } finally {
            second.close();
        }
    }

    @Test
    public void resolvePortFallsBackToDefaultOnInvalidInput() {
        assertThat(ProtegeMcpTcpTransport.resolvePort(null, null),
                equalTo(ProtegeMcpTcpTransport.DEFAULT_PORT));
        assertThat(ProtegeMcpTcpTransport.resolvePort("", null),
                equalTo(ProtegeMcpTcpTransport.DEFAULT_PORT));
        assertThat(ProtegeMcpTcpTransport.resolvePort("not-a-number", null),
                equalTo(ProtegeMcpTcpTransport.DEFAULT_PORT));
        assertThat(ProtegeMcpTcpTransport.resolvePort("99999", null),
                equalTo(ProtegeMcpTcpTransport.DEFAULT_PORT));
        assertThat(ProtegeMcpTcpTransport.resolvePort("12345", null),
                equalTo(12345));
        assertThat(ProtegeMcpTcpTransport.resolvePort(null, "23456"),
                equalTo(23456));
        // Property wins over env.
        assertThat(ProtegeMcpTcpTransport.resolvePort("12345", "23456"),
                equalTo(12345));
    }

    @Test
    public void tcpEnabledRespectsFalsyAndTruthyValues() {
        // Mirrors stdio falsy parsing; reuses the activator helper internally.
        assertTrue(ProtegeMcpBundleActivator.enabledByDefault(null, null));
        assertTrue(ProtegeMcpBundleActivator.enabledByDefault("true", null));
        assertTrue(ProtegeMcpBundleActivator.enabledByDefault(null, "yes"));
        // and disable cases:
        org.junit.Assert.assertFalse(ProtegeMcpBundleActivator.enabledByDefault("false", null));
        org.junit.Assert.assertFalse(ProtegeMcpBundleActivator.enabledByDefault(null, "0"));
    }

    private static void writeFrame(OutputStream out, String json) throws IOException {
        byte[] payload = json.getBytes(StandardCharsets.UTF_8);
        out.write(("Content-Length: " + payload.length + "\r\n\r\n")
                .getBytes(StandardCharsets.US_ASCII));
        out.write(payload);
        out.flush();
    }
}
