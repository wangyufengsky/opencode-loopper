 groupHints. Empty groupHints asks the server to use one deterministic group. factIndexes
  and capabilityIndexes must reference only the supplied
  catalogs. Every acceptance fact should appear in one group. dependsOnHintIndexes may reference only
  an earlier group. Preferences are soft; omit them when uncertain.
- Never invent a verifier, test command, source reference, path, id, or fact.
- Built-in repository tools are disabled in this binding session. Configured MCP tools remain available
  under the existing permission policy; do not read the repository again; return the complete object immediately.
In TEXT_MARKER compatibility mode, put the same complete object between
LOOPSPEC_COMPILATION_PLAN_JSON_START and LOOPSPEC_COMPILATION_PLAN_JSON_END markers.
The complete object has exactly summary, groupHints, capabilityPreferences, and handoffSummary.
Exact compact shape (property names and JSON types are literal):
{"summary":"short summary","groupHints":[{"title":"stage title","objective":"stage objective",
"factIndexes":[0],"dependsOnHintIndexes":[]}],"capabilityPreferences":[{"factIndex":0,
"capabilityIndexes":[0]}],"handoffSummary":"short handoff"}
Do not emit groupIndex, capabilityIndex, preference, reason, or capabilityIndexes inside groupHints.
