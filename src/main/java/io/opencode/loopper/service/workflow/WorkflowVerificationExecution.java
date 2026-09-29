package io.opencode.loopper.service.workflow;

import io.opencode.loopper.runtime.ImmutableContentStore;
import io.opencode.loopper.workflow.*;
import java.nio.*;
import java.nio.charset.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

/** Recomputable pure checks on exact accepted bytes; neither the checkout nor a subprocess is used. */
@Service
@Transactional(propagation=Propagation.NOT_SUPPORTED)
public class WorkflowVerificationExecution {
    private final WorkflowVerificationStore store;
    private final WorkflowCodeSnapshots codes;
    private final WorkflowEncoding encoding;
    public WorkflowVerificationExecution(WorkflowVerificationStore store,WorkflowCodeSnapshots codes,WorkflowEncoding encoding){this.store=store;this.codes=codes;this.encoding=encoding;}
    public void advance(String id) {
        var context=store.context(id);if(context==null)return;
        var rows=new ArrayList<Map<String,Object>>();boolean error=false;
        try {
            var input=context.input();var reference=encoding.decode(encoding.encode(input.content()),WorkflowCodeSnapshot.Reference.class);
            var manifest=codes.manifest(context.project(),context.requirement(),input.attemptId(),reference);
            for(var check:context.spec().checks()) {
                var file=manifest.files().stream().filter(value->value.path().equals(check.path())).findFirst();
                boolean pass;String actual=null;
                if(check.type().equals("FILE_NOT_EXISTS"))pass=file.isEmpty() && manifest.files().stream().noneMatch(value->value.path().startsWith(check.path()+"/"));
                else if(file.isEmpty())pass=false;
                else {
                    byte[] bytes=codes.read(context.project(),context.requirement(),input.attemptId(),reference,check.path());actual=ImmutableContentStore.hash(bytes);
                    if(check.type().equals("FILE_HASH"))pass=actual.equalsIgnoreCase(check.expected());
                    else {String text=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
                        pass=check.matchMode().equals("EXACT")?text.equals(check.expected()):text.contains(check.expected());}
                }
                var result=new LinkedHashMap<String,Object>();result.put("title",check.title());result.put("type",check.type());result.put("path",check.path());result.put("state",pass?"PASS":"FAIL");
                if(actual!=null)result.put("actualSha256",actual);rows.add(result);
            }
        }catch(RuntimeException|CharacterCodingException failure){error=true;rows.add(Map.of("state","ERROR","detail","固定交付物无法完整读取或校验，请检查原交付物后重试。","code",failure instanceof RuntimeException runtime?WorkflowFailures.code(runtime):"WORKFLOW_FILE_NOT_TEXT"));}
        boolean passed=!error && rows.stream().allMatch(row->"PASS".equals(row.get("state")));
        String summary=error?"检查未能完成，已保留固定输入和诊断。":passed?"交付物检查全部通过。":"交付物检查存在未通过项。";
        var report=Map.of("version",1,"producerAttempt",context.input().attemptId(),"inputSha256",context.input().sha256(),"passed",passed,"checks",rows);
        var delivery=new WorkflowDelivery(summary,passed?"PASS":"FAIL",Map.of("summary",new WorkflowDelivery.Value(WorkflowGraph.DataKind.TEXT,encoding.decode(encoding.encode(summary),tools.jackson.databind.JsonNode.class)),
                "report",new WorkflowDelivery.Value(WorkflowGraph.DataKind.JSON,encoding.decode(encoding.encode(report),tools.jackson.databind.JsonNode.class))));
        store.finish(context,delivery,error);
    }
}
