package io.opencode.loopper.service;

import io.opencode.loopper.persistence.LoopperMapper;
import io.opencode.loopper.persistence.ProjectRow;
import io.opencode.loopper.persistence.StoryBindingRow;
import io.opencode.loopper.runtime.OpenCodeClient;
import java.nio.file.Path;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class StoryBindingService {
    private final LoopperMapper mapper;
    private final ProjectService projects;
    private final OpenCodeClient openCode;

    public StoryBindingService(LoopperMapper mapper, ProjectService projects, OpenCodeClient openCode) {
        this.mapper = mapper;
        this.projects = projects;
        this.openCode = openCode;
    }

    @Transactional
    public void attachDesigner(String designerSessionId, StoryBindingConfiguration configuration) {
        StoryBindingConfiguration value = configuration == null ? StoryBindingConfiguration.disabled() : configuration.normalized();
        if (!value.enabled()) return;
        String now = Instant.now().toString();
        StoryBindingRow binding = new StoryBindingRow(UUID.randomUUID().toString(), value.systemCode(),
                value.storyCode(), 0, now);
        mapper.insertStoryBinding(binding);
        mapper.bindDesignerStory(designerSessionId, binding.id());
    }

    public StoryBindingConfiguration configurationForDesigner(String sessionId) {
        return mapper.findDesignerStoryBinding(sessionId)
                .map(row -> new StoryBindingConfiguration(true, row.systemCode(), row.storyCode()))
                .orElseGet(StoryBindingConfiguration::disabled);
    }

    public Capability capability(String projectId) {
        ProjectRow project = projects.get(projectId);
        OpenCodeClient.CommandCapabilityProbe probe = openCode.commandCapabilities(Path.of(project.rootPath()));
        boolean available = probe.state() == OpenCodeClient.CapabilityState.AVAILABLE && probe.contains("aicoding");
        String reason = available ? "已检测到 OpenCode aicoding 命令"
                : probe.state() == OpenCodeClient.CapabilityState.AVAILABLE
                ? "当前 OpenCode 未注册 aicoding 命令"
                : probe.detail() == null || probe.detail().isBlank()
                ? "无法检测当前 OpenCode 命令"
                : probe.detail();
        return new Capability(available, probe.state().name(), reason, Instant.now().toString());
    }

    public record Capability(boolean available, String state, String reason, String checkedAt) { }
}
