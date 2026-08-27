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
  with `authoritativeGate=true`.

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

Synthetic evidence does not replace an isolated packaged-JAR replay against the
configured weak model. Each release must separately record the sample boundary,
model-call count, final Review Gate state, path-conservation result, Task count,
JAR identity, and whether the temporary process was stopped. It must not replace
the service listening on port 8080 without explicit authorization.
