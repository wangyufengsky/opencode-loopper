package io.opencode.loopper.runtime;

import io.opencode.loopper.service.NotFoundException;
import io.opencode.loopper.service.ServiceUnavailableException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashMap;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;

/** Projects live OpenCode MCP state; secrets and runtime-private server identifiers never leave this service. */
@Service
public class OpenCodeToolInventory {
    private final OpenCodeRuntimeManager runtime;
    private final McpToolCatalogReader catalogs;
    private final OpenCodeHttpTransport transport;
    private final LinkedHashMap<String, Cached> cache = new LinkedHashMap<>();
    public record Server(String id, String name, String status, String type) { }
    public record Inventory(List<Server> servers, String checkedAt, boolean complete) { }
    private record Context(RestClient client, String internalName, String identity) { }
    private record Cached(Instant at, McpToolCatalogReader.Catalog catalog) { }

    public OpenCodeToolInventory(OpenCodeRuntimeManager runtime, McpToolCatalogReader catalogs) {
        this.runtime = runtime; this.catalogs = catalogs;
        this.transport = new OpenCodeHttpTransport(RestClient.builder(), Duration.ofSeconds(3), Duration.ofSeconds(8));
    }

    public Inventory inventory(Path directory) {
        Context context = context();
        JsonNode statuses = get(context.client(), "/mcp", directory);
        JsonNode configs = get(context.client(), "/config", directory).path("mcp");
        var names = new java.util.LinkedHashSet<String>();
        if (configs.isObject()) configs.propertyStream().forEach(entry -> names.add(entry.getKey()));
        statuses.propertyStream().forEach(entry -> names.add(entry.getKey()));
        var result = new ArrayList<Server>();
        names.stream().limit(256).forEach(name -> {
            boolean internal = name.equals(context.internalName());
            JsonNode state = statuses.path(name);
            String status = state.isTextual() ? state.asText() : state.path("status").asText("unknown");
            result.add(new Server(internal ? "@loopper-internal" : name,
                    internal ? "Loopper 内部候选提交" : name, status, configs.path(name).path("type").asText("unknown")));
        });
        return new Inventory(List.copyOf(result), Instant.now().toString(), names.size() <= 256);
    }

    public synchronized McpToolCatalogReader.Catalog tools(Path directory, String serverId) {
        Context context = context();
        String name = "@loopper-internal".equals(serverId) ? context.internalName() : serverId;
        if (name == null || name.isBlank()) throw new NotFoundException("MCP server not found");
        JsonNode statuses = get(context.client(), "/mcp", directory);
        JsonNode state = statuses.path(name);
        String status = state.isTextual() ? state.asText() : state.path("status").asText();
        if (!"connected".equalsIgnoreCase(status)) return new McpToolCatalogReader.Catalog(List.of(), false,
                "MCP 尚未连接，请在 OpenCode 中连接或完成授权后刷新");
        JsonNode config = get(context.client(), "/config", directory).path("mcp").path(name);
        if (!config.isObject()) return McpToolCatalogReader.unavailable();
        if (!config.path("enabled").asBoolean(true)) return new McpToolCatalogReader.Catalog(List.of(), false, "MCP 已停用");
        // A config change invalidates the projection, but its content is never returned or logged.
        String key = context.identity() + "\n" + directory + "\n" + name + "\n" + config.hashCode();
        Cached cached = cache.get(key);
        if (cached != null && cached.at().plusSeconds(20).isAfter(Instant.now())) return cached.catalog();
        McpToolCatalogReader.Catalog catalog;
        try { catalog = catalogs.read(config, directory); }
        catch (RuntimeException unavailable) { catalog = McpToolCatalogReader.unavailable(); }
        if (cache.size() >= 128) cache.remove(cache.keySet().iterator().next());
        if (catalog.complete()) cache.put(key, new Cached(Instant.now(), catalog));
        return catalog;
    }

    private Context context() {
        var connection = runtime.connectionForClient();
        return new Context(transport.client(new OpenCodeConnectionDetails(connection.endpoint(), connection.username(),
                connection.password(), connection.managed(), connection.generation(), connection.internalMcpServer())),
                connection.internalMcpServer(), connection.endpoint() + ":" + connection.generation());
    }
    private JsonNode get(RestClient client, String path, Path directory) {
        try {
            JsonNode body = client.get().uri(uri -> OpenCodeHttpTransport.directoryUri(uri, path, directory))
                    .retrieve().body(JsonNode.class);
            if (body == null || !body.isObject()) throw new IllegalStateException("Invalid inventory");
            return body;
        } catch (RuntimeException failure) {
            throw new ServiceUnavailableException("OPENCODE_TOOLS_UNAVAILABLE", "无法读取 OpenCode 的 MCP 信息，请检查运行环境后重试");
        }
    }
}
