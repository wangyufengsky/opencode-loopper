package io.opencode.loopper.service.knowledge;

import io.opencode.loopper.persistence.KnowledgeMapper;
import io.opencode.loopper.service.NotFoundException;
import java.nio.file.*;
import java.util.*;
import org.springframework.stereotype.Service;

/** Opens model-mentioned files through the conversation's frozen source permissions. */
@Service
public class KnowledgeFileLinks {
    private final KnowledgeMapper mapper;
    private final KnowledgeSources sources;
    private final KnowledgeReader reader;
    public KnowledgeFileLinks(KnowledgeMapper mapper, KnowledgeSources sources, KnowledgeReader reader) {
        this.mapper = mapper; this.sources = sources; this.reader = reader;
    }
    public Map<String, Object> read(String conversationId, String path, int start, int end, int section) {
        var conversation = mapper.conversation(conversationId).orElseThrow(() -> new NotFoundException("对话不存在，请重新打开历史对话"));
        if (path == null || path.isBlank() || path.length() > 4096 || start < 1 || end < 0 || end > 0 && end < start)
            throw KnowledgeSources.bad("文件位置无效，请从资料来源中查找文件");
        Path requested;
        try { requested = Path.of(path); } catch (InvalidPathException invalid) { throw KnowledgeSources.bad("文件路径无效"); }
        if (!requested.normalize().equals(requested) || java.io.File.separatorChar != '\\' && path.contains("\\")) throw KnowledgeSources.bad("文件路径不在已授权范围内");
        var frozen = sources.frozen(conversation);
        var candidates = frozen.stream().filter(s -> Set.of("CODE", "DOCUMENTS", "DIRECTORY").contains(s.kind()))
                .filter(s -> requested.isAbsolute() ? requested.startsWith(Path.of(s.path())) : Path.of(s.path()).equals(Path.of(conversation.rootPath())))
                .sorted(Comparator.<KnowledgeSources.Bound>comparingInt(s -> Path.of(s.path()).getNameCount()).reversed()
                        .thenComparing(s -> s.kind().equals("CODE") ? 0 : 1)).toList();
        if (candidates.isEmpty()) throw KnowledgeSources.bad("该文件不在本对话已选资料中，请新建对话并选择相应来源");
        var source = candidates.getFirst();
        String relative = requested.isAbsolute() ? Path.of(source.path()).relativize(requested).toString() : requested.toString();
        var body = new LinkedHashMap<>(reader.read(source, relative, section, start, null));
        if (Set.of("md", "markdown").contains(KnowledgeFiles.extension(relative))) {
            int scanned = 0;
            while (((Number) body.getOrDefault("endLine", 0)).intValue() < start && ((Number) body.getOrDefault("nextSection", -1)).intValue() >= 0) {
                if (++scanned >= 100) throw KnowledgeSources.bad("文档分段较多，请从资料来源目录定位所需章节");
                body = new LinkedHashMap<>(reader.read(source, relative, ((Number) body.get("nextSection")).intValue(), 1, null));
            }
        }
        body.put("changeNotice", "当前文件预览，包含本地最新内容；不代表回答时保存的证据。历史依据请打开编号引用。");
        return body;
    }
}
