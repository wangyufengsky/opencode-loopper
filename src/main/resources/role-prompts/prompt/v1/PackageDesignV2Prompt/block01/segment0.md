PACKAGE_DESIGN_V2, fixed prompt PACKAGE_PROMPT_V2_20260907_R2.
Express each behavior as: while/precondition, when/action, shall/observableResult; record exceptions and invariants.
requirements capture original obligations; scenarios enumerate observable applicable branches; deliverables describe exact
scoped outputs and native focused tests, including tests to create when the frozen policy permits them.
stages.includes contains candidate-local scenario/review/deliverable keys, each owned exactly once.
all = joint conditions; any = alternative applicable branches, ALL branches still need behavior and coverage;
unless has two operands, base behavior then exception behavior. Operands reference scenarios or relation nodes.
At most 32 relation nodes and 4 relation levels, no cycles. If too complex, request a split; never truncate logic.
sourceBindings map candidateRefs to the frozen sourceRefs below (ordinary REQ-Lxxx, original document DOC-n, or historical requirement RQ-n). Bind every requirement/scenario and preserve every source.
References prove provenance only. Re-check negation, exceptions, state changes, idempotency, compensation, cross-stage invariants.
For each state transition, precondition describes the state BEFORE the triggering action; observableResult describes
the state AFTER it. Never assume the requested post-state in precondition. Keep the initial transition and repeated
requests as separate scenarios. Example: given a claimed/running job, when cancellation first arrives, return stopping
and wait for confirmation; given an already stopping job, a repeated cancellation preserves stopping.
For overlapping conditions, explicitly state priority and exceptions: if an administrator is always allowed,
a revoked-delegation denial must exclude administrators. Check the truth table across all applicable branches.
Frozen file/directory scope is an upper bound. Do not invent another path, including a repository-relative placeholder
for an external destination. A prohibition and a requested forbidden operation are a conflict, never an ANY choice.
Preserve all hard constraints even when proposing NEEDS_INPUT; do not delete them to obtain acceptance.
Never fabricate repository facts, user choices, permissions or executable server fields. Original input outranks preparation.
READY uses gapCodes:[] and gapClaims:[]. NEEDS_INPUT preserves collections and supplies gapClaims with key, closed code,
sourceRefs, question and alternatives. A model's gap code is an unverified claim. Distinguish unknown repository facts,
tests to construct, candidate expression errors, explicitly undecided business alternatives and proven frozen conflicts.
This is a complete SHAPE example; replace its values and sources. Optional reviews may be empty:
