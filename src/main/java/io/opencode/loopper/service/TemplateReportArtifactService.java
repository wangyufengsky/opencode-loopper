package io.opencode.loopper.service;

import io.opencode.loopper.domain.TaskFailure;
import io.opencode.loopper.persistence.*;
import io.opencode.loopper.template.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/** Freezes report bytes before writing them; interrupted materialization is replayed from immutable artifacts. */
@Service
public final class TemplateReportArtifactService {
    private final LoopperMapper mapper;
    private final TemplateWorkspaceService workspace;
    private final TemplateRunEvidenceService evidence;
    private final TransactionTemplate transactions;
    private final ObjectMapper json;

    TemplateReportArtifactService(LoopperMapper mapper, TemplateWorkspaceService workspace, TemplateRunEvidenceService evidence,
                                  ObjectMapper json, PlatformTransactionManager manager) {
        this.mapper = mapper; this.workspace = workspace; this.evidence = evidence; this.json = json;
        this.transactions = new TransactionTemplate(manager);
    }

    public void publish(AttemptRow attempt, TemplateAnalysis.Accepted accepted) {
        TaskRow task = mapper.findTask(attempt.taskId()).orElseThrow();
        var run = evidence.require(task.id());
        var snapshot = evidence.read(run);
        var report = TemplateReportCompiler.compile(TemplateTaskDefinition.valueOf(run.templateId()),
                mapper.findProject(task.projectId()).orElseThrow().name(), snapshot, accepted);
        var items = new ArrayList<TaskArtifactRow>();
        report.documents().forEach(document -> items.add(row(task, attempt, run.repairRound(), "TEMPLATE_REPORT", document.path(), "text/markdown", document.markdown())));
        items.add(row(task, attempt, run.repairRound(), "TEMPLATE_ANALYSIS", "analysis.json", "application/json", json.writeValueAsString(accepted)));
        items.add(row(task, attempt, run.repairRound(), "TEMPLATE_JUDGE_EVIDENCE", "report-review-evidence.json", "application/json",
                json.writeValueAsString(Map.of("contract", evidence.contract(task.id()), "source", snapshot,
                        "analysis", accepted, "ranking", report.ranking(), "documents", report.documents()))));
        transactions.executeWithoutResult(ignored -> {
            if (!mapper.findTask(task.id()).orElseThrow().state().equals("RUNNING")
                    || !mapper.findAttempt(attempt.id()).orElseThrow().state().equals("RUNNING")) {
                throw new ConflictException("TEMPLATE_ARTIFACT_OWNER_CHANGED", "报告生成期间任务状态已改变");
            }
            List<TaskArtifactRow> stored = artifacts(task.id(), attempt.id());
            for (var item : items) {
                var previous = stored.stream().filter(value -> value.kind().equals(item.kind()) && value.name().equals(item.name())).findFirst().orElse(null);
                if (previous == null) mapper.insertTaskArtifact(item);
                else if (!previous.content().equals(item.content()) && !(item.contentType().equals("application/json")
                        && json.readTree(previous.content()).equals(json.readTree(item.content())))) throw new ConflictException("TEMPLATE_ARTIFACT_CHANGED", "冻结报告内容不一致");
            }
        });
        materialize(task, attempt.id());
    }

    private TaskArtifactRow row(TaskRow task, AttemptRow attempt, int repairRound, String kind, String name, String type, String content) {
        return new TaskArtifactRow(UUID.randomUUID().toString(), task.id(), attempt.id(), null, kind, name, type, content,
                json.writeValueAsString(Map.of("sha256", TemplateGitEvidenceCollector.hash(content), "authority", "SERVER_COMPILED",
                        "repairRound", repairRound, "displayName", kind.equals("TEMPLATE_REPORT") ? content.lines().findFirst().orElse(name).replaceFirst("^# +", "") : name)), Instant.now().toString());
    }

    public void materialize(TaskRow task, String attemptId) {
        workspace.requireWritable(task);
        Path root = workspace.root(task), reportRoot = root.resolve("reports").resolve(attemptId);
        try {
            for (var artifact : artifacts(task.id(), attemptId)) {
                if (!artifact.kind().equals("TEMPLATE_REPORT")) continue;
                Path target = reportRoot.resolve(artifact.name()).normalize();
                if (!target.startsWith(reportRoot)) throw new TaskFailure("TEMPLATE_REPORT_PATH_INVALID", "报告路径超出任务目录");
                for (Path part = target; part != null && part.startsWith(root); part = part.getParent()) {
                    if (Files.isSymbolicLink(part)) throw new TaskFailure("TEMPLATE_REPORT_SYMLINK", "报告目录包含符号链接，已停止写入");
                }
                Files.createDirectories(target.getParent());
                if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                    if (!Files.readString(target).equals(artifact.content())) throw new TaskFailure("TEMPLATE_REPORT_FILE_CHANGED", "报告文件被外部修改，请保留文件并重新发起任务");
                } else {
                    Path pending = Files.createTempFile(target.getParent(), ".report-", ".pending");
                    Files.writeString(pending, artifact.content(), StandardCharsets.UTF_8, StandardOpenOption.TRUNCATE_EXISTING);
                    try { Files.move(pending, target, java.nio.file.StandardCopyOption.ATOMIC_MOVE); }
                    catch (java.nio.file.AtomicMoveNotSupportedException unavailable) { Files.move(pending, target); }
                }
            }
        } catch (java.io.IOException failure) {
            throw new TaskFailure("TEMPLATE_REPORT_WRITE_FAILED", "报告文件写入失败，请检查任务目录与磁盘空间");
        }
    }

    TaskEvidenceService.JudgeCandidateSource judgeSource(TaskRow task, AttemptRow attempt, String role) {
        String text = artifacts(task.id(), attempt.id()).stream().filter(row -> row.kind().equals("TEMPLATE_JUDGE_EVIDENCE"))
                .findFirst().orElseThrow(() -> new TaskFailure("TEMPLATE_REPORT_EVIDENCE_MISSING", "尚无完整报告证据可评审")).content();
        String prompt = JudgePromptPolicy.prompt(evidence.contract(task.id()).spec(), role,
                "已冻结 Git 证据并编译报告", "程序已校验完整覆盖、身份归属、评分公式和报告文件。请独立检查分析与等级是否有充分证据。",
                "以下是模板报告与对应冻结证据。评审报告质量；发现代码缺陷或低分不构成报告失败。禁止执行文件或命令。"
                        + "必须主动查找报告误报：逐项确认 finding 违反已有明确契约或支持输入，并确有错误行为。"
                        + "文档明确不支持的输入、输入类型之外的数据、仅缺少测试用例或防御性建议不是代码缺陷。"
                        + "如果报告把这些内容列为 finding，必须 REVISE 并点名删除或改为 limitations；不要因为行号存在就认可结论。"
                        + "同一根因不得重复报告。正确测试暴露错误实现时，测试断言是证据，不是测试文件的缺陷；"
                        + "若报告把测试将失败列为另一条 finding，或把另一作者的实现错误扣给测试作者，必须 REVISE。"
                        + "逐人核对摘要和每项评分理由中的文件与实际提交归属；上下文引用不能转移贡献或缺陷责任。\n" + text, attempt.id());
        return new TaskEvidenceService.JudgeCandidateSource(prompt, new JudgeDecisionCompilation.EvidenceCatalog(List.of(
                new JudgeDecisionCompilation.EvidenceItem("template-report", "TEMPLATE_REPORT", "完整报告与冻结 Git 证据", TemplateGitEvidenceCollector.hash(text)))));
    }

    public List<TaskArtifactRow> artifacts(String taskId, String attemptId) {
        return mapper.listTaskArtifacts(taskId).stream().filter(row -> attemptId.equals(row.attemptId()) && row.kind().startsWith("TEMPLATE_")).toList();
    }
}
