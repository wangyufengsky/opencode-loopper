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
  shapes belong to `DesignerSemanticContracts` and must not depend back on the facade.
  OpenCode `question` capability selection, answer validation, durable decision-log
  encoding and compatibility-chat projection belong to `DesignerQuestionSupport`;
  that collaborator must not perform Designer lifecycle transitions.
- `TaskService` owns ordinary execution ordering and error-layer escalation. It
  must not assemble immutable design snapshots, verification aggregates, baseline
  diffs, or Judge evidence prompts; those belong to `TaskEvidenceService`.
  `TaskCancellationCoordinator` owns durable `STOPPING -> CANCELLED` child-state closure,
  while `TaskWriterTerminationService` owns remote Session/Judge termination proof and
  persistent unconfirmed-writer evidence; neither may release/admit the next Direct lease.
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
  Neither may infer repository technologies from `AGENTS.md` or permit AI labels to create evidence.
- `DesignerVerificationIntentMapper` owns source-backed test-to-scenario relationships, including
  default ownership by a package's sole positive focused-test deliverable and unambiguous references
  back to it; regression-only, test-style, and negated framework references are execution constraints,
  not scenario coverage or capabilities. `DesignerDesignFactExtractor` rejects partial or duplicated
  controlled section sets before facts can be merged. `DesignerAcceptancePlanCompiler`
  owns fact grouping, closed capability selection and Stage assembly;
  `DesignerAcceptancePathPolicy` alone decides whether Designer-owned text is a standalone repository-relative
  path rule. Natural-language scope descriptions must not leak into executable `GIT_DIFF` policies.
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
