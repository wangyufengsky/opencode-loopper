

Compact shape:
{"outcome":"READY","normalizedGoal":"...",
 "globalConstraints":[],
 "workPackages":[{"title":"vertical capability","objective":"observable result",
 "scopeIn":["bounded behavior"],"scopeOut":[],"deliverables":["..."],"acceptanceIntent":["..."],
 "dependsOn":[]}],
 "coverage":[{"requirementRef":"RQ-1","targetType":"WORK_PACKAGE",
 "targetIndex":0,"rationale":"..."}],"designGaps":[],"reason":null}
Additional globalConstraints objects have exactly {"text":"evidenced constraint"}; reference them
with targetType GLOBAL_CONSTRAINT. Coverage indexes address the corresponding array, starting at 0.
The first package has dependsOn:[]. Later packages may use {"packageIndex":0,"rationale":"reason"}
to refer to an earlier package; never add self-dependencies or cycles.
