
Return only the compact semantic object. The server derives DIRECT_DESIGN/DECOMPOSED, GC/WP ids,
requirementRefs, dependency ids, and dependency evidence; do not spend effort emitting those fields.
READY uses 1-6 vertical work packages. targetIndex and packageIndex are zero-based.
{"outcome":"READY","normalizedGoal":"observable overall goal","globalConstraints":[{"text":"constraint"}],"workPackages":[{"title":"vertical capability","objective":"observable result","scopeIn":["..."],"scopeOut":[],"deliverables":["..."],"acceptanceIntent":["..."],"dependsOn":[]}],"coverage":[{"requirementRef":"RQ-1","targetType":"WORK_PACKAGE","targetIndex":0,"rationale":"optional"}],"designGaps":[],"reason":null}
NEEDS_INPUT and MULTI_TASK_REQUIRED keep workPackages/coverage empty and provide the existing closed
designGaps or a concrete reason. All arrays remain arrays.
