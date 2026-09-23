package io.opencode.loopper.service.ppt.agent;

import io.opencode.loopper.api.CursorPage;
import io.opencode.loopper.config.LoopperProperties;
import io.opencode.loopper.persistence.PptAgentMapper;
import io.opencode.loopper.persistence.PptAgentRows.*;
import io.opencode.loopper.runtime.OpenCodeModelSelection;
import io.opencode.loopper.service.*;
import io.opencode.loopper.service.assist.AssistRedaction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Bounded UI projections and user commands, separate from runtime I/O. */
@Service
public class PptAgentService {
    private final PptAgentMapper mapper;
    private final PptAgentPersistence persistence;
    private final PptAgentCoordinator coordinator;
    private final PptAgentWorkspace workspace;
    private final ObjectMapper json;
    private final LoopperProperties properties;
    private final PptAgentWorkflowGate workflow;
    private final PptAgentActivity activity;
    public PptAgentService(PptAgentMapper mapper, PptAgentPersistence persistence, PptAgentCoordinator coordinator,
            PptAgentWorkspace workspace, ObjectMapper json, LoopperProperties properties,PptAgentWorkflowGate workflow, PptAgentActivity activity) {
        this.mapper = mapper; this.persistence = persistence; this.coordinator = coordinator;
        this.workspace = workspace; this.json = json; this.properties = properties;this.workflow=workflow;
        this.activity = activity;
    }
    public record Send(String idempotencyKey, String text, long expectedRevision, JsonNode scope) { }
    public record Reply(String idempotencyKey, String answer, long expectedRevision, long version, Boolean confirmed) {
        public Reply(String idempotencyKey, String answer, long expectedRevision, long version) {
            this(idempotencyKey, answer, expectedRevision, version, null);
        }
    }
    public record QuestionView(String id, String prompt, List<String> options, String state, String answer, long version,
                               String kind, Boolean confirmed) { }
    public record Message(String id, String documentId, String idempotencyKey, String text, String answer,
                          String state, String detail, JsonNode scope, long expectedRevision, long version,
                          String createdAt, String updatedAt, List<QuestionView> questions, String thinking, List<PptAgentActivity.Call> calls, io.opencode.loopper.persistence.PptRecoveryMapper.Failure failure) { }
    public record AgentStatus(String state, String runId, String detail, long version, List<QuestionView> questions, String requirementsState) { }
    public Message send(String document, Send input) {
        return send(document,input,null);
    }
    /** Only the server workflow calls this; HTTP send DTOs cannot supply authorization. */
    public Message sendAutomatic(String document,Send input,PptAgentWorkflowGate.Authorization authorization) {
        Objects.requireNonNull(authorization);return send(document,input,authorization);
    }
    private Message send(String document,Send input,PptAgentWorkflowGate.Authorization authorization) {
        if (input == null) throw bad("请输入 PPT 请求");
        key(input.idempotencyKey()); text(input.text(), authorization==null?24000:PptDiscussionTranscript.MAX_CHARACTERS);
        JsonNode scope = validateScope(input.scope());
        if (!Set.of("managed", "fake").contains(properties.getOpenCode().getMode())) throw bad("PPT 助手需要受管 OpenCode");
        Object identity=authorization==null?List.of(input.text(),input.expectedRevision(),scope):List.of(input.text(),input.expectedRevision(),scope,authorization);
        String sha = hash(PptAgentJson.canonical(json.valueToTree(identity), json));
        var replay = mapper.replay(document, input.idempotencyKey());
        if (replay.isPresent()) {
            if (!replay.get().inputSha().equals(sha)) throw PptAgentPersistence.conflict("同一请求标识不能用于不同请求");
            coordinator.enqueue(document); return message(replay.get());
        }
        var work = workspace.workspace(document); requireRevision(work, input.expectedRevision());
        var context=((tools.jackson.databind.node.ObjectNode)work.context()).deepCopy();
        context.put("pptToolProtocol", PptAgentPayloads.PROTOCOL);
        if(authorization!=null) {
            context.set("generationAuthorization",json.valueToTree(authorization));
            context.set("generationAnswers",json.valueToTree(workflow.answers(document,authorization)));
            context.set("recoveryCheckpoint",json.valueToTree(workflow.recoveryContext(authorization)));
            PptRequirements.freeze(context, authorization);
        } else if (work.phase().equals("BRIEFING")) {
            context.put("pptDiscussionProtocol", PptDiscussionTranscript.PROTOCOL);
        }
        String configured = work.model() == null || work.model().isBlank() ? properties.getOpenCode().getModel() : work.model();
        var model = OpenCodeModelSelection.configured(configured);
        if (model == null) throw bad("请先配置有效的 provider/model 模型");
        String id = UUID.randomUUID().toString(), now = Instant.now().toString();
        var desired = new Run(id, document, input.idempotencyKey(), sha, input.text(), json.writeValueAsString(scope),
                input.expectedRevision(), work.phase(), json.writeValueAsString(model), work.root().toString(),
                json.writeValueAsString(context), "PREPARED", "", "", null, null, null,
                "msg_ppt_" + id.replace("-", "") + "_0", null, null, 0, 0, null, null, null, null, now, now, 0);
        Run saved = persistence.begin(desired, () -> {
            if(authorization==null)workflow.assertManualAdmission(document);else workflow.validateAutomatic(document,input.idempotencyKey(),authorization);
            var latest = workspace.workspace(document); requireRevision(latest, input.expectedRevision());
            if (!latest.phase().equals(work.phase())) throw PptAgentPersistence.conflict("作品阶段已变化");
        });
        coordinator.enqueue(document); return message(saved);
    }
    public Message reply(String document, String questionId, Reply input) {
        if (input == null) throw bad("请输入回答"); key(input.idempotencyKey()); text(input.answer(), 12000);
        var question = mapper.question(document, questionId).orElseThrow(() -> new NotFoundException("问题不属于此作品"));
        Boolean confirmed = PptRequirements.replyDecision(question, input.confirmed());
        // Preserve historical hashes for ordinary replies; confirmation includes its explicit UI decision.
        var identity = new ArrayList<Object>(List.of(input.answer(), input.expectedRevision(), input.version()));
        if (confirmed != null) identity.add(confirmed);
        String sha = hash(json.writeValueAsString(identity));
        if (question.state().equals("ANSWERED") && input.idempotencyKey().equals(question.replyKey())) {
            if (!sha.equals(question.replySha())) throw PptAgentPersistence.conflict("同一回答请求标识不能用于不同回答");
            return message(persistence.require(question.runId()));
        }
        if (question.version() != input.version()) throw PptAgentPersistence.conflict("问题版本已变化");
        var work = workspace.workspace(document); requireRevision(work, input.expectedRevision());
        var run = persistence.require(question.runId());
        var context=((tools.jackson.databind.node.ObjectNode)work.context()).deepCopy();
        context.remove("pptToolProtocol");
        for(String field:List.of("pptToolProtocol","recoveryCheckpoint","pptDiscussionProtocol","requirementsConfirmedByUser")) {
            var value=json.readTree(run.contextJson()).get(field);if(value!=null)context.set(field,value);
        }
        var authorization=json.readTree(run.contextJson()).get("generationAuthorization");
        if(authorization!=null)context.set("generationAuthorization",authorization);
        var previousAnswers=json.readTree(run.contextJson()).get("generationAnswers");
        if(previousAnswers!=null)context.set("generationAnswers",previousAnswers);
        var requirementsProtocol=json.readTree(run.contextJson()).get("requirementsProtocol");
        if(requirementsProtocol!=null)context.set("requirementsProtocol",requirementsProtocol);
        var saved = persistence.reply(question, input.answer(), input.idempotencyKey(), sha, confirmed, work.revision(),
                json.writeValueAsString(context), () -> {
                    workflow.validateRun(run);
                    var current = workspace.workspace(document); requireRevision(current, input.expectedRevision());
                    if (!PptAgentAuthority.phaseMatches(run.phase(), current.phase())) throw PptAgentPersistence.conflict("作品阶段已变化，请停止后新建请求");
                });
        coordinator.enqueue(document); return message(saved);
    }
    @org.springframework.transaction.annotation.Transactional
    public AgentStatus stop(String document) {
        workspace.workspace(document);workflow.cancel(document); persistence.stop(document, "USER"); coordinator.enqueue(document); return status(document);
    }
    public void assertNoActiveWriter(String document) {
        if (mapper.activeCount(document) != 0) throw PptAgentPersistence.conflict("PPT 助手尚未停止，请等待完成或停止当前请求后操作");
    }
    public AgentStatus status(String document) {
        var row = mapper.status(document);
        if (row.isEmpty()) return new AgentStatus("IDLE", null, "", 0, List.of(), "NOT_REQUIRED");
        var run = row.get(); var questions = mapper.questions(run.id());
        return new AgentStatus(run.state(), run.id(), run.detail(), run.version(), views(questions.stream().filter(q -> q.state().equals("PENDING")).toList()),
                PptRequirements.state(persistence.require(run.id()), questions, json));
    }
    public CursorPage<Message> messages(String document, String cursor, Integer requested) {
        workspace.workspace(document); var page = PageCursor.decode(cursor); int limit = PageCursor.limit(requested);
        var rows = mapper.history(document, page == null ? "9999" : page.value(), page == null ? "~" : page.id(), limit + 1);
        var selected = new ArrayList<>(rows.stream().limit(limit).toList());
        if (selected.isEmpty()) return new CursorPage<>(List.of(), null);
        var last = selected.getLast(); var groups = mapper.questionsFor(selected.stream().map(Run::id).toList()).stream()
                .collect(java.util.stream.Collectors.groupingBy(Question::runId));
        Collections.reverse(selected);
        var activities = activity.views(selected.stream().map(Run::id).toList());
        var failures = mapper.failures(selected.stream().map(Run::id).toList()).stream().collect(java.util.stream.Collectors.toMap(io.opencode.loopper.persistence.PptRecoveryMapper.Failure::runId, f->f));
        return new CursorPage<>(selected.stream().map(row -> view(row, groups.getOrDefault(row.id(), List.of()), activities.getOrDefault(row.id(), PptAgentActivity.View.EMPTY),failures.get(row.id()))).toList(),
                rows.size() > limit ? new PageCursor(last.createdAt(), last.id()).encode() : null);
    }
    public Message message(Run row) { return view(row, mapper.questions(row.id()), activity.views(List.of(row.id())).getOrDefault(row.id(), PptAgentActivity.View.EMPTY),mapper.failures(List.of(row.id())).stream().findFirst().orElse(null)); }
    private Message view(Run row, List<Question> questions, PptAgentActivity.View activity,io.opencode.loopper.persistence.PptRecoveryMapper.Failure failure) {
        return new Message(row.id(), row.documentId(), row.idempotencyKey(), row.userText(), row.answer(), row.state(),
                row.detail(), json.readTree(row.scopeJson()), row.sourceRevision(), row.version(), row.createdAt(), row.updatedAt(), views(questions), activity.thinking(), activity.calls(),failure);
    }
    private List<QuestionView> views(List<Question> rows) {
        return rows.stream().map(q -> new QuestionView(q.id(), q.prompt(), json.readTree(q.optionsJson()).valueStream().map(JsonNode::asText).toList(),
                q.state(), q.answer(), q.version(), q.kind(), q.confirmed())).toList();
    }
    public JsonNode validateScope(JsonNode input) {
        var scope = input == null || input.isNull() ? json.valueToTree(Map.of("kind", "DOCUMENT")) : input;
        if (!scope.isObject() || scope.size() > 4 || !Set.of("DOCUMENT", "SECTION", "SLIDE", "ELEMENT").contains(scope.path("kind").asText())) throw bad("修改范围无效");
        for (var name : scope.propertyNames()) if (!Set.of("kind", "section", "slideId", "elementId").contains(name)) throw bad("修改范围含未知字段");
        String kind = scope.path("kind").asText();
        for (String name : List.of("section", "slideId", "elementId")) {
            var value = scope.get(name); if (value != null && (!value.isTextual() || value.asText().isBlank() || value.asText().length() > 200)) throw bad("修改对象标识无效");
        }
        if (kind.equals("SECTION") && !scope.hasNonNull("section") || Set.of("SLIDE", "ELEMENT").contains(kind) && !scope.hasNonNull("slideId")
                || kind.equals("ELEMENT") && !scope.hasNonNull("elementId")) throw bad("修改范围缺少对象标识");
        return scope;
    }
    private void requireRevision(PptAgentWorkspace.Workspace work, long revision) {
        if (revision < 0 || work.revision() != revision) throw PptAgentPersistence.conflict("作品版本已变化，请刷新后发送");
    }
    static void key(String key) { if (key == null || !key.matches("[A-Za-z0-9_-]{8,128}")) throw bad("请求标识无效"); }
    static void text(String text, int limit) {
        if (text == null || text.isBlank() || text.length() > limit || !text.equals(AssistRedaction.text(text))) throw bad("文字为空、过长或包含内部作用域凭证");
    }
    static String hash(String text) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    static BadRequestException bad(String message) { return new BadRequestException("PPT_AGENT_INPUT_INVALID", message); }
}
