package io.opencode.loopper.service.workflow;

import io.opencode.loopper.template.TemplateReportCompiler;
import io.opencode.loopper.workflow.WorkflowSnapshotWork;
import java.util.List;

/** Reports retain the frozen source names and hashes even if the original plan later changes. */
final class WorkflowSnapshotReuseNotes {
    private WorkflowSnapshotReuseNotes(){ }
    static String render(List<WorkflowSnapshotWork.Reuse> sources){
        if(sources.isEmpty())return "";
        var note=new StringBuilder("\n\n复用历史有效分析 ").append(sources.size()).append(" 批；复用项未新建模型会话，历史无问题结论未经独立复核。\n");
        for(var source:sources)note.append("\n- 来源需求：").append(TemplateReportCompiler.text(source.sourceTitle()))
            .append("；节点：").append(TemplateReportCompiler.text(source.sourceNodeTitle())).append("；源结果 SHA-256：").append(source.sourceDeliverySha256());
        return note.toString();
    }
}
