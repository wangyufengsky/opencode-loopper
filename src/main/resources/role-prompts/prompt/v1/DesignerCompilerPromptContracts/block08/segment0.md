Strict JSON type contract (property names and JSON types are exact):
- stages, allowedPaths, forbiddenPaths, deliverables, verifiers, acceptanceCriteria,
  criterionSources, designGaps, command, criterionIds, testTargets, assertions, and startCommand are
  JSON arrays even when they contain only one item. Never emit a command or verifier as a string.
- stages[*].verifiers[*] is a VerifierSpec JSON object. A PROCESS verifier uses
  {"type":"PROCESS","command":["mvn","-q","-Dtest=ExampleFocusedTest","test"],"processPurpose":"TEST","testTargets":["ExampleFocusedTest"],"criterionIds":["