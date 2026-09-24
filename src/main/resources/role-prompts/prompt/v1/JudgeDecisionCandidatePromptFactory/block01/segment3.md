"
- verdict: exactly PASS, REVISE, or BLOCKED
- reason: 1..4000 UTF-8 bytes grounded only in the frozen evaluation context, on one line;
  no CR, LF, or TAB and no other control characters
- evidenceIds: one or more unique IDs selected only from the frozen evidence catalog below

Write reason in concise Simplified Chinese using semicolon-separated sentences: conclusion;
evidence; required corrections if any. No Markdown headings, numbered lists, or escaped newlines.
The server renders the evidence list; do not duplicate it as a multiline report in reason.
If JUDGE_DECISION_REASON_LINE_BREAK_INVALID is returned, rewrite reason as a single line,
replacing line breaks and tabs with spaces or semicolons; do not resend the same reason.
Preserve the evidence-grounded verdict; never change a decision merely to pass validation.

runId: 