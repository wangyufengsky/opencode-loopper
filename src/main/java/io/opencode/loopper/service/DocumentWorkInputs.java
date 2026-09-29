package io.opencode.loopper.service;

import static io.opencode.loopper.service.MachineCandidateSubmission.*;
import io.opencode.loopper.persistence.DocumentTemplateMapper;
import io.opencode.loopper.persistence.DocumentTemplateModelMapper;
import io.opencode.loopper.persistence.DocumentTemplateModelRow;
import io.opencode.loopper.template.DocumentModelInput;
import io.opencode.loopper.workflow.WorkResult;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Map;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

/** Bridges completed document roles to reusable, pinned work inputs without changing old frozen JSON. */
@Service
public final class DocumentWorkInputs {
    private final DocumentTemplateMapper documents;
    private final DocumentTemplateModelMapper models;
    private final AcceptedWorkResults results;
    private final ObjectMapper json;

    public DocumentWorkInputs(DocumentTemplateMapper documents, DocumentTemplateModelMapper models,
            AcceptedWorkResults results, ObjectMapper json) {
        this.documents = documents; this.models = models; this.results = results; this.json = json;
    }

    public DocumentModelInput freeze(String runId, DocumentModelInput input, Map<String, String> producers) {
        if (!input.workResults().isEmpty() || producers.isEmpty() || producers.size() > 2) throw invalid();
        var scope = scope(runId);
        var bindings = new ArrayList<WorkResult.Binding>();
        // Canonical ordering also makes repeated create requests produce identical input hashes.
        for (var name : producers.keySet().stream().sorted().toList()) {
            var row = completed(runId, producers.get(name), name);
            var owner = new CandidateOwnerRef(CandidateOwnerType.DOCUMENT_TEMPLATE_MODEL_RUN, row.id());
            results.verifyNative(scope, owner, row.id(), row.outputJson(), row.outputSha256());
            var accepted = results.find(scope, owner, row.id());
            String content = accepted.map(WorkResult::content).orElse(row.outputJson());
            matches(input, name, content);
            accepted.ifPresent(value -> bindings.add(value.bind(name)));
        }
        return input.withWorkResults(bindings);
    }

    public void verify(String runId, DocumentModelInput input) {
        if (input.workResults().isEmpty()) return; // Original immutable contracts have no shared input bindings.
        if (input.workResults().size() > 2) throw invalid();
        var names = new HashSet<String>();
        var scope = scope(runId);
        for (var binding : input.workResults()) {
            if (!names.add(binding.inputName()) || !binding.producerRunId().equals(binding.producerId())
                    || !binding.producerType().equals(CandidateOwnerType.DOCUMENT_TEMPLATE_MODEL_RUN.name())) throw invalid();
            var row = completed(runId, binding.producerId(), binding.inputName());
            var result = results.resolve(scope, binding);
            if (!row.outputJson().equals(result.content()) || !row.outputSha256().equals(result.reference().sha256())) throw invalid();
            matches(input, binding.inputName(), result.content());
        }
    }

    private CandidateScope scope(String runId) {
        return CandidateScope.project(documents.find(runId).orElseThrow(DocumentWorkInputs::invalid).projectId());
    }
    private DocumentTemplateModelRow completed(String runId, String id, String name) {
        var row = models.find(id).orElseThrow(DocumentWorkInputs::invalid);
        String expected = switch (name) {
            case "requirements" -> "DOCUMENT_REQUIREMENTS_V1";
            case "requirementFeedback" -> "DOCUMENT_REQUIREMENT_REVIEW_V1";
            default -> throw invalid();
        };
        if (!row.runId().equals(runId) || !row.state().equals("VALIDATED") || !row.candidateKind().equals(expected)
                || row.outputJson() == null || row.outputSha256() == null) throw invalid();
        return row;
    }
    private void matches(DocumentModelInput input, String name, String content) {
        Object value = switch (name) {
            case "requirements" -> input.requirements();
            case "requirementFeedback" -> input.requirementFeedback();
            default -> throw invalid();
        };
        if (value == null || !json.valueToTree(value).equals(json.readTree(content))) throw invalid();
    }
    private static ConflictException invalid() {
        return new ConflictException("WORK_INPUT_INVALID", "上游工作尚未完成、超出当前范围或交付物版本不一致，请检查输入来源");
    }
}
