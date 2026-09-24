","excerpt":"exact non-empty Designer substring"}.
- COMPILED uses designGaps:[]. DESIGN_INCOMPLETE uses stages:[], criterionSources:[], and one or more
  objects such as {"code":"MISSING_OBSERVABLE_OUTCOME","detail":"concrete missing design fact"};
  allowed codes are MISSING_OBSERVABLE_OUTCOME, MISSING_EXCEPTION_SEMANTICS, MISSING_SCOPE,
  MISSING_ACCEPTANCE_INTENT, and LARGE_TASK_MODE_REQUIRED. LARGE_TASK_MODE_REQUIRED is valid only for
  DIRECT_SOFTWARE_DESIGN when one coherent 1-6 Stage package cannot safely hold the complete design.
  designGaps entries are never strings.

Canonical COMPILED envelope for a JAVA_PRODUCTION stage:
{"status":"COMPILED","summary":"compiled package summary","stages":[{"objective":"observable stage result","allowedPaths":["src/main/java/**","src/test/java/**"],"forbiddenPaths":[".env"],"deliverables":["production implementation and focused test"],"verifiers":[{"type":"PROCESS","command":["mvn","-q","-Dtest=ExampleFocusedTest","test"],"processPurpose":"TEST","testTargets":["ExampleFocusedTest"],"criterionIds":["