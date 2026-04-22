package org.protege.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

public class ProtegeMcpServer {

    private static final String JSON_RPC_VERSION = "2.0";
    private static final String MCP_PROTOCOL_VERSION = "2025-03-26";

    private final ObjectMapper objectMapper;
    private final McpContentLengthCodec codec;
    private final McpToolExecutor toolExecutor;

    public ProtegeMcpServer(McpToolExecutor toolExecutor) {
        this(new ObjectMapper(), toolExecutor);
    }

    ProtegeMcpServer(ObjectMapper objectMapper, McpToolExecutor toolExecutor) {
        this.objectMapper = objectMapper;
        this.codec = new McpContentLengthCodec(objectMapper);
        this.toolExecutor = toolExecutor;
    }

    public void serve(InputStream inputStream, OutputStream outputStream) throws IOException {
        while (!Thread.currentThread().isInterrupted()) {
            JsonNode request = codec.read(inputStream);
            if (request == null) {
                return;
            }
            JsonNode response = handle(request);
            if (response != null) {
                codec.write(outputStream, response);
            }
        }
    }

    JsonNode handle(JsonNode request) {
        JsonNode id = request.path("id");
        if (!request.hasNonNull("method")) {
            return errorResponse(id, -32600, "Invalid Request", null);
        }
        String method = request.path("method").asText();
        JsonNode params = request.path("params");
        try {
            switch (method) {
                case "initialize":
                    return successResponse(id, initializeResult());
                case "notifications/initialized":
                    return id.isMissingNode() || id.isNull() ? null : successResponse(id, NullNode.instance);
                case "tools/list":
                    return successResponse(id, toolsListResult());
                case "tools/call":
                    return successResponse(id, toolsCallResult(params));
                default:
                    return errorResponse(id, -32601, "Method not found", objectNode().put("method", method));
            }
        } catch (ProtegeMcpToolExecutor.McpException e) {
            return errorResponse(id, e.getCode(), e.getMessage(), e.getData());
        } catch (Exception e) {
            ObjectNode data = objectNode();
            data.put("exception", e.getClass().getName());
            data.put("message", e.getMessage() == null ? "" : e.getMessage());
            return errorResponse(id, -32000, "Tool execution failed", data);
        }
    }

    private ObjectNode initializeResult() {
        ObjectNode result = objectNode();
        result.put("protocolVersion", MCP_PROTOCOL_VERSION);
        ObjectNode capabilities = result.putObject("capabilities");
        capabilities.putObject("tools");
        ObjectNode serverInfo = result.putObject("serverInfo");
        serverInfo.put("name", "protege-mcp");
        serverInfo.put("version", ProtegeMcpVersion.get());
        return result;
    }

    private ObjectNode toolsListResult() {
        ObjectNode result = objectNode();
        ArrayNode tools = result.putArray("tools");
        for (ObjectNode tool : toolExecutor.listTools()) {
            tools.add(tool);
        }
        return result;
    }

    private ObjectNode toolsCallResult(JsonNode params) throws Exception {
        String toolName = params.path("name").asText(null);
        if (toolName == null || toolName.isEmpty()) {
            throw new ProtegeMcpToolExecutor.McpException(-32602, "Missing tool name", null);
        }
        JsonNode arguments = params.path("arguments");
        JsonNode structuredContent = toolExecutor.execute(toolName, arguments);
        ObjectNode result = objectNode();
        result.put("isError", false);
        result.set("structuredContent", structuredContent);
        ArrayNode content = result.putArray("content");
        ObjectNode textContent = content.addObject();
        textContent.put("type", "text");
        textContent.put("text", structuredContent.toPrettyString());
        return result;
    }

    private ObjectNode successResponse(JsonNode id, JsonNode result) {
        ObjectNode response = objectNode();
        response.put("jsonrpc", JSON_RPC_VERSION);
        response.set("id", normalizeId(id));
        response.set("result", result);
        return response;
    }

    private ObjectNode errorResponse(JsonNode id, int code, String message, JsonNode data) {
        ObjectNode response = objectNode();
        response.put("jsonrpc", JSON_RPC_VERSION);
        response.set("id", normalizeId(id));
        ObjectNode error = response.putObject("error");
        error.put("code", code);
        error.put("message", message);
        if (data != null && !data.isNull()) {
            error.set("data", data);
        }
        return response;
    }

    private JsonNode normalizeId(JsonNode id) {
        return id == null || id.isMissingNode() ? NullNode.instance : id;
    }

    private ObjectNode objectNode() {
        return objectMapper.createObjectNode();
    }
}
