package io.opencode.loopper.runtime;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class OpenCodeToolInventoryTest {
    @Test void exposesAllStatusesButNeverCredentialsOrPrivateServerNames() throws Exception {
        var http = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var paths = new java.util.ArrayList<String>();
        http.createContext("/", exchange -> {
            paths.add(exchange.getRequestURI().toString());
            String body = exchange.getRequestURI().getPath().equals("/mcp")
                    ? "{\"enabled\":{\"status\":\"connected\"},\"disabled\":{\"status\":\"disabled\"},\"private-generation-name\":{\"status\":\"connected\"}}"
                    : "{\"mcp\":{\"enabled\":{\"type\":\"remote\",\"headers\":{\"Authorization\":\"secret-token\"}},\"disabled\":{\"type\":\"local\"},\"private-generation-name\":{\"type\":\"remote\"}}}";
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length); exchange.getResponseBody().write(bytes); exchange.close();
        });
        http.start();
        try {
            var runtime = mock(OpenCodeRuntimeManager.class); var catalogs = mock(McpToolCatalogReader.class);
            when(runtime.connectionForClient()).thenReturn(new OpenCodeRuntimeManager.Connection(
                    URI.create("http://127.0.0.1:" + http.getAddress().getPort()), null, null, true, "generation", "private-generation-name"));
            when(catalogs.read(any(), any())).thenReturn(new McpToolCatalogReader.Catalog(
                    List.of(new McpToolCatalogReader.Tool("search", "Find indexed code")), true, null));
            var service = new OpenCodeToolInventory(runtime, catalogs); Path directory = Path.of("/tmp/project & query");
            var inventory = service.inventory(directory);
            assertThat(inventory.servers()).hasSize(3);
            String encoded = JsonMapper.builder().build().writeValueAsString(inventory);
            assertThat(encoded).contains("Loopper 内部候选提交").doesNotContain("private-generation-name", "secret-token", "Authorization");
            assertThat(service.tools(directory, "disabled").complete()).isFalse();
            verifyNoInteractions(catalogs);
            assertThat(service.tools(directory, "enabled").tools()).hasSize(1);
            service.tools(directory, "enabled");
            verify(catalogs, times(1)).read(any(), eq(directory));
            assertThat(paths).allSatisfy(path -> assertThat(path).contains("%26").doesNotContain("query&"));
        } finally { http.stop(0); }
    }
}
