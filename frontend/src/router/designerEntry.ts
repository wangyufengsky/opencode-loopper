
/** Keep explicit historical sessions reachable while sending new work to the requirement canvas. */
export function designerEntry(to: { query: Record<string, string | string[] | null | undefined> }) {
  if (typeof to.query.sessionId === 'string' && to.query.sessionId.trim()) return true
  const projectId = typeof to.query.projectId === 'string' ? to.query.projectId : ''
  try {
    if (!projectId) {
      const saved = JSON.parse(sessionStorage.getItem('opencode-loopper.designer-workspace') || 'null')
      if (saved && typeof saved.sessionId === 'string' && saved.sessionId.trim()) {
        return { path: '/designer', query: { sessionId: saved.sessionId } }
      }
    }
  } catch { /* Unavailable or malformed local storage must not block the new entry. */ }
  return { path: '/requirements/new', query: { ...(projectId ? { projectId } : {}), legacyDraft: '1' } }
}
