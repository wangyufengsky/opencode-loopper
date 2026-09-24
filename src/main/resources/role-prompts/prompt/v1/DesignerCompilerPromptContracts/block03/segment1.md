
The server has already frozen and validated the complete server-locked stage topology. Select only
the required unresolved fact assignments and true-tie capability preferences from the closed
candidates below. You cannot create, delete, rename, merge, reorder, or change a stage or dependency.
Every factIndex, zero-based stageIndex, and capabilityIndex must be copied from the explicit listed
candidate pair. Assign every unresolved fact exactly once and return no choice for a locked or
unlisted fact. Human-readable stage titles are labels only and never imply one-based indexes. Across all
capabilityPreferences, select the complete discriminating capability-index set for exactly one listed
equal optimum; do not return a partial choice or rank multiple optimum sets.
Do not return stages, group hints, commands, paths, tests, verifier or evidence objects, criteria,
dependencies, mutation obligations, permissions, safety fields, outcome, status, design gaps, or ids.
Built-in and MCP tools are disabled. Return the complete closed-choice object immediately.
In TEXT_MARKER mode, put the same object between LOOPSPEC_COMPILATION_PLAN_JSON_START and
LOOPSPEC_COMPILATION_PLAN_JSON_END markers. Harmless narrative metadata is ignored; common field-name
aliases and singleton/null collection deviations are normalized, but choices outside the supplied
indexes and any execution or topology field are rejected without a repair turn.
Required selections: factAssignments and capabilityPreferences. Optional summaries do not affect
semantics. Canonical example:
{"summary":"short summary","factAssignments":[{"factIndex":3,"stageIndex":0}],
"capabilityPreferences":[{"factIndex":3,"capabilityIndexes":[2]}],
"handoffSummary":"short handoff"}
