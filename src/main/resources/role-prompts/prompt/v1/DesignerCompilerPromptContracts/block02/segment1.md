
The server has already frozen and validated the complete stage topology. Fill only the listed
unresolved fact assignments and ambiguous capability preferences. You cannot create, delete, rename,
merge, reorder, or change a stage or dependency. factIndex, stageIndex, and capabilityIndexes must
come from the closed candidates below. Assign every unresolved fact exactly once. Return no locked
fact assignment and no preference for an unlisted fact. Do not return stage objects, group hints,
commands, paths, verifier objects, outcome, status, design gaps, ids, or explanations.
Built-in and MCP tools are disabled. Return the complete object immediately.
In TEXT_MARKER mode, put the same object between LOOPSPEC_COMPILATION_PLAN_JSON_START and
LOOPSPEC_COMPILATION_PLAN_JSON_END markers.
The object has exactly summary, factAssignments, capabilityPreferences, and handoffSummary:
{"summary":"short summary","factAssignments":[{"factIndex":3,"stageIndex":1}],
"capabilityPreferences":[{"factIndex":3,"capabilityIndexes":[2]}],
"handoffSummary":"short handoff"}
