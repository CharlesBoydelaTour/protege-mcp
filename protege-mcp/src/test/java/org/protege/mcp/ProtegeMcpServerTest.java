package org.protege.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.Before;
import org.junit.Test;

import java.util.Collections;
import java.util.List;

import static org.junit.Assert.*;

/**
 * Unit tests for {@link ProtegeMcpServer#handle(JsonNode)}.
 * Uses a stub {@link McpToolExecutor} – no Protege runtime required.
 */
public class ProtegeMcpServerTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ProtegeMcpServer server;

    @Before
    public void setUp() {
        server = new ProtegeMcpServer(MAPPER, stubExecutor());
    }

    // -----------------------------------------------------------------------
    // initialize
    // -----------------------------------------------------------------------

    @Test
    public void handle_initialize_returnsCapabilities() {
        JsonNode req = request(1, "initialize", null);
        JsonNode resp = server.handle(req);

        assertNotNull(resp);
        assertEquals("2.0", resp.path("jsonrpc").asText());
        assertEquals(1, resp.path("id").asInt());
        assertTrue(resp.has("result"));
        JsonNode result = resp.path("result");
        assertEquals("2025-03-26", result.path("protocolVersion").asText());
        assertTrue(result.path("capabilities").has("tools"));
        assertEquals("protege-mcp", result.path("serverInfo").path("name").asText());
    }

    // -----------------------------------------------------------------------
    // notifications/initialized
    // -----------------------------------------------------------------------

    @Test
    public void handle_notificationsInitialized_noId_returnsNull() {
        // Notifications have no "id"
        ObjectNode req = MAPPER.createObjectNode();
        req.put("jsonrpc", "2.0");
        req.put("method", "notifications/initialized");
        assertNull(server.handle(req));
    }

    @Test
    public void handle_notificationsInitialized_withId_returnsResult() {
        JsonNode req = request(99, "notifications/initialized", null);
        JsonNode resp = server.handle(req);
        assertNotNull(resp);
        assertEquals(99, resp.path("id").asInt());
    }

    // -----------------------------------------------------------------------
    // tools/list
    // -----------------------------------------------------------------------

    @Test
    public void handle_toolsList_returnsToolArray() {
        JsonNode req = request(2, "tools/list", null);
        JsonNode resp = server.handle(req);

        assertNotNull(resp);
        assertTrue(resp.path("result").has("tools"));
        JsonNode tools = resp.path("result").path("tools");
        assertTrue(tools.isArray());
        assertEquals(1, tools.size());
        assertEquals("stub_tool", tools.get(0).path("name").asText());
    }

    // -----------------------------------------------------------------------
    // tools/call
    // -----------------------------------------------------------------------

    @Test
    public void handle_toolsCall_success() {
        ObjectNode params = MAPPER.createObjectNode();
        params.put("name", "stub_tool");
        params.putObject("arguments");

        JsonNode req = request(3, "tools/call", params);
        JsonNode resp = server.handle(req);

        assertNotNull(resp);
        assertFalse(resp.path("result").path("isError").asBoolean(true));
        assertEquals("ok", resp.path("result").path("structuredContent").path("status").asText());
    }

    @Test
    public void handle_toolsCall_unknownTool_returnsToolError() {
        ObjectNode params = MAPPER.createObjectNode();
        params.put("name", "does_not_exist");
        params.putObject("arguments");

        JsonNode req = request(4, "tools/call", params);
        JsonNode resp = server.handle(req);

        assertNotNull(resp);
        assertTrue(resp.has("error"));
        assertEquals(-32601, resp.path("error").path("code").asInt());
    }

    @Test
    public void handle_toolsCall_missingName_returnsInvalidParams() {
        ObjectNode params = MAPPER.createObjectNode();
        params.putObject("arguments");

        JsonNode req = request(5, "tools/call", params);
        JsonNode resp = server.handle(req);

        assertTrue(resp.has("error"));
        assertEquals(-32602, resp.path("error").path("code").asInt());
    }

    // -----------------------------------------------------------------------
    // Unknown method
    // -----------------------------------------------------------------------

    @Test
    public void handle_unknownMethod_returnsMethodNotFound() {
        JsonNode req = request(6, "no/such/method", null);
        JsonNode resp = server.handle(req);

        assertTrue(resp.has("error"));
        assertEquals(-32601, resp.path("error").path("code").asInt());
    }

    // -----------------------------------------------------------------------
    // Malformed request
    // -----------------------------------------------------------------------

    @Test
    public void handle_missingMethod_returnsInvalidRequest() {
        ObjectNode req = MAPPER.createObjectNode();
        req.put("jsonrpc", "2.0");
        req.put("id", 7);
        JsonNode resp = server.handle(req);

        assertTrue(resp.has("error"));
        assertEquals(-32600, resp.path("error").path("code").asInt());
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private static JsonNode request(int id, String method, ObjectNode params) {
        ObjectNode req = MAPPER.createObjectNode();
        req.put("jsonrpc", "2.0");
        req.put("id", id);
        req.put("method", method);
        if (params != null) {
            req.set("params", params);
        }
        return req;
    }

    private static McpToolExecutor stubExecutor() {
        ObjectNode toolDef = MAPPER.createObjectNode();
        toolDef.put("name", "stub_tool");
        toolDef.put("description", "A stub tool for testing.");
        toolDef.putObject("inputSchema").put("type", "object");

        return new McpToolExecutor() {
            @Override
            public List<ObjectNode> listTools() {
                return Collections.singletonList(toolDef);
            }

            @Override
            public JsonNode execute(String toolName, JsonNode arguments) {
                if ("stub_tool".equals(toolName)) {
                    return MAPPER.createObjectNode().put("status", "ok");
                }
                throw new ProtegeMcpToolExecutor.McpException(-32601, "Unknown tool: " + toolName, null);
            }
        };
    }
}
