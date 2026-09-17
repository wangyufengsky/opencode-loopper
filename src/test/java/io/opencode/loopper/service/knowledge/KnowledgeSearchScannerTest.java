package io.opencode.loopper.service.knowledge;

import io.opencode.loopper.service.assist.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class KnowledgeSearchScannerTest {
    @Test void searchesAllAllowedSchemaMetadataInPagesAndReturnsExactReadArguments() {
        var databases = mock(DatabaseQueryService.class);
        var config = new DatabaseConfig(DatabaseConfig.Type.MYSQL, "host", 3306, "app", "reader", "driver.jar", "a.Driver", List.of("app", "other"), Map.of(), 2, 100);
        var bound = new DatabaseConnectionService.Bound("db", "业务库", config, "private-ref", 3);
        var scanner = new KnowledgeSearchScanner(mock(KnowledgeReader.class), databases, new ObjectMapper());
        var columns = List.of("TABLE_NAME", "COLUMN_NAME", "REMARKS", "TYPE_NAME", "ORDINAL_POSITION").stream().map(n -> Map.of("name", n)).toList();
        when(databases.searchColumns(bound, "app", 0)).thenReturn(Map.of("columns", columns, "rows", List.of(List.of("customer", "customer_id", "客户编号", "VARCHAR", 103)), "nextOffset", 100, "truncated", true, "collectedAt", "now"));
        when(databases.searchColumns(bound, "app", 100)).thenReturn(Map.of("columns", columns, "rows", List.of(), "nextOffset", -1));
        when(databases.inspect(bound, "app", null, "tables", 0)).thenReturn(Map.of("columns", columns, "rows", List.of(List.of("orders", "", "客户编号订单", "", 0)), "nextOffset", -1, "collectedAt", "later"));
        var query = new KnowledgeSearchQuery("customerId", "AUTO", List.of("客户编号"));
        var first = scanner.database(bound, query, null); assertThat(first.nextCursor()).isEqualTo("0:columns:100");
        assertThat(first.matches()).singleElement().satisfies(m -> {
            assertThat(m).containsEntry("matchType", "FIELD").containsEntry("configurationVersion", 3L).containsEntry("location", "app.customer.customer_id");
            assertThat(m.get("read")).isEqualTo(Map.of("tool", "inspect_database_schema", "arguments", Map.of("connectionId", "db", "schema", "app", "table", "customer", "kind", "columns", "offset", 100)));
            assertThat(m.toString()).doesNotContain("private-ref", "host", "reader");
        });
        var second = scanner.database(bound, query, first.nextCursor()); assertThat(second.nextCursor()).isEqualTo("0:tables:0");
        var third = scanner.database(bound, query, second.nextCursor()); assertThat(third.nextCursor()).isEqualTo("1:columns:0");
        assertThat(third.matches()).singleElement().satisfies(m -> assertThat(m).containsEntry("matchType", "EXPANDED"));
        verify(databases, never()).query(any(), any());
    }
}
