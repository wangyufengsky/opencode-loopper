# Code design contract

This contract governs production code structure. It complements the runtime,
Designer, OpenCode, and UI contracts: those documents define observable
behavior, while this document defines how that behavior is divided into code.

## Design goals

- A class has one primary reason to change. Coordination, policy, persistence,
  transport, parsing, and presentation are separate responsibilities.
- Dependencies point from adapters and application orchestration toward stable
  domain contracts. Domain policies do not depend on Spring, MyBatis, HTTP, or
  process execution.
- Public facades stay small. They validate the boundary and delegate to focused
  collaborators; they do not become a second implementation of those collaborators.
- Prefer composition. Inheritance is reserved for genuine substitutability, not
  for sharing service implementation.
- Optimize for deletion and replacement: an adapter, policy, or parser should be
  testable and replaceable without constructing the complete application graph.

## Responsibility boundaries

| Responsibility | Code shape | Must not own |
| --- | --- | --- |
| Application workflow | `*Coordinator` / focused `*Service` | HTTP/SQL/process parsing details |
| Domain decision | immutable value, `*Policy`, strategy | Spring context, persistence, external I/O |
| Object construction | `*Factory` / assembler | lifecycle transitions or remote calls |
| Persistence | aggregate-oriented `*Mapper` / `*Persistence` | network, Git, model, browser, long file I/O |
| External integration | `*Client`, `*Transport`, adapter | Task/Designer lifecycle decisions |
| Project fact discovery | bounded `*Analyzer` plus immutable snapshot | AI routing, task mutation, long DB transactions |
| Untrusted payload parsing | `*Parser`, normalizer | persistence mutations or retries |

Use a design pattern only when it makes a changing axis explicit:

- Strategy for interchangeable algorithms selected by stable domain input.
- Factory/assembler for construction with validation or protocol-specific shape.
- Policy for deterministic decisions without side effects.
- Coordinator for a bounded multi-step use case.
- Adapter for HTTP, Git, process, filesystem, browser, or provider protocols.

Do not add a pattern merely to rename procedural code. A new abstraction must
reduce coupling, isolate a changing axis, or make a contract independently testable.

## Active compatibility-facade boundaries

The historical `DesignerSessionService` and `TaskService` remain public
compatibility coordinators while their use cases are migrated. Their public
signatures may stay stable, but extracted implementation must follow these
ownership rules:

- `DesignerSessionService` owns Designer workflow ordering, lifecycle transitions,
  remote role invocation, and persistence coordination. It must not implement
  compact package-plan normalization, semantic validation, or executable verifier
  synthesis; those belong to `DesignerPackagePlanCompiler`. Shared machine payload
  shapes and marker-extraction patterns belong to `DesignerSemanticContracts` and must not
  depend back on the facade.
  OpenCode `question` capability selection, answer validation, durable decision-log
  encoding and compatibility-chat projection belong to `DesignerQuestionSupport`;
  that collaborator must not perform Designer lifecycle transitions.
- `TaskService` owns ordinary execution ordering and error-layer escalation. It
  must not assemble immutable design snapshots, verification aggregates, baseline
  diffs, or Judge evidence prompts; those belong to `TaskEvidenceService`.
  `TaskCancellationCoordinator` owns durable `STOPPING -> CANCELLED` child-state closure,
  while `TaskWriterTerminationService` owns remote Session/Judge termination proof and
  persistent unconfirmed-writer evidence; neither may release/admit the next Direct lease.
- Rolling execution is split by reason to change: `RollingPackageService` owns package command
  boundaries and cumulative TaskSpec/Stage append; `RollingPackageCheckpointService` owns the
  verify-checkpoint-fact-lease saga and crash recovery; `RollingPackagePlanService` owns only
  unfinished-suffix revisions and correction packages; `RollingPackagePlanGenerationService`
  owns only read-only AI suggestion Session dispatch, polling, bounded extraction, and base-snapshot
  verification; `RollingPackageReadService` owns bounded
  workbench/fact projections. It may project policy-owned capabilities but may not derive a second
  UI state matrix. None may rewrite the immutable LoopDraft.
  `RollingPackageTaskHooks` is the narrow bridge from the legacy Task facade, not a second workflow
  implementation. `TaskEvidenceService` remains the sole owner of proven diff/evidence material.
  `RollingPackageCommandPolicy` is the only authority for package command capabilities and
  write validation; `RollingPackageCommandContextService` is the single builder of persisted
  Task/Run/Queue, checkpoint, writer, Judge, verifier, and Designer facts passed to that policy.
  Both Task overview and rolling workbench reads use this context, and the workbench response owns
  its `taskVersion`, `currentPackageRunId`, package versions, and complete capability snapshot.
  Controllers and Vue components may not mix that response with an older overview, duplicate the
  state matrix, or repair inconsistent aggregates after commit.
- `ProjectStackAnalyzer` owns bounded filesystem evidence and manifest fingerprinting;
  `ProjectStackProfileService` owns immutable snapshot persistence and freshness checks.
  Analyzer snapshot collections must be emitted in canonical lexical order so filesystem
  visitation order cannot change persisted profiles or cross-platform verification results.
  Project list reads must use persisted summaries and must not call either filesystem path.
- Tests that mock canonical workspace/session paths must derive both the registered project root
  and the expected runtime key from the same normalized absolute `@TempDir`; Unix-only literals
  such as `/tmp` must not encode path equality assumptions into cross-platform contracts.
- `TaskProfileRouter` owns deterministic selection from a supplied stack snapshot,
  `TaskProfileSafetyPolicy` owns requested external-mutation and publication-target classification,
  `TaskProfileIntentPolicy` owns task-level read-only review and positive-mutation classification,
  while `TaskProfileOverridePolicy` owns versioned component/profile override validation.
  `TaskProfileRouterRunService` owns request/monitor run claiming, confirmed remote cancellation,
  latest-run read projection, and optimistic terminal-reroute validation. `TaskProfileService`
  remains the profile persistence and routing coordinator.
  Neither may infer repository technologies from `AGENTS.md` or permit AI labels to create evidence.
- `DesignerVerificationIntentMapper` owns source-backed test-to-scenario relationships, including
  default ownership by a package's sole positive focused-test deliverable and unambiguous references
  back to it; regression-only, test-style, and negated framework references are execution constraints,
  not scenario coverage or capabilities. `DesignerDesignFactExtractor` rejects partial or duplicated
  controlled section sets before facts can be merged. `DesignerAcceptancePlanCompiler`
  owns fact grouping, closed capability selection and Stage assembly;
  `DesignerAcceptancePathPolicy` alone decides whether Designer-owned text is a standalone repository-relative
  path rule. Natural-language scope descriptions must not leak into executable `GIT_DIFF` policies.
- `DesignerAcceptanceFastPathResolver` alone owns v6/v7 exact-reference normalization, Stage topology
  validation, direct/AI/incomplete routing, and the merge of closed-set Compiler assignments. It must
  never perform fuzzy or substring matching. `DesignerAcceptanceWorkflow` owns persistence and solver
  coordination; `DesignerClosedChoiceContract` owns the current-v7 minimal prompt projection and raw-output
  semantic firewall, while `DesignerCompilerPromptContracts` owns legacy, frozen-v6, and current-v7 Compiler
  role instructions. `OpenCodeStructuredSchemas` owns only the transport
  envelope and cannot authorize execution/topology/safety fields merely because an extra property is transport-valid.
  `DesignerSessionService` may select the route and coordinate lifecycle only. Adding this path must
  lower or preserve its legacy line-count ratchet; prompt text and parsing rules do not move back into it.
- `DesignerMutationObligationExtractor` alone freezes source-backed positive repository mutation obligations;
  the shared polarity classifier must classify path-bearing facts as positive, negative/example, or ambiguous,
  `DesignerRequirementMutationActionPolicy` binds actions to each referenced path without treating nearby business nouns
  as delete/move commands, while `DesignerRepositoryPathSyntax` owns common root-file, explicit-directory, and
  exact-path/path-rule typing. Clause/list scope, external-path rejection, source excerpts, and hashes must remain deterministic.
  `DesignerAcceptanceStagePathPlanner` owns executable Stage path selection and path provenance; package scope,
  global fact and technology fallbacks may shape execution but cannot prove a Stage owns a frozen obligation.
  `DesignerMutationStageBinder` alone owns v7 deterministic obligation-to-Stage binding: exact controlled fact
  reference, one uniquely covering existing Stage rule, or an exact path added to the only Stage. It must keep
  real multi-Stage candidates blocked and must not expose mutation choices to the model.
  `DesignerAcceptanceCapabilitySolver` owns exact branch-and-bound capability cover, bounded greedy fallback,
  the complete business-score tuple, the full set of equal optimal solution signatures, and bounded
  non-exhaustive diagnostics. Stable candidate order may stabilize output but must not collapse a true business tie;
  a current-v7 Compiler may see only the membership-discriminating candidates from exhaustive equal optima and
  must select the complete discriminating signature of exactly one optimum. A non-exhaustive search is diagnostic
  evidence only and cannot authorize a model choice or a compiled plan.
  `DesignerAcceptancePlanCompiler` only orchestrates Stage assembly and lowering.
  `MutationConservationPolicy` runs before lowering and may only use bounded rule-containment/overlap relations
  shared with `VerifierPathPolicy`; unproved containment, forbidden overlap, delete, or move-source must fail closed.
- `DesignerAcceptanceShadowEvaluator` owns only bounded rollout arithmetic and per-sample count invariants. Its input
  contract carries an independent expected mutation/hard-gap baseline, rejects negative or over-reported counts, and
  forbids aggregate compensation of one sample's safety or focused-test regression by another sample. It excludes
  prompts, model output, path values, record IDs, persistence, lifecycle, and OpenCode dependencies. The versioned
  corpus executes its exact production guard methods, while the same-input compiler harness lives in tests. Key guards
  publish only bounded actual counts through a deterministic test-only registry; complete qualification checks those
  counts rather than inferring metrics from green test totals. All reports stay below `target/`, may block a release,
  and must never become a second planning authority.
- `LoopSpecAcceptanceService` owns the final cross-source acceptance contract. A focused Maven/Gradle
  test with no criterion mapping is a valid blocking gate only for a `JAVA_PRODUCTION` Stage whose
  criteria are all Judge-only; machine or mixed criteria still require explicit focused-test coverage.
- `ProjectConventionService` owns proposal/apply lifecycle coordination, while
  `ProjectConventionStackPolicy` owns the stack-bound prompt, required-section validation,
  unsupported-technology rejection, and apply-time fingerprint guard. Guarded AGENTS.md
  reads, atomic writes, permission preservation and marker replacement belong only to
  `ProjectConventionDocumentStore`.
- A responsibility is considered extracted only when the old implementation body
  is removed, focused tests cover the new boundary, facade integration tests remain
  green, and the legacy line-count ratchet is lowered in the same change.
- New collaborators may return domain results or normalization notes. They must not
  call back into the compatibility facade, duplicate lifecycle transitions, or
  become generic utility bags for unrelated future methods.

## Size and dependency limits

- Prefer at most 400 physical lines per production class and 250 per interface.
- The default hard limit is 600 physical lines for a production Java source file.
  Existing files above the limit are explicit ratchet debt: every edit must keep
  them at or below the recorded cap, and structural work must lower that cap.
- Prefer methods of at most 40 lines; 80 lines is the review threshold. Split by
  named decision or phase, not arbitrary line chunks.
- Prefer at most 8 constructor dependencies; 12 is the review threshold. A larger
  graph normally means the class contains multiple application use cases.
- A Mapper groups one aggregate or tightly coupled transaction boundary. Cross-
  aggregate deletion/orchestration belongs in a named persistence coordinator.
- New nested DTOs are allowed only when private to the owner. Shared API/read-model
  contracts use a dedicated top-level type or a cohesive contract container.

`CodeStructureContractTest` enforces the source-file hard limit and the legacy
ratchet. It is intentionally dependency-free so it runs in every Maven test and
release build. Lower a legacy cap in the same change that extracts responsibility;
never raise a cap to make a build green.

The current compatibility ratchets are 5,401 physical lines for
`DesignerSessionService` and 2,727 for `TaskService`. Rolling package behavior must stay in the
collaborators above; a later change may only preserve or lower those caps.

## Change workflow

1. Identify the class's reasons to change and its upstream/downstream callers.
2. Protect observable behavior with the closest existing test.
3. Define the collaborator interface around domain terms, not the old method's locals.
4. Move one responsibility at a time; keep transactions and external I/O on their
   existing side of the boundary.
5. Add focused tests for extracted policies/parsers and rerun integration tests for
   the facade. Use fakes at external boundaries, not mocks of internal data plumbing.
6. Update this contract and `AGENTS.md` when a new structural rule or exception is needed.

## Review checklist

- Can the class's responsibility be stated without “and”?
- Are transaction boundaries short and free of HTTP, model, process, browser, and
  long filesystem operations?
- Is error ownership preserved at the correct FIELD/VERIFICATION/SESSION/TASK layer?
- Does a collaborator expose domain input/output instead of the caller's internal state?
- Are names concise and behavior-revealing, with no pass-through abstraction that
  merely adds indirection?
- Do focused and integration tests prove the extracted boundary and the unchanged flow?
