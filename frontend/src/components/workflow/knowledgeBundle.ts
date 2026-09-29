import type { KnowledgeContent } from '@/types/domain'
export type KnowledgeBundle = { version: 1; type: 'KNOWLEDGE_EVIDENCE'; entries: { reference: string; toolName: string; createdAt: string; sha256: string; content: Record<string, unknown> }[]; limitations: string[] }
export function knowledgeBundle(value: unknown): KnowledgeBundle | null {
  if (!value || typeof value !== 'object') return null
  const data = value as Partial<KnowledgeBundle>
  return data.version === 1 && data.type === 'KNOWLEDGE_EVIDENCE' && Array.isArray(data.entries) && data.entries.length <= 12
    && data.entries.every(entry => entry && typeof entry.toolName === 'string' && typeof entry.reference === 'string' && typeof entry.sha256 === 'string' && typeof entry.createdAt === 'string' && entry.content && typeof entry.content === 'object' && !Array.isArray(entry.content))
    && Array.isArray(data.limitations) && data.limitations.every(item => typeof item === 'string') ? data as KnowledgeBundle : null
}
export function knowledgeBody(content: Record<string, unknown>, name: string): KnowledgeContent {
  const object = (value: unknown) => value != null && typeof value === 'object' && !Array.isArray(value)
  const strings = ['kind', 'sourceId', 'path', 'name', 'sha256', 'text', 'location', 'sql']
  const invalid = strings.some(key => content[key] != null && typeof content[key] !== 'string')
    || ['startLine', 'endLine'].some(key => content[key] != null && (!Number.isSafeInteger(content[key]) || Number(content[key]) < 1))
    || content.limitations != null && (!Array.isArray(content.limitations) || content.limitations.some(item => typeof item !== 'string'))
    || ['items', 'authors'].some(key => content[key] != null && (!Array.isArray(content[key]) || (content[key] as unknown[]).some(item => !object(item))))
    || content.rows != null && (!Array.isArray(content.rows) || content.rows.some(row => !Array.isArray(row)))
    || content.columns != null && (!Array.isArray(content.columns) || content.columns.some(column => typeof column !== 'string' && !object(column)))
  if (invalid) return { kind: 'METADATA', sourceId: '', path: '', name, sha256: '', savedContent: content }
  return { kind: 'METADATA', sourceId: '', path: '', name, sha256: '', ...content } as KnowledgeContent
}
