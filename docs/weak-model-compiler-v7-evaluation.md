# Weak-model Compiler v7 evaluation contract

This document defines the repeatable evidence gate for enabling the current
`DESIGN_ACCEPTANCE_V7` contract. Compiler-format success is not a release metric by
itself: path conservation, end-to-end executable planning, deterministic test
coverage, hard-gap retention, and model cost must pass together.

## Versioned corpus

The synthetic golden corpus is
`src/test/resources/designer-acceptance-v7/golden-corpus-v2.json`. Every sample has
a stable ID, stack/workflow classification, expected v6/v7 bounded outcome,
an independent expected mutation/hard-gap baseline, and an exact executable JUnit
guard that owns the fixed input and authoritative assertions. Corpus counts are
versioned expectations only: they are never passed to the rollout evaluator as
measured observations and the generated corpus report sets `authoritativeGate=false`.
The corpus harness runs
all 22 unique guard methods rather than merely reflecting that their names exist;
the corpus covers path omission, unique and multiple Stage ownership,
allow/forbid overlap, project-external paths, delete/rename source, unique optimum
and true capability ties, weak-model aliases/extras/omissions/duplicates/out-of-range
or dangerous fields, Java/Node/Python, direct/packaged/rolling flows, SQLite
fresh/upgrade/restart, external writes, and frozen v5/v6 recovery.

Run the complete local evaluation with:

```bash
./scripts/evaluate-weak-model-v7.sh
```

The command runs the corpus, all referenced algorithm/integration guards, the
same-input read-only shadow, paired frozen-v6/new-v7 large-package cost probes,
an actual one-Session closed-choice workflow probe, and migration coverage. It writes generated evidence
only below `target/`:

- `weak-model-compiler-v7-report.json` — expected synthetic summary, per-sample
  expectations, and exact-guard execution result; explicitly not a gate;
- `weak-model-compiler-v7-readonly-shadow.json` — one v6/v7 comparison from the
  same frozen requirement/design input; it marks `authoritativeMeasurement=true`
  and `completeQualification=false`, and ratios with no applicable denominator are
  `null` rather than synthetic `100%`;
- `weak-model-compiler-v7-qualification.json` — the complete local qualification:
  all 22 exact production guards, three supplemental metric guards, the authoritative
  same-input measurement, and their bounded actual values. This is the only report
  with `authoritativeGate=true` for the existing v7 JSON workflow. It also carries
  a separate `candidateFeatureQualification`; until a real candidate workflow guard
  records every required observation, that nested gate is explicitly
  `complete=false` and `passed=false` and cannot authorize the candidate feature.

All outputs are bounded contracts containing only sample IDs, stable gap codes,
booleans and counts. They cannot contain requirement text, model output, absolute
paths, repository-relative path values, persistence IDs, or OpenCode Session IDs.
The evaluator has no mapper, lifecycle, Task, or OpenCode dependency; an exception
fails the evaluation command and cannot advance or damage a Designer session.

## Gate definitions

The exact guards and the actual same-input shadow fail release qualification unless
all of the following hold at once. Hand-written corpus expectations cannot satisfy
or compensate any gate condition:

- executable guards must preserve their asserted mutation basis, including the
  ambiguous-Stage case's four total obligations, two resolved obligations, and two
  unresolved obligations; actual shadow samples must preserve every eligible
  explicit write/move-destination and conserve 100%;
- known compile-success/runtime-path-escape cases are zero under v7;
- project-external, delete/move-source, forbidden overlap, external-write, and
  invalid closed-choice cases must retain their per-sample hard-gap baseline and
  remain blocked at 100%;
- v7 end-to-end executable samples do not regress and at least one target sample
  improves;
- one-Stage omitted facts resolve on the server without a Compiler call, harmless
  zero-match labels are audited and dropped, same-name multi-match facts remain
  invalid, and multi-Stage prompts expose explicit zero-based candidate pairs;
- Compiler model calls and full-design retries do not increase;
- no sample may use invalid/over-reported counts, increase Judge-only ratio, or
  decrease focused-test coverage; aggregate ratios are reported only after those
  per-sample checks pass;
- automatic authorization of project-external, delete, or external-system writes
  remains zero;
- fresh/upgrade SQLite, restart, direct WP-1, rolling package, and frozen v5/v6
  compatibility guards all pass.

The complete qualification does not infer those results from a green test count.
Exact guards publish bounded actual measurements through a test-only registry:
same-input compile/executable/Judge/focused/path values; server-direct and ambiguous
mutation totals; unique-optimum and true-tie routing evidence; actual closed-choice
workflow prompt and persisted-Session counts; blocked external writes; and paired
large-package v6/v7 Compiler-call and redesign counts. Qualification checks those
values before writing `passed=true`; unknown evidence IDs, metric names or flags,
conflicting values and negative measurements fail the run. The registry is cleared
per run and cannot carry prompts, paths, Session IDs, persistence identifiers, or
arbitrary unregistered fields. Hard-gap and unsafe-authorization measurements are
derived from the actual compilation/result objects exercised by their guards.

### Acceptance candidate structural qualification

The internal-MCP acceptance candidate feature has a separate structural qualification.
The production property `loopper.internal-candidate.acceptance-closed-choice-v7-enabled`
defaults to `true` after the isolated 0.3.5 packaged-JAR replay proved configured real-model
MCP tool adoption and same-Session correction after a rejected submission. Setting it to
`false` remains an operational rollback for new runs and does not disable recovery of
persisted candidate state. The test-only registry reserves four closed evidence IDs backed
by real Designer/OpenCode guards:

- unique global optimum;
- exhaustive true capability tie;
- non-enumerable or non-exhaustive capability result;
- path-ownership or permission-safety block.

Each evidence item must atomically record `modelCalls`, `candidateSessions`, and
`candidateSubmissions` from the same observation. Partial records are rejected so
separate runs cannot be combined into a false pass. The exact bounds are:

| Scenario | Model calls | Candidate Sessions | Candidate submissions |
| --- | ---: | ---: | ---: |
| unique optimum / server direct | 0 | 0 | 0 |
| exhaustive true tie | 1 | 1 | 1–2 |
| non-enumerable or non-exhaustive | 0 | 0 | 0 |
| path or permission safety block | 0 | 0 | 0 |

Candidate submissions are MCP tool invocations inside the one candidate Session;
they are not extra model calls or extra Sessions. The second submission is allowed
only after a mechanical closed-set selection rejection. Security, contract,
topology, execution, path, permission, and non-enumerable failures are terminal
for the candidate loop. The current structural qualification observes those four workflows
as `0/0/0`, `1/1/2`, `0/0/0`, and `0/0/0` respectively, with
`candidateFeatureQualification.complete=true` and `passed=true`. Counts come from
the same workflow's actual Fake OpenCode prompt history plus persisted candidate
run/attempt rows. The two true-tie MCP submissions are issued by the integration driver,
not by Fake OpenCode or a configured model. Coordinator unit tests and the legacy JSON
Compiler guard cannot populate this evidence. The result therefore proves the server
pipeline, retry bounds, persistence, and counters, but it does not prove model tool use
or self-correction and cannot enable the production default. The evaluator reports the
qualification but never mutates runtime configuration by itself.

The V51-V55 durability guards are prerequisites rather than substitutes for that real-model
qualification. They prove that legacy handoff, attested internal launch, one-shot prompt
dispatch, termination intent promotion, restart recovery, cancellation and requirement
replacement cannot create overlapping writers or infer success from uncertain transport.
The packaged-JAR qualification must still show one configured model Session issuing the
private MCP call, receiving a server rejection, correcting within that same Session and
ending in a server-accepted candidate. Until that evidence is recorded, the default remains
`false` even when every structural and durability guard is green.

The v2 synthetic corpus currently contains 25 samples backed by 22 exact guards. Its versioned expected
baseline is v6/v7 design compilation `14/25 -> 15/25`, end-to-end executable planning
`11/25 -> 15/25`, Compiler calls `10 -> 8`, full redesigns `7 -> 0`, hard-gap
retention `100%`, path conservation `100%`, Judge-only ratio unchanged, focused-test
coverage unchanged at `100%`, and dangerous automatic authorization `0`. These are
expectations for the named guards, not measured gate inputs or a production pass-rate claim.

## Rollout and evidence boundary

The same-input shadow compiles both versions through the production fact, mutation,
capability, resolver, and compiler pipeline in memory. Its evaluator result is one
authoritative measurement, not complete release qualification; hard-gap, dangerous
write, invalid closed-choice, tie, persistence, restart, rolling, and historical
compatibility evidence comes from the exact production guards and is joined with
that measurement in `weak-model-compiler-v7-qualification.json`. The process creates
no model Session, writes no planning state, and creates no Task. After the complete gate passes, only newly
frozen `2026-08-dynamic-v7` designs use v7 authoritatively. Existing v5/v6 snapshots
are never migrated or reinterpreted. Runtime `GIT_DIFF`, focused-test, and Judge
rules remain authoritative and cannot be disabled to make the gate pass.

The same-input shadow and the existing one-Session JSON closed-choice probe are
not candidate-transport observations: neither invokes the internal
`submit_candidate` tool. They therefore cannot satisfy or partially populate
`candidateFeatureQualification`; only the four separately executed production
workflow guards described above can do so.

Synthetic evidence does not replace an isolated packaged-JAR replay against the
configured weak model. Each release must separately record the sample boundary,
model-call count, final Review Gate state, path-conservation result, Task count,
JAR identity, and whether the temporary process was stopped. It must not replace
the service listening on port 8080 without explicit authorization.
