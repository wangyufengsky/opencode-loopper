import { workflowApi } from '@/api/workflow'
import type { WorkflowGraph, WorkflowLayout, WorkflowReceipt, WorkflowTemplate } from '@/types/domain'
import { clone } from './graph'

export interface WorkflowDraft { title: string; description: string; graph: WorkflowGraph; layout: WorkflowLayout }
export interface WorkflowSave {
  id: string | null
  draft: WorkflowDraft
  graphKey: string
  layoutKey: string
  version: number
  revision: number
  graphReceipt?: WorkflowReceipt
  layoutReceipt?: WorkflowReceipt
  copySource?: { id: string; revision: number }
}
const content = (draft: WorkflowDraft) => JSON.stringify([draft.title, draft.description, draft.graph])
export function prepareSave(draft: WorkflowDraft, base: WorkflowTemplate | null): WorkflowSave {
  const operation: WorkflowSave = { id: base?.id ?? null, draft: clone(draft), graphKey: crypto.randomUUID(), layoutKey: crypto.randomUUID(), version: base?.version ?? 0, revision: base?.revision ?? 0 }
  if (base && content(draft) === content(base)) operation.graphReceipt = { id: base.id, revision: base.revision, version: base.version, layoutVersion: base.layoutVersion, state: 'ACTIVE' }
  return operation
}
/** Keep immutable command bodies and successful receipts across an uncertain network result. */
export async function saveDraft(operation: WorkflowSave): Promise<WorkflowTemplate> {
  if (!operation.graphReceipt) {
    const { title, description, graph, layout } = operation.draft
    operation.graphReceipt = operation.copySource
      ? await workflowApi.copy(operation.copySource.id, { requestKey: operation.graphKey, sourceRevision: operation.copySource.revision, title })
      : operation.id
      ? await workflowApi.revise(operation.id, { requestKey: operation.graphKey, expectedVersion: operation.version, expectedRevision: operation.revision, title, description, graph })
      : await workflowApi.create({ requestKey: operation.graphKey, title, description, graph, layout })
    if (!operation.id) operation.layoutReceipt = operation.graphReceipt
  }
  if (!operation.layoutReceipt) {
    const receipt = operation.graphReceipt
    operation.layoutReceipt = await workflowApi.layout(receipt.id, { requestKey: operation.layoutKey, expectedRevision: receipt.revision, expectedLayoutVersion: receipt.layoutVersion, layout: operation.draft.layout })
  }
  return workflowApi.get(operation.graphReceipt.id)
}
export function prepareCopy(base: WorkflowTemplate): WorkflowSave {
  return { ...prepareSave({ ...base, title: `${base.title} 副本`.slice(0, 120) }, null), copySource: { id: base.id, revision: base.revision } }
}
