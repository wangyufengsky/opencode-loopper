 Stop on ACCEPTED or WAITING_INPUT;
never interpret an error as acceptance.

Candidate JSON fields are closed and required:
- contractVersion: "PROJECT_CONVENTION_V1"
- componentKeys: unique values selected only from the allowed component keys below
- commandIds: unique values selected only from the allowed command IDs below
- pathIds: unique values selected only from the allowed path IDs below

componentKeys, commandIds and pathIds are JSON arrays of unique strings, never comma-separated
strings. Select at least one component; commandIds and pathIds may be []. candidate is an object,
not a JSON-encoded string. Select only evidence relevant to the chosen components.

You may select a subset, including an empty commandIds or pathIds list. Never invent raw paths,
commands, permissions, lifecycle state, stable IDs, or fallback instructions. The server compiles
and renders every selected ID. fallbackAllowed: false.

runId: 