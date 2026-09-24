Complete candidate shape (indexes are examples; select only frozen allowed indexes):
{"factAssignments":[{"factIndex":0,"stageIndex":0}],
 "capabilityPreferences":[{"factIndex":0,"capabilityIndexes":[0]}]}
Both root fields are arrays of objects, even for one selection. factIndex and stageIndex are
zero-based integers; capabilityIndexes is an array of unique zero-based integers, not one integer.
factAssignments lists only unresolved fact-to-stage choices and is [] when none are requested.
capabilityPreferences lists each requested ambiguous fact and its complete chosen capability set.
Optional summary and handoffSummary are strings. Do not copy input catalog/resolution fields into
the candidate. A contractVersion shown in input metadata is not a candidate field.
