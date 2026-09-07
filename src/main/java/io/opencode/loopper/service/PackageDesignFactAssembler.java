package io.opencode.loopper.service;

import static io.opencode.loopper.service.DesignerAcceptancePlanning.*;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

/** Converts validated semantic fields directly to facts; rendered text supplies audit provenance only. */
final class PackageDesignFactAssembler {
    Catalog assemble(PackageDesignCompilation.Input input, PackageDesignCandidateDocument candidate,
                     PackageDesignMarkdownRenderer.Projection projection) {
        List<Fact> facts = new ArrayList<>();
        for (var item : candidate.deliverables()) {
            add(facts, projection, item.key(), FactKind.valueOf(item.kind()), item.target(), null, null, null, null,
                    ("SCOPE".equals(item.kind()) ? "范围内：" : "交付：") + item.description());
        }
        for (var item : candidate.scenarios()) {
            add(facts, projection, item.key(), FactKind.SCENARIO, item.title(), item.precondition(), item.action(),
                    item.observableResult(), item.invariant(), null);
        }
        for (var item : candidate.reviews()) {
            add(facts, projection, item.key(), FactKind.REVIEW, item.title(), null, null, null, null,
                    item.criteria() + "；仅人工原因：" + item.humanOnlyReason());
        }
        for (var item : candidate.requirements()) {
            add(facts, projection, item.key(), FactKind.POLICY, "验收约束", null, null, null, null, item.statement());
        }
        List<StageHint> stages = new ArrayList<>();
        for (var item : candidate.stages()) {
            add(facts, projection, item.key(), FactKind.DEPENDENCY, item.title(), null, null, null, null,
                    "前置阶段：" + String.join("；", item.dependencies()));
            stages.add(new StageHint(item.title(), item.objective(), item.includes(), item.dependencies(),
                    List.of(), List.of(), List.of(), item.key()));
        }
        return new Catalog(CONTRACT_VERSION_V7, input.workPackage().packageId(),
                input.workPackage().designRevision(), sha256(projection.markdown()), true,
                facts, stages, List.of());
    }

    private void add(List<Fact> facts, PackageDesignMarkdownRenderer.Projection projection, String key,
                     FactKind kind, String title, String condition, String action, String expected,
                     String invariant, String detail) {
        var source = projection.sources().get(PackageDesignMarkdownRenderer.key(key));
        if (source == null) throw new IllegalStateException("Rendered candidate provenance is missing");
        facts.add(new Fact(facts.size(), kind, title, condition, action, expected, invariant, detail,
                source.ref(), source.excerpt(), sha256(source.excerpt()), key));
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
