package io.opencode.loopper.template;

import io.opencode.loopper.template.SnapshotReview.*;
import java.util.*;
import java.util.function.Function;

/** Pure rendering of an observation, never a complete-report or execution success decision. */
public final class SnapshotReviewPartialCompiler {
    private SnapshotReviewPartialCompiler(){ }
    public record Batch(String id,String title,Analysis analysis){ }
    public record Judgment(String id,String title,String analysisId,Review review){ }
    public record Result(String content,int analyzedUnits,int pendingUnits,int excludedUnits){ }
    public static Result compile(Snapshot snapshot,String capturedAt,String state,String note,List<Batch> batches,
                                 List<Judgment> reviews,Function<List<Reference>,String> authors) {
        var completed=new HashSet<String>();batches.forEach(b->b.analysis().coverage().forEach(c->completed.add(c.unitId())));
        StringBuilder out=new StringBuilder("# 代码审查阶段报告（非完整报告）\n\n生成时间：").append(capturedAt)
            .append("\n\n任务状态：").append(text(state)).append("\n\n目标 SHA：").append(snapshot.targetSha())
            .append("\n\n基线 SHA：").append(Objects.toString(snapshot.baselineSha(),"无"))
            .append("\n\n只包含已校验且完成收尾的批次；正在执行、失败、已取消或停止尚未证实的结果不计入已分析。无问题项未经独立复核；本轮未执行目标项目测试。\n\n")
            .append(note).append("\n\n");
        int analyzed=0,pending=0,excluded=0;
        out.append("## 覆盖清单\n\n| 文件 / 单元 | 状态 |\n| --- | --- |\n");
        for(var unit:snapshot.units()) {
            String status;
            if(unit.excerpt().isBlank()&&unit.limitation()!=null){excluded++;status="已排除，未审查："+unit.limitation();}
            else if(completed.contains(unit.id())){analyzed++;status="已分析，复核状态见问题详情";}
            else{pending++;status="尚未完成分析";}
            out.append("| ").append(text(unit.path())).append(" / ").append(text(unit.id())).append(" | ").append(text(status)).append(" |\n");
        }
        out.append("\n已分析 ").append(analyzed).append("；未完成 ").append(pending).append("；排除 ").append(excluded).append("。\n\n## 已保存问题与证据\n\n");
        for(var batch:batches)render(out,batch,reviews,authors);
        return new Result(out.toString(),analyzed,pending,excluded);
    }
    private static void render(StringBuilder out,Batch batch,List<Judgment> reviews,Function<List<Reference>,String> authors) {
        var own=reviews.stream().filter(r->r.analysisId().equals(batch.id())).toList();
        out.append("### ").append(text(batch.title())).append("\n\n");
        for(var finding:batch.analysis().findings()) {
            out.append("#### ").append(text(finding.title())).append("\n\n级别：").append(finding.severity())
                .append("\n\n触发条件：").append(text(finding.trigger())).append("\n\n错误行为：").append(text(finding.behavior()))
                .append("\n\n建议：").append(text(finding.recommendation())).append("\n\n");
            references(out,finding.evidence());var authorRefs=new ArrayList<>(finding.evidence());boolean judged=false;
            for(var row:own)for(var decision:row.review().decisions())if(decision.findingKey().equals(finding.key())) {
                judged=true;out.append(text(row.title())).append("：").append(verdict(decision.verdict())).append("\n\n")
                    .append(text(decision.reason())).append("\n\n");references(out,decision.evidence());authorRefs.addAll(decision.evidence());
                if(decision.verdict()==Verdict.DUPLICATE)out.append("合并目标：").append(duplicate(decision.duplicateOf(),row,batch,reviews)).append("\n\n");
            }
            if(!judged)out.append("尚未独立复核（仅候选）\n\n");
            out.append(authors.apply(authorRefs));
        }
        if(batch.analysis().findings().isEmpty())out.append("本批未报告候选问题，未经独立复核，不代表证明无缺陷。\n\n");
        batch.analysis().limitations().forEach(l->out.append("- 局限：").append(text(l)).append("\n"));
        batch.analysis().coverage().forEach(c->c.limitations().forEach(l->out.append("- 单元 ").append(text(c.unitId())).append("：").append(text(l)).append("\n")));
        for(var row:own){out.append("\n复核结论：").append(text(row.review().conclusion())).append("\n\n");references(out,row.review().evidence());row.review().limitations().forEach(l->out.append("- 复核局限：").append(text(l)).append("\n"));}
    }
    private static String duplicate(String target,Judgment own,Batch batch,List<Judgment> reviews) {
        if(target==null)return "未提供合并目标";
        int slash=target.indexOf('/');String id=slash<0?own.id():target.substring(0,slash),key=slash<0?target:target.substring(slash+1);
        var match=reviews.stream().filter(r->r.id().equals(id)).findFirst();
        if(match.isEmpty())return "引用的复核不在当前有效结果中，不能据此认定本报告已有独立支持";
        String title=match.get().id().equals(own.id())?batch.analysis().findings().stream().filter(f->f.key().equals(key)).map(Finding::title).findFirst().orElse(key):key;
        return text(match.get().title()+" / "+title);
    }
    private static String verdict(Verdict value){return switch(value){case SUPPORTED->"独立复核支持";case DISMISSED->"复核不成立";case UNDETERMINED->"复核待确认";case DUPLICATE->"复核判定重复";};}
    private static void references(StringBuilder out,List<Reference> refs){for(var r:refs)out.append("- ").append(text(r.path())).append("：").append(r.startLine()).append("–").append(r.endLine()).append("；版本 ").append(r.version()).append("；blob ").append(r.blob()).append("\n\n    ").append(text(r.quote()).replace("\n","\n    ")).append("\n\n");}
    private static String text(String value){return TemplateReportCompiler.text(value);}
}
