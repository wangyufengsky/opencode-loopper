package io.opencode.loopper.service.workflow;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import io.opencode.loopper.persistence.*;
import io.opencode.loopper.service.*;
import io.opencode.loopper.workflow.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class WorkflowInputSnapshotsTest {
    private final ObjectMapper json = new ObjectMapper();
    private final WorkflowEncoding encoding = new WorkflowEncoding(json);
    private final WorkflowExecutionMapper mapper = mock(WorkflowExecutionMapper.class);
    private final WorkflowInputSnapshots snapshots = new WorkflowInputSnapshots(mapper, encoding);
    private final String content = "固定内容 😀";
    private final WorkflowDelivery delivery = new WorkflowDelivery("说明", null,
            Map.of("result", new WorkflowDelivery.Value(WorkflowGraph.DataKind.TEXT, json.valueToTree(content))));
    private final String body = encoding.encode(delivery), hash = WorkflowEncoding.hash(body);
    private WorkflowDelivery.Input input(String name, String attempt) {
        return new WorkflowDelivery.Input(name, WorkflowGraph.DataKind.TEXT, "NODE", "producer-"+attempt,
                "result", attempt, hash, json.valueToTree(content));
    }
    private WorkflowDelivery.Inputs inputs(WorkflowDelivery.Input... values) {
        return new WorkflowDelivery.Inputs(1, "requirement", 3, "consumer", "目标", List.of(values));
    }
    private WorkflowExecutionMapper.InputDelivery row(String attempt) {
        return new WorkflowExecutionMapper.InputDelivery(attempt, "producer-"+attempt, "requirement", body, hash);
    }
    @Test void frozenReferenceRoundTripsWithoutCopyingBodyAndLoadsOneOutputByExactAttempt() {
        when(mapper.inputDeliveries(Set.of("a", "b"))).thenReturn(List.of(row("a"),row("b")));
        var original = inputs(input("first", "a"), input("second", "b"));
        var frozen = snapshots.freeze(original);
        assertThat(frozen.version()).isEqualTo(2);assertThat(encoding.encode(frozen)).doesNotContain(content);
        assertThat(frozen.values()).allSatisfy(input -> {assertThat(input.content()).isNull();assertThat(input.reference().version()).isEqualTo(1);});
        var restored = encoding.decode(encoding.encode(frozen), WorkflowDelivery.Inputs.class);
        clearInvocations(mapper);
        when(mapper.inputDeliveries(Set.of("a"))).thenReturn(List.of(row("a")));
        assertThat(snapshots.input(restored,"first")).isEqualTo(original.values().getFirst());
        verify(mapper).inputDeliveries(Set.of("a"));verifyNoMoreInteractions(mapper);
        clearInvocations(mapper);assertThat(snapshots.materialize(restored)).isEqualTo(original);
        verify(mapper).inputDeliveries(Set.of("a","b"));verifyNoMoreInteractions(mapper);
    }
    @Test void legacyInlineInputsRemainReadableWithoutSourceLookupOrAddingReferenceFields() {
        var original=inputs(input("old","a"));String saved=encoding.encode(original);
        assertThat(saved).doesNotContain("reference");
        var restored=encoding.decode(saved,WorkflowDelivery.Inputs.class);
        assertThat(snapshots.materialize(restored)).isEqualTo(original);
        assertThat(snapshots.input(restored,"old").content().asString()).isEqualTo(content);verifyNoInteractions(mapper);
    }
    @Test void missingUnfinishedForeignProducerOrDamagedDeliveryCannotResolve() {
        when(mapper.inputDeliveries(Set.of("a"))).thenReturn(List.of(row("a")));
        var frozen=snapshots.freeze(inputs(input("first","a")));
        for(var rows:List.of(List.<WorkflowExecutionMapper.InputDelivery>of(),
                List.of(new WorkflowExecutionMapper.InputDelivery("a","producer-a","other",body,hash)),
                List.of(new WorkflowExecutionMapper.InputDelivery("a","other","requirement",body,hash)),
                List.of(new WorkflowExecutionMapper.InputDelivery("a","producer-a","requirement",body+" ",hash)),
                List.of(new WorkflowExecutionMapper.InputDelivery("a","producer-a","requirement",body,"0".repeat(64))))) {
            when(mapper.inputDeliveries(Set.of("a"))).thenReturn(rows);
            assertThatThrownBy(()->snapshots.materialize(frozen)).isInstanceOf(ConflictException.class);
        }
    }
    @Test void changedOutputHashSizeNameKindOrUnsupportedReferenceFailsClosed() {
        when(mapper.inputDeliveries(Set.of("a"))).thenReturn(List.of(row("a")));
        var frozen=snapshots.freeze(inputs(input("first","a")));
        String saved=encoding.encode(frozen), digest=frozen.values().getFirst().reference().contentSha256();
        for(String changed:List.of(saved.replace(digest,"0".repeat(64)),saved.replace("\"sizeBytes\":"+frozen.values().getFirst().reference().sizeBytes(),"\"sizeBytes\":1"),
                saved.replace("\"outputName\":\"result\"","\"outputName\":\"missing\""),saved.replace("\"kind\":\"TEXT\"","\"kind\":\"JSON\""),
                saved.replace("\"version\":1","\"version\":99"),saved.replace("\"version\":2","\"version\":1"),
                saved.replace("\"content\":null","\"content\":\"conflicting inline content\""))) {
            assertThat(changed).isNotEqualTo(saved);
            assertThatThrownBy(()->snapshots.materialize(encoding.decode(changed,WorkflowDelivery.Inputs.class))).isInstanceOf(ConflictException.class);
        }
    }
    @Test void admissionCannotFreezeAClaimedBodyDifferentFromAcceptedOutput() {
        when(mapper.inputDeliveries(Set.of("a"))).thenReturn(List.of(row("a")));
        var claim=new WorkflowDelivery.Input("first",WorkflowGraph.DataKind.TEXT,"NODE","producer-a","result","a",hash,json.valueToTree("偷换内容"));
        assertThatThrownBy(()->snapshots.freeze(inputs(claim))).isInstanceOf(ConflictException.class);
    }
    @Test void snapshotChecksOwnerAndAttemptRevisionInAdditionToHash() {
        var original=inputs(input("old","a"));String saved=encoding.encode(original);
        var attempt=new WorkflowExecutionRows.Attempt("consumer-attempt","consumer-run",1,3,"RUNNING",saved,WorkflowEncoding.hash(saved),null,"human.v1",null,1,"time","time");
        when(mapper.node("consumer-run")).thenReturn(Optional.of(new WorkflowExecutionRows.Node("consumer-run","other","consumer","{}","hash","ACTIVE",1,attempt.id(),1,"time","time")));
        assertThatThrownBy(()->snapshots.snapshot(attempt)).isInstanceOf(ConflictException.class);
    }
    @Test void unknownInputDoesNotFetchUnrelatedDeliveries() {
        when(mapper.inputDeliveries(Set.of("a"))).thenReturn(List.of(row("a")));var frozen=snapshots.freeze(inputs(input("first","a")));clearInvocations(mapper);
        assertThatThrownBy(()->snapshots.input(frozen,"unbound")).isInstanceOf(BadRequestException.class);verifyNoInteractions(mapper);
    }
    @Test void unicodePaginationUsesStableOffsetsWithoutSplittingCodePoints() {
        var input=input("first","a");String text="中😀末";
        var first=WorkflowInputPages.page(input,text,0,2);
        assertThat(first.text()).isEqualTo("中😀");assertThat(first.nextOffset()).isEqualTo(3);assertThat(first.totalLength()).isEqualTo(4);
        var last=WorkflowInputPages.page(input,text,3,1);assertThat(last.text()).isEqualTo("末");assertThat(last.nextOffset()).isNull();
        for(int offset:List.of(-1,2,5))assertThatThrownBy(()->WorkflowInputPages.page(input,text,offset,1)).isInstanceOf(BadRequestException.class);
        assertThatThrownBy(()->WorkflowInputPages.page(input,text,0,12001)).isInstanceOf(BadRequestException.class);
    }
}
