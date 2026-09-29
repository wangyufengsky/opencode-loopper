package io.opencode.loopper.service;

import io.opencode.loopper.template.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Deterministic document bytes shared by the legacy template and independently bound workflow nodes. */
public final class SourceDesignDocuments {
    private SourceDesignDocuments(){ }
    public record Draft(int ordinal,SourceDesign.Candidate candidate,String review) { }
    public record Output(Map<String,String> files,Map<String,List<String>> coverage) { }
    public static Output render(String sourceSha,List<SourceManifest.File> source,List<Draft> drafts) {
        var output=new LinkedHashMap<String,String>();var coverage=new TreeMap<String,List<String>>();
        var reviewed=new HashMap<String,Boolean>();var ordinals=new HashSet<Integer>();
        var index=new StringBuilder("# 详细设计总览\n\n源码快照：`").append(sourceSha).append("`\n\n");
        for(var draft:drafts) {
            if(draft.ordinal()<0||!ordinals.add(draft.ordinal())||!Set.of("PASS","REVISE","NOT_REVIEWED").contains(draft.review()))throw invalid();
            var document=draft.candidate();
            index.append("## ").append(SourceDesignMarkdown.text(document.title())).append("\n\n")
                    .append(SourceDesignMarkdown.text(document.summary())).append("\n\n");
            if(!draft.review().equals("PASS"))index.append(draft.review().equals("REVISE")?"复核要求返修。\n\n":"未绑定通过的独立复核。\n\n");
            for(var section:document.sections()) {
                SourceDesignMarkdown.validate(section.markdown());
                if(!section.key().matches("[A-Za-z0-9_-]{1,80}"))throw invalid();
                String name="module-"+(draft.ordinal()+1)+"-"+section.key()+".md";
                var body=new StringBuilder("# ").append(SourceDesignMarkdown.text(section.title())).append("\n\n")
                        .append(section.markdown()).append("\n\n## 源码依据\n\n");
                for(var ref:section.references()) {
                    body.append("- `").append(SourceDesignMarkdown.text(ref.path())).append("`，行 ")
                            .append(ref.startLine()).append("–").append(ref.endLine()).append("，SHA-256：`")
                            .append(ref.sha256()).append("`\n\n");
                    ref.quote().lines().forEach(line->body.append("    ").append(line).append("\n"));body.append("\n");
                }
                if(!document.limitations().isEmpty()) {
                    body.append("## 未知事项与局限\n\n");document.limitations().forEach(item->body.append("- ").append(SourceDesignMarkdown.text(item)).append("\n"));
                }
                if(!draft.review().equals("PASS"))body.append(draft.review().equals("REVISE")?"\n复核要求返修，本文件不代表复核通过。\n":"\n本文件未绑定通过的独立复核。\n");
                body.append("\n[返回总览](overview.md)\n");
                if(output.putIfAbsent(name,body.toString())!=null)throw invalid();
                index.append("- [").append(SourceDesignMarkdown.text(section.title())).append("](").append(name).append(")\n");
                section.paths().forEach(path->{coverage.computeIfAbsent(path,ignored->new ArrayList<>()).add(name);reviewed.merge(path,draft.review().equals("PASS"),(a,b)->a&&b);});
            }
            index.append("\n");
        }
        var expected=source.stream().filter(SourceManifest.File::processable).map(SourceManifest.File::path).toList();
        if(expected.isEmpty()||!coverage.keySet().equals(new HashSet<>(expected))||source.stream().anyMatch(f->f.target()&&SourceTreeCapture.unresolved(f.exclusion())))throw invalid();
        var list=new StringBuilder("# 源码覆盖清单\n\n| 源码 | 结果 | 文档或原因 |\n| --- | --- | --- |\n");
        for(var file:source)if(file.target()) {
            list.append("| ").append(SourceDesignMarkdown.text(file.path())).append(" | ");
            if(file.exclusion()!=null)list.append("排除 | ").append(SourceDesignMarkdown.text(file.exclusion()));
            else list.append(Boolean.TRUE.equals(reviewed.get(file.path()))?"已复核 | ":"已生成，未通过独立复核 | ")
                    .append(String.join("、",coverage.get(file.path()).stream().map(name->"["+name+"]("+name+")").toList()));
            list.append(" |\n");
        }
        list.append("\n[返回总览](overview.md)\n");index.append("[源码覆盖清单](coverage.md)\n");
        output.put("overview.md",index.toString());output.put("coverage.md",list.toString());
        if(output.size()>1026||output.values().stream().mapToLong(text->text.getBytes(StandardCharsets.UTF_8).length).sum()>64L*1024*1024)
            throw new BadRequestException("SOURCE_ARTIFACT_LIMIT","文档包超过文件数或 64 MiB 上限，已保留设计和复核结果。");
        return new Output(Collections.unmodifiableMap(output),Collections.unmodifiableMap(coverage));
    }
    private static BadRequestException invalid(){return new BadRequestException("SOURCE_COVERAGE_INCOMPLETE","文档必须完整覆盖本次冻结源码，且章节与文件名称不能重复。请核对绑定的设计稿。");}
}
