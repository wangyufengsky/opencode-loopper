package io.opencode.loopper.service.workflow;

import io.opencode.loopper.runtime.ImmutableContentStore;
import io.opencode.loopper.service.*;
import io.opencode.loopper.template.SourceManifest;
import io.opencode.loopper.workflow.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.springframework.stereotype.Component;

/** Reads only authenticated frozen source bytes; no checkout, installation or test command is executed. */
@Component
public final class WorkflowTestProfileBuilder {
    private final WorkflowSourceRecords sources;
    private final WorkflowSourceContent content;
    private final SourceTestProfiles profiles;
    private final WorkflowEncoding encoding;
    public WorkflowTestProfileBuilder(WorkflowSourceRecords sources,WorkflowSourceContent content,SourceTestProfiles profiles,WorkflowEncoding encoding){this.sources=sources;this.content=content;this.profiles=profiles;this.encoding=encoding;}
    public WorkflowTestProfile.Frozen build(WorkflowTestProfileStore.Context context) {
        String output=WorkflowTestProfile.require(context.node());var source=context.inputs().values().getFirst();
        if(!source.source().equals("NODE")||source.attemptId()==null)throw invalid("请选择已完成的冻结源码资料。");
        var reference=encoding.decode(encoding.encode(source.content()),WorkflowSourceSnapshot.Reference.class);
        var manifest=sources.manifest(context.project(),context.inputs().requirementId(),source.attemptId(),reference,WorkflowSourceSnapshot.Purpose.UNIT_TEST);
        var files=new HashMap<String,SourceManifest.File>();for(var file:manifest.files())if(file.sha256()!=null)files.putIfAbsent(file.sha256(),file);
        var cache=new HashMap<String,String>();
        var profile=profiles.resolve(manifest.sha256(),manifest.sourcePath(),output,manifest.files(),sha->cache.computeIfAbsent(sha,key->{
            var file=files.get(key);if(file==null)throw invalid("测试配置引用的冻结文件不存在。");
            try{return StandardCharsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(content.read(reference.snapshotId(),file))).toString();}
            catch(java.nio.charset.CharacterCodingException|ImmutableContentStore.StorageFailure missing){throw invalid("冻结源码正文缺失、损坏或无法读取，请处理原源码资料。");}
        }));
        var result=new WorkflowTestProfile.Frozen(1,WorkflowTestProfile.TYPE,source.attemptId(),reference,profile);
        if(encoding.encode(result).getBytes(StandardCharsets.UTF_8).length>120*1024)throw invalid("测试配置超过单节点交付容量，请按源码模块拆分后重新识别。");
        return result;
    }
    public WorkflowDelivery delivery(WorkflowTestProfile.Frozen result,String code,String message) {
        var outputs=new LinkedHashMap<String,WorkflowDelivery.Value>();var report=new LinkedHashMap<String,Object>();
        report.put("version",1);report.put("type",WorkflowTestProfile.TYPE);report.put("complete",result!=null);
        if(result!=null){outputs.put("profile",value(result));report.put("moduleCount",result.profile().modules().size());report.put("sourceCount",result.profile().modules().stream().mapToInt(m->m.sourcePaths().size()).sum());}
        if(code!=null)report.put("code",code);if(message!=null)report.put("message",message.length()>2048?message.substring(0,2048):message);
        String summary=result==null?"测试配置尚未确定，请查看识别报告并调整输入。":"已识别并冻结测试框架、目录和命令；测试尚未执行。";
        outputs.put("report",value(report));outputs.put("summary",new WorkflowDelivery.Value(WorkflowGraph.DataKind.TEXT,encoding.decode(encoding.encode(summary),tools.jackson.databind.JsonNode.class)));
        return new WorkflowDelivery(summary,null,outputs);
    }
    private WorkflowDelivery.Value value(Object value){return new WorkflowDelivery.Value(WorkflowGraph.DataKind.JSON,encoding.decode(encoding.encode(value),tools.jackson.databind.JsonNode.class));}
    private static BadRequestException invalid(String reason){return new BadRequestException("WORKFLOW_TEST_PROFILE_INVALID",reason);}
}
