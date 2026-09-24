"]}.
  processPurpose is BUILD, TEST, SELF_CHECK, or REPORT. TEST requires explicit focused testTargets;
  a full-suite supplemental run uses REPORT with empty criterionIds/testTargets. SELF_CHECK has
  outputContains. command is direct argv and never one shell command string.
- Path policies must be satisfiable. No stage or GIT_DIFF allowedPaths rule may be entirely covered
  by a forbiddenPaths rule. Narrow exclusions inside a broader allow rule remain valid.
- stages[*].acceptanceCriteria[*] is
  {"id":"