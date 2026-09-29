package io.opencode.loopper.service.workflow;

import io.opencode.loopper.config.LoopperProperties;
import io.opencode.loopper.runtime.ImmutableContentStore;
import io.opencode.loopper.service.*;
import io.opencode.loopper.workflow.WorkflowHistorySnapshot;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

/** Writes independently addressable commit documents outside transactions; manifests contain no patch bodies. */
@Service
@Transactional(propagation=Propagation.NOT_SUPPORTED)
public class WorkflowHistoryContent {
    private final ImmutableContentStore content;
    private final WorkflowEncoding encoding;
    public WorkflowHistoryContent(LoopperProperties properties,WorkflowEncoding encoding) {
        content=new ImmutableContentStore(properties.getDataDir().resolve("workflow-history-content"));this.encoding=encoding;
    }
    public WorkflowHistorySnapshot.Manifest capture(GitHistoryJobProtocol.Input input,GitHistoryJobProtocol.Frozen frozen,String evidenceHash) {
        var value=frozen.evidence();var files=new ArrayList<WorkflowHistorySnapshot.File>();
        var overview=new LinkedHashMap<String,Object>();overview.put("version",value.version());overview.put("branchId",value.branchId());overview.put("head",value.head());
        overview.put("startDate",value.startDate());overview.put("endDate",value.endDate());overview.put("timezone",value.timezone());overview.put("mailmapHash",value.mailmapHash());overview.put("commitCount",value.commits().size());
        files.add(save(input.nodeId(),"overview.json",overview));int ordinal=0;long changes=0,excluded=0,total=0;
        for(var commit:value.commits()) {
            var file=save(input.nodeId(),String.format(Locale.ROOT,"commits/%06d-%s.json",++ordinal,commit.sha()),commit);files.add(file);total+=file.sizeBytes();
            if(total>WorkflowHistorySnapshot.MAX_FILE_BYTES)throw new io.opencode.loopper.domain.TaskFailure("TEMPLATE_EVIDENCE_LIMIT","历史证据超过保存容量，请缩小范围后新增采集节点。");
            changes+=commit.changes().size();excluded+=commit.changes().stream().filter(c->c.exclusionReason()!=null).count();
        }
        return new WorkflowHistorySnapshot.Manifest(1,WorkflowHistorySnapshot.TYPE,input.nodeId(),value.branchId(),value.head(),frozen.binding().prefix(),
                value.startDate(),value.endDate(),value.timezone(),evidenceHash,value.commits().size(),changes,excluded,files);
    }
    public byte[] read(String owner,WorkflowHistorySnapshot.File file) {return content.read(owner,file.sha256(),file.sizeBytes(),WorkflowHistorySnapshot.MAX_FILE_BYTES);}
    private WorkflowHistorySnapshot.File save(String owner,String path,Object value) {
        byte[] bytes=encoding.encode(value).getBytes(StandardCharsets.UTF_8);String sha=ImmutableContentStore.hash(bytes);
        content.write(owner,sha,bytes,WorkflowHistorySnapshot.MAX_FILE_BYTES);return new WorkflowHistorySnapshot.File(path,bytes.length,sha);
    }
}
