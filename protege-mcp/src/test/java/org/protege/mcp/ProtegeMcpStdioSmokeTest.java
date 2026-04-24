package org.protege.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * End-to-end stdio smoke test for {@link ProtegeMcpServer}.
 *
 * <p>
 * Drives the server with real {@code Content-Length} framed JSON-RPC over
 * piped byte streams using a real {@link ProtegeMcpToolExecutor}. No Protege
 * workspace is running, so tools that require an {@code OWLModelManager} are
 * expected to surface as well-formed JSON-RPC error envelopes (not crash the
 * server).
 */
public class ProtegeMcpStdioSmokeTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    public void initializeListAndCallToolsOverStdio() throws Exception {
        ProtegeMcpServer server = new ProtegeMcpServer(new ProtegeMcpToolExecutor());

        ByteArrayOutputStream requestBuf = new ByteArrayOutputStream();
        writeFrame(requestBuf, "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{}}");
        writeFrame(requestBuf, "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}");
        writeFrame(requestBuf, "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\"}");
        writeFrame(requestBuf, "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"tools/call\","
                + "\"params\":{\"name\":\"server_info\",\"arguments\":{}}}");
        writeFrame(requestBuf, "{\"jsonrpc\":\"2.0\",\"id\":4,\"method\":\"tools/call\","
                + "\"params\":{\"name\":\"ontology_list\",\"arguments\":{}}}");

        ByteArrayInputStream in = new ByteArrayInputStream(requestBuf.toByteArray());
        ByteArrayOutputStream out = new ByteArrayOutputStream();

        server.serve(in, out);

        List<JsonNode> responses = readFrames(new ByteArrayInputStream(out.toByteArray()));
        assertThat("expected 4 responses (notification produces no reply)",
                responses.size(), equalTo(4));

        // 1) initialize
        JsonNode initResp = responses.get(0);
        assertThat(initResp.path("id").asInt(), equalTo(1));
        assertThat(initResp.path("jsonrpc").asText(), equalTo("2.0"));
        JsonNode initResult = initResp.path("result");
        assertThat("protocolVersion present",
                initResult.path("protocolVersion").asText(), equalTo("2025-03-26"));
        assertThat(initResult.path("serverInfo").path("name").asText(), equalTo("protege-mcp"));
        assertTrue("initialize result must declare tools capability",
                initResult.path("capabilities").has("tools"));

        // 2) tools/list
        JsonNode listResp = responses.get(1);
        assertThat(listResp.path("id").asInt(), equalTo(2));
        JsonNode tools = listResp.path("result").path("tools");
        assertTrue("tools array present", tools.isArray());
        assertThat("at least 20 tools advertised",
                tools.size(), greaterThanOrEqualTo(20));

        // 3) tools/call server_info -> success, no Protege required
        JsonNode infoResp = responses.get(2);
        assertThat(infoResp.path("id").asInt(), equalTo(3));
        assertNull("server_info must not produce an error envelope",
                infoResp.get("error"));
        JsonNode infoStructured = infoResp.path("result").path("structuredContent");
        assertThat(infoStructured.path("name").asText(), equalTo("protege-mcp"));
        assertThat(infoStructured.path("transport").asText(), equalTo("stdio"));
        assertThat("isError flag false on success",
                infoResp.path("result").path("isError").asBoolean(), equalTo(false));

        // 4) tools/call ontology_list -> error envelope, no crash
        JsonNode listOntResp = responses.get(3);
        assertThat(listOntResp.path("id").asInt(), equalTo(4));
        JsonNode err = listOntResp.get("error");
        assertNotNull("ontology_list without a Protege workspace must yield a JSON-RPC error", err);
        assertThat(err.path("code").asInt(), equalTo(-32000));
        assertThat(err.path("message").asText(),
                containsString("No active Protege workspace"));
        assertThat("error envelope must not also carry a result",
                listOntResp.get("result"), equalTo(null));
        assertThat(listOntResp.path("jsonrpc").asText(), equalTo("2.0"));
    }

    private static void writeFrame(ByteArrayOutputStream out, String json) throws IOException {
        byte[] payload = json.getBytes(StandardCharsets.UTF_8);
        out.write(("Content-Length: " + payload.length + "\r\n\r\n")
                .getBytes(StandardCharsets.US_ASCII));
        out.write(payload);
    }

    private static List<JsonNode> readFrames(InputStream in) throws IOException {
        List<JsonNode> out = new ArrayList<>();
        McpContentLengthCodec codec = new McpContentLengthCodec(MAPPER);
        while (true) {
            JsonNode node = codec.read(in);
            if (node == null) {
                return out;
            }
            out.add(node);
        }
    }
}
