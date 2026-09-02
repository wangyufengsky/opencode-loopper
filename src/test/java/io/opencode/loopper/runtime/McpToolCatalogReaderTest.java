package io.opencode.loopper.runtime;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.assertThat;

class McpToolCatalogReaderTest {
    @TempDir Path temp;
    private final JsonMapper json = JsonMapper.builder().build();

    @Test void listsAllRemotePagesWithoutCallingAnyTool() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        List<String> methods = java.util.Collections.synchronizedList(new ArrayList<>());
        server.createContext("/mcp", exchange -> {
            if (!"POST".equals(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(405, -1); exchange.close(); return;
            }
            var request = json.readTree(exchange.getRequestBody().readAllBytes());
            String method = request.path("method").asText(); methods.add(method);
            if (!request.has("id")) { exchange.sendResponseHeaders(202, -1); exchange.close(); return; }
            String result;
            if ("initialize".equals(method)) result = "{\"protocolVersion\":\"" + request.path("params").path("protocolVersion").asText()
                    + "\",\"serverInfo\":{\"name\":\"fixture\",\"version\":\"1\"},\"capabilities\":{\"tools\":{}}}";
            else if (!request.path("params").has("cursor")) result = "{\"tools\":[{\"name\":\"first\",\"description\":\"Read records\",\"inputSchema\":{\"type\":\"object\"}}],\"nextCursor\":\"next\"}";
            else result = "{\"tools\":[{\"name\":\"second\",\"description\":\"Search descriptions\",\"inputSchema\":{\"type\":\"object\"}}]}";
            byte[] response = ("{\"jsonrpc\":\"2.0\",\"id\":" + request.get("id") + ",\"result\":" + result + "}").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length); exchange.getResponseBody().write(response); exchange.close();
        });
        server.start();
        try {
            var config = json.readTree("{\"type\":\"remote\",\"url\":\"http://127.0.0.1:" + server.getAddress().getPort() + "/mcp\"}");
            var catalog = new McpToolCatalogReader().read(config, temp);
            assertThat(catalog.complete()).isTrue();
            assertThat(catalog.tools()).extracting(McpToolCatalogReader.Tool::name).containsExactly("first", "second");
            assertThat(catalog.tools().getFirst().description()).isEqualTo("Read records");
            assertThat(methods).contains("initialize", "tools/list").doesNotContain("tools/call", "sampling/createMessage");
        } finally { server.stop(0); }
    }

    @Test void localInventoryUsesTheSelectedProjectDirectory() throws Exception {
        String executable = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        String classes = Path.of("target", "test-classes").toAbsolutePath().toString();
        var config = json.valueToTree(java.util.Map.of("type", "local", "command",
                List.of(executable, "-cp", classes, StdioFixture.class.getName())));
        var catalog = new McpToolCatalogReader().read(config, temp);
        assertThat(catalog.complete()).isTrue();
        Path expected = temp.toRealPath();
        assertThat(catalog.tools()).singleElement().satisfies(tool -> {
            assertThat(tool.name()).isEqualTo("cwd");
            assertThat(Path.of(tool.description())).isEqualTo(expected);
        });
    }

    public static class StdioFixture {
        public static void main(String[] args) throws Exception {
            var input = new java.io.BufferedReader(new java.io.InputStreamReader(System.in));
            String line;
            while ((line = input.readLine()) != null) {
                var id = java.util.regex.Pattern.compile("\"id\"\\s*:\\s*(\"[^\"]*\"|[0-9]+)").matcher(line);
                if (!id.find()) continue;
                String result = line.contains("\"initialize\"")
                        ? "{\"protocolVersion\":\"2025-11-25\",\"serverInfo\":{\"name\":\"fixture\",\"version\":\"1\"},\"capabilities\":{\"tools\":{}}}"
                        : "{\"tools\":[{\"name\":\"cwd\",\"description\":\"" + System.getProperty("user.dir").replace("\\", "\\\\")
                            + "\",\"inputSchema\":{\"type\":\"object\"}}]}";
                System.out.println("{\"jsonrpc\":\"2.0\",\"id\":" + id.group(1) + ",\"result\":" + result + "}");
            }
        }
    }
}
