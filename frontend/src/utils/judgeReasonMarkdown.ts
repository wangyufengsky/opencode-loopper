/**
 * Older judge prompts asked for a prose reason and often produced one long
 * paragraph with `(1) ... (2) ...` evidence. Preserve the words while turning
 * that established shape into a Markdown list. Already-structured Markdown is
 * passed through unchanged.
 */
export function judgeReasonMarkdown(reason: string) {
  const trimmed = reason.trim()
  const numberedClauses = [...trimmed.matchAll(/(?:^|\s)[(（]\d+[)）]\s+/g)]
  if (numberedClauses.length < 2) return trimmed

  return trimmed.replace(/(^|\s+)[(（](\d+)[)）]\s+/g, (_match, prefix: string, ordinal: string) => {
    return `${prefix ? '\n\n' : ''}${ordinal}. `
  })
}
