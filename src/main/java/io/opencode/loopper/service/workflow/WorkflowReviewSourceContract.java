package io.opencode.loopper.service.workflow;

import io.opencode.loopper.persistence.*;
import io.opencode.loopper.runtime.DurableCommandProtocol;
import io.opencode.loopper.service.*;
import io.opencode.loopper.template.SnapshotReview;
import io.opencode.loopper.workflow.*;
import java.io.IOException;
import java.time.Instant;
import java.util.*;
import org.springframework.stereotype.Component;

/** Database-only source identity and accepted delivery; all capture I/O precedes the final transaction. */
@Component
public final class WorkflowReviewSourceContract {
    private final WorkflowReviewMapper mapper;
    private final LoopperMapper projects;
    private final WorkflowEncoding encoding;
    public WorkflowReviewSourceContract(WorkflowReviewMapper mapper, LoopperMapper projects, WorkflowEncoding encoding) { this.mapper = mapper; this.projects = projects; this.encoding = encoding; }
    public record Context(WorkflowReviewMapper.Snapshot row, GitReviewJobProtocol.Input input, int timeoutSeconds) { }
    public boolean supports(WorkflowGraph.Node node) { return WorkflowReviewSource.MODULE.equals(node.moduleId()); }
    public void validate(WorkflowGraph.Node node, WorkflowDelivery.Inputs inputs) {
        try { WorkflowReviewSource.require(node); selection(text(inputs, "branch")); if (WorkflowReviewSource.mode(node) == SnapshotReview.Mode.DATE_INCREMENTAL) dates(inputs); }
        catch (RuntimeException invalid) { throw new BadRequestException("WORKFLOW_REVIEW_SOURCE_PARAMETERS", "请选择明确分支和审查模式；日期增量需填写有效的开始、结束日期，全面审查不携带日期输入。"); }
    }
    public void admit(WorkflowExecutionRows.Attempt attempt, WorkflowNodeActions.Admission admission) {
        if (!supports(admission.definition())) return;
        var owner = admission.owner(); var inputs = admission.inputs(); String branch = text(inputs, "branch");
        if (mapper.find(attempt.nodeRunId()).isPresent()) { context(attempt, admission.definition(), inputs); return; }
        var project = projects.findProject(owner.projectId()).orElseThrow(WorkflowCommands::conflict);
        var input = request(admission.definition(), inputs, attempt.nodeRunId(), project.rootPath(), owner.projectId());
        if (mapper.insert(new WorkflowReviewMapper.Snapshot(attempt.nodeRunId(), owner.id(), owner.projectId(), branch, encoding.encode(input), hash(input), null, null, Instant.now().toString())) != 1)
            throw WorkflowCommands.conflict();
    }
    public Context context(WorkflowExecutionRows.Attempt attempt, WorkflowGraph.Node node, WorkflowDelivery.Inputs inputs) {
        if (!supports(node)) return null; validate(node, inputs);
        var row = mapper.find(attempt.nodeRunId()).orElseThrow(WorkflowCommands::conflict); var input = encoding.decode(row.inputJson(), GitReviewJobProtocol.Input.class);
        var expected = request(node, inputs, attempt.nodeRunId(), input.source().projectPath(), row.projectId());
        if (!input.equals(expected) || !row.branchId().equals(text(inputs, "branch")) || !row.inputSha256().equals(hash(input))) throw WorkflowCommands.conflict();
        return new Context(row, input, WorkflowReviewSource.require(node));
    }
    public WorkflowCommandContract.Evaluation evaluate(WorkflowCommandStore.Context context, DurableCommandProtocol.Result result, WorkflowReviewSource.Manifest manifest) {
        var review = context.review(); boolean success = result.successful() && manifest != null;
        var values = new LinkedHashMap<String, WorkflowDelivery.Value>(); var report = new LinkedHashMap<String, Object>();
        report.put("version", 1); report.put("type", WorkflowReviewSource.TYPE); report.put("complete", success); report.put("branchId", review.row().branchId());
        report.put("mode", review.input().mode()); report.put("startDate", review.input().startDate()); report.put("endDate", review.input().endDate()); report.put("timezone", "Asia/Shanghai");
        if (success) {
            if (manifest.version() != 1 || !manifest.type().equals(WorkflowReviewSource.TYPE) || !manifest.nodeRunId().equals(review.row().nodeRunId())
                    || !manifest.branchId().equals(review.row().branchId()) || manifest.mode() != review.input().mode()
                    || !Objects.equals(manifest.startDate(), review.input().startDate()) || !Objects.equals(manifest.endDate(), review.input().endDate())
                    || manifest.files().size() != manifest.unitCount() + 1) throw WorkflowCommands.conflict();
            String body = encoding.encode(manifest), sha = WorkflowEncoding.hash(body); var current = mapper.find(review.row().nodeRunId()).orElseThrow(WorkflowCommands::conflict);
            if (current.manifestJson() == null) { if (mapper.manifest(current.nodeRunId(), body, sha) != 1) throw WorkflowCommands.conflict(); }
            else if (!current.manifestJson().equals(body) || !current.manifestSha256().equals(sha)) throw WorkflowCommands.conflict();
            values.put("source", value(WorkflowGraph.DataKind.DOCUMENT, new WorkflowReviewSource.Reference(1, WorkflowReviewSource.TYPE, current.nodeRunId(), sha)));
            report.put("sourceSha", manifest.sourceSha()); report.put("baselineSha", manifest.baselineSha()); report.put("targetSha", manifest.targetSha());
            report.put("projectPrefix", manifest.projectPrefix()); report.put("capturedAt", manifest.capturedAt()); report.put("noChanges", manifest.noChanges());
            report.put("nonMonotonic", manifest.nonMonotonic()); report.put("unitCount", manifest.unitCount()); report.put("excludedCount", manifest.excludedCount());
        } else {
            String code = result.timedOut() ? "TEMPLATE_GIT_TIMEOUT" : "WORKFLOW_REVIEW_CAPTURE_FAILED";
            var match = java.util.regex.Pattern.compile("LOOPPER_GIT_REVIEW_FAILURE:([A-Z][A-Z0-9_]{0,100})").matcher(result.output()); if (match.find()) code = match.group(1); report.put("code", code);
        }
        String summary = success ? "版本审查资料已固定，后续节点读取同一份版本和代码证据。" : "版本审查资料采集未完成，请查看原因后恢复原节点。";
        values.put("summary", value(WorkflowGraph.DataKind.TEXT, summary)); values.put("report", value(WorkflowGraph.DataKind.JSON, report));
        return new WorkflowCommandContract.Evaluation(new WorkflowDelivery(summary, null, values), success);
    }
    private WorkflowDelivery.Value value(WorkflowGraph.DataKind kind, Object content) { return new WorkflowDelivery.Value(kind, encoding.decode(encoding.encode(content), tools.jackson.databind.JsonNode.class)); }
    private static GitReviewJobProtocol.Input request(WorkflowGraph.Node node, WorkflowDelivery.Inputs inputs, String nodeId, String path, String project) {
        var branch = selection(text(inputs, "branch")); var mode = WorkflowReviewSource.mode(node);
        return new GitReviewJobProtocol.Input(new GitSnapshotJobProtocol.Input(nodeId, path, branch.ref(), branch.remote()), project, mode,
                mode == SnapshotReview.Mode.FULL ? null : text(inputs, "startDate"), mode == SnapshotReview.Mode.FULL ? null : text(inputs, "endDate"));
    }
    private static String text(WorkflowDelivery.Inputs inputs, String name) {
        var value = inputs.values().stream().filter(i -> i.name().equals(name)).findFirst().orElseThrow(WorkflowCommands::conflict).content();
        if (!value.isString()) throw WorkflowCommands.conflict(); return value.asString();
    }
    private static void dates(WorkflowDelivery.Inputs inputs) {
        String start = text(inputs, "startDate"), end = text(inputs, "endDate");
        if (!start.matches("\\d{4}-\\d{2}-\\d{2}") || !end.matches("\\d{4}-\\d{2}-\\d{2}")) throw new IllegalArgumentException();
        io.opencode.loopper.template.TemplateDateRange.parse(start, end, java.time.Clock.systemUTC());
    }
    private static GitCommitReader.Selection selection(String id) {
        if (id.startsWith("local:refs/heads/")) return new GitCommitReader.Selection(id.substring(6), null);
        var match = java.util.regex.Pattern.compile("remote:([^:]+):(refs/heads/.+)").matcher(id); if (match.matches()) return new GitCommitReader.Selection(match.group(2), match.group(1)); throw new IllegalArgumentException();
    }
    private static String hash(GitReviewJobProtocol.Input input) { try { return DurableCommandProtocol.hash(GitReviewJobProtocol.input(input)); } catch (IOException invalid) { throw WorkflowCommands.conflict(); } }
}
