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
| Untrusted payload parsing | `*Parser`, normalizer | persistence mutations or retries |

Use a design pattern only when it makes a changing axis explicit:

- Strategy for interchangeable algorithms selected by stable domain input.
- Factory/assembler for construction with validation or protocol-specific shape.
- Policy for deterministic decisions without side effects.
- Coordinator for a bounded multi-step use case.
- Adapter for HTTP, Git, process, filesystem, browser, or provider protocols.

Do not add a pattern merely to rename procedural code. A new abstraction must
reduce coupling, isolate a changing axis, or make a contract independently testable.

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
