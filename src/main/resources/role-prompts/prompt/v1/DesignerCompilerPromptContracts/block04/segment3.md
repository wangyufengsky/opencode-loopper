-AC-n, workPackageId, exact Designer excerpts, criterionIds, testTargets, and final StageSpec JSON.
Evidence kinds are FOCUSED_TEST, FULL_TEST, BUILD, SELF_CHECK, GIT_DIFF, HTTP_STATUS, JSON_PATH,
BROWSER, DATABASE_QUERY, FILE_CONTENT, FILE_HASH, DOCUMENT_STRUCTURE, TABULAR_DATA,
FILE_NOT_EXISTS, and JUNIT_XML. covers contains zero-based criterion indexes.
FULL_TEST/BUILD/GIT_DIFF/FILE_NOT_EXISTS/JUNIT_XML are supplemental and must use covers:[].
FOCUSED_TEST uses the current stack's safe direct test argv; SELF_CHECK includes successMarker and
must emit that marker on success; source-text searches such as grep/rg are not behavior SELF_CHECK
commands. Every criterion must either be covered by one native behavior evidence item or provide
both judgeRubric and judgeOnlyReason for a genuinely Judge-only result. Every JAVA_PRODUCTION Stage
must include a focused Maven/Gradle TEST even if all criteria are Judge-only; that gate may use
covers:[] but FULL_TEST and BUILD never replace it. Merge Java wiring/demo work into the related
focused-test Stage instead of emitting a production-only final Stage. Criteria contain only
observable business outcomes. Code style, source shape, annotations, assembly shape, build success,
and test success stay in deliverables or supplemental evidence instead of becoming criteria.
Shells, pipes, redirects, unsafe paths, fake tests, and missing tests required by the frozen Role Pack
are still rejected by the server validator.
