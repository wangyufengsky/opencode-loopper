` with runId, a fresh idempotencyKey,
the candidate object, and expectedSubmissionRevision. candidate must be a JSON object, not a JSON-encoded string.
Do not return the candidate as final assistant text. On REJECTED, require
CANDIDATE_DIAGNOSTIC_V2 and repair every returned problem using parameter, JSON Pointer, category,
expected, actual, detail, allowedValues, and repairHint. Follow action; diagnosticsComplete=false
or truncated=true means only the returned bounded set is known. Use submissionRevision to replace the complete candidate and
call the same tool again in this Session. 