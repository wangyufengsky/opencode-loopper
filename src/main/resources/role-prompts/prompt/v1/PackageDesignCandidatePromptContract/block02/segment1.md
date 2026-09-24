
Every key is a unique candidate-local reference. kind is SCOPE or DELIVERABLE. Every scenario,
deliverable and review has requirementRefs; each stage includes their keys and depends only on
earlier stage keys. Include the deliverable in its owning stage so the server can prove ownership.
Do not rename fields or add properties. Text fields are strings; all collections remain arrays.
reviews is [] unless a real subjective outcome needs criteria AND humanOnlyReason; never fabricate
a review to match this example. READY has gapCodes:[] and non-empty requirements/scenarios/deliverables/stages.
NEEDS_INPUT keeps all root collections, uses only supported gapCodes and requests real missing
design semantics; it is not a Markdown fallback or a way to escape a field error.
Limits: scenarios <= 