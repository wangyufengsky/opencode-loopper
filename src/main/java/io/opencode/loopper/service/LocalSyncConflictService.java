package io.opencode.loopper.service;

import io.opencode.loopper.config.LoopperProperties;
import io.opencode.loopper.domain.LocalSyncConflictState;
import io.opencode.loopper.domain.LoopSpec;
import io.opencode.loopper.domain.TaskState;
import io.opencode.loopper.domain.VerificationState;
import io.opencode.loopper.persistence.LocalSyncConflictFileRow;
import io.opencode.loopper.persistence.LocalSyncConflictSessionRow;
import io.opencode.loopper.persistence.LoopperMapper;
import io.opencode.loopper.persistence.ProjectRow;
import io.opencode.loopper.persistence.TaskRow;
import io.opencode.loopper.runtime.GitWorktreeManager;
import io.opencode.loopper.runtime.OpenCodeClient;
import io.opencode.loopper.runtime.ProcessResult;
import io.opencode.loopper.runtime.SafeProcessRunner;
import io.opencode.loopper.verification.VerifierEngine;
import io.opencode.loopper.verification.VerifierOutcome;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

/** Persisted, user-confirmed three-way merge workflow for repositories without a remote. */
@Service
public class LocalSyncConflictService {
    static final int MAX_TASK_PATHS = 200;
    static final long MAX_TEXT_BYTES = 1024L * 1024L;
    static final long MAX_AI_BYTES = 200L * 1024L;
    static final long MAX_SESSION_BYTES = 100L * 1024L * 1024L;
    private static final Duration GIT_TIMEOUT = Duration.ofSeconds(30);
    private static final Duration MERGE_TIMEOUT = Duration.ofSeconds(30);
    private static final Duration AI_TIMEOUT = Duration.ofSeconds(75);
    private static final String MISSING = "MISSING";

    private final LoopperMapper mapper;
    private final TaskService tasks;
    private final ProjectService projects;
    private final GitWorktreeManager worktrees;
    private final SafeProcessRunner runner;
    private final OpenCodeClient openCode;
    private final LoopperProperties properties;
    private final LoopDraftService drafts;
    private final VerifierEngine verifiers;
    private final TaskEventService events;
    private final ObjectMapper json;
    private final ConcurrentHashMap<String, ReentrantLock> sourceLocks = new ConcurrentHashMap<>();

    public LocalSyncConflictService(LoopperMapper mapper, TaskService tasks, ProjectService projects,
                                    GitWorktreeManager worktrees, SafeProcessRunner runner,
                                    OpenCodeClient openCode, LoopperProperties properties,
                                    LoopDraftService drafts, VerifierEngine verifiers,
                                    TaskEventService events, ObjectMapper json) {
        this.mapper = mapper;
        this.tasks = tasks;
        this.projects = projects;
        this.worktrees = worktrees;
        this.runner = runner;
        this.openCode = openCode;
        this.properties = properties;
        this.drafts = drafts;
        this.verifiers = verifiers;
        this.events = events;
        this.json = json;
    }

    public SessionView createOrRefresh(String taskId) {
        TaskRow task = requireTask(taskId);
        Path workspace = workspace(task);
        ProjectRow project = projects.get(task.projectId());
        Path source = sourceRoot(project);
        String taskHead = gitRequired(workspace, List.of("git", "rev-parse", "HEAD"), "TASK_HEAD_UNAVAILABLE");
        String sourceHead = gitRequired(source, List.of("git", "rev-parse", "HEAD"), "SOURCE_HEAD_UNAVAILABLE");
        requireNoUnmergedPaths(source);

        List<RawChange> changes = rawChanges(workspace, task.baselineCommit(), taskHead);
        if (changes.isEmpty()) {
            throw new ConflictException("LOCAL_SYNC_NO_TASK_CHANGES", "任务提交没有可同步的文件差异");
        }
        if (changes.size() > MAX_TASK_PATHS) {
            throw new ConflictException("LOCAL_SYNC_PATH_LIMIT", "任务涉及超过 200 个路径，不能创建本地同步会话");
        }

        String sessionId = UUID.randomUUID().toString();
        Path sessionDir = managedSessionDir(sessionId);
        long storedBytes = 0;
        List<LocalSyncConflictFileRow> files = new ArrayList<>();
        try {
            Files.createDirectories(sessionDir.resolve("files"));
            for (RawChange change : changes) {
                BuiltFile built = buildFile(sessionId, sessionDir, workspace, source, change);
                storedBytes += built.storedBytes();
                if (storedBytes > MAX_SESSION_BYTES) {
                    throw new ConflictException("LOCAL_SYNC_SESSION_SIZE_LIMIT",
                            "冲突会话三方内容超过 100 MiB 安全上限");
                }
                files.add(built.row());
            }
        } catch (RuntimeException failure) {
            deleteTreeQuietly(sessionDir);
            throw failure;
        } catch (Exception failure) {
            deleteTreeQuietly(sessionDir);
            throw new ConflictException("LOCAL_SYNC_PREFLIGHT_FAILED", safeMessage(failure));
        }

        int resolved = (int) files.stream().filter(file -> file.resolution() != null).count();
        String state = resolved == files.size() ? LocalSyncConflictState.READY.name() : LocalSyncConflictState.OPEN.name();
        String now = Instant.now().toString();
        LocalSyncConflictSessionRow session = new LocalSyncConflictSessionRow(
                sessionId, task.id(), source.toString(), task.baselineCommit(), taskHead, sourceHead,
                state, files.size(), resolved, null, null, null, null, now, now, 0);
        mapper.insertLocalSyncConflictSession(session);
        files.forEach(mapper::insertLocalSyncConflictFile);
        events.emit(task.id(), "LOCAL_SYNC_CONFLICT_DISCOVERED", Map.of(
                "conflictSessionId", session.id(), "conflictCount", files.size(), "resolvedCount", resolved));
        return view(session);
    }

    public SessionView active(String taskId) {
        tasks.get(taskId);
        return mapper.findActiveLocalSyncConflictSession(taskId).map(this::view).orElse(null);
    }

    public SessionView get(String taskId, String sessionId) {
        return view(requireSession(taskId, sessionId));
    }

    public List<FileSummary> files(String taskId, String sessionId) {
        requireSession(taskId, sessionId);
        return mapper.listLocalSyncConflictFiles(sessionId).stream().map(this::summary).toList();
    }

    public FileContent content(String taskId, String sessionId, String literalPath) {
        LocalSyncConflictSessionRow session = requireSession(taskId, sessionId);
        String path = safeRelative(Path.of(session.sourceRoot()), literalPath);
        LocalSyncConflictFileRow file = requireFile(session.id(), path);
        if (!"TEXT".equals(file.contentType())) {
            return new FileContent(file.path(), file.contentType(), null, null, null, null,
                    file.baseHash(), file.sourceHash(), file.taskHash(), file.resolution(),
                    file.aiSuggestion(), false, file.version());
        }
        return new FileContent(file.path(), file.contentType(), file.baseContent(), file.sourceContent(),
                file.taskContent(), file.resolvedContent() == null ? file.mergedContent() : file.resolvedContent(),
                file.baseHash(), file.sourceHash(), file.taskHash(), file.resolution(),
                file.aiSuggestion(), aiEligible(file), file.version());
    }

    public FileContent saveResolution(String taskId, String sessionId, ResolutionRequest request) {
        if (request == null) throw new BadRequestException("LOCAL_SYNC_RESOLUTION_REQUIRED", "解决方案不能为空");
        LocalSyncConflictSessionRow session = requireSession(taskId, sessionId);
        requireEditable(session);
        String path = safeRelative(Path.of(session.sourceRoot()), request.path());
        LocalSyncConflictFileRow file = requireFile(session.id(), path);
        if (file.version() != request.expectedVersion()) {
            throw new ConflictException("LOCAL_SYNC_FILE_VERSION_CONFLICT", "文件解决方案已被更新，请重新载入");
        }
        String resolution = request.resolution() == null ? "" : request.resolution().strip().toUpperCase(Locale.ROOT);
        if (!Set.of("SOURCE", "TASK", "MANUAL").contains(resolution)) {
            throw new BadRequestException("LOCAL_SYNC_RESOLUTION_INVALID", "解决方式必须是 SOURCE、TASK 或 MANUAL");
        }
        String manual = null;
        if ("MANUAL".equals(resolution)) {
            if (!"TEXT".equals(file.contentType())) {
                throw new BadRequestException("LOCAL_SYNC_MANUAL_UNSUPPORTED", "二进制或超大文件只能选择源项目或任务版本");
            }
            manual = request.content() == null ? "" : request.content();
            if (manual.getBytes(StandardCharsets.UTF_8).length > MAX_TEXT_BYTES || manual.indexOf('\0') >= 0) {
                throw new BadRequestException("LOCAL_SYNC_MANUAL_TOO_LARGE", "手工合并内容不能超过 1 MiB");
            }
        }
        LocalSyncConflictFileRow updated = copyFile(file, resolution, manual, file.aiSuggestion(),
                file.aiSuggestionHash(), Instant.now().toString());
        if (mapper.updateLocalSyncConflictFile(updated) != 1) {
            throw new ConflictException("LOCAL_SYNC_FILE_VERSION_CONFLICT", "文件解决方案已被更新，请重新载入");
        }
        refreshSessionCounts(session.id());
        events.emit(taskId, "LOCAL_SYNC_RESOLUTION_SAVED", Map.of(
                "conflictSessionId", session.id(), "path", file.path(), "resolution", resolution));
        return content(taskId, sessionId, path);
    }

    public AiSuggestion suggest(String taskId, String sessionId, AiSuggestionRequest request) {
        if (request == null) throw new BadRequestException("LOCAL_SYNC_AI_REQUEST_REQUIRED", "AI 建议请求不能为空");
        LocalSyncConflictSessionRow session = requireSession(taskId, sessionId);
        requireEditable(session);
        String path = safeRelative(Path.of(session.sourceRoot()), request.path());
        LocalSyncConflictFileRow file = requireFile(session.id(), path);
        if (file.version() != request.expectedVersion()) {
            throw new ConflictException("LOCAL_SYNC_FILE_VERSION_CONFLICT", "文件内容已更新，请重新载入");
        }
        if (!aiEligible(file)) {
            throw new BadRequestException("LOCAL_SYNC_AI_UNSUPPORTED", "AI 建议只支持三方内容均不超过 200 KiB 的文本文件");
        }
        if (!openCode.healthy()) {
            throw new ServiceUnavailableException("OPENCODE_UNAVAILABLE", "当前 OpenCode 模型不可用");
        }
        Path aiWorkspace = managedSessionDir(session.id()).resolve("ai");
        try { Files.createDirectories(aiWorkspace); }
        catch (IOException failure) { throw new ServiceUnavailableException("LOCAL_SYNC_AI_FAILED", safeMessage(failure)); }
        OpenCodeClient.OpenCodeSession aiSession;
        try {
            aiSession = openCode.createReadOnlySession(aiWorkspace,
                    "Loopper Local Sync Merge Suggestion (READ_ONLY)", configuredModel());
            openCode.promptAsync(aiSession, aiPrompt(tasks.get(taskId), file));
        } catch (RuntimeException failure) {
            throw new ServiceUnavailableException("LOCAL_SYNC_AI_FAILED", safeMessage(failure));
        }
        String suggestion = awaitAi(aiSession);
        LocalSyncConflictFileRow updated = copyFile(file, file.resolution(), file.resolvedContent(), suggestion,
                sha256(suggestion.getBytes(StandardCharsets.UTF_8)), Instant.now().toString());
        if (mapper.updateLocalSyncConflictFile(updated) != 1) {
            throw new ConflictException("LOCAL_SYNC_FILE_VERSION_CONFLICT", "文件内容已更新；AI 建议未覆盖现有方案");
        }
        events.emit(taskId, "LOCAL_SYNC_AI_SUGGESTED", Map.of(
                "conflictSessionId", session.id(), "path", file.path(), "automaticallySelected", false));
        return new AiSuggestion(path, suggestion, false, file.version() + 1);
    }

    public SessionView apply(String taskId, String sessionId, ApplyRequest request) {
        LocalSyncConflictSessionRow initial = requireSession(taskId, sessionId);
        if (request == null || !request.confirmed()) {
            throw new BadRequestException("LOCAL_SYNC_CONFIRMATION_REQUIRED", "必须显式确认合并、验证和自动回滚");
        }
        if (initial.version() != request.expectedVersion()) {
            throw new ConflictException("LOCAL_SYNC_SESSION_VERSION_CONFLICT", "冲突会话已更新，请重新确认");
        }
        String lockKey;
        try { lockKey = Path.of(initial.sourceRoot()).toRealPath().toString(); }
        catch (IOException failure) { throw new ConflictException("SOURCE_ROOT_UNAVAILABLE", "源项目目录不可用"); }
        ReentrantLock lock = sourceLocks.computeIfAbsent(lockKey, ignored -> new ReentrantLock());
        if (!lock.tryLock()) {
            throw new ConflictException("LOCAL_SYNC_SOURCE_ACTIVE", "另一个任务正在同步同一源项目，请稍后重试");
        }
        try {
            return applyLocked(taskId, sessionId, request.expectedVersion());
        } finally {
            lock.unlock();
            if (!lock.hasQueuedThreads()) sourceLocks.remove(lockKey, lock);
        }
    }

    private SessionView applyLocked(String taskId, String sessionId, long expectedVersion) {
        LocalSyncConflictSessionRow session = requireSession(taskId, sessionId);
        if (session.version() != expectedVersion) {
            throw new ConflictException("LOCAL_SYNC_SESSION_VERSION_CONFLICT", "冲突会话已更新，请重新确认");
        }
        List<LocalSyncConflictFileRow> files = mapper.listLocalSyncConflictFiles(session.id());
        if (files.isEmpty() || files.stream().anyMatch(file -> file.resolution() == null)) {
            throw new ConflictException("LOCAL_SYNC_UNRESOLVED", "全部文件解决后才能确认同步");
        }
        if (!Set.of(LocalSyncConflictState.READY.name(), LocalSyncConflictState.ROLLED_BACK.name()).contains(session.state())) {
            throw new ConflictException("LOCAL_SYNC_SESSION_NOT_READY", "当前冲突会话不能应用");
        }
        Path source = Path.of(session.sourceRoot());
        ensureFresh(session, files, source);
        Set<String> affected = affectedPaths(files);
        Map<String, String> outsideBefore = outsideChanges(source, affected);
        Path backupDir = managedSessionDir(session.id()).resolve("backup-" + UUID.randomUUID());
        RecoveryLog recovery;
        try {
            recovery = snapshot(source, affected, backupDir);
        } catch (Exception failure) {
            throw new ConflictException("LOCAL_SYNC_SNAPSHOT_FAILED", "建立源项目恢复快照失败：" + safeMessage(failure));
        }
        session = updateSession(session, LocalSyncConflictState.APPLYING.name(), backupDir.toString(),
                writeJson(recovery), null, null);
        events.emit(taskId, "LOCAL_SYNC_APPLY_STARTED", Map.of(
                "conflictSessionId", session.id(), "fileCount", files.size(), "sourceHead", session.sourceHead()));
        try {
            prepareCandidates(files);
            for (LocalSyncConflictFileRow file : files) applyFile(source, file);
            session = updateSession(session, LocalSyncConflictState.VERIFYING.name(), backupDir.toString(),
                    session.recoveryLogJson(), null, null);
            VerificationEvidence evidence = verifyApplied(tasks.get(taskId), session, files, source, outsideBefore, affected);
            events.emit(taskId, "LOCAL_SYNC_VERIFIED", Map.of(
                    "conflictSessionId", session.id(), "passed", evidence.passed(), "checks", evidence.checks().size()));
            if (!evidence.passed()) {
                throw new VerificationFailure(writeJson(evidence));
            }
            session = updateSession(session, LocalSyncConflictState.APPLIED.name(), null, session.recoveryLogJson(),
                    writeJson(evidence), null);
            tasks.recordLocalSourceSync(taskId, session.taskCommit(), "RESOLVED_MERGE");
            deleteTreeQuietly(backupDir);
            events.emit(taskId, "LOCAL_SYNC_APPLIED", Map.of(
                    "conflictSessionId", session.id(), "mode", "RESOLVED_MERGE", "taskCommit", session.taskCommit()));
            return view(session);
        } catch (Exception failure) {
            String evidence = failure instanceof VerificationFailure verification ? verification.evidence : null;
            return rollbackAfterFailure(taskId, session, recovery, backupDir, failure, evidence);
        }
    }

    private SessionView rollbackAfterFailure(String taskId, LocalSyncConflictSessionRow session, RecoveryLog recovery,
                                             Path backupDir, Exception failure, String verificationEvidence) {
        events.emit(taskId, "LOCAL_SYNC_ROLLBACK_STARTED", Map.of(
                "conflictSessionId", session.id(), "reason", safeMessage(failure)));
        try {
            restore(recovery, Path.of(session.sourceRoot()));
            LocalSyncConflictSessionRow rolledBack = updateSession(session, LocalSyncConflictState.ROLLED_BACK.name(),
                    null, session.recoveryLogJson(), verificationEvidence, safeMessage(failure));
            deleteTreeQuietly(backupDir);
            events.emit(taskId, "LOCAL_SYNC_ROLLED_BACK", Map.of(
                    "conflictSessionId", session.id(), "reason", safeMessage(failure)));
            return view(rolledBack);
        } catch (Exception rollbackFailure) {
            String message = "自动恢复失败：" + safeMessage(rollbackFailure) + "；备份保留在 " + backupDir;
            LocalSyncConflictSessionRow failed = updateSession(session, LocalSyncConflictState.ROLLBACK_FAILED.name(),
                    backupDir.toString(), session.recoveryLogJson(), verificationEvidence, message);
            events.emit(taskId, "LOCAL_SYNC_ROLLBACK_FAILED", Map.of(
                    "conflictSessionId", session.id(), "backupDir", backupDir.toString(), "reason", message));
            return view(failed);
        }
    }

    @EventListener(ApplicationReadyEvent.class)
    public void recoverInterruptedApplications() {
        for (LocalSyncConflictSessionRow session : mapper.recoverableLocalSyncConflictSessions()) {
            if (session.recoveryLogJson() == null || session.backupDir() == null) {
                updateSession(session, LocalSyncConflictState.ROLLBACK_FAILED.name(), session.backupDir(),
                        session.recoveryLogJson(), session.verificationEvidenceJson(),
                        "进程中断且恢复日志不完整，请检查源项目和受管数据目录");
                continue;
            }
            try {
                RecoveryLog log = json.readValue(session.recoveryLogJson(), RecoveryLog.class);
                restore(log, Path.of(session.sourceRoot()));
                updateSession(session, LocalSyncConflictState.ROLLED_BACK.name(), null, session.recoveryLogJson(),
                        session.verificationEvidenceJson(), "检测到进程中断，已从持久化快照自动恢复");
                deleteTreeQuietly(Path.of(session.backupDir()));
                events.emit(session.taskId(), "LOCAL_SYNC_STARTUP_RECOVERED", Map.of("conflictSessionId", session.id()));
            } catch (Exception failure) {
                updateSession(session, LocalSyncConflictState.ROLLBACK_FAILED.name(), session.backupDir(),
                        session.recoveryLogJson(), session.verificationEvidenceJson(),
                        "启动恢复失败：" + safeMessage(failure) + "；备份保留在 " + session.backupDir());
            }
        }
    }

    private BuiltFile buildFile(String sessionId, Path sessionDir, Path workspace, Path source, RawChange change)
            throws IOException {
        validateRelative(change.oldPath());
        validateRelative(change.newPath());
        if (unsafeMode(change.oldMode()) || unsafeMode(change.newMode())) {
            throw new ConflictException("LOCAL_SYNC_UNSUPPORTED_FILE_TYPE",
                    "第一版不支持 symlink、submodule 或不安全文件类型：" + change.displayPath());
        }
        byte[] base = missingSha(change.oldSha()) ? null : readGitBlob(workspace, change.oldSha());
        byte[] task = missingSha(change.newSha()) ? null : readGitBlob(workspace, change.newSha());
        SourceSnapshot sourceSnapshot = sourceSnapshot(source, change);
        if (sourceSnapshot.unsafe()) {
            throw new ConflictException("LOCAL_SYNC_UNSUPPORTED_FILE_TYPE",
                    "第一版不支持 symlink、submodule、目录或特殊文件：" + change.displayPath());
        }
        byte[] current = sourceSnapshot.primary();
        String contentType = contentType(base, current, task);
        String changeType = classifiedChange(change, base, current, task, sourceSnapshot);
        MergeChoice choice = choose(base, current, task, change.oldMode(), sourceSnapshot.primaryMode(),
                change.newMode(), contentType, sessionDir);
        String id = UUID.randomUUID().toString();
        Path external = sessionDir.resolve("files").resolve(id);
        long storedBytes = size(base) + size(current) + size(task);
        if (!"TEXT".equals(contentType)) {
            Files.createDirectories(external);
            writeOptional(external.resolve("base.bin"), base);
            writeOptional(external.resolve("source.bin"), current);
            writeOptional(external.resolve("task.bin"), task);
        }
        if (choice.bytes() != null) {
            Files.createDirectories(external);
            Files.write(external.resolve("resolved.bin"), choice.bytes());
            storedBytes += choice.bytes().length;
        }
        String now = Instant.now().toString();
        String path = change.displayPath();
        LocalSyncConflictFileRow row = new LocalSyncConflictFileRow(
                id, sessionId, path, change.oldPath(), change.newPath(), changeType, contentType,
                hash(base), sourceSnapshot.combinedHash(), hash(task), mode(change.oldMode()),
                sourceSnapshot.combinedMode(), mode(change.newMode()),
                textOrNull(base, contentType), textOrNull(current, contentType), textOrNull(task, contentType),
                choice.mergedText(), choice.resolution(), choice.resolvedText(), null, null,
                Files.exists(external) ? external.toString() : null, now, now, 0);
        return new BuiltFile(row, storedBytes);
    }

    private MergeChoice choose(byte[] base, byte[] source, byte[] task, String baseMode, String sourceMode,
                               String taskMode, String contentType, Path sessionDir) throws IOException {
        if (sameState(source, sourceMode, base, baseMode)) {
            return auto(task, contentType);
        }
        if (sameState(task, taskMode, base, baseMode)) {
            return auto(source, contentType);
        }
        if (sameState(source, sourceMode, task, taskMode)) {
            return auto(source, contentType);
        }
        if (!"TEXT".equals(contentType)) return new MergeChoice(null, null, null, null);
        ThreeWay merged = mergeText(sessionDir, base, source, task);
        if (merged.clean() && compatibleMergedMode(baseMode, sourceMode, taskMode)) {
            byte[] bytes = merged.content().getBytes(StandardCharsets.UTF_8);
            return new MergeChoice("AUTO", merged.content(), merged.content(), bytes);
        }
        return new MergeChoice(null, merged.content(), null, null);
    }

    private MergeChoice auto(byte[] selected, String contentType) {
        String text = "TEXT".equals(contentType) && selected != null ? new String(selected, StandardCharsets.UTF_8) : null;
        return new MergeChoice("AUTO", text, text, selected);
    }

    private ThreeWay mergeText(Path sessionDir, byte[] base, byte[] source, byte[] task) throws IOException {
        Path temp = Files.createTempDirectory(sessionDir, "merge-");
        try {
            Files.write(temp.resolve("source"), orEmpty(source));
            Files.write(temp.resolve("base"), orEmpty(base));
            Files.write(temp.resolve("task"), orEmpty(task));
            ProcessResult result = runner.run(temp, List.of("git", "merge-file", "-p", "-L", "源项目",
                    "-L", "BASE", "-L", "任务", "source", "base", "task"), MERGE_TIMEOUT);
            // git merge-file returns the number of conflict regions (capped at 127),
            // not just a boolean 0/1 status.  Only 128+ represents an execution error.
            if (result.timedOut() || result.outputTruncated() || result.exitCode() > 127) {
                throw new ConflictException("LOCAL_SYNC_MERGE_FAILED", "确定性三方合并失败");
            }
            return new ThreeWay(result.exitCode() == 0, result.output());
        } finally {
            deleteTreeQuietly(temp);
        }
    }

    private SourceSnapshot sourceSnapshot(Path root, RawChange change) throws IOException {
        Path old = safeResolve(root, change.oldPath());
        Path target = safeResolve(root, change.newPath());
        FileSnapshot oldState = fileSnapshot(old);
        FileSnapshot targetState = change.oldPath().equals(change.newPath()) ? oldState : fileSnapshot(target);
        boolean renameAlreadyApplied = !change.oldPath().equals(change.newPath()) && !oldState.exists() && targetState.exists();
        FileSnapshot primary = renameAlreadyApplied ? targetState : oldState;
        String combinedHash = change.oldPath().equals(change.newPath()) ? primary.hash()
                : oldState.hash() + ":" + targetState.hash();
        String combinedMode = change.oldPath().equals(change.newPath()) ? primary.mode()
                : oldState.mode() + ":" + targetState.mode();
        return new SourceSnapshot(primary.bytes(), primary.mode(), combinedHash, combinedMode,
                oldState.unsafe() || targetState.unsafe(), targetState.exists());
    }

    private FileSnapshot fileSnapshot(Path path) throws IOException {
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return new FileSnapshot(false, null, MISSING, MISSING, false);
        if (Files.isSymbolicLink(path) || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            return new FileSnapshot(true, null, "UNSAFE", "UNSAFE", true);
        }
        long size = Files.size(path);
        if (size > MAX_SESSION_BYTES) throw new ConflictException("LOCAL_SYNC_SESSION_SIZE_LIMIT", "单个文件超过 100 MiB 安全上限");
        byte[] content = Files.readAllBytes(path);
        return new FileSnapshot(true, content, sha256(content), sourceMode(path), false);
    }

    private void ensureFresh(LocalSyncConflictSessionRow session, List<LocalSyncConflictFileRow> files, Path source) {
        TaskRow task = requireTask(session.taskId());
        String currentTaskHead = gitRequired(workspace(task), List.of("git", "rev-parse", "HEAD"),
                "TASK_HEAD_UNAVAILABLE");
        if (!session.taskCommit().equals(currentTaskHead)) {
            markStale(session, "任务提交已变化，请刷新冲突会话");
        }
        String currentHead = gitRequired(source, List.of("git", "rev-parse", "HEAD"), "SOURCE_HEAD_UNAVAILABLE");
        if (!session.sourceHead().equals(currentHead)) {
            markStale(session, "源项目 HEAD 已变化，请刷新冲突会话");
        }
        requireNoUnmergedPaths(source);
        Set<String> affected = affectedPaths(files);
        Set<String> staged = nulPaths(gitAllowEmpty(source,
                List.of("git", "diff", "--cached", "--name-only", "-z", "--"), "SOURCE_INDEX_CHECK_FAILED"));
        if (staged.stream().anyMatch(affected::contains)) {
            markStale(session, "任务涉及路径已暂存，请取消暂存或提交后刷新会话");
        }
        for (LocalSyncConflictFileRow file : files) {
            try {
                RawChange synthetic = new RawChange(file.baseMode(), file.taskMode(), "", "", "M",
                        file.sourcePath(), file.taskPath());
                SourceSnapshot current = sourceSnapshot(source, synthetic);
                if (!file.sourceHash().equals(current.combinedHash()) || !file.sourceMode().equals(current.combinedMode())) {
                    markStale(session, "源项目文件已变化，请刷新冲突会话：" + file.path());
                }
            } catch (IOException failure) {
                markStale(session, "无法重新核对源项目文件：" + file.path());
            }
        }
    }

    private void markStale(LocalSyncConflictSessionRow session, String message) {
        updateSession(session, LocalSyncConflictState.STALE.name(), session.backupDir(), session.recoveryLogJson(),
                session.verificationEvidenceJson(), message);
        events.emit(session.taskId(), "LOCAL_SYNC_STALE", Map.of("conflictSessionId", session.id(), "reason", message));
        throw new ConflictException("LOCAL_SYNC_STALE", message);
    }

    private void requireNoUnmergedPaths(Path source) {
        String unmerged = gitAllowEmpty(source, List.of("git", "diff", "--name-only", "--diff-filter=U", "-z", "--"),
                "SOURCE_CONFLICT_CHECK_FAILED");
        if (!unmerged.isEmpty()) {
            throw new ConflictException("SOURCE_HAS_UNRESOLVED_CONFLICTS", "源项目存在未解决的 Git 冲突，不能应用本地同步会话");
        }
    }

    private RecoveryLog snapshot(Path source, Set<String> affected, Path backupDir) throws IOException {
        Files.createDirectories(backupDir);
        List<BackupEntry> entries = new ArrayList<>();
        int index = 0;
        for (String relative : affected.stream().sorted().toList()) {
            Path target = safeResolve(source, relative);
            FileSnapshot state = fileSnapshot(target);
            String backup = null;
            if (state.exists()) {
                backup = String.format("%03d.bin", index++);
                Files.write(backupDir.resolve(backup), state.bytes());
            }
            entries.add(new BackupEntry(relative, state.exists(), state.mode(), state.hash(), backup));
        }
        return new RecoveryLog(backupDir.toString(), entries);
    }

    private void restore(RecoveryLog log, Path source) throws IOException {
        Path backupDir = Path.of(log.backupDir());
        for (BackupEntry entry : log.entries()) {
            Path target = safeResolve(source, entry.path());
            if (!entry.existed()) {
                Files.deleteIfExists(target);
                continue;
            }
            if (entry.backupFile() == null) throw new IOException("missing backup for " + entry.path());
            byte[] bytes = Files.readAllBytes(backupDir.resolve(entry.backupFile()));
            if (!entry.hash().equals(sha256(bytes))) throw new IOException("backup checksum mismatch for " + entry.path());
            atomicWrite(target, bytes, entry.mode());
        }
    }

    private void prepareCandidates(List<LocalSyncConflictFileRow> files) throws IOException {
        for (LocalSyncConflictFileRow file : files) {
            if ("SOURCE".equals(file.resolution())) continue;
            byte[] selected = selectedBytes(file);
            if (selected != null) {
                Path external = file.externalDir() == null
                        ? managedSessionDir(file.sessionId()).resolve("files").resolve(file.id())
                        : Path.of(file.externalDir());
                Files.createDirectories(external);
                Files.write(external.resolve("candidate.bin"), selected);
            }
        }
    }

    private void applyFile(Path source, LocalSyncConflictFileRow file) throws IOException {
        if ("SOURCE".equals(file.resolution())) return;
        byte[] selected = selectedBytes(file);
        Path oldPath = safeResolve(source, file.sourcePath());
        Path targetPath = safeResolve(source, file.taskPath());
        boolean taskDeletion = MISSING.equals(file.taskMode());
        if ("TASK".equals(file.resolution()) && taskDeletion) {
            Files.deleteIfExists(targetPath);
        } else if (selected == null && taskDeletion) {
            Files.deleteIfExists(targetPath);
        } else {
            atomicWrite(targetPath, selected == null ? new byte[0] : selected, selectedMode(file));
        }
        if (!file.sourcePath().equals(file.taskPath())) Files.deleteIfExists(oldPath);
    }

    private VerificationEvidence verifyApplied(TaskRow task, LocalSyncConflictSessionRow session,
                                               List<LocalSyncConflictFileRow> files, Path source,
                                               Map<String, String> outsideBefore, Set<String> affected) {
        List<VerificationCheck> checks = new ArrayList<>();
        for (LocalSyncConflictFileRow file : files) {
            try {
                if ("SOURCE".equals(file.resolution())) {
                    checks.add(new VerificationCheck("INCLUSION", file.path(), true, "保留源项目版本"));
                    continue;
                }
                Path target = safeResolve(source, file.taskPath());
                byte[] expected = selectedBytes(file);
                boolean expectedDelete = expected == null && MISSING.equals(file.taskMode());
                boolean passed = expectedDelete ? !Files.exists(target, LinkOption.NOFOLLOW_LINKS)
                        : Files.isRegularFile(target) && MessageDigest.isEqual(expected == null ? new byte[0] : expected,
                        Files.readAllBytes(target));
                checks.add(new VerificationCheck("INCLUSION", file.path(), passed,
                        passed ? "实际写入等于确认方案" : "实际写入与确认方案不一致"));
            } catch (Exception failure) {
                checks.add(new VerificationCheck("INCLUSION", file.path(), false, safeMessage(failure)));
            }
        }
        Map<String, String> outsideAfter = outsideChanges(source, affected);
        checks.add(new VerificationCheck("OUTSIDE_PATHS", "*", outsideBefore.equals(outsideAfter),
                outsideBefore.equals(outsideAfter) ? "任务路径外既有改动保持不变" : "任务路径外内容发生变化"));

        if (task.loopDraftId() != null) {
            LoopSpec spec = drafts.spec(mapper.findDraft(task.loopDraftId())
                    .orElseThrow(() -> new ConflictException("LOOPSPEC_NOT_FOUND", "任务 LoopSpec 不存在")));
            Duration timeout = Duration.ofSeconds(spec.limits().verifierTimeoutSeconds());
            int stageIndex = 0;
            for (LoopSpec.StageSpec stage : spec.stages()) {
                int verifierIndex = 0;
                for (LoopSpec.VerifierSpec verifier : stage.verifiers()) {
                    String type = verifier.type().toUpperCase(Locale.ROOT);
                    if ("GIT_DIFF".equals(type)) {
                        checks.add(new VerificationCheck("PUBLICATION_INCLUSION", stageIndex + ":" + verifierIndex,
                                true, "发布验证已用包含性检查替代原 GIT_DIFF"));
                    } else {
                        try {
                            VerifierOutcome outcome = verifiers.verify(source, session.baselineCommit(), verifier, timeout);
                            checks.add(new VerificationCheck(type, stageIndex + ":" + verifierIndex,
                                    outcome.state() == VerificationState.PASS, outcome.summary()));
                        } catch (RuntimeException failure) {
                            checks.add(new VerificationCheck(type, stageIndex + ":" + verifierIndex,
                                    false, safeMessage(failure)));
                        }
                    }
                    verifierIndex++;
                }
                stageIndex++;
            }
        }
        return new VerificationEvidence(checks.stream().allMatch(VerificationCheck::passed), checks);
    }

    private Map<String, String> outsideChanges(Path source, Set<String> affected) {
        String status = gitAllowEmpty(source,
                List.of("git", "status", "--porcelain=v1", "-z", "--untracked-files=all"), "SOURCE_STATUS_FAILED");
        List<String> tokens = List.of(status.split("\u0000", -1));
        Map<String, String> snapshot = new LinkedHashMap<>();
        for (int i = 0; i < tokens.size(); i++) {
            String token = tokens.get(i);
            if (token.length() < 4) continue;
            String path = token.substring(3);
            if (token.charAt(0) == 'R' || token.charAt(1) == 'R') {
                if (i + 1 < tokens.size()) path = tokens.get(++i);
            }
            if (affected.contains(path)) continue;
            try {
                Path target = safeResolve(source, path);
                snapshot.put(token.substring(0, 2) + ":" + path, fileSnapshot(target).hash());
            } catch (Exception failure) {
                snapshot.put(token.substring(0, 2) + ":" + path, "UNREADABLE");
            }
        }
        return snapshot;
    }

    private byte[] selectedBytes(LocalSyncConflictFileRow file) throws IOException {
        return switch (file.resolution()) {
            case "SOURCE" -> readStored(file, "source.bin", file.sourceContent());
            case "TASK" -> MISSING.equals(file.taskMode()) ? null : readStored(file, "task.bin", file.taskContent());
            case "MANUAL" -> file.resolvedContent().getBytes(StandardCharsets.UTF_8);
            case "AUTO" -> readStored(file, "resolved.bin", file.resolvedContent());
            default -> throw new IOException("unresolved file " + file.path());
        };
    }

    private byte[] readStored(LocalSyncConflictFileRow file, String name, String text) throws IOException {
        if (text != null) return text.getBytes(StandardCharsets.UTF_8);
        if (file.externalDir() == null) return null;
        Path value = Path.of(file.externalDir()).resolve(name);
        return Files.exists(value) ? Files.readAllBytes(value) : null;
    }

    private void atomicWrite(Path target, byte[] bytes, String mode) throws IOException {
        Files.createDirectories(target.getParent());
        Path temporary = Files.createTempFile(target.getParent(), ".loopper-sync-", ".tmp");
        try {
            Files.write(temporary, bytes);
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
            applyMode(target, mode);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private void applyMode(Path target, String mode) {
        if (mode == null || MISSING.equals(mode) || mode.contains(":")) return;
        try {
            Set<PosixFilePermission> permissions = new HashSet<>(Set.of(
                    PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE,
                    PosixFilePermission.GROUP_READ, PosixFilePermission.OTHERS_READ));
            if (mode.endsWith("755")) permissions.addAll(Set.of(PosixFilePermission.OWNER_EXECUTE,
                    PosixFilePermission.GROUP_EXECUTE, PosixFilePermission.OTHERS_EXECUTE));
            Files.setPosixFilePermissions(target, permissions);
        } catch (UnsupportedOperationException | IOException ignored) { }
    }

    private String selectedMode(LocalSyncConflictFileRow file) {
        if ("MANUAL".equals(file.resolution())) {
            return !MISSING.equals(file.taskMode()) ? file.taskMode() : file.sourceMode();
        }
        if ("AUTO".equals(file.resolution()) && !MISSING.equals(file.taskMode())) return file.taskMode();
        return file.taskMode();
    }

    private void refreshSessionCounts(String sessionId) {
        for (int attempt = 0; attempt < 3; attempt++) {
            LocalSyncConflictSessionRow session = mapper.findLocalSyncConflictSession(sessionId).orElseThrow();
            int resolved = (int) mapper.listLocalSyncConflictFiles(sessionId).stream()
                    .filter(file -> file.resolution() != null).count();
            String state = resolved == session.conflictCount()
                    ? LocalSyncConflictState.READY.name() : LocalSyncConflictState.OPEN.name();
            LocalSyncConflictSessionRow changed = new LocalSyncConflictSessionRow(session.id(), session.taskId(),
                    session.sourceRoot(), session.baselineCommit(), session.taskCommit(), session.sourceHead(), state,
                    session.conflictCount(), resolved, session.backupDir(), session.recoveryLogJson(),
                    session.verificationEvidenceJson(), null, session.createdAt(), Instant.now().toString(), session.version());
            if (mapper.updateLocalSyncConflictSession(changed) == 1) return;
        }
        throw new ConflictException("LOCAL_SYNC_SESSION_VERSION_CONFLICT", "会话解决进度已变化，请重新载入");
    }

    private LocalSyncConflictSessionRow updateSession(LocalSyncConflictSessionRow session, String state,
                                                       String backupDir, String recoveryLog, String evidence,
                                                       String error) {
        LocalSyncConflictSessionRow changed = new LocalSyncConflictSessionRow(session.id(), session.taskId(),
                session.sourceRoot(), session.baselineCommit(), session.taskCommit(), session.sourceHead(), state,
                session.conflictCount(), session.resolvedCount(), backupDir, recoveryLog, evidence, error,
                session.createdAt(), Instant.now().toString(), session.version());
        if (mapper.updateLocalSyncConflictSession(changed) != 1) {
            throw new ConflictException("LOCAL_SYNC_SESSION_VERSION_CONFLICT", "冲突会话已被更新，请重新载入");
        }
        return mapper.findLocalSyncConflictSession(session.id()).orElseThrow();
    }

    private TaskRow requireTask(String taskId) {
        TaskRow task = tasks.get(taskId);
        if (!TaskState.SUCCEEDED.name().equals(task.state())) {
            throw new ConflictException("TASK_NOT_SUCCEEDED", "只有验收成功的任务才能解决本地同步冲突");
        }
        if (task.baselineCommit() == null || task.baselineCommit().isBlank()) {
            throw new ConflictException("TASK_BASELINE_MISSING", "任务缺少 Git 基线");
        }
        return task;
    }

    private LocalSyncConflictSessionRow requireSession(String taskId, String sessionId) {
        LocalSyncConflictSessionRow session = mapper.findLocalSyncConflictSession(sessionId)
                .orElseThrow(() -> new NotFoundException("Local sync conflict session not found: " + sessionId));
        if (!session.taskId().equals(taskId)) throw new NotFoundException("Local sync conflict session not found: " + sessionId);
        return session;
    }

    private LocalSyncConflictFileRow requireFile(String sessionId, String path) {
        return mapper.findLocalSyncConflictFile(sessionId, path)
                .orElseThrow(() -> new NotFoundException("Local sync conflict file not found: " + path));
    }

    private void requireEditable(LocalSyncConflictSessionRow session) {
        if (!Set.of(LocalSyncConflictState.OPEN.name(), LocalSyncConflictState.READY.name(),
                LocalSyncConflictState.ROLLED_BACK.name()).contains(session.state())) {
            throw new ConflictException("LOCAL_SYNC_SESSION_NOT_EDITABLE", "当前会话不可编辑；若已过期请先刷新");
        }
    }

    private Path workspace(TaskRow task) {
        Path workspace = Path.of(task.worktreePath());
        worktrees.requireManaged(workspace);
        return workspace;
    }

    private Path sourceRoot(ProjectRow project) {
        try {
            Path source = Path.of(project.rootPath()).toRealPath();
            String top = gitRequired(source, List.of("git", "rev-parse", "--show-toplevel"), "SOURCE_REPOSITORY_UNAVAILABLE");
            if (!source.equals(Path.of(top).toRealPath())) throw new IOException("registered root is not repository root");
            return source;
        } catch (IOException failure) {
            throw new ConflictException("SOURCE_REPOSITORY_UNAVAILABLE", "登记的源项目不是可用的 Git 仓库根目录");
        }
    }

    private Path managedSessionDir(String sessionId) {
        if (!sessionId.matches("[0-9a-fA-F-]{36}")) throw new IllegalArgumentException("invalid session id");
        return properties.getDataDir().toAbsolutePath().normalize().resolve("local-sync-conflicts").resolve(sessionId);
    }

    private List<RawChange> rawChanges(Path workspace, String baseline, String taskHead) {
        if (baseline == null || baseline.isBlank()) throw new ConflictException("TASK_BASELINE_MISSING", "任务缺少 Git 基线");
        String raw = gitAllowEmpty(workspace,
                List.of("git", "diff", "--raw", "-z", "-M", "--no-ext-diff", baseline, taskHead, "--"),
                "LOCAL_SYNC_DIFF_FAILED");
        List<String> tokens = List.of(raw.split("\u0000", -1));
        List<RawChange> changes = new ArrayList<>();
        for (int index = 0; index < tokens.size();) {
            String header = tokens.get(index++);
            if (header.isBlank()) continue;
            String[] fields = header.split(" ");
            if (fields.length != 5 || !fields[0].startsWith(":")) {
                throw new ConflictException("LOCAL_SYNC_DIFF_INVALID", "无法解析任务 Git 差异");
            }
            String status = fields[4];
            if (index >= tokens.size()) throw new ConflictException("LOCAL_SYNC_DIFF_INVALID", "任务 Git 差异缺少路径");
            String oldPath = tokens.get(index++);
            String newPath = oldPath;
            if (status.startsWith("R") || status.startsWith("C")) {
                if (index >= tokens.size()) throw new ConflictException("LOCAL_SYNC_DIFF_INVALID", "重命名差异缺少目标路径");
                newPath = tokens.get(index++);
            }
            changes.add(new RawChange(fields[0].substring(1), fields[1], fields[2], fields[3], status,
                    oldPath, newPath));
        }
        return changes;
    }

    private byte[] readGitBlob(Path workspace, String objectId) throws IOException {
        Process process = new ProcessBuilder("git", "cat-file", "blob", objectId).directory(workspace.toFile())
                .redirectErrorStream(true).start();
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (var input = process.getInputStream()) {
            byte[] buffer = new byte[8192];
            int read;
            long total = 0;
            while ((read = input.read(buffer)) >= 0) {
                total += read;
                if (total > MAX_SESSION_BYTES) {
                    process.destroyForcibly();
                    throw new ConflictException("LOCAL_SYNC_SESSION_SIZE_LIMIT", "Git 对象超过 100 MiB 安全上限");
                }
                bytes.write(buffer, 0, read);
            }
        }
        try {
            if (!process.waitFor(GIT_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS) || process.exitValue() != 0) {
                process.destroyForcibly();
                throw new IOException("git cat-file failed");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IOException("git cat-file interrupted", interrupted);
        }
        return bytes.toByteArray();
    }

    private String contentType(byte[]... values) {
        boolean large = false;
        for (byte[] value : values) {
            if (value == null) continue;
            if (!isUtf8Text(value)) return "BINARY";
            if (value.length > MAX_TEXT_BYTES) large = true;
        }
        return large ? "LARGE_TEXT" : "TEXT";
    }

    private boolean isUtf8Text(byte[] bytes) {
        for (byte value : bytes) if (value == 0) return false;
        try {
            StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes));
            return true;
        } catch (CharacterCodingException failure) {
            return false;
        }
    }

    private String classifiedChange(RawChange raw, byte[] base, byte[] source, byte[] task,
                                    SourceSnapshot snapshot) {
        if (raw.status().startsWith("R")) {
            boolean simple = same(base, source) && !snapshot.targetExists();
            return simple ? "RENAME" : "RENAME_CONFLICT";
        }
        if (base == null && task != null && source != null) return "ADD_ADD";
        if (task == null && source != null && !same(base, source)) return "MODIFY_DELETE";
        if (source == null && task != null && base != null) return "DELETE_MODIFY";
        return switch (raw.status().substring(0, 1)) {
            case "A" -> "ADD";
            case "D" -> "DELETE";
            default -> "MODIFY";
        };
    }

    private SessionView view(LocalSyncConflictSessionRow row) {
        return new SessionView(row.id(), row.taskId(), row.state(), row.sourceRoot(), row.sourceHead(),
                row.taskCommit(), row.baselineCommit(), row.conflictCount(), row.resolvedCount(),
                row.errorMessage(), row.backupDir(), row.verificationEvidenceJson(), row.createdAt(),
                row.updatedAt(), row.version());
    }

    private FileSummary summary(LocalSyncConflictFileRow row) {
        return new FileSummary(row.path(), row.sourcePath(), row.taskPath(), row.changeType(), row.contentType(),
                row.resolution(), row.resolution() != null, row.aiSuggestion() != null,
                row.baseHash(), row.sourceHash(), row.taskHash(), row.version());
    }

    private String safeRelative(Path root, String raw) {
        validateRelative(raw);
        Path resolved = safeResolve(root, raw);
        return root.relativize(resolved).toString().replace('\\', '/');
    }

    private Path safeResolve(Path root, String relative) {
        validateRelative(relative);
        Path normalizedRoot = root.toAbsolutePath().normalize();
        Path resolved = normalizedRoot.resolve(relative).normalize();
        if (!resolved.startsWith(normalizedRoot)) throw new BadRequestException("LOCAL_SYNC_PATH_ESCAPE", "路径越出源项目根目录");
        Path cursor = normalizedRoot;
        for (Path part : normalizedRoot.relativize(resolved)) {
            cursor = cursor.resolve(part);
            if (Files.isSymbolicLink(cursor)) {
                throw new BadRequestException("LOCAL_SYNC_PATH_ESCAPE", "路径不能经过符号链接：" + relative);
            }
        }
        return resolved;
    }

    private void validateRelative(String value) {
        if (value == null || value.isBlank()) throw new BadRequestException("LOCAL_SYNC_PATH_INVALID", "文件路径不能为空");
        Path path;
        try { path = Path.of(value); }
        catch (RuntimeException failure) { throw new BadRequestException("LOCAL_SYNC_PATH_INVALID", "文件路径无效"); }
        boolean gitMetadata = false;
        for (Path part : path) if (".git".equalsIgnoreCase(part.toString())) gitMetadata = true;
        if (path.isAbsolute() || path.normalize().startsWith("..") || path.toString().indexOf('\0') >= 0
                || gitMetadata) {
            throw new BadRequestException("LOCAL_SYNC_PATH_ESCAPE", "路径必须位于源项目内且不能进入 .git");
        }
    }

    private String gitRequired(Path directory, List<String> command, String code) {
        String output = gitAllowEmpty(directory, command, code);
        if (output.isBlank()) throw new ConflictException(code, "Git 未返回所需信息");
        return output.strip();
    }

    private String gitAllowEmpty(Path directory, List<String> command, String code) {
        ProcessResult result = runner.run(directory, command, GIT_TIMEOUT);
        if (result.timedOut()) throw new ConflictException(code, "Git 检查超时");
        if (result.outputTruncated()) throw new ConflictException(code, "Git 检查输出超过安全上限");
        if (result.exitCode() != 0) throw new ConflictException(code, result.output() == null ? "Git 检查失败" : result.output().strip());
        return result.output() == null ? "" : result.output();
    }

    private Set<String> nulPaths(String value) {
        Set<String> paths = new HashSet<>();
        for (String path : value.split("\u0000", -1)) if (!path.isBlank()) paths.add(path);
        return paths;
    }

    private Set<String> affectedPaths(List<LocalSyncConflictFileRow> files) {
        Set<String> paths = new LinkedHashSet<>();
        for (LocalSyncConflictFileRow file : files) {
            paths.add(file.sourcePath());
            paths.add(file.taskPath());
        }
        return paths;
    }

    private String sourceMode(Path path) throws IOException {
        return Files.isExecutable(path) ? "100755" : "100644";
    }

    private boolean unsafeMode(String mode) { return mode != null && (mode.equals("120000") || mode.equals("160000")); }
    private boolean missingSha(String sha) { return sha == null || sha.chars().allMatch(character -> character == '0'); }
    private String mode(String mode) { return mode == null || mode.chars().allMatch(character -> character == '0') ? MISSING : mode; }
    private long size(byte[] bytes) { return bytes == null ? 0 : bytes.length; }
    private int bytes(String value) { return value == null ? 0 : value.getBytes(StandardCharsets.UTF_8).length; }
    private boolean aiEligible(LocalSyncConflictFileRow file) {
        return "TEXT".equals(file.contentType()) && bytes(file.baseContent()) <= MAX_AI_BYTES
                && bytes(file.sourceContent()) <= MAX_AI_BYTES && bytes(file.taskContent()) <= MAX_AI_BYTES;
    }
    private byte[] orEmpty(byte[] bytes) { return bytes == null ? new byte[0] : bytes; }
    private boolean same(byte[] left, byte[] right) { return java.util.Arrays.equals(left, right); }
    private boolean sameState(byte[] left, String leftMode, byte[] right, String rightMode) {
        return same(left, right) && mode(leftMode).equals(mode(rightMode));
    }
    private boolean compatibleMergedMode(String base, String source, String task) {
        return mode(source).equals(mode(base)) || mode(task).equals(mode(base)) || mode(source).equals(mode(task));
    }
    private String hash(byte[] value) { return value == null ? MISSING : sha256(value); }
    private String textOrNull(byte[] value, String contentType) {
        return "TEXT".equals(contentType) && value != null ? new String(value, StandardCharsets.UTF_8) : null;
    }
    private void writeOptional(Path path, byte[] value) throws IOException { if (value != null) Files.write(path, value); }
    private String sha256(byte[] value) {
        try { return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value)); }
        catch (Exception failure) { throw new IllegalStateException(failure); }
    }
    private String writeJson(Object value) {
        try { return json.writeValueAsString(value); }
        catch (Exception failure) { throw new IllegalStateException("Unable to persist local sync recovery evidence", failure); }
    }
    private String safeMessage(Throwable failure) {
        String message = failure == null || failure.getMessage() == null ? "未知错误" : failure.getMessage();
        return message.substring(0, Math.min(message.length(), 1200));
    }
    private void deleteTreeQuietly(Path root) {
        if (root == null || !Files.exists(root)) return;
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
        } catch (Exception ignored) { }
    }

    private OpenCodeClient.OpenCodeModel configuredModel() {
        String configured = properties.getOpenCode().getModel();
        if (configured == null) return null;
        int separator = configured.indexOf('/');
        if (separator <= 0 || separator >= configured.length() - 1) return null;
        return new OpenCodeClient.OpenCodeModel(configured.substring(0, separator), configured.substring(separator + 1), null);
    }

    private String aiPrompt(TaskRow task, LocalSyncConflictFileRow file) {
        return """
                你是只读的单文件三方合并建议器。不要调用工具，不要读取工作区，不要输出 Markdown 或解释。
                仅根据下方 BASE、源项目、任务三个版本和任务目标，返回完整建议文件内容。
                建议不会被自动采用，用户会在编辑器中复核后手工确认。

                任务目标：%s
                文件：%s
                ===== BASE =====
                %s
                ===== 源项目 =====
                %s
                ===== 任务 =====
                %s
                """.formatted(tasks.goal(task.id()), file.path(), nullToEmpty(file.baseContent()),
                nullToEmpty(file.sourceContent()), nullToEmpty(file.taskContent()));
    }

    private String awaitAi(OpenCodeClient.OpenCodeSession session) {
        long deadline = System.nanoTime() + AI_TIMEOUT.toNanos();
        while (System.nanoTime() < deadline) {
            try {
                OpenCodeClient.SessionStatus status = openCode.sessionStatus(session);
                if (status.completed()) {
                    String output = openCode.sessionOutput(session);
                    if (output == null || output.isBlank()) throw new ServiceUnavailableException("LOCAL_SYNC_AI_EMPTY", "AI 未返回建议");
                    if (output.getBytes(StandardCharsets.UTF_8).length > MAX_TEXT_BYTES) {
                        throw new ServiceUnavailableException("LOCAL_SYNC_AI_TOO_LARGE", "AI 建议超过 1 MiB 安全上限");
                    }
                    return output;
                }
                if (status.failed()) throw new ServiceUnavailableException("LOCAL_SYNC_AI_FAILED", status.detail());
                TimeUnit.MILLISECONDS.sleep(250);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                try { openCode.abort(session); } catch (RuntimeException ignored) { }
                throw new ServiceUnavailableException("LOCAL_SYNC_AI_INTERRUPTED", "AI 建议生成被中断");
            }
        }
        try { openCode.abort(session); } catch (RuntimeException ignored) { }
        throw new ServiceUnavailableException("LOCAL_SYNC_AI_TIMEOUT", "AI 建议生成超时");
    }

    private String nullToEmpty(String value) { return value == null ? "" : value; }

    private LocalSyncConflictFileRow copyFile(LocalSyncConflictFileRow file, String resolution,
                                               String resolvedContent, String aiSuggestion,
                                               String aiSuggestionHash, String updatedAt) {
        return new LocalSyncConflictFileRow(file.id(), file.sessionId(), file.path(), file.sourcePath(), file.taskPath(),
                file.changeType(), file.contentType(), file.baseHash(), file.sourceHash(), file.taskHash(),
                file.baseMode(), file.sourceMode(), file.taskMode(), file.baseContent(), file.sourceContent(),
                file.taskContent(), file.mergedContent(), resolution, resolvedContent, aiSuggestion,
                aiSuggestionHash, file.externalDir(), file.createdAt(), updatedAt, file.version());
    }

    public record SessionView(String id, String taskId, String state, String sourceRoot, String sourceHead,
                              String taskCommit, String baselineCommit, int conflictCount, int resolvedCount,
                              String errorMessage, String backupDir, String verificationEvidence,
                              String createdAt, String updatedAt, long version) { }
    public record FileSummary(String path, String sourcePath, String taskPath, String changeType,
                              String contentType, String resolution, boolean resolved, boolean hasAiSuggestion,
                              String baseHash, String sourceHash, String taskHash, long version) { }
    public record FileContent(String path, String contentType, String baseContent, String sourceContent,
                              String taskContent, String mergedContent, String baseHash, String sourceHash,
                              String taskHash, String resolution, String aiSuggestion, boolean aiEligible,
                              long version) { }
    public record ResolutionRequest(String path, String resolution, String content, long expectedVersion) { }
    public record AiSuggestionRequest(String path, long expectedVersion) { }
    public record AiSuggestion(String path, String suggestion, boolean automaticallySelected, long version) { }
    public record ApplyRequest(boolean confirmed, long expectedVersion) { }
    private record RawChange(String oldMode, String newMode, String oldSha, String newSha, String status,
                             String oldPath, String newPath) {
        private String displayPath() { return newPath; }
    }
    private record BuiltFile(LocalSyncConflictFileRow row, long storedBytes) { }
    private record MergeChoice(String resolution, String mergedText, String resolvedText, byte[] bytes) { }
    private record ThreeWay(boolean clean, String content) { }
    private record FileSnapshot(boolean exists, byte[] bytes, String hash, String mode, boolean unsafe) { }
    private record SourceSnapshot(byte[] primary, String primaryMode, String combinedHash, String combinedMode,
                                  boolean unsafe, boolean targetExists) { }
    public record RecoveryLog(String backupDir, List<BackupEntry> entries) { }
    public record BackupEntry(String path, boolean existed, String mode, String hash, String backupFile) { }
    public record VerificationEvidence(boolean passed, List<VerificationCheck> checks) { }
    public record VerificationCheck(String type, String path, boolean passed, String summary) { }
    private static final class VerificationFailure extends Exception {
        private final String evidence;
        private VerificationFailure(String evidence) { super("发布验证失败，已启动自动恢复"); this.evidence = evidence; }
    }
}
