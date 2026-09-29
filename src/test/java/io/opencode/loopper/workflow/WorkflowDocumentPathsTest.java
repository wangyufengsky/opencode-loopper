package io.opencode.loopper.workflow;

import static org.assertj.core.api.Assertions.*;
import java.util.List;
import org.junit.jupiter.api.Test;

class WorkflowDocumentPathsTest {
    @Test void reportMembersStayPortableAndCannotEscapeTheirNamedDirectory() {
        WorkflowDocumentPaths.require(WorkflowHistoryReport.TYPE,"历史报告_项目_001/明细_001/问题清单_001.md");
        for(String path:List.of("../report.md","/root/report.md","root/../report.md","root//report.md","root/child/../../report.md","root/child\\report.md","root/a:b.md","root/a\n.md","root/a\nb\nc.md","root/"+"长".repeat(100)+".md","root/child./report.md","root/a.json"))
            assertThatThrownBy(()->WorkflowDocumentPaths.require(WorkflowHistoryReport.TYPE,path)).as(path).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void existingDocumentTypesKeepTheirFlatFileBoundary() {
        WorkflowDocumentPaths.require(WorkflowDocument.TYPE,"summary.md");
        assertThatThrownBy(()->WorkflowDocumentPaths.require(WorkflowDocument.TYPE,"目录/报告.md")).isInstanceOf(IllegalArgumentException.class);
        assertThat(WorkflowDocumentPaths.limit(WorkflowDocument.TYPE)).isEqualTo(1026);
    }
}
