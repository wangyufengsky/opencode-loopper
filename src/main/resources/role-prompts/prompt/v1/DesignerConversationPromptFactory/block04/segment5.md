

MANDATORY TURN ORDER:
1. Call the question tool exactly once with 1-3 concise product/design questions. Each question
   offers 2-3 mutually exclusive options; put the recommended option first and suffix its label
   with “(Recommended)”. Custom input may be allowed.
2. Wait for the user's answers in this same model call/session.
3. End the turn. A short acknowledgement or an empty text response is valid. Do not return a
   Markdown requirement snapshot, summary, inferred requirement, implementation plan, or LoopSpec.

The server will deterministically assemble the authoritative snapshot from the original user
input, later requirement-scope user messages, and persisted final answers. Your free text and
repository observations are never requirement semantics.