import { ApiError } from '@/api/client'
import { definitiveW4Rejection } from '@/pages/w4/shared/core'
import { workflowRuns } from '@/api/workflowRuns'
import { clone } from '@/components/workflow/graph'
import type { WorkflowGraph, WorkflowLayout, WorkflowReceipt, WorkflowTemplateMode, WorkflowTemplatePreview } from '@/types/domain'
import { createRequirementScope, ownedState } from './core'

export function createSaveTemplateController(requirement: string, captured: { revision: number; title: string; graph: WorkflowGraph; layout: WorkflowLayout }) {
  const snapshot = clone(captured)
  const owner = createRequirementScope('requirement-save-template', requirement, { ...ownedState(), mode: 'CURRENT' as WorkflowTemplateMode, title: `${snapshot.title} · 流程`.slice(0, 120), description: '', preview: null as WorkflowTemplatePreview | null, initialAvailable: true, saved: null as WorkflowReceipt | null, selected: '', edge: '', roleNames: {} as Record<string, string> })
  const selection = () => ({ expectedRevision: snapshot.revision, mode: owner.getSnapshot().mode, graph: clone(snapshot.graph), layout: clone(snapshot.layout) })
  async function read() {
    if (!owner.active() || owner.locked() || owner.getSnapshot().saved) return
    const ticket = owner.ticket('template-preview'), original = selection(); owner.patch({ loading: true, preview: null, error: '', selected: '', edge: '' })
    try { const preview = await workflowRuns.previewTemplate(requirement, original); if (!ticket.current()) return; if (preview.mode !== original.mode) throw new Error('模板预览与所选结构不一致，请重新生成。'); owner.patch({ preview, initialAvailable: preview.initialAvailable }) }
    catch (cause) { if (ticket.current()) owner.fail(cause, '暂时无法预览，请重试。') }
    finally { if (ticket.current()) owner.patch({ loading: false }) }
  }
  async function save() {
    const s = owner.getSnapshot(); if (!s.preview || s.loading || owner.locked() || s.saved || !s.title.trim() || s.title.length > 120 || s.description.length > 4000) return
    const body = { requestKey: crypto.randomUUID(), title: s.title.trim(), description: s.description.trim(), selection: selection(), previewSha256: s.preview.sha256 }
    await owner.mutate({ label: '另存为流程模板', input: { endpoint: `/workflows/requirements/${encodeURIComponent(requirement)}/templates`, method: 'POST', requestKey: body.requestKey, body, versions: { revision: snapshot.revision } }, rejected: cause => definitiveW4Rejection(cause) || cause instanceof ApiError && cause.status === 409 && ['WORKFLOW_VERSION_CONFLICT', 'WORKFLOW_TEMPLATE_PREVIEW_CHANGED'].includes(cause.code ?? ''), write: original => workflowRuns.saveTemplate(requirement, original), read: async (receipt, context) => { if (!receipt.id || !Number.isInteger(receipt.revision)) throw new Error('保存回执无法核对，请保留原操作。'); context.apply(() => owner.patch({ saved: receipt, dirty: false })) } })
    if (owner.getSnapshot().command.phase === 'SETTLED' && !owner.getSnapshot().command.accepted) owner.patch({ preview: null })
  }
  owner.setStart(() => { void read() })
  return Object.assign(owner, { requirement, captured: snapshot, selection, read, save,
    change(values: { title?: string; description?: string }) { owner.edit(values) },
    mode(mode: WorkflowTemplateMode) { if (owner.locked() || mode === 'INITIAL' && !owner.getSnapshot().initialAvailable) return; owner.edit({ mode }); void read() },
    select(selected: string, edge = '') { owner.patch({ selected, edge }) },
    discard() { if (!owner.locked()) owner.patch({ dirty: false }) },
  })
}
