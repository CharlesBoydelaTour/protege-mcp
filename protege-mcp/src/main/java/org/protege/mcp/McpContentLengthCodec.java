package org.protege.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

final class McpContentLengthCodec {

    private static final byte[] HEADER_SEPARATOR = "\r\n\r\n".getBytes(StandardCharsets.US_ASCII);

    private final ObjectMapper objectMapper;

    McpContentLengthCodec(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    JsonNode read(InputStream inputStream) throws IOException {
        String headerBlock = readHeaderBlock(inputStream);
        if (headerBlock == null) {
            return null;
        }
        int contentLength = parseContentLength(headerBlock);
        byte[] payload = readFully(inputStream, contentLength);
        return objectMapper.readTree(payload);
    }

    void write(OutputStream outputStream, JsonNode jsonNode) throws IOException {
        byte[] payload = objectMapper.writeValueAsBytes(jsonNode);
        outputStream.write(("Content-Length: " + payload.length + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
        outputStream.write(payload);
        outputStream.flush();
    }

    private String readHeaderBlock(InputStream inputStream) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        int matched = 0;
        while (true) {
            int value = inputStream.read();
            if (value == -1) {
                if (buffer.size() == 0) {
                    return null;
                }
                throw new IOException("Unexpected end of stream while reading MCP headers");
            }
            buffer.write(value);
            if (value == HEADER_SEPARATOR[matched]) {
                matched++;
                if (matched == HEADER_SEPARATOR.length) {
                    byte[] bytes = buffer.toByteArray();
                    return new String(bytes, 0, bytes.length - HEADER_SEPARATOR.length, StandardCharsets.US_ASCII);
                }
            }
            else {
                matched = value == HEADER_SEPARATOR[0] ? 1 : 0;
            }
        }
    }

    private int parseContentLength(String headerBlock) throws IOException {
        String[] lines = headerBlock.split("\\r\\n");
        for (String line : lines) {
            int separatorIndex = line.indexOf(':');
            if (separatorIndex < 0) {
                continue;
            }
            String name = line.substring(0, separatorIndex).trim();
            if (!"Content-Length".equalsIgnoreCase(name)) {
                continue;
            }
            String value = line.substring(separatorIndex + 1).trim();
            return Integer.parseInt(value);
        }
        throw new IOException("Missing Content-Length header");
    }

    private byte[] readFully(InputStream inputStream, int length) throws IOException {
        byte[] payload = new byte[length];
        int offset = 0;
        while (offset < length) {
            int read = inputStream.read(payload, offset, length - offset);
            if (read == -1) {
                throw new IOException("Unexpected end of stream while reading MCP payload");
            }
            offset += read;
        }
        return payload;
    }
}
