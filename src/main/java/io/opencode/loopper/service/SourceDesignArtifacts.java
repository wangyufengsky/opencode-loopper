package io.opencode.loopper.service;

import io.opencode.loopper.domain.SourceTemplateState;
import io.opencode.loopper.persistence.*;
import io.opencode.loopper.template.*;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/** Only fully reviewed source coverage can become a server-owned final document package. */
@Service
public final class SourceDesignArtifacts {
    private final SourceTemplateAdmission admission;
    private final SourceTemplateMapper runs;
    private final SourceTemplateModelMapper models;
    private final SourceArtifactMapper artifacts;
    private final SourceDesignPlan plans;
    private final SourceArtifactFiles files;
    private final TransactionTemplate transactions;
    private final ObjectMapper json;
    public SourceDesignArtifacts(SourceTemplateAdmission admission, SourceTemplateMapper runs,
            SourceTemplateModelMapper models, SourceArtifactMapper artifacts, SourceDesignPlan plans,
            SourceArtifactFiles files, TransactionTemplate transactions, ObjectMapper json) {
        this.admission = admission; this.runs = runs; this.models = models; this.artifacts = artifacts;
        this.plans = plans; this.files = files; this.transactions = transactions; this.json = json;
    }
    public void publish(SourceTemplateRunRow run) {
        var plan = plans.require(run.id());
        var source = runs.files(run.id());
        if (source.stream().anyMatch(f -> f.target() == 1 && SourceTreeCapture.unresolved(f.exclusion())))
            throw new BadRequestException("SOURCE_COVERAGE_INCOMPLETE", "部分目标源码无法读取或解析，文档包尚未完成；请查看逐文件原因");
        int generation = models.progress(run.id()).orElseThrow().generation();
        var drafts = models.current(run.id(), "SOURCE_DETAILED_DESIGN_V1", generation);
        var reviews = models.current(run.id(), "SOURCE_DESIGN_REVIEW_V1", generation);
        if (drafts.size() != plan.batches().size() || reviews.size() != drafts.size()) throw SourceTemplateAdmission.conflict();
        var output = new LinkedHashMap<String, String>();
        var coverage = new TreeMap<String, List<String>>();
        var index = new StringBuilder("# 详细设计总览\n\n源码快照：`")
                .append(json.readValue(run.snapshotJson(), SourceSnapshot.class).manifestSha256()).append("`\n\n");
        for (var draft : drafts) {
            var review = reviews.stream().filter(r -> r.ordinal() == draft.ordinal()).findFirst().orElseThrow();
            var reviewInput = json.readValue(review.inputJson(), SourceDesign.Input.class);
            if (!draft.state().equals("VALIDATED") || !review.state().equals("VALIDATED")
                    || !reviewInput.draftModelId().equals(draft.id()) || !reviewInput.draftSha256().equals(draft.outputSha256())
                    || !json.readValue(review.outputJson(), SourceDesign.Review.class).verdict().equals("PASS"))
                throw new BadRequestException("SOURCE_REVIEW_INCOMPLETE", "文档尚未全部通过独立复核");
            var document = json.readValue(draft.outputJson(), SourceDesign.Candidate.class);
            index.append("## ").append(SourceDesignMarkdown.text(document.title())).append("\n\n")
                    .append(SourceDesignMarkdown.text(document.summary())).append("\n\n");
            for (var section : document.sections()) {
                String name = "module-" + (draft.ordinal() + 1) + "-" + section.key() + ".md";
                var body = new StringBuilder("# ").append(SourceDesignMarkdown.text(section.title())).append("\n\n")
                        .append(section.markdown()).append("\n\n## 源码依据\n\n");
                for (var ref : section.references()) {
                    body.append("- `").append(SourceDesignMarkdown.text(ref.path())).append("`，行 ")
                            .append(ref.startLine()).append("–").append(ref.endLine()).append("，SHA-256：`")
                            .append(ref.sha256()).append("`\n\n");
                    ref.quote().lines().forEach(line -> body.append("    ").append(line).append("\n"));
                    body.append("\n");
                }
                if (!document.limitations().isEmpty()) {
                    body.append("## 未知事项与局限\n\n");
                    document.limitations().forEach(item -> body.append("- ").append(SourceDesignMarkdown.text(item)).append("\n"));
                }
                body.append("\n[返回总览](overview.md)\n");
                output.put(name, body.toString());
                index.append("- [").append(SourceDesignMarkdown.text(section.title())).append("](").append(name).append(")\n");
                section.paths().forEach(path -> coverage.computeIfAbsent(path, ignored -> new ArrayList<>()).add(name));
            }
            index.append("\n");
        }
        var expected = source.stream().filter(f -> f.target() == 1 && f.exclusion() == null).map(SourceTemplateMapper.File::path).toList();
        if (!coverage.keySet().equals(new HashSet<>(expected))) throw SourceTemplateAdmission.conflict();
        var list = new StringBuilder("# 源码覆盖清单\n\n| 源码 | 结果 | 文档或原因 |\n| --- | --- | --- |\n");
        for (var file : source) if (file.target() == 1) {
            list.append("| ").append(SourceDesignMarkdown.text(file.path())).append(" | ");
            if (file.exclusion() != null) list.append("排除 | ").append(SourceDesignMarkdown.text(file.exclusion()));
            else list.append("已复核 | ").append(String.join("、", coverage.get(file.path()).stream().map(name -> "[" + name + "](" + name + ")").toList()));
            list.append(" |\n");
        }
        list.append("\n[返回总览](overview.md)\n");
        index.append("[源码覆盖清单](coverage.md)\n");
        output.put("overview.md", index.toString()); output.put("coverage.md", list.toString());
        if (output.values().stream().mapToLong(text -> text.getBytes(StandardCharsets.UTF_8).length).sum() > 64L * 1024 * 1024)
            throw new BadRequestException("SOURCE_ARTIFACT_LIMIT", "文档包超过 64 MiB，保留已复核结果等待处理");
        transactions.executeWithoutResult(ignored -> {
            var current = admission.require(run.id());
            if (!current.state().equals("REPORTING") || current.version() != run.version()) throw SourceTemplateAdmission.conflict();
            var stored = artifacts.all(run.id());
            String now = Instant.now().toString();
            output.forEach((name, content) -> {
                var previous = stored.stream().filter(a -> a.name().equals(name)).findFirst();
                if (previous.isPresent()) {
                    if (!previous.get().content().equals(content)) throw SourceTemplateAdmission.conflict();
                } else artifacts.insert(new SourceArtifactMapper.Artifact(UUID.randomUUID().toString(), run.id(), name,
                        "DETAILED_DESIGN", content, DocumentModelStore.hash(content), now));
            });
            coverage.forEach((path, sections) -> runs.coverageResult(run.id(), path, "REVIEWED",
                    json.writeValueAsString(Map.of("documents", sections)), now));
        });
        files.materialize(run.id());
        admission.transition(admission.require(run.id()), SourceTemplateState.COMPLETED, null, null, null);
    }
}
