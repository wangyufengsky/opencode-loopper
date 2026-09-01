package io.opencode.loopper.service;

import io.opencode.loopper.domain.TaskPackageRunState;
import io.opencode.loopper.persistence.DesignWorkPackageRow;
import io.opencode.loopper.persistence.LoopperMapper;
import io.opencode.loopper.persistence.TaskPackagePlanRevisionRow;
import io.opencode.loopper.persistence.TaskPackageRunRow;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/** Loads only frozen SQLite facts used by both rolling candidate and manual compilation adapters. */
interface RollingPackagePlanCompilationInputLoader {
    Set<String> UNFINISHED = Set.of(TaskPackageRunState.PLANNED.name(),
            TaskPackageRunState.DESIGNING.name(), TaskPackageRunState.DESIGN_REVIEW.name(),
            TaskPackageRunState.EXECUTION_READY.name(), TaskPackageRunState.WAITING_INPUT.name());

    RollingPackagePlanCompilation.Input load(CandidatePolicy.Context context);

    default RollingPackagePlanCompilation.Input loadTask(String taskId) {
        throw new UnsupportedOperationException("Task snapshot loading is unavailable");
    }

    final class MapperLoader implements RollingPackagePlanCompilationInputLoader {
        private final LoopperMapper mapper;
        private final ObjectMapper json;

        MapperLoader(LoopperMapper mapper, ObjectMapper json) {
            this.mapper = mapper;
            this.json = json;
        }

        @Override
        public RollingPackagePlanCompilation.Input load(CandidatePolicy.Context context) {
            TaskPackagePlanRevisionRow owner = mapper.findTaskPackagePlanRevision(context.owner().id())
                    .orElseThrow(() -> new ConflictException(
                            "CANDIDATE_OWNER_MISSING", "Rolling package plan candidate owner no longer exists"));
            boolean exact = owner.version() == context.ownerVersion();
            boolean dispatched = context.ownerVersion() != Long.MAX_VALUE
                    && owner.version() == context.ownerVersion() + 1
                    && "RUNNING".equals(owner.externalSessionState());
            if (!context.scope().id().equals(owner.taskId())
                    || context.sourceRevision() != owner.revision()
                    || !"GENERATING".equals(owner.state()) || !exact && !dispatched) {
                throw new ConflictException("CANDIDATE_OWNER_REVISION_STALE",
                        "Rolling package plan candidate owner revision has changed");
            }
            return loadTask(owner.taskId());
        }

        @Override
        public RollingPackagePlanCompilation.Input loadTask(String taskId) {
            List<RollingPackagePlanCompilation.CurrentPackage> current = new ArrayList<>();
            LinkedHashSet<String> frozenKeys = new LinkedHashSet<>();
            LinkedHashSet<String> requirementRefs = new LinkedHashSet<>();
            for (TaskPackageRunRow run : mapper.listTaskPackageRuns(taskId)) {
                if (!taskId.equals(run.taskId())) continue;
                DesignWorkPackageRow design = mapper.findDesignWorkPackage(run.designWorkPackageId())
                        .orElseThrow(() -> new ConflictException("CANDIDATE_PACKAGE_SNAPSHOT_MISSING",
                                "Frozen rolling package design snapshot no longer exists"));
                List<String> dependencies = strings(design.dependenciesJson());
                requirementRefs.addAll(strings(design.requirementRefsJson()));
                if (UNFINISHED.contains(run.state())) {
                    current.add(new RollingPackagePlanCompilation.CurrentPackage(
                            run.id(), run.packageKey(), dependencies));
                }
                if (TaskPackageRunState.FACT_FROZEN.name().equals(run.state())) {
                    frozenKeys.add(run.packageKey());
                }
            }
            return new RollingPackagePlanCompilation.Input(
                    List.copyOf(current), List.copyOf(frozenKeys), List.copyOf(requirementRefs));
        }

        private List<String> strings(String value) {
            try {
                return value == null ? List.of() : json.readValue(value, new TypeReference<>() { });
            } catch (JacksonException invalid) {
                throw new ConflictException("CANDIDATE_PACKAGE_SNAPSHOT_INVALID",
                        "Frozen rolling package inputs cannot be read");
            }
        }
    }
}
