package io.opencode.loopper.service;

import io.opencode.loopper.domain.MachineCandidateKind;
import io.opencode.loopper.persistence.LoopperDesignerMapper;
import io.opencode.loopper.persistence.LoopperMachineCandidateMapper;
import io.opencode.loopper.persistence.PackageDesignAcceptedResultRow;
import java.time.Instant;
import tools.jackson.databind.ObjectMapper;

/** Recompiles a canonical package candidate and inserts its immutable accepted result in the ACCEPTED transaction. */
final class PackageDesignAcceptedCandidateWriter implements AcceptedCandidateWriter {
    private final LoopperMachineCandidateMapper mapper;
    private final PackageDesignCompilationInputLoader inputs;
    private final PackageDesignCompilation compilation;

    PackageDesignAcceptedCandidateWriter(
            LoopperMachineCandidateMapper mapper,
            LoopperDesignerMapper designerMapper,
            ObjectMapper json,
            PackageDesignCompilation compilation) {
        this(mapper, new PackageDesignCompilationInputLoader.MapperLoader(designerMapper, json), compilation);
    }

    PackageDesignAcceptedCandidateWriter(
            LoopperMachineCandidateMapper mapper,
            PackageDesignCompilationInputLoader inputs,
            PackageDesignCompilation compilation) {
        this.mapper = mapper;
        this.inputs = inputs;
        this.compilation = compilation;
    }

    @Override
    public boolean supports(MachineCandidateKind kind) {
        return kind == MachineCandidateKind.PACKAGE_DESIGN_V1;
    }

    @Override
    public void write(CandidatePolicy.Context context, String canonicalCandidateJson,
                      String canonicalResultSha256) {
        PackageDesignCompilation.Result result = compilation.compileCandidate(
                inputs.load(context), canonicalCandidateJson);
        if (!result.accepted() || result.canonicalMarkdown() == null || result.compiledResultJson() == null) {
            throw new ConflictException("PACKAGE_DESIGN_ACCEPTED_RESULT_INVALID",
                    "Accepted package design no longer compiles from frozen owner facts");
        }
        String now = Instant.now().toString();
        PackageDesignAcceptedResultRow row = new PackageDesignAcceptedResultRow(
                context.runId(), context.owner().id(), context.sourceRevision(),
                context.ownerVersion(), context.contractVersion(), result.canonicalCandidateJson(),
                result.canonicalMarkdown(), result.compiledResultJson(), canonicalResultSha256,
                null, now, now, 0);
        if (mapper.insertPackageDesignAcceptedResult(row) != 1) {
            throw new ConflictException("PACKAGE_DESIGN_ACCEPTED_RESULT_CONFLICT",
                    "Accepted package design result could not be inserted");
        }
    }
}
