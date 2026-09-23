package io.opencode.loopper.service.ppt.agent;

import io.opencode.loopper.persistence.PptAgentMapper;
import io.opencode.loopper.persistence.PptAgentRows.Run;
import io.opencode.loopper.service.ppt.PptSupport;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

/** Rebuilds the durable user/assistant discussion from completed server-owned runs. */
@Service
public class PptDiscussionTranscript {
    public static final String PROTOCOL = "FREEFORM_DIALOGUE_V1";
    private static final int PAGE_SIZE = 100;
    private static final int MAX_CHARACTERS = 120_000;
    private final PptAgentMapper mapper;
    private final ObjectMapper json;

    public PptDiscussionTranscript(PptAgentMapper mapper, ObjectMapper json) {
        this.mapper = mapper;
        this.json = json;
    }

    public record Snapshot(String text, String latestRunId, long latestVersion, int turns) { }

    public Snapshot freeze(String document) {
        var turns = collect(document, "9999", "~");
        if (turns.isEmpty()) throw PptSupport.bad("PPT_DISCUSSION_REQUIRED", "请先和 PPT 助手完成一轮需求讨论，再确认执行");
        if (!turns.getLast().state().equals("COMPLETED"))
            throw PptSupport.conflict("请等待 PPT 助手完成最新回复后再确认需求");
        if (mapper.pendingDiscussion(document).isPresent())
            throw PptSupport.conflict("请先回答讨论中的待补充问题，再确认需求");
        var latest = turns.getLast();
        return new Snapshot(render(turns), latest.id(), latest.version(), turns.size());
    }

    public static String before(PptAgentMapper mapper, Run current, ObjectMapper json) {
        return new PptDiscussionTranscript(mapper, json).prior(current);
    }

    private String prior(Run current) {
        if (!discussion(current)) return "";
        return render(collect(current.documentId(), current.createdAt(), current.id()));
    }

    private List<Run> collect(String document, String beforeTime, String beforeId) {
        var result = new ArrayList<Run>();
        String cursorTime = beforeTime, cursorId = beforeId;
        while (true) {
            var page = mapper.history(document, cursorTime, cursorId, PAGE_SIZE);
            if (page.isEmpty()) break;
            for (var run : page) {
                if (discussion(run)) {
                    result.add(run);
                    checkSize(result);
                }
            }
            var last = page.getLast();
            cursorTime = last.createdAt();
            cursorId = last.id();
            if (page.size() < PAGE_SIZE) break;
        }
        java.util.Collections.reverse(result);
        return result;
    }

    private void checkSize(List<Run> runs) {
        long size = 0;
        for (var run : runs) size += run.userText().length() + (run.answer() == null ? 0 : run.answer().length());
        if (size > MAX_CHARACTERS)
            throw PptSupport.bad("PPT_DISCUSSION_TOO_LONG", "讨论记录已超出可安全提交的长度，请先让助手整理当前需求后再确认");
    }

    private String render(List<Run> runs) {
        return runs.stream().map(run -> "用户：\n" + run.userText() + "\n助手：\n"
                + (run.answer() == null ? "（本轮没有保存文字回复）" : run.answer()))
                .collect(java.util.stream.Collectors.joining("\n\n"));
    }

    private boolean discussion(Run run) {
        return PROTOCOL.equals(json.readTree(run.contextJson()).path("pptDiscussionProtocol").asText());
    }
}
