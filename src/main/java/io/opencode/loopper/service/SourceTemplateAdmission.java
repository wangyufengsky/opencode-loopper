package io.opencode.loopper.service;

import io.opencode.loopper.domain.*;
import io.opencode.loopper.lifecycle.LifecycleTransitionService;
import io.opencode.loopper.persistence.*;
import io.opencode.loopper.template.*;
import java.time.Instant;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/** Short CAS transactions own source intake facts; filesystem and model I/O never enter this class. */
@Service
public class SourceTemplateAdmission {
    @org.springframework.beans.factory.annotation.Autowired(required = false) private RoleSessions roleSessions;
    private final SourceTemplateMapper mapper;
    private final LifecycleTransitionService lifecycle;
    private final ObjectMapper json;
    public SourceTemplateAdmission(SourceTemplateMapper mapper, LifecycleTransitionService lifecycle, ObjectMapper json) {
        this.mapper = mapper; this.lifecycle = lifecycle; this.json = json;
    }
    public SourceTemplateRunRow require(String id) {
        return mapper.find(id).orElseThrow(() -> new NotFoundException("源码模板任务不存在"));
    }
    @Transactional
    public SourceTemplateRunRow create(SourceTemplateRunRow proposed) {
        var previous = mapper.request(proposed.requestKey()).orElse(null);
        if (previous != null) return same(previous, proposed.requestSha256());
        lifecycle.create(subject(proposed), proposed.state(), Map.of("template", proposed.templateId()),
                () -> mapper.insert(proposed), SourceTemplateAdmission::conflict);
        RoleSessions.freeze(roleSessions, "SOURCE_TEMPLATE_RUN", proposed.id(), null, null);
        return require(proposed.id());
    }
    public SourceTemplateRunRow transition(SourceTemplateRunRow row, SourceTemplateState next,
            String resume, String code, String message) {
        lifecycle.transition(subject(row), row.state(), next.name(), code, Map.of(),
                () -> mapper.transition(row.id(), row.version(), next.name(), resume, code, message, Instant.now().toString()),
                SourceTemplateAdmission::conflict);
        return require(row.id());
    }
    @Transactional
    public SourceTemplateRunRow planSnapshot(SourceTemplateRunRow row, SourceManifest manifest) {
        var current = require(row.id());
        if (current.version() != row.version() || !current.state().equals("PREPARING")) throw conflict();
        var identity = new SourceSnapshot(manifest.sha256(), manifest.files().size(), (int) manifest.targetCount(), false);
        if (current.snapshotJson() != null) {
            var previous = json.readValue(current.snapshotJson(), SourceSnapshot.class);
            if (!previous.manifestSha256().equals(identity.manifestSha256())) throw new ConflictException(
                    "SOURCE_SNAPSHOT_CHANGED", "待保存的冻结源码已经变化，不能用新内容替换原输入");
            return current;
        }
        if (mapper.snapshot(row.id(), row.version(), json.writeValueAsString(identity), Instant.now().toString()) != 1) throw conflict();
        for (int index = 0; index < manifest.files().size(); index++) {
            var file = manifest.files().get(index);
            if (mapper.insertFile(new SourceTemplateMapper.File(row.id(), index, file.path(), file.target() ? 1 : 0,
                    file.sizeBytes(), file.sha256(), file.exclusion())) != 1) throw conflict();
            if (file.target() && SourceTreeCapture.unresolved(file.exclusion()))
                mapper.coverageResult(row.id(), file.path(), "INCOMPLETE",
                        json.writeValueAsString(Map.of("reason", file.exclusion())), Instant.now().toString());
        }
        return require(row.id());
    }
    @Transactional
    public SourceTemplateRunRow ready(SourceTemplateRunRow row) {
        var snapshot = json.readValue(row.snapshotJson(), SourceSnapshot.class);
        String ready = json.writeValueAsString(new SourceSnapshot(snapshot.manifestSha256(), snapshot.fileCount(), snapshot.targetCount(), true));
        if (mapper.snapshotReady(row.id(), row.version(), ready, Instant.now().toString()) != 1) throw conflict();
        return require(row.id());
    }
    @Transactional
    public SourceTemplateRunRow start(String id, SourceTemplateRequests.Command command) {
        validateCommand(command);
        var row = require(id);
        String digest = "start:" + command.expectedVersion();
        var previous = mapper.command(id, command.requestKey());
        if (previous.isPresent()) {
            if (!previous.get().equals(digest)) throw conflict();
            return row;
        }
        if (row.version() != command.expectedVersion() || !row.state().equals("PENDING_START")) throw conflict();
        row = transition(row, SourceTemplateState.PREPARING, null, null, null);
        if (mapper.commandRecord(id, command.requestKey(), digest, Instant.now().toString()) != 1) throw conflict();
        return row;
    }
    public static void validateCommand(SourceTemplateRequests.Command command) {
        if (command == null || command.requestKey() == null || !command.requestKey().matches("[A-Za-z0-9_-]{16,100}"))
            throw new BadRequestException("SOURCE_COMMAND_INVALID", "操作标识无效，请刷新后重试");
    }
    public static SourceTemplateRunRow same(SourceTemplateRunRow row, String digest) {
        if (!row.requestSha256().equals(digest))
            throw new ConflictException("SOURCE_REQUEST_CONFLICT", "同一发起标识的参数已变化，请使用原参数重试或重新发起");
        return row;
    }
    public static LifecycleTransitionService.Subject subject(SourceTemplateRunRow row) {
        return new LifecycleTransitionService.Subject(LifecycleMachineType.SOURCE_TEMPLATE_RUN,
                row.id(), LifecycleScopeType.PROJECT, row.projectId());
    }
    public static ConflictException conflict() {
        return new ConflictException("SOURCE_TEMPLATE_VERSION_CONFLICT", "源码模板状态已变化，请刷新后重试");
    }
}
