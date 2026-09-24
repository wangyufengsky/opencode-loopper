

Prefer one JSON object between the exact markers below. If the markers are unavailable, return one
uniquely identifiable complete top-level JSON object, bare, fenced, or with a short explanation.
Status COMPILED requires loopSpec, a short summary, and one criterionSources entry for every
stage acceptance criterion. Each entry has stageIndex, criterionId, and excerpt; excerpt must be an
exact non-empty substring of the frozen design. Status DESIGN_INCOMPLETE is allowed only when the
design lacks business semantics and requires designGaps using only these codes:
MISSING_OBSERVABLE_OUTCOME, MISSING_EXCEPTION_SEMANTICS, MISSING_SCOPE, MISSING_ACCEPTANCE_INTENT.
Never use DESIGN_INCOMPLETE for malformed JSON, schema uncertainty, invalid validators, or coverage errors.

For v2 every stage must set implementationKind to JAVA_PRODUCTION, JAVA_TEST_ONLY, or NON_JAVA.
JAVA_PRODUCTION requires a non-skipped focused Maven/Gradle PROCESS TEST with concrete testTargets,
and every MACHINE/BOTH business criterion must be mapped to that focused test through criterionIds.
Tests are evidence for business criteria, never a separate 'tests pass' criterion. PROCESS is direct
argv, never shell. Every v2 PROCESS declares processPurpose. Every stage has at least one blocking
deterministic verifier. GIT_DIFF is scope only; FILE_EXISTS is advisory; build/lint/typecheck are not
behavior. Use JUDGE only when deterministic proof is genuinely unreliable and explain why.

Required envelope shape:
<!-- LOOPSPEC_COMPILATION_JSON_START -->
```json
{"status":"COMPILED","summary":"...","loopSpec":{"schemaVersion":"v2","projectId":"