package io.opencode.loopper.service.workflow;

import io.opencode.loopper.api.CursorPage;
import io.opencode.loopper.persistence.WorkflowModelMapper.Launch;
import io.opencode.loopper.runtime.*;
import io.opencode.loopper.service.*;
import io.opencode.loopper.template.SnapshotReview;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import static io.opencode.loopper.service.workflow.WorkflowModelTools.*;
import static io.opencode.loopper.service.workflow.WorkflowSnapshotWorkStore.invalid;

/** Bounded V3 related-context reads use the source's private objects and write only this role's receipts. */
@Service
@Transactional(propagation=Propagation.NOT_SUPPORTED)
public class WorkflowSnapshotReads {
    private final WorkflowModelStore models;
    private final WorkflowNodeRuns nodes;
    private final WorkflowSnapshotInputs sources;
    private final WorkflowSnapshotEvidence evidence;
    private final WorkflowSnapshotWorkStore inputs;
    private final GitReviewJobs repositories;
    private final GitEvidenceProcess git;
    private final DocumentCodeContentCache cache;
    public WorkflowSnapshotReads(WorkflowModelStore models,WorkflowNodeRuns nodes,WorkflowSnapshotInputs sources,WorkflowSnapshotEvidence evidence,WorkflowSnapshotWorkStore inputs,GitReviewJobs repositories,GitEvidenceProcess git,DocumentCodeContentCache cache){this.models=models;this.nodes=nodes;this.sources=sources;this.evidence=evidence;this.inputs=inputs;this.repositories=repositories;this.git=git;this.cache=cache;}
    public Object call(Launch row,String tool,Map<String,Object> args){
        if(!"source".equals(args.get("name")))throw invalid("版本审查文件工具仅接受固定 source 资料。");
        var context=sources.read(models.definition(row),nodes.inputs(models.attempt(row)));var input=inputs.require(row.attemptId());
        String version=text(args,"version",64);
        if(!version.equals(input.targetSha())&&!version.equals(input.baselineSha()))throw invalid("代码版本不属于本批固定目标或基线。");
        var snapshot=evidence.overview(context.manifest());
        if(tool.equals(WorkflowModelProfile.FILES))return files(row,args,snapshot,version);
        String path=text(args,"path",2048),blob=text(args,"blobSha",64);
        var file=snapshot.files().stream().filter(f->f.version().equals(version)&&f.path().equals(path)).findFirst().orElseThrow(()->invalid("文件不属于固定目录。"));
        if(!file.blob().equals(blob)||file.limitation()!=null||GitSnapshotInventory.protectedPath(path)||!Set.of("100644","100755").contains(file.mode()))throw invalid("文件被排除或引用版本不匹配。");
        return args.containsKey("query")?search(row,args,file,context.manifest().nodeRunId()):read(row,args,file,context.manifest().nodeRunId());
    }
    private Object files(Launch row,Map<String,Object> args,SnapshotReview.Snapshot snapshot,String version){
        if(!Set.of("name","version","cursor","limit").containsAll(args.keySet()))throw invalid("请使用 name、version、cursor 和 limit 列出文件。");
        String after=args.containsKey("cursor")?text(args,"cursor",2048):"";int limit=integer(args,"limit",50,1,100);
        reserve(row,"files",version,after,limit);
        var found=snapshot.files().stream().filter(f->f.version().equals(version)&&f.path().compareTo(after)>0).sorted(Comparator.comparing(SnapshotReview.File::path)).limit(limit+1L).toList();
        models.activeWork(row);inputs.active(row);var page=found.stream().limit(limit).toList();return new CursorPage<>(page,found.size()>limit?page.getLast().path():null);
    }
    private Object read(Launch row,Map<String,Object> args,SnapshotReview.File file,String owner){
        if(!Set.of("name","version","path","blobSha","startLine","lineCount").containsAll(args.keySet()))throw invalid("版本审查须按 startLine、lineCount 读取代码。");
        int start=integer(args,"startLine",1,1,Integer.MAX_VALUE),limit=integer(args,"lineCount",200,1,200);
        reserve(row,"read",file.version(),file.path(),file.blob(),start,limit);String[] lines=content(owner,file).split("\n",-1);
        if(start>lines.length)throw invalid("起始行超出固定文件。");int end=(int)Math.min(lines.length,(long)start+limit-1);
        String body=String.join("\n",Arrays.copyOfRange(lines,start-1,end));if(body.length()>32000)throw invalid("代码片段超过 32000 字符，请缩小行范围。");
        models.activeWork(row);var reference=new SnapshotReview.Reference(file.version(),file.path(),file.blob(),start,end,body);inputs.receipt(row,reference);
        return Map.of("reference",reference,"totalLines",lines.length,"hasMore",end<lines.length);
    }
    private Object search(Launch row,Map<String,Object> args,SnapshotReview.File file,String owner){
        if(!Set.of("name","version","path","blobSha","query","afterLine").containsAll(args.keySet()))throw invalid("单文件检索参数不完整。");
        String query=text(args,"query",200);int after=integer(args,"afterLine",0,0,Integer.MAX_VALUE);reserve(row,"search",file.version(),file.path(),file.blob(),query,after);
        String[] lines=content(owner,file).split("\n",-1);var matches=new ArrayList<Map<String,Object>>();int next=-1;
        for(int i=after;i<lines.length;i++){if(!lines[i].contains(query))continue;if(matches.size()==20){next=i;break;}
            matches.add(Map.of("line",i+1,"text",lines[i].substring(0,Math.min(1000,lines[i].length())),"truncated",lines[i].length()>1000));}
        models.activeWork(row);inputs.active(row);return Map.of("matches",matches,"nextAfterLine",next,"limitation","仅检索指定固定文件；搜索不授予引用证据，无命中不证明代码不存在。");
    }
    private void reserve(Launch row,Object... arguments){models.activeWork(row);inputs.reserve(row,WorkflowEncoding.hash(models.encoding().encode(arguments)));}
    private String content(String owner,SnapshotReview.File file){
        return cache.read("workflow-snapshot/"+owner,file.version(),file.blob(),()->{
            Path directory=repositories.repository(owner);
            try{
                if(Files.isSymbolicLink(directory)||!directory.toRealPath().equals(directory.toAbsolutePath().normalize()))throw invalid("私有审查仓库位置发生变化。");
                var result=git.bytes(directory,Duration.ofSeconds(10),List.of("cat-file","blob",GitSnapshotInventory.objectId(file.blob())));result.requireSuccess(List.of("cat-file"));
                byte[] bytes=result.output();var digest=MessageDigest.getInstance(file.blob().length()==64?"SHA-256":"SHA-1");
                digest.update(("blob "+bytes.length+"\0").getBytes(StandardCharsets.US_ASCII));
                if(bytes.length!=file.bytes()||!HexFormat.of().formatHex(digest.digest(bytes)).equals(file.blob()))throw invalid("固定代码对象摘要不匹配。");
                String text=new String(bytes,StandardCharsets.UTF_8);if(text.indexOf('\0')>=0||text.indexOf('\uFFFD')>=0)throw invalid("二进制或非 UTF-8 不能作为代码引用。");return text;
            }catch(java.io.IOException|java.security.NoSuchAlgorithmException failure){throw invalid("固定代码对象不可读取；请保留资料并在局限中说明缺失上下文。");}
        });
    }
}
