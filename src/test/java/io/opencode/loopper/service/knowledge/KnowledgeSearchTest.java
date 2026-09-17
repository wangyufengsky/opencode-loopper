package io.opencode.loopper.service.knowledge;

import io.opencode.loopper.service.assist.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import static io.opencode.loopper.service.knowledge.KnowledgeSearchContracts.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class KnowledgeSearchTest {
    private final KnowledgeSearchScanner scanner = mock(KnowledgeSearchScanner.class);
    private static KnowledgeSources.Bound file(String id) { return new KnowledgeSources.Bound(id, "CODE", id, "/fixture", null, "READY", "", 0); }
    private static Request request(String cursor) { return new Request("customerId", "AUTO", List.of("客户编号"), null, null, 1, cursor); }
    private static Map<String,Object> match(String id) { return Map.of("resourceKey", id, "sha256", "sha", "path", id, "score", 110); }
    private static KnowledgeSources.Selection selected(String... ids) { return new KnowledgeSources.Selection(Arrays.stream(ids).map(KnowledgeSearchTest::file).toList(), List.of()); }
    @Test void literalIdentifierPhraseAndExplicitConceptLeadsHaveDifferentEvidenceStrength() {
        var auto = new KnowledgeSearchQuery("customerId", "AUTO", List.of("客户编号"));
        assertThat(auto.find("private String customer_id;").matchType()).isEqualTo("FIELD");
        assertThat(auto.find("CUSTOMER-ID").matchType()).isEqualTo("FIELD");
        assertThat(auto.find("客户编号不能为空").matchType()).isEqualTo("EXPANDED");
        assertThat(auto.find("账户状态")).isNull();
        assertThat(new KnowledgeSearchQuery("customerId", "FIELD", List.of()).find("other_customer_id_suffix")).isNull();
        assertThat(new KnowledgeSearchQuery("payment must be approved", "PHRASE", List.of()).find("payment\nmust be approved").matchType()).isEqualTo("PHRASE");
        assertThat(new KnowledgeSearchQuery("付款必须审批", "EXACT", List.of()).find("规定：付款必须审批。")).isNotNull();
        assertThat(new KnowledgeSearchQuery("付款必须审批", "PHRASE", List.of()).find("规定：付款必须\n审批。").matchType()).isEqualTo("PHRASE");
        assertThatThrownBy(() -> Request.from(Map.of("query", "q", "terms", "not-an-array"))).isInstanceOf(AssistFailure.class);
    }
    @Test void mergesDeduplicatesPaginatesAndReplaysOnlyWithinTheExactOwnerAndQuery() {
        when(scanner.files(any(), any(), anyString(), isNull())).thenReturn(new Chunk(List.of(match("a"), match("b")), "next", true, List.of(), 2));
        when(scanner.files(any(), any(), anyString(), eq("next"))).thenReturn(new Chunk(List.of(match("b"), match("c")), null, false, List.of(), 2));
        var service = new KnowledgeSearchService(scanner, new ObjectMapper());
        try {
            var selection = selected("code", "documents"); var first = service.search("turn", selection, request(null));
            String cursor = (String) first.get("nextCursor"); var second = service.search("turn", selection, request(cursor));
            assertThat(second.get("matches")).isEqualTo(List.of(match("b")));
            assertThat(service.search("turn", selection, request(cursor))).isSameAs(second);
            var third = service.search("turn", selection, request((String) second.get("nextCursor")));
            assertThat(third.get("matches")).isEqualTo(List.of(match("c"))); assertThat(third.get("nextCursor")).isNull(); assertThat(third.get("incomplete")).isEqualTo(false);
            assertThatThrownBy(() -> service.search("other-turn", selection, request(cursor))).hasMessageContaining("变化");
            assertThatThrownBy(() -> service.search("turn", selected("code"), request(cursor))).hasMessageContaining("变化");
            assertThatThrownBy(() -> service.search("turn", selection, new Request("other", "AUTO", null, null, null, 1, cursor))).hasMessageContaining("变化");
            verify(scanner, times(4)).files(any(), any(), anyString(), any());
        } finally { service.close(); }
    }
    @Test void sourceFailuresAndSkippedGitRemainVisibleAlongsideUsefulResults() {
        when(scanner.files(eq(file("code")), any(), anyString(), any())).thenReturn(new Chunk(List.of(match("a")), null, false, List.of(), 1));
        when(scanner.files(eq(file("broken")), any(), anyString(), any())).thenThrow(new IllegalStateException("private path password"));
        var git = new KnowledgeSources.Bound("git", "GIT", "仓库", "/fixture", null, "READY", "", 0);
        var service = new KnowledgeSearchService(scanner, new ObjectMapper());
        try {
            var result = service.search("turn", new KnowledgeSources.Selection(List.of(file("code"), file("broken"), git), List.of()), request(null));
            assertThat(result.get("matches")).isEqualTo(List.of(match("a"))); assertThat(result.get("incomplete")).isEqualTo(true);
            assertThat(result.toString()).contains("FAILED", "SKIPPED", "COMPLETE").doesNotContain("private path password");
            assertThatThrownBy(() -> service.search("turn", selected("code"), new Request("q", null, null, List.of("outside"), null, 1, null))).hasMessageContaining("授权");
            assertThatThrownBy(() -> service.search("turn", selected("code"), new Request("q", null, null, null, null, 31, null))).hasMessageContaining("1–30");
        } finally { service.close(); }
    }
    @Test void deadlineDoesNotHideFastSourcesOrPretendSlowSourcesCompleted() throws Exception {
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        when(scanner.files(eq(file("slow")), any(), anyString(), any())).thenAnswer(call -> {
            entered.countDown(); while (release.getCount() != 0) { try { release.await(); } catch (InterruptedException ignored) { } }
            return new Chunk(List.of(match("late")), null, false, List.of(), 1);
        });
        when(scanner.files(eq(file("fast")), any(), anyString(), any())).thenReturn(new Chunk(List.of(match("fast")), null, false, List.of(), 1));
        var service = new KnowledgeSearchService(scanner, new ObjectMapper());
        try {
            var result = service.search("turn", selected("slow", "fast"), request(null));
            assertThat(entered.getCount()).isZero(); assertThat(result.get("matches")).isEqualTo(List.of(match("fast")));
            assertThat(result.toString()).contains("TIMED_OUT").doesNotContain("late");
        } finally { release.countDown(); service.close(); }
    }
}
