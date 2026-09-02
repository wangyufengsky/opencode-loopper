package io.opencode.loopper.service;

import org.springframework.stereotype.Component;

/** Adapts the persisted Judge text protocol without accepting model-owned evidence binding. */
@Component
final class JudgeDecisionLegacyAdapter {
    private final TaskJudgeDecisionParser parser;
    private final JudgeDecisionCompilation compilation;

    JudgeDecisionLegacyAdapter(AiOutputExtractor extractor, JudgeDecisionCompilation compilation) {
        this.parser = new TaskJudgeDecisionParser(extractor);
        this.compilation = compilation;
    }

    JudgeDecisionCompilation.Result compile(JudgeDecisionCompilation.Input input, String rawOutput) {
        TaskJudgeDecisionParser.Decision parsed = parser.parse(rawOutput);
        JudgeDecisionCompilation.Candidate candidate = new JudgeDecisionCompilation.Candidate(
                JudgeDecisionCompilation.CONTRACT_VERSION, input.role(), parsed.verdict(), parsed.reason(),
                input.evidenceCatalog().items().stream().map(JudgeDecisionCompilation.EvidenceItem::id).toList());
        return compilation.compile(input, candidate);
    }
}
