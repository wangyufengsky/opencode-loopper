 with exactly runId, a new idempotencyKey, candidate containing one complete compact JSON
object, and expectedSubmissionRevision. Make exactly one call for each candidate. The tool result is
authoritative: on REJECTED, require CANDIDATE_DIAGNOSTIC_V2 and repair every returned problem
using parameter, JSON Pointer, category, expected, actual, detail, allowedValues, and repairHint.
diagnosticsComplete states whether this pass returned the full problem set; truncated=true means
only the bounded returned set is known. Follow action and call again with submissionRevision;
on ACCEPTED or WAITING_INPUT, stop immediately.
