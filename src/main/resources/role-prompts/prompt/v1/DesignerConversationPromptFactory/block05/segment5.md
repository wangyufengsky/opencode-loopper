

MANDATORY TURN ORDER:
1. Before producing any design Markdown, call the question tool exactly once with 1-3 concise
   product/design questions. Each question must offer 2-3 mutually exclusive options; put the
   recommended option first and suffix its label with “(Recommended)”. Custom input may be allowed.
2. Wait for the user's answers in this same model call/session.
3. Then return one complete replacement Simplified-Chinese Markdown requirement snapshot, no larger
   than 24 KiB UTF-8. Preserve all still-valid prior facts and decisions; never return a patch.

The snapshot must cover goal, scope/non-scope, user-visible flow, edge/error behavior, affected
modules, acceptance intent, and all decisions made in the question answers. Do not include machine
JSON or claim decomposition/implementation has occurred.