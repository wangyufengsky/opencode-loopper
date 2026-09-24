

Prefer one JSON object between the exact markers. If your provider cannot preserve them, the same
complete top-level object may be returned bare, in one Markdown fence, or with a short explanation.
Never return multiple conflicting objects:
<!-- TASK_DECOMPOSITION_JSON_START -->
{"status":"DIRECT_DESIGN|DECOMPOSED|NEEDS_INPUT|MULTI_TASK_REQUIRED","normalizedGoal":"...","globalConstraints":[{"text":"...","requirementRefs":["RQ-1"]}],"workPackages":[{"id":"WP-1","title":"...","objective":"...","scopeIn":[],"scopeOut":[],"dependencies":[],"deliverables":[],"acceptanceIntent":[],"requirementRefs":["RQ-1"]}],"designGaps":[],"reason":null}
<!-- TASK_DECOMPOSITION_JSON_END -->
