package io.opencode.loopper.service.ppt.agent;

import io.opencode.loopper.persistence.PptAgentMapper;
import io.opencode.loopper.persistence.PptAgentRows.*;
import java.time.Instant;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Agent-owned questions and durable tool receipts; business batches keep their own atomic receipts. */
@Service
public class PptAgentToolWrites {
    private final PptAgentMapper mapper;
    private final PptAgentAuthority authority;
    private final ObjectMapper json;
    public PptAgentToolWrites(PptAgentMapper mapper, PptAgentAuthority authority, ObjectMapper json) {
        this.mapper = mapper; this.authority = authority; this.json = json;
    }
    @Transactional
    public Object question(Run run, JsonNode args, String key, String sha) {
        var old = mapper.receipt(run.id(), key); if (old.isPresent()) return replay(old.get(), "ppt_request_input", sha);
        authority.validate(run, "ppt_request_input");
        String prompt = args.path("prompt").asText(); PptAgentService.text(prompt, 3000);
        var options = args.path("options");
        if (!options.isMissingNode() && (!options.isArray() || options.size() > 6)) throw PptAgentService.bad("选项最多 6 项");
        List<String> values = new ArrayList<>();
        for (var option : options) { if (!option.isTextual()) throw PptAgentService.bad("选项必须是文字"); PptAgentService.text(option.asText(), 300); values.add(option.asText()); }
        if (mapper.pending(run.id()).isPresent()) throw PptAgentPersistence.conflict("已有待回答问题，请停止当前轮次等待用户回答");
        if (mapper.questions(run.id()).size() >= 100) throw PptAgentService.bad("本请求问题数已到上限，请停止并新建请求");
        var row = new Question(UUID.randomUUID().toString(), run.id(), run.documentId(), prompt, json.writeValueAsString(values),
                "PENDING", null, null, null, Instant.now().toString(), 0);
        if (mapper.insertQuestion(row) != 1) throw PptAgentPersistence.conflict("问题保存失败");
        Object result = Map.of("questionId", row.id(), "state", "PENDING", "action", "STOP_AND_WAIT_FOR_INPUT",
                "detail", "问题已保存，请结束本轮；系统确认停止后用户可以回答");
        save(run, key, "ppt_request_input", sha, result); return result;
    }
    @Transactional
    public Object save(Run run, String key, String tool, String sha, Object result) {
        var old = mapper.receipt(run.id(), key); if (old.isPresent()) return replay(old.get(), tool, sha);
        authority.validate(run, tool);
        if (mapper.insertReceipt(new Receipt(run.id(), key, tool, sha, json.writeValueAsString(result), Instant.now().toString())) != 1)
            throw PptAgentPersistence.conflict("工具回执保存冲突");
        return result;
    }
    public Object replay(Receipt row, String tool, String sha) {
        if (!row.tool().equals(tool) || !row.inputSha().equals(sha)) throw PptAgentPersistence.conflict("同一幂等键已用于不同工具或参数");
        return json.readTree(row.responseJson());
    }
}
