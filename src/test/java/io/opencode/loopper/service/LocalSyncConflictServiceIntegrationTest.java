package io.opencode.loopper.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.opencode.loopper.LoopperApplication;
import io.opencode.loopper.domain.LoopSpec;
import io.opencode.loopper.persistence.LocalSyncConflictSessionRow;
import io.opencode.loopper.persistence.LoopDraftRow;
import io.opencode.loopper.persistence.LoopperMapper;
import io.opencode.loopper.persistence.ProjectRow;
import io.opencode.loopper.persistence.TaskRow;
import io.opencode.loopper.runtime.FakeOpenCodeClient;
import io.opencode.loopper.runtime.GitWorktreeManager;
import io.opencode.loopper.runtime.OpenCodeClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.nio.file.attribute.PosixFilePermission;
import java.security.MessageDigest;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(classes = LoopperApplication.class, properties = {"loopper.opencode.mode=fake", "loopper.monitor-delay=1h"})
class LocalSyncConflictServiceIntegrationTest {
    @Autowired private Flyway flyway;
    @Autowired private ProjectService projects;
    @Autowired private LoopDraftService drafts;
    @Autowired private TaskService tasks;
    @Autowired private LocalSyncConflictService conflicts;
    @Autowired private LoopperMapper mapper;
    @Autowired private GitWorktreeManager worktrees;
    @Autowired private OpenCodeClient openCode;
    @Autowired private ObjectMapper json;
    @TempDir Path temp;

    @BeforeEach
    void resetDatabase() {
        flyway.clean();
        flyway.migrate();
        ((FakeOpenCodeClient) openCode).reset();
    }

    @Test
    void deterministicMergeCombinesIndependentSourceAndTaskEdits() throws Exception {
        Path source = repository("alpha\nmiddle\nomega\n");
        Files.writeString(source.resolve("delete-me.txt"), "obsolete\n");
        run(source, "git", "add", ".");
        run(source, "git", "commit", "-m", "deletion fixture");
        TaskRow task = task(source, verifier("git", "status", "--short"));
        Files.writeString(source.resolve("README.md"), "source-alpha\nmiddle\nomega\n");
        Files.writeString(Path.of(task.worktreePath()).resolve("README.md"), "alpha\nmiddle\ntask-omega\n");
        Files.delete(Path.of(task.worktreePath()).resolve("delete-me.txt"));
        commitTask(task);

        var session = conflicts.createOrRefresh(task.id());
        var file = conflicts.files(task.id(), session.id()).getFirst();
        var deletion = mapper.listLocalSyncConflictFiles(session.id()).stream()
                .filter(candidate -> candidate.path().equals("delete-me.txt")).findFirst().orElseThrow();

        assertThat(session.state()).isEqualTo("READY");
        assertThat(file.resolution()).isEqualTo("AUTO");
        assertThat(deletion.taskMode()).isEqualTo("MISSING");
        assertThat(deletion.resolution()).isEqualTo("AUTO");
        assertThat(conflicts.apply(task.id(), session.id(),
                new LocalSyncConflictService.ApplyRequest(true, session.version())).state()).isEqualTo("APPLIED");
        assertThat(Files.readString(source.resolve("README.md")))
                .isEqualTo("source-alpha\nmiddle\ntask-omega\n");
        assertThat(source.resolve("delete-me.txt")).doesNotExist();
    }

    @Test
    void multipleTextConflictRegionsRemainEditableInsteadOfBeingTreatedAsMergeToolFailure() throws Exception {
        String base = java.util.stream.IntStream.rangeClosed(1, 24)
                .mapToObj(index -> "line-" + index).collect(java.util.stream.Collectors.joining("\n", "", "\n"));
        Path source = repository(base);
        TaskRow task = task(source, verifier("git", "status", "--short"));
        String sourceText = base.replace("line-2\n", "source-2\n").replace("line-22\n", "source-22\n");
        String taskText = base.replace("line-2\n", "task-2\n").replace("line-22\n", "task-22\n");
        Files.writeString(source.resolve("README.md"), sourceText);
        Files.writeString(Path.of(task.worktreePath()).resolve("README.md"), taskText);
        commitTask(task);

        var session = conflicts.createOrRefresh(task.id());
        var content = conflicts.content(task.id(), session.id(), "README.md");

        assertThat(session.state()).isEqualTo("OPEN");
        assertThat(content.resolution()).isNull();
        assertThat(content.mergedContent()).contains("<<<<<<< 源项目", "source-2", "task-2", "source-22", "task-22");
        assertThat(content.mergedContent().split("<<<<<<< 源项目", -1)).hasSize(3);
        assertThatThrownBy(() -> conflicts.saveResolution(task.id(), session.id(),
                new LocalSyncConflictService.ResolutionRequest("README.md", "MANUAL",
                        content.mergedContent(), content.version())))
                .isInstanceOfSatisfying(BadRequestException.class,
                        failure -> assertThat(failure.code()).isEqualTo("LOCAL_SYNC_UNRESOLVED_MARKERS"))
                .hasMessageContaining("冲突标记");
    }

    @Test
    void legacyManualResolutionWithConflictMarkersIsReopenedBeforeAnySourceWrite() throws Exception {
        Path source = repository("base\n");
        TaskRow task = task(source, verifier("git", "status", "--short"));
        Files.writeString(source.resolve("README.md"), "source\n");
        Files.writeString(Path.of(task.worktreePath()).resolve("README.md"), "task\n");
        commitTask(task);
        var session = conflicts.createOrRefresh(task.id());
        var file = mapper.listLocalSyncConflictFiles(session.id()).getFirst();
        String unresolved = "<<<<<<< 源项目\nsource\n=======\ntask\n>>>>>>> 任务\n";
        var legacyFile = new io.opencode.loopper.persistence.LocalSyncConflictFileRow(
                file.id(), file.sessionId(), file.path(), file.sourcePath(), file.taskPath(), file.changeType(),
                file.contentType(), file.baseHash(), file.sourceHash(), file.taskHash(), file.baseMode(),
                file.sourceMode(), file.taskMode(), file.baseContent(), file.sourceContent(), file.taskContent(),
                file.mergedContent(), "MANUAL", unresolved, file.aiSuggestion(), file.aiSuggestionHash(),
                file.externalDir(), file.createdAt(), Instant.now().toString(), file.version());
        assertThat(mapper.updateLocalSyncConflictFile(legacyFile)).isEqualTo(1);
        LocalSyncConflictSessionRow stored = mapper.findLocalSyncConflictSession(session.id()).orElseThrow();
        LocalSyncConflictSessionRow incorrectlyReady = new LocalSyncConflictSessionRow(
                stored.id(), stored.taskId(), stored.sourceRoot(), stored.baselineCommit(), stored.taskCommit(),
                stored.sourceHead(), "READY", stored.conflictCount(), stored.conflictCount(), stored.backupDir(),
                stored.recoveryLogJson(), stored.verificationEvidenceJson(), null, stored.createdAt(),
                Instant.now().toString(), stored.version());
        assertThat(mapper.updateLocalSyncConflictSession(incorrectlyReady)).isEqualTo(1);
        var ready = conflicts.get(task.id(), session.id());

        assertThatThrownBy(() -> conflicts.apply(task.id(), ready.id(),
                new LocalSyncConflictService.ApplyRequest(true, ready.version())))
                .isInstanceOfSatisfying(ConflictException.class,
                        failure -> assertThat(failure.code()).isEqualTo("LOCAL_SYNC_UNRESOLVED_MARKERS"));

        var reopened = conflicts.get(task.id(), session.id());
        assertThat(reopened.state()).isEqualTo("OPEN");
        assertThat(reopened.resolvedCount()).isZero();
        assertThat(conflicts.files(task.id(), session.id()).getFirst().resolution()).isNull();
        assertThat(conflicts.content(task.id(), session.id(), "README.md").mergedContent()).isEqualTo(unresolved);
        assertThat(Files.readString(source.resolve("README.md"))).isEqualTo("source\n");
    }

    @Test
    void addAddDeleteModifyRenameAndBinaryExposeSafeResolutionChoices() throws Exception {
        Path source = repository("base\n");
        Files.writeString(source.resolve("rename-old.txt"), "rename me\n");
        Files.write(source.resolve("binary.dat"), new byte[]{0, 1, 2});
        run(source, "git", "add", ".");
        run(source, "git", "commit", "-m", "fixture files");
        TaskRow task = task(source, verifier("git", "status", "--short"));
        Path workspace = Path.of(task.worktreePath());

        Files.writeString(source.resolve("added.txt"), "source add\n");
        Files.writeString(workspace.resolve("added.txt"), "task add\n");
        Files.writeString(source.resolve("README.md"), "source kept\n");
        Files.delete(workspace.resolve("README.md"));
        run(workspace, "git", "mv", "rename-old.txt", "rename-new.txt");
        Files.write(source.resolve("binary.dat"), new byte[]{0, 3, 4});
        Files.write(workspace.resolve("binary.dat"), new byte[]{0, 5, 6});
        commitTask(task);

        var session = conflicts.createOrRefresh(task.id());
        var files = conflicts.files(task.id(), session.id());

        assertThat(files).extracting(LocalSyncConflictService.FileSummary::changeType)
                .contains("ADD_ADD", "MODIFY_DELETE", "RENAME");
        assertThat(files).filteredOn(file -> file.path().equals("binary.dat"))
                .singleElement().extracting(LocalSyncConflictService.FileSummary::contentType).isEqualTo("BINARY");
        var binary = conflicts.content(task.id(), session.id(), "binary.dat");
        assertThat(binary.baseContent()).isNull();
        assertThatThrownBy(() -> conflicts.saveResolution(task.id(), session.id(),
                new LocalSyncConflictService.ResolutionRequest("binary.dat", "MANUAL", "unsafe", binary.version())))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("二进制");
        assertThatThrownBy(() -> conflicts.content(task.id(), session.id(), "../.git/config"))
                .isInstanceOf(BadRequestException.class);
        Path outside = temp.resolve("outside");
        Files.createDirectories(outside);
        Files.createSymbolicLink(source.resolve("escape-link"), outside);
        assertThatThrownBy(() -> conflicts.content(task.id(), session.id(), "escape-link/file.txt"))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("符号链接");
    }

    @Test
    void sourceFileChangesMakeTheSessionStaleBeforeAnyWrite() throws Exception {
        Path source = repository("base\n");
        TaskRow task = task(source, verifier("git", "status", "--short"));
        Files.writeString(source.resolve("README.md"), "source\n");
        Files.writeString(Path.of(task.worktreePath()).resolve("README.md"), "task\n");
        commitTask(task);
        var session = conflicts.createOrRefresh(task.id());
        var file = conflicts.content(task.id(), session.id(), "README.md");
        conflicts.saveResolution(task.id(), session.id(),
                new LocalSyncConflictService.ResolutionRequest("README.md", "TASK", null, file.version()));
        session = conflicts.get(task.id(), session.id());
        Files.writeString(source.resolve("README.md"), "changed again\n");

        LocalSyncConflictService.SessionView ready = session;
        assertThatThrownBy(() -> conflicts.apply(task.id(), ready.id(),
                new LocalSyncConflictService.ApplyRequest(true, ready.version())))
                .isInstanceOfSatisfying(ConflictException.class,
                        failure -> assertThat(failure.code()).isEqualTo("LOCAL_SYNC_STALE"));
        assertThat(conflicts.get(task.id(), ready.id()).state()).isEqualTo("STALE");
        assertThat(Files.readString(source.resolve("README.md"))).isEqualTo("changed again\n");
    }

    @Test
    void sourceHeadChangesMakeTheSessionStaleEvenWhenTaskPathsAreUnchanged() throws Exception {
        Path source = repository("base\n");
        TaskRow task = task(source, verifier("git", "status", "--short"));
        Files.writeString(source.resolve("README.md"), "source\n");
        Files.writeString(Path.of(task.worktreePath()).resolve("README.md"), "task\n");
        commitTask(task);
        var session = conflicts.createOrRefresh(task.id());
        var file = conflicts.content(task.id(), session.id(), "README.md");
        conflicts.saveResolution(task.id(), session.id(), new LocalSyncConflictService.ResolutionRequest(
                "README.md", "TASK", null, file.version()));
        session = conflicts.get(task.id(), session.id());
        Files.writeString(source.resolve("unrelated-commit.txt"), "new source head\n");
        run(source, "git", "add", "unrelated-commit.txt");
        run(source, "git", "commit", "-m", "advance source head");

        LocalSyncConflictService.SessionView ready = session;
        assertThatThrownBy(() -> conflicts.apply(task.id(), ready.id(),
                new LocalSyncConflictService.ApplyRequest(true, ready.version())))
                .isInstanceOf(ConflictException.class).hasMessageContaining("HEAD");
        assertThat(conflicts.get(task.id(), ready.id()).state()).isEqualTo("STALE");
    }

    @Test
    void taskHeadChangesMakeTheSessionStaleBeforeAnyWrite() throws Exception {
        Path source = repository("base\n");
        TaskRow task = task(source, verifier("git", "status", "--short"));
        Files.writeString(source.resolve("README.md"), "source\n");
        Path workspace = Path.of(task.worktreePath());
        Files.writeString(workspace.resolve("README.md"), "task\n");
        commitTask(task);
        var session = conflicts.createOrRefresh(task.id());
        var file = conflicts.content(task.id(), session.id(), "README.md");
        conflicts.saveResolution(task.id(), session.id(), new LocalSyncConflictService.ResolutionRequest(
                "README.md", "TASK", null, file.version()));
        session = conflicts.get(task.id(), session.id());
        Files.writeString(workspace.resolve("README.md"), "newer task commit\n");
        commitTask(task);

        LocalSyncConflictService.SessionView ready = session;
        assertThatThrownBy(() -> conflicts.apply(task.id(), ready.id(),
                new LocalSyncConflictService.ApplyRequest(true, ready.version())))
                .isInstanceOf(ConflictException.class).hasMessageContaining("任务提交已变化");
        assertThat(conflicts.get(task.id(), ready.id()).state()).isEqualTo("STALE");
        assertThat(Files.readString(source.resolve("README.md"))).isEqualTo("source\n");
    }

    @Test
    void stagedTaskPathBlocksApplyWhileUnrelatedWorkingFilesRemainUntouched() throws Exception {
        Path source = repository("base\n");
        TaskRow task = task(source, verifier("git", "status", "--short"));
        Files.writeString(source.resolve("README.md"), "source\n");
        Files.writeString(source.resolve("unrelated.txt"), "keep me\n");
        Files.writeString(Path.of(task.worktreePath()).resolve("README.md"), "task\n");
        commitTask(task);
        var session = conflicts.createOrRefresh(task.id());
        var file = conflicts.content(task.id(), session.id(), "README.md");
        conflicts.saveResolution(task.id(), session.id(),
                new LocalSyncConflictService.ResolutionRequest("README.md", "TASK", null, file.version()));
        session = conflicts.get(task.id(), session.id());
        run(source, "git", "add", "README.md");

        LocalSyncConflictService.SessionView ready = session;
        assertThatThrownBy(() -> conflicts.apply(task.id(), ready.id(),
                new LocalSyncConflictService.ApplyRequest(true, ready.version())))
                .isInstanceOf(ConflictException.class).hasMessageContaining("暂存");
        assertThat(Files.readString(source.resolve("unrelated.txt"))).isEqualTo("keep me\n");
    }

    @Test
    void failedLoopSpecVerificationRollsBackAllTaskPathsAndKeepsSolutions() throws Exception {
        Path source = repository("base\n");
        TaskRow task = task(source, verifier("false"));
        Files.writeString(source.resolve("README.md"), "source before\n");
        Files.writeString(Path.of(task.worktreePath()).resolve("README.md"), "task after\n");
        commitTask(task);
        var session = conflicts.createOrRefresh(task.id());
        var file = conflicts.content(task.id(), session.id(), "README.md");
        conflicts.saveResolution(task.id(), session.id(),
                new LocalSyncConflictService.ResolutionRequest("README.md", "TASK", null, file.version()));
        session = conflicts.get(task.id(), session.id());

        var result = conflicts.apply(task.id(), session.id(),
                new LocalSyncConflictService.ApplyRequest(true, session.version()));

        assertThat(result.state()).isEqualTo("ROLLED_BACK");
        assertThat(result.verificationEvidence()).contains("PROCESS", "exitCode", "output");
        assertThat(result.errorMessage()).contains("PROCESS[0:0]", "Process exited 1");
        assertThat(Files.readString(source.resolve("README.md"))).isEqualTo("source before\n");
        assertThat(conflicts.files(task.id(), session.id()).getFirst().resolution()).isEqualTo("TASK");
    }

    @Test
    void writeFailureRestoresEarlierWritesWithoutTouchingUnrelatedFiles() throws Exception {
        Path source = repository("base\n");
        Files.writeString(source.resolve("a.txt"), "base a\n");
        run(source, "git", "add", ".");
        run(source, "git", "commit", "-m", "write rollback fixture");
        TaskRow task = task(source, verifier("git", "status", "--short"));
        Path workspace = Path.of(task.worktreePath());
        Files.writeString(source.resolve("a.txt"), "source a\n");
        Files.writeString(source.resolve("unrelated.txt"), "preserve\n");
        Files.writeString(workspace.resolve("a.txt"), "task a\n");
        Files.createDirectories(workspace.resolve("locked"));
        Files.writeString(workspace.resolve("locked/b.txt"), "task b\n");
        commitTask(task);
        var session = conflicts.createOrRefresh(task.id());
        var a = conflicts.content(task.id(), session.id(), "a.txt");
        conflicts.saveResolution(task.id(), session.id(), new LocalSyncConflictService.ResolutionRequest(
                "a.txt", "TASK", null, a.version()));
        session = conflicts.get(task.id(), session.id());
        Path locked = source.resolve("locked");
        Files.createDirectories(locked);
        Files.setPosixFilePermissions(locked, Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_EXECUTE));
        try {
            var rolledBack = conflicts.apply(task.id(), session.id(),
                    new LocalSyncConflictService.ApplyRequest(true, session.version()));
            assertThat(rolledBack.state()).isEqualTo("ROLLED_BACK");
            assertThat(Files.readString(source.resolve("a.txt"))).isEqualTo("source a\n");
            assertThat(Files.readString(source.resolve("unrelated.txt"))).isEqualTo("preserve\n");
        } finally {
            Files.setPosixFilePermissions(locked, Set.of(PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE));
        }
    }

    @Test
    void missingRecoveryBackupEndsInRollbackFailedWithManualRecoveryLocation() throws Exception {
        Path source = repository("base\n");
        TaskRow task = task(source, verifier("./break-rollback.sh"));
        Files.writeString(source.resolve("README.md"), "source before\n");
        Files.writeString(Path.of(task.worktreePath()).resolve("README.md"), "task after\n");
        commitTask(task);
        var session = conflicts.createOrRefresh(task.id());
        var file = conflicts.content(task.id(), session.id(), "README.md");
        conflicts.saveResolution(task.id(), session.id(), new LocalSyncConflictService.ResolutionRequest(
                "README.md", "TASK", null, file.version()));
        session = conflicts.get(task.id(), session.id());
        Path script = source.resolve("break-rollback.sh");
        Path sessionData = Path.of(System.getProperty("java.io.tmpdir"), "loopper-test-data",
                "local-sync-conflicts", session.id());
        Files.writeString(script, "#!/bin/sh\nrm -f '" + sessionData + "'/backup-*/*.bin\nexit 1\n");
        Files.setPosixFilePermissions(script, Set.of(PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE));

        var failed = conflicts.apply(task.id(), session.id(),
                new LocalSyncConflictService.ApplyRequest(true, session.version()));

        assertThat(failed.state()).isEqualTo("ROLLBACK_FAILED");
        assertThat(failed.backupDir()).contains("backup-");
        assertThat(failed.errorMessage()).contains("自动恢复失败", failed.backupDir());
    }

    @Test
    void sourceRootLockRejectsConcurrentApplyForTheSameProject() throws Exception {
        Path source = repository("base\n");
        Path sleeper = source.resolve("slow-verify.sh");
        Files.writeString(sleeper, "#!/bin/sh\nsleep 1\nexit 0\n");
        Files.setPosixFilePermissions(sleeper, Set.of(PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE));
        run(source, "git", "add", ".");
        run(source, "git", "commit", "-m", "slow verifier");
        TaskRow task = task(source, verifier("./slow-verify.sh"));
        Files.writeString(source.resolve("README.md"), "source\n");
        Files.writeString(Path.of(task.worktreePath()).resolve("README.md"), "task\n");
        commitTask(task);
        var session = conflicts.createOrRefresh(task.id());
        var file = conflicts.content(task.id(), session.id(), "README.md");
        conflicts.saveResolution(task.id(), session.id(), new LocalSyncConflictService.ResolutionRequest(
                "README.md", "TASK", null, file.version()));
        session = conflicts.get(task.id(), session.id());
        LocalSyncConflictService.SessionView ready = session;
        CompletableFuture<LocalSyncConflictService.SessionView> first = CompletableFuture.supplyAsync(() ->
                conflicts.apply(task.id(), ready.id(), new LocalSyncConflictService.ApplyRequest(true, ready.version())));
        LocalSyncConflictService.SessionView active = null;
        for (int attempt = 0; attempt < 100; attempt++) {
            active = conflicts.get(task.id(), ready.id());
            if (Set.of("APPLYING", "VERIFYING").contains(active.state())) break;
            Thread.sleep(10);
        }
        assertThat(active).isNotNull();
        LocalSyncConflictService.SessionView current = active;
        assertThatThrownBy(() -> conflicts.apply(task.id(), current.id(),
                new LocalSyncConflictService.ApplyRequest(true, current.version())))
                .isInstanceOfSatisfying(ConflictException.class,
                        failure -> assertThat(failure.code()).isEqualTo("LOCAL_SYNC_SOURCE_ACTIVE"));
        assertThat(first.get(5, TimeUnit.SECONDS).state()).isEqualTo("APPLIED");
    }

    @Test
    void startupRecoveryUsesThePersistedSnapshotAndMarksTheSessionRolledBack() throws Exception {
        Path source = repository("base\n");
        TaskRow task = task(source, verifier("git", "status", "--short"));
        Files.writeString(source.resolve("README.md"), "source original\n");
        Files.writeString(Path.of(task.worktreePath()).resolve("README.md"), "task\n");
        commitTask(task);
        var session = conflicts.createOrRefresh(task.id());
        Path backupDir = temp.resolve("startup-backup");
        Files.createDirectories(backupDir);
        byte[] original = "source original\n".getBytes(StandardCharsets.UTF_8);
        Files.write(backupDir.resolve("000.bin"), original);
        LocalSyncConflictService.RecoveryLog log = new LocalSyncConflictService.RecoveryLog(backupDir.toString(),
                List.of(new LocalSyncConflictService.BackupEntry("README.md", true, "100644",
                        java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(original)), "000.bin")));
        LocalSyncConflictSessionRow row = mapper.findLocalSyncConflictSession(session.id()).orElseThrow();
        LocalSyncConflictSessionRow applying = new LocalSyncConflictSessionRow(row.id(), row.taskId(), row.sourceRoot(),
                row.baselineCommit(), row.taskCommit(), row.sourceHead(), "APPLYING", row.conflictCount(),
                row.resolvedCount(), backupDir.toString(), json.writeValueAsString(log), null, null,
                row.createdAt(), Instant.now().toString(), row.version());
        assertThat(mapper.updateLocalSyncConflictSession(applying)).isEqualTo(1);
        Files.writeString(source.resolve("README.md"), "partially applied\n");

        conflicts.recoverInterruptedApplications();

        assertThat(Files.readString(source.resolve("README.md"))).isEqualTo("source original\n");
        assertThat(conflicts.get(task.id(), session.id()).state()).isEqualTo("ROLLED_BACK");
        assertThat(backupDir).doesNotExist();
    }

    @Test
    void aiSuggestionIsPersistedButNeverSelectedAutomatically() throws Exception {
        Path source = repository("base\n");
        TaskRow task = task(source, verifier("git", "status", "--short"));
        Files.writeString(source.resolve("README.md"), "source\n");
        Files.writeString(Path.of(task.worktreePath()).resolve("README.md"), "task\n");
        commitTask(task);
        var session = conflicts.createOrRefresh(task.id());
        var file = conflicts.content(task.id(), session.id(), "README.md");
        ((FakeOpenCodeClient) openCode).setJudgeOutput("REQUIREMENT", "suggested merge\n");

        var suggestion = conflicts.suggest(task.id(), session.id(),
                new LocalSyncConflictService.AiSuggestionRequest("README.md", file.version()));

        assertThat(suggestion.automaticallySelected()).isFalse();
        assertThat(conflicts.content(task.id(), session.id(), "README.md").resolution()).isNull();
        assertThat(conflicts.content(task.id(), session.id(), "README.md").aiSuggestion())
                .isEqualTo("suggested merge\n");
    }

    @Test
    void cupXml2JavaStyleMergeKeepsBothDependenciesSyncsStateMachineAndPassesMaven() throws Exception {
        Path source = repository("fixture\n");
        Files.delete(source.resolve("README.md"));
        Files.createDirectories(source.resolve("src/main/java/demo"));
        Files.writeString(source.resolve("pom.xml"), """
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                  <modelVersion>4.0.0</modelVersion><groupId>demo</groupId><artifactId>cup</artifactId><version>1</version>
                  <properties><maven.compiler.release>17</maven.compiler.release></properties>
                </project>
                """);
        Files.writeString(source.resolve("src/main/java/demo/Main.java"), """
                package demo;
                public class Main {
                    private final String value;
                    public Main(String value) { this.value = value; }
                    public String run() { return value; }
                }
                """);
        run(source, "git", "add", ".");
        run(source, "git", "commit", "-m", "java baseline");
        TaskRow task = task(source, verifier("mvn", "-q", "test"));
        Path workspace = Path.of(task.worktreePath());
        Files.writeString(workspace.resolve("src/main/java/demo/Main.java"), """
                package demo;
                public class Main {
                    private final String value;
                    private final StateMachineFactory stateMachineFactory;
                    public Main(String value, StateMachineFactory stateMachineFactory) { this.value = value; this.stateMachineFactory = stateMachineFactory; }
                    public String run() { return stateMachineFactory.run(value); }
                }
                """);
        Files.writeString(workspace.resolve("src/main/java/demo/StateMachineFactory.java"), """
                package demo;
                public class StateMachineFactory { public String run(String value) { return value + "-state"; } }
                """);
        Files.writeString(source.resolve("src/main/java/demo/Main.java"), """
                package demo;
                public class Main {
                    private final String value;
                    private final GlobalExceptionHandler handler;
                    public Main(String value, GlobalExceptionHandler handler) { this.value = value; this.handler = handler; }
                    public String run() { return handler.handle(value); }
                }
                """);
        Files.writeString(source.resolve("src/main/java/demo/GlobalExceptionHandler.java"), """
                package demo;
                public class GlobalExceptionHandler { public String handle(String value) { return value; } }
                """);
        Files.writeString(source.resolve("local-note.txt"), "must remain unchanged\n");
        commitTask(task);

        var session = conflicts.createOrRefresh(task.id());
        assertThat(session.conflictCount()).isEqualTo(2);
        var main = conflicts.content(task.id(), session.id(), "src/main/java/demo/Main.java");
        String merged = """
                package demo;
                public class Main {
                    private final String value;
                    private final GlobalExceptionHandler handler;
                    private final StateMachineFactory stateMachineFactory;
                    public Main(String value, GlobalExceptionHandler handler, StateMachineFactory stateMachineFactory) {
                        this.value = value; this.handler = handler; this.stateMachineFactory = stateMachineFactory;
                    }
                    public String run() { return handler.handle(stateMachineFactory.run(value)); }
                }
                """;
        conflicts.saveResolution(task.id(), session.id(), new LocalSyncConflictService.ResolutionRequest(
                main.path(), "MANUAL", merged, main.version()));
        session = conflicts.get(task.id(), session.id());

        var applied = conflicts.apply(task.id(), session.id(),
                new LocalSyncConflictService.ApplyRequest(true, session.version()));

        assertThat(applied.state()).isEqualTo("APPLIED");
        assertThat(Files.readString(source.resolve("src/main/java/demo/Main.java")))
                .contains("GlobalExceptionHandler", "StateMachineFactory");
        assertThat(source.resolve("src/main/java/demo/StateMachineFactory.java")).exists();
        assertThat(Files.readString(source.resolve("local-note.txt"))).isEqualTo("must remain unchanged\n");
        assertThat(run(source, "mvn", "-q", "test")).isBlank();
    }

    private TaskRow task(Path root, LoopSpec.VerifierSpec verifier) {
        ProjectRow project = projects.create("fixture-" + System.nanoTime(), root.toString());
        LoopDraftRow draft = drafts.create(new LoopSpec("v1", project.id(), "merge local source safely", null,
                List.of(new LoopSpec.StageSpec("verify merged source", List.of(), List.of(), List.of("source"),
                        List.of(verifier))), null, null, null, null));
        TaskRow ready = drafts.confirm(draft.id(), "local sync conflict test");
        // The conflict center is retained only for legacy hidden-worktree tasks.
        // New tasks edit the registered checkout directly and therefore never
        // need to merge a separate task checkout back into that same directory.
        GitWorktreeManager.Worktree legacy = worktrees.create(root, ready.id(),
                "legacy local sync conflict test", ready.baselineCommit());
        TaskRow legacyReady = new TaskRow(ready.id(), ready.projectId(), ready.loopDraftId(), ready.title(), ready.state(),
                legacy.path().toString(), legacy.branch(), legacy.baselineCommit(), ready.createdAt(),
                Instant.now().toString(), ready.version());
        assertThat(mapper.prepareTask(legacyReady)).isEqualTo(1);
        ready = tasks.get(ready.id());
        TaskRow succeeded = new TaskRow(ready.id(), ready.projectId(), ready.loopDraftId(), ready.title(), "SUCCEEDED",
                ready.worktreePath(), ready.branchName(), ready.baselineCommit(), ready.createdAt(), Instant.now().toString(), ready.version());
        assertThat(mapper.updateTaskState(succeeded)).isEqualTo(1);
        return tasks.get(ready.id());
    }

    private LoopSpec.VerifierSpec verifier(String... argv) {
        return new LoopSpec.VerifierSpec("PROCESS", List.of(argv), null, null, null, null, null);
    }

    private void commitTask(TaskRow task) throws Exception {
        Path workspace = Path.of(task.worktreePath());
        run(workspace, "git", "add", "--all");
        run(workspace, "git", "commit", "-m", "#3032_test_local_sync_conflict");
    }

    private Path repository(String readme) throws Exception {
        Path root = temp.resolve("repository-" + System.nanoTime());
        Files.createDirectories(root);
        run(root, "git", "init", "-b", "main");
        run(root, "git", "config", "user.email", "test@example.invalid");
        run(root, "git", "config", "user.name", "Loopper Test");
        Files.writeString(root.resolve("README.md"), readme, StandardCharsets.UTF_8);
        run(root, "git", "add", ".");
        run(root, "git", "commit", "-m", "initial");
        return root;
    }

    private String run(Path directory, String... argv) throws Exception {
        Process process = new ProcessBuilder(argv).directory(directory.toFile()).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(process.waitFor()).as(String.join(" ", argv) + "\n" + output).isZero();
        return output;
    }
}
