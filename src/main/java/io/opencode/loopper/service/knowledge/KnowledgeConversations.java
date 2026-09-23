package io.opencode.loopper.service.knowledge;

import io.opencode.loopper.api.CursorPage;
import io.opencode.loopper.config.LoopperProperties;
import io.opencode.loopper.persistence.*;
import io.opencode.loopper.persistence.KnowledgeRows.*;
import io.opencode.loopper.runtime.OpenCodeClient;
import io.opencode.loopper.runtime.OpenCodeModelSelection;
import io.opencode.loopper.service.*;
import java.time.Instant;
import java.util.*;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

@Service
public class KnowledgeConversations {
    private final KnowledgeMapper mapper;
    private final KnowledgeV2Mapper options;
    private final KnowledgePersistence persistence;
    private final KnowledgeSources sources;
    private final ProjectService projects;
    private final SettingsService settings;
    private final LoopperProperties properties;
    private final ObjectMapper json;
    public KnowledgeConversations(KnowledgeMapper mapper, KnowledgePersistence persistence, KnowledgeSources sources,
            ProjectService projects, SettingsService settings, LoopperProperties properties, ObjectMapper json, KnowledgeV2Mapper options) {
        this.options = options; this.mapper = mapper; this.persistence = persistence; this.sources = sources; this.projects = projects;
        this.settings = settings; this.properties = properties; this.json = json;
    }
    public record Create(String id, String projectId, String title, String model, List<String> sourceIds, String timezone) {
        public Create(String id, String projectId, String title, String model, List<String> sourceIds) { this(id, projectId, title, model, sourceIds, null); }
    }
    public record View(String id, String projectId, String title, String model, String state,
            List<KnowledgeSources.View> sources, String createdAt, String updatedAt, long version, KnowledgeMapper.Usage usage, KnowledgeV2Mapper.Options options) { }
    public record CitationView(String id, String kind, String name, String location, String sha256, String createdAt) { }
    public record Message(String id, int ordinal, String state, String userText, String answer, String detail,
            Long inputTokens, Long outputTokens, String createdAt, List<CitationView> citations, List<Call> calls, String thinking, List<KnowledgeQuestions.View> questions) { }
    public View create(Create input) {
        if (input == null || input.id() == null || !input.id().matches("[a-fA-F0-9-]{36}") || input.title() == null || input.title().isBlank() || input.title().length() > 100 || input.sourceIds() == null || input.sourceIds().isEmpty())
            throw new BadRequestException("KNOWLEDGE_INPUT_INVALID", "请填写有效的问题标题和请求标识");
        String timezone;
        try { timezone = java.time.ZoneId.of(input.timezone() == null ? java.time.ZoneId.systemDefault().getId() : input.timezone()).getId(); }
        catch (java.time.DateTimeException invalid) { throw KnowledgeSources.bad("时区无效，请刷新浏览器后重试"); }
        var existing = mapper.conversation(input.id());
        if (existing.isPresent()) {
            var row = existing.get();
            var ids = sources.frozenViews(row).stream().map(KnowledgeSources.View::id).toList();
            if (!row.projectId().equals(input.projectId()) || !row.title().equals(input.title()) || (input.model() != null && !input.model().isBlank() && !view(row).model().equals(input.model())) || !new HashSet<>(ids).equals(new HashSet<>(input.sourceIds())))
                throw KnowledgePersistence.conflict("创建请求标识已用于另一会话");
            return view(row);
        }
        var project = projects.get(input.projectId()); var selection = sources.freeze(project.id(), input.sourceIds());
        String configured = input.model() == null || input.model().isBlank() ? properties.getOpenCode().getModel() : input.model();
        if (configured == null || configured.isBlank()) throw new BadRequestException("KNOWLEDGE_MODEL_REQUIRED", "请先在设置中配置模型");
        // The saved global model is already configured by the operator. Starting a default chat
        // must not depend on a fresh CLI catalog process; explicit alternatives still require discovery.
        if (!configured.equals(properties.getOpenCode().getModel()) && !"fake".equals(properties.getOpenCode().getMode())
                && settings.models().stream().noneMatch(m -> m.id().equals(configured)))
            throw new BadRequestException("KNOWLEDGE_MODEL_UNAVAILABLE", "所选模型不可用，请刷新模型列表");
        var model = OpenCodeModelSelection.configured(configured); String now = Instant.now().toString();
        var row = new Conversation(input.id(), project.id(), project.rootPath(), input.title(), json.writeValueAsString(model),
                json.writeValueAsString(selection.sources()), json.writeValueAsString(selection.connections()), "IDLE", null, null, now, now, 0);
        persistence.create(row, timezone); return view(row);
    }
    public View get(String id) {
        View view = view(persistence.require(id));
        return new View(view.id(), view.projectId(), view.title(), view.model(), view.state(), view.sources(), view.createdAt(), view.updatedAt(), view.version(), mapper.latestUsage(id).orElse(null), view.options());
    }
    public Map<String,Object> receipt(String id, String key) {
        persistence.require(id);
        if (key == null || !key.matches("[a-zA-Z0-9_-]{16,100}")) throw new BadRequestException("KNOWLEDGE_REQUEST_INVALID", "请求标识无效");
        return mapper.replay(id, key).map(turn -> Map.<String,Object>of("accepted", true, "messageId", turn.id(), "state", turn.state()))
                .orElse(Map.of("accepted", false));
    }
    public CursorPage<View> list(String project, String cursor, Integer requested) {
        return list(project, cursor, requested, "active", "", "", "", "");
    }
    public CursorPage<View> list(String project, String cursor, Integer requested, String archive, String state, String query, String since, String until) {
        project = Objects.toString(project, ""); if (!project.isBlank()) projects.get(project);
        if (!Set.of("active", "archived", "all").contains(archive) || !Set.of("", "IDLE", "RUNNING", "STOPPING", "DISCONNECTED", "WAITING_INPUT").contains(state)
                || query.length() > 200) throw KnowledgeSources.bad("历史筛选条件无效");
        for (String date : List.of(since, until)) if (!date.isBlank()) try { Instant.parse(date); } catch (java.time.format.DateTimeParseException invalid) { throw KnowledgeSources.bad("筛选时间无效"); }
        var page = PageCursor.decode(cursor); int limit = PageCursor.limit(requested);
        var rows = options.history(project, archive, state, query, since, until, page == null ? "9999" : page.value(), page == null ? "~" : page.id(), limit + 1);
        var visible = rows.stream().limit(limit).toList();
        if (visible.isEmpty()) return new CursorPage<>(List.of(), null);
        var meta = options.optionsFor(visible.stream().map(Conversation::id).toList()).stream().collect(java.util.stream.Collectors.toMap(KnowledgeV2Mapper.Options::conversationId, o -> o));
        var last = visible.getLast();
        return new CursorPage<>(visible.stream().map(row -> view(row, meta.get(row.id()))).toList(), rows.size() > limit ? new PageCursor(meta.get(last.id()).lastActivityAt(), last.id()).encode() : null);
    }
    public View archive(String id, boolean archived, long version) {
        persistence.require(id); var old = options.options(id);
        if (old == null || old.version() != version) throw KnowledgePersistence.conflict("对话列表已变化，请刷新后操作");
        if ((old.archivedAt() != null) != archived && options.archive(id, version, archived ? Instant.now().toString() : null) != 1)
            throw KnowledgePersistence.conflict("归档状态已变化，请刷新后操作");
        return get(id);
    }
    public CursorPage<Message> messages(String id, String cursor, Integer requested) {
        persistence.require(id); var page = PageCursor.decode(cursor); int limit = PageCursor.limit(requested);
        var rows = mapper.turns(id, page == null ? "9999" : page.value(), page == null ? "~" : page.id(), limit + 1);
        var descending = rows.stream().limit(limit).toList(); var visible = new ArrayList<>(descending); Collections.reverse(visible);
        return new CursorPage<>(messages(visible), rows.size() > limit ? new PageCursor(descending.getLast().createdAt(), descending.getLast().id()).encode() : null);
    }
    public Message message(Turn turn) { return messages(List.of(turn)).getFirst(); }
    public CursorPage<Message> updates(String id, int afterOrdinal, String cursor) {
        persistence.require(id); if (afterOrdinal < 0) throw KnowledgeSources.bad("消息位置无效，请重新打开对话");
        var page = PageCursor.decode(cursor);
        var rows = mapper.updates(id, afterOrdinal, page == null ? "" : page.value(), page == null ? "" : page.id(), 51);
        var visible = rows.stream().limit(50).toList();
        return new CursorPage<>(messages(visible), rows.size() > 50 ? new PageCursor(visible.getLast().createdAt(), visible.getLast().id()).encode() : null);
    }
    private static final java.util.regex.Pattern REFERENCE = java.util.regex.Pattern.compile("\\[([^\\]\\n]{1,80})\\]\\(knowledge:([^)]{1,160})\\)");
    private List<Message> messages(List<Turn> rows) {
        if (rows.isEmpty()) return List.of();
        String conversation = rows.getFirst().conversationId(); var ids = rows.stream().map(Turn::id).toList();
        var citations = mapper.citationsForTurns(conversation, ids).stream().collect(java.util.stream.Collectors.groupingBy(Citation::turnId));
        var questions = options.questionsFor(conversation, ids).stream().collect(java.util.stream.Collectors.groupingBy(KnowledgeV2Mapper.Question::turnId));
        var calls = mapper.callsForTurns(conversation, ids).stream().collect(java.util.stream.Collectors.groupingBy(Call::turnId));
        var requested = new LinkedHashSet<String>();
        for (var row : rows) { var matcher = REFERENCE.matcher(row.answer()); while (matcher.find() && requested.size() < 500) requested.add(matcher.group(2)); }
        var requestedIds = requested.stream().map(s -> s.split("#",2)[0]).distinct().toList();
        Map<String,KnowledgeMapper.CitationRange> known = requestedIds.isEmpty() ? Map.of() : mapper.citationRanges(conversation, requestedIds).stream()
                .collect(java.util.stream.Collectors.toMap(KnowledgeMapper.CitationRange::id, r -> r));
        return rows.stream().map(turn -> new Message(turn.id(), turn.ordinal(), turn.state(), turn.userText(), safeAnswer(turn.answer(), known), turn.detail(),
                turn.inputTokens(), turn.outputTokens(), turn.createdAt(), citations.getOrDefault(turn.id(), List.of()).stream().map(KnowledgeConversations::citationView).toList(),
                calls.getOrDefault(turn.id(), List.of()), turn.thinking(), questions.getOrDefault(turn.id(), List.of()).stream().map(q -> KnowledgeQuestions.view(q, json)).toList())).toList();
    }
    public Map<String,Object> citation(String id, String citationId) {
        persistence.require(id); var row = mapper.citation(id, citationId).orElseThrow(() -> new NotFoundException("引用不存在或不属于当前会话"));
        return Map.of("citation", citationView(row), "body", json.readTree(row.bodyJson()));
    }
    public View view(Conversation row) { return view(row, options.options(row.id())); }
    private View view(Conversation row, KnowledgeV2Mapper.Options metadata) {
        var model = json.readValue(row.modelJson(), OpenCodeClient.OpenCodeModel.class);
        return new View(row.id(), row.projectId(), row.title(), model.providerId() + "/" + model.modelId(), row.state(), sources.frozenViews(row), row.createdAt(), row.updatedAt(), row.version(), null, metadata);
    }
    private static CitationView citationView(Citation c) { return new CitationView(c.id(), c.kind(), c.name(), c.location(), c.sha256(), c.createdAt()); }
    private boolean validReference(String target, Map<String,KnowledgeMapper.CitationRange> known) {
        String[] parts = target.split("#", -1); var range = known.get(parts[0]);
        if (range == null) return false;
        if (parts.length == 1) return true;
        if (parts.length != 2) return false;
        var match = java.util.regex.Pattern.compile("([LR])(\\d{1,8})-([LR])(\\d{1,8})").matcher(parts[1]);
        if (!match.matches() || !match.group(1).equals(range.unit()) || !match.group(3).equals(range.unit())) return false;
        int first = Integer.parseInt(match.group(2)), last = Integer.parseInt(match.group(4));
        return first >= range.first() && last >= first && last <= range.last();
    }
    private String safeAnswer(String answer, Map<String,KnowledgeMapper.CitationRange> known) {
        return REFERENCE.matcher(answer).replaceAll(match -> java.util.regex.Matcher.quoteReplacement(validReference(match.group(2), known)
                ? match.group() : match.group(1) + "（引用未核实）"));
    }
}
