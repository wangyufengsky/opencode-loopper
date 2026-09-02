package io.opencode.loopper.runtime;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.client.transport.HttpClientSseClientTransport;
import io.modelcontextprotocol.client.transport.ServerParameters;
import io.modelcontextprotocol.client.transport.StdioClientTransport;
import io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapper;
import io.modelcontextprotocol.spec.McpClientTransport;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Reads schemas only. No tools/call, model sampling, prompts or configuration writes. */
@Component
public class McpToolCatalogReader {
    private static final Duration TIMEOUT = Duration.ofSeconds(5);
    private final JacksonMcpJsonMapper json = new JacksonMcpJsonMapper(JsonMapper.builder().build());
    public record Tool(String name, String description) { }
    public record Catalog(List<Tool> tools, boolean complete, String detail) { }

    public Catalog read(JsonNode config, Path directory) {
        if ("local".equals(config.path("type").asText())) return list(local(config, directory));
        if (!"remote".equals(config.path("type").asText())) return unavailable();
        try { return list(remote(config, false)); }
        catch (RuntimeException first) { return list(remote(config, true)); }
    }

    private Catalog list(McpClientTransport transport) {
        var client = McpClient.sync(transport).requestTimeout(TIMEOUT).initializationTimeout(TIMEOUT).build();
        try {
            var initialized = client.initialize();
            if (initialized.capabilities().tools() == null) return new Catalog(List.of(), true, null);
            var tools = new ArrayList<Tool>();
            var cursors = new HashSet<String>();
            String cursor = null;
            long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
            do {
                var page = client.listTools(cursor);
                for (var tool : page.tools()) {
                    if (tools.size() >= 512) return new Catalog(List.copyOf(tools), false, "工具数量超过单次读取上限，请在 MCP 服务端查看完整列表");
                    tools.add(new Tool(bounded(tool.name(), 256), bounded(tool.description(), 16_384)));
                }
                cursor = page.nextCursor();
                if (cursor != null && (!cursors.add(cursor) || System.nanoTime() > deadline)) {
                    return new Catalog(List.copyOf(tools), false, "工具列表尚未全部读取，请刷新重试");
                }
            } while (cursor != null && !cursor.isBlank());
            return new Catalog(List.copyOf(tools), true, null);
        } finally {
            // AutoCloseable.close() starts asynchronous cleanup; wait for the stdio child to exit.
            if (!client.closeGracefully()) throw new IllegalStateException("MCP catalog client shutdown timed out");
        }
    }

    private McpClientTransport local(JsonNode config, Path directory) {
        List<String> command = new ArrayList<>();
        config.path("command").forEach(value -> command.add(value.asText()));
        if (command.isEmpty() || command.size() > 128) throw new IllegalArgumentException("Invalid MCP command");
        var parameters = ServerParameters.builder(command.getFirst()).args(command.subList(1, command.size()))
                .env(strings(config.path("environment"))).build();
        var transport = new StdioClientTransport(parameters, json) {
            @Override protected ProcessBuilder getProcessBuilder() {
                return super.getProcessBuilder().directory(directory.toFile());
            }
        };
        transport.setStdErrorHandler(ignored -> { }); // Server stderr may contain credentials.
        return transport;
    }

    private McpClientTransport remote(JsonNode config, boolean sse) {
        URI uri = URI.create(config.path("url").asText());
        if (!List.of("http", "https").contains(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null) {
            throw new IllegalArgumentException("Invalid configured MCP endpoint");
        }
        String base = uri.getScheme() + "://" + uri.getRawAuthority();
        String endpoint = uri.getRawPath() + (uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery());
        var request = HttpRequest.newBuilder().timeout(TIMEOUT);
        strings(config.path("headers")).forEach(request::header);
        var http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).connectTimeout(TIMEOUT);
        if (sse) return HttpClientSseClientTransport.builder(base).sseEndpoint(endpoint).jsonMapper(json)
                .clientBuilder(http).requestBuilder(request).connectTimeout(TIMEOUT).build();
        return HttpClientStreamableHttpTransport.builder(base).endpoint(endpoint).jsonMapper(json)
                .clientBuilder(http).requestBuilder(request).connectTimeout(TIMEOUT).openConnectionOnStartup(false).build();
    }

    public static Catalog unavailable() {
        return new Catalog(List.of(), false, "暂时无法读取工具描述；请检查 MCP 连接和授权后重试");
    }
    private static Map<String, String> strings(JsonNode node) {
        Map<String, String> values = new LinkedHashMap<>();
        if (node.isObject()) node.propertyStream().forEach(entry -> {
            if (entry.getValue().isTextual()) values.put(entry.getKey(), entry.getValue().asText());
        });
        return values;
    }
    private static String bounded(String value, int limit) {
        if (value == null) return "";
        return value.length() <= limit ? value : value.substring(0, limit) + "…";
    }
}
