package io.opencode.loopper.service.ppt;

import io.opencode.loopper.service.ppt.agent.PptAgentService;
import java.util.Map;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

/** User decisions are separate from candidate edits and remote dispatch. */
@Service
public class PptCommands {
    private final PptDocuments documents;
    private final PptAgentService agent;
    private final ObjectMapper json;
    public PptCommands(PptDocuments documents,PptAgentService agent,ObjectMapper json){this.documents=documents;this.agent=agent;this.json=json;}
    public PptDocuments.View action(String id,String action,PptDocuments.Action input) {
        if(!java.util.Set.of("confirm-direction","start-production","finish-production","finish-planning","reopen","archive","restore").contains(action))throw PptSupport.bad("PPT_ACTION_INVALID","不支持的作品操作");
        documents.action(id,action,input,()->agent.assertNoActiveWriter(id));
        if("start-production".equals(action)&&!Boolean.FALSE.equals(input.useAgent()))agent.send(id,new PptAgentService.Send("produce_"+PptSupport.hash(input.idempotencyKey().getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                "按照已确认方案逐页制作。先读取当前页面，只制作缺少或有问题的页面，保留成功页面与锁定对象。测量并检查后修正阻断问题，保存完整作品。",input.expectedRevision(),json.valueToTree(Map.of("kind","DOCUMENT"))));
        return documents.get(id);
    }
}
