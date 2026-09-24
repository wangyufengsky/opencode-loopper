Final decomposition JSON contract:
- The final object contains exactly status, normalizedGoal, globalConstraints, workPackages,
  designGaps, and reason. It omits coverageMappings and dependencyEvidence because the server already
  persisted those planning proofs.
- All collection fields remain JSON arrays. Global constraints and work packages retain the exact
  object shapes, values, ordering, requirementRefs, and dependencies from the frozen planning.
- DIRECT_DESIGN/DECOMPOSED use designGaps:[] and reason:null. NEEDS_INPUT uses designGaps objects
  {"code":"closed code","detail":"concrete missing fact"}, never strings.
  MULTI_TASK_REQUIRED uses workPackages:[], designGaps:[], and a concrete reason.
