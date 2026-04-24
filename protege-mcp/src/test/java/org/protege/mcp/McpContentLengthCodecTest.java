package org.protege.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.Assert.*;

/**
 * Unit tests for {@link McpContentLengthCodec}.
 * Exercises the Content-Length framing without requiring a Protege runtime.
 */
public class McpContentLengthCodecTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    public void roundTrip_simpleMessage() throws Exception {
        McpContentLengthCodec codec = new McpContentLengthCodec(MAPPER);
        ObjectNode msg = MAPPER.createObjectNode();
        msg.put("jsonrpc", "2.0");
        msg.put("id", 1);
        msg.put("method", "tools/list");

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        codec.write(baos, msg);

        byte[] bytes = baos.toByteArray();
        String wire = new String(bytes, StandardCharsets.US_ASCII);
        assertTrue("wire must start with Content-Length header",
                wire.startsWith("Content-Length:"));

        ByteArrayInputStream bais = new ByteArrayInputStream(bytes);
        var read = codec.read(bais);
        assertNotNull(read);
        assertEquals("2.0", read.path("jsonrpc").asText());
        assertEquals(1, read.path("id").asInt());
        assertEquals("tools/list", read.path("method").asText());
    }

    @Test
    public void read_returnsNull_onEmptyStream() throws Exception {
        McpContentLengthCodec codec = new McpContentLengthCodec(MAPPER);
        var result = codec.read(new ByteArrayInputStream(new byte[0]));
        assertNull("empty stream should return null", result);
    }

    @Test
    public void write_producesCorrectContentLength() throws Exception {
        McpContentLengthCodec codec = new McpContentLengthCodec(MAPPER);
        ObjectNode msg = MAPPER.createObjectNode();
        msg.put("x", "hello");

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        codec.write(baos, msg);

        String wire = new String(baos.toByteArray(), StandardCharsets.US_ASCII);
        // Split on CRLFCRLF
        int sep = wire.indexOf("\r\n\r\n");
        assertTrue(sep > 0);
        String header = wire.substring(0, sep);
        String body   = wire.substring(sep + 4);
        String[] parts = header.split(":");
        int declaredLength = Integer.parseInt(parts[1].trim());
        assertEquals(declaredLength, body.length());
    }

    @Test
    public void roundTrip_unicodePayload() throws Exception {
        McpContentLengthCodec codec = new McpContentLengthCodec(MAPPER);
        ObjectNode msg = MAPPER.createObjectNode();
        msg.put("label", "Bürgermeister");

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        codec.write(baos, msg);

        ByteArrayInputStream bais = new ByteArrayInputStream(baos.toByteArray());
        var result = codec.read(bais);
        assertNotNull(result);
        assertEquals("Bürgermeister", result.path("label").asText());
    }

    @Test(expected = java.io.IOException.class)
    public void read_throwsOnTruncatedPayload() throws Exception {
        McpContentLengthCodec codec = new McpContentLengthCodec(MAPPER);
        // Declare 100 bytes but send only 5
        byte[] wire = "Content-Length: 100\r\n\r\nhello".getBytes(StandardCharsets.US_ASCII);
        codec.read(new ByteArrayInputStream(wire));
    }
}
