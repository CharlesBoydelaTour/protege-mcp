package org.protege.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.List;

interface McpToolExecutor {

    List<ObjectNode> listTools();

    JsonNode execute(String toolName, JsonNode arguments) throws Exception;
}
