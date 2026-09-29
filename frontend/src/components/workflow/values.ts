import type { WorkflowOutput, WorkflowValue } from '@/types/domain'
export function readValues(outputs: WorkflowOutput[], text: Record<string, string>): Record<string, WorkflowValue> {
  const values: Record<string, WorkflowValue> = {}
  for (const field of outputs) {
    const value = text[field.name] || ''
    if (!value.trim()) { if (field.required) throw new Error(`请填写“${field.title}”。`); continue }
    if (field.kind === 'TEXT') values[field.name] = { kind: field.kind, content: value }
    else {
      let content: unknown
      try { content = JSON.parse(value) } catch { throw new Error(`“${field.title}”需要有效的 JSON 内容。`) }
      if (!content || typeof content !== 'object' || (field.kind !== 'JSON' && Array.isArray(content))) throw new Error(`请检查“${field.title}”的数据结构。`)
      values[field.name] = { kind: field.kind, content }
    }
  }
  return values
}
