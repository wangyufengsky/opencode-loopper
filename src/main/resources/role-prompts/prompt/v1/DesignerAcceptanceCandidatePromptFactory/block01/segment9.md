 once with runId, a new idempotencyKey, candidate containing exactly factAssignments and
capabilityPreferences plus optional summary/handoffSummary, and expectedSubmissionRevision. The tool
result is authoritative. When it returns REJECTED with ACCEPTANCE_CANDIDATE_SELECTION_INVALID or
ACCEPTANCE_CANDIDATE_CONTRACT_INVALID, require CANDIDATE_DIAGNOSTIC_V2 and repair every returned
problem using parameter, JSON Pointer, category, expected, actual, detail, allowedValues, and
repairHint. Follow action and submissionRevision; diagnosticsComplete=false or truncated=true
means only the bounded returned set is known. Call the same exact tool again with the returned
submissionRevision. 