import { useMemo, useState } from 'react'
import type { WorkflowGraph, WorkflowLayout, WorkflowNode, WorkflowNodeSummary, WorkflowRequirementState } from '@/types/domain'
import { NodeRun } from '@/pages/w5/requirements/NodeRun'
import { createNodeController } from '@/pages/w5/requirements/nodeController'
import { FinishPanel } from '@/pages/w5/requirements/Finish'
import { createFinishController } from '@/pages/w5/requirements/finishController'
import { CandidatesPanel } from '@/pages/w5/requirements/Candidates'
import { createCandidatesController } from '@/pages/w5/requirements/candidatesController'
import { SaveTemplatePanel } from '@/pages/w5/requirements/SaveTemplate'
import { createRequirementController } from '@/pages/w5/requirements/controller'
import { useTestPage } from './react-test-root'
/** Read/write ports are injected test boundaries; all protocol state stays in the production owner. */
export function WorkflowNodeRun({ requirement, node, summary, version, onChanged }: { requirement: string; node: WorkflowNode; summary?: WorkflowNodeSummary; version: number; onChanged?(): void }) {
  const parent = useMemo(() => { const value = createRequirementController(requirement); value.refresh = async () => { onChanged?.() }; return value }, [requirement])
  const controller = useMemo(() => createNodeController(requirement, { version, node, summary }), [requirement, node.id])
  return <NodeRun page={useTestPage()} parent={parent} controller={controller} node={node} summary={summary} version={version} />
}
export function WorkflowFinish({ requirement, version, state, disabled, beforeOpen, onChanged }: { requirement: string; version: number; state: WorkflowRequirementState; disabled?: boolean; beforeOpen?(): boolean; onChanged?(): void }) {
  const owner = useMemo(() => createFinishController(requirement, { version, state }), [requirement])
  const parent = useMemo(() => { const value = createRequirementController(requirement); value.refresh = async () => { onChanged?.() }; return value }, [requirement])
  parent.canStartWrite = () => !disabled && (!beforeOpen || beforeOpen())
  return <FinishPanel page={useTestPage()} controller={owner} parent={parent} version={version} state={state} />
}
export function WorkflowCandidates({ requirement, onChanged, onClose }: { requirement: string; onChanged?(): void; onClose?(): void }) {
  const owner = useMemo(() => createCandidatesController(requirement), [requirement]), [visible, setVisible] = useState(true)
  const parent = useMemo(() => { const value = createRequirementController(requirement); value.refresh = async () => { onChanged?.() }; return value }, [requirement])
  return <CandidatesPanel page={useTestPage()} controller={owner} parent={parent} visible={visible} onClose={() => { setVisible(false); onClose?.() }} />
}
export function WorkflowSaveTemplate({ requirement, revision, title, graph, layout, onClose }: { requirement: string; revision: number; title: string; graph: WorkflowGraph; layout: WorkflowLayout; onClose?(): void }) {
  const parent = useMemo(() => createRequirementController(requirement), [requirement]), captured = useMemo(() => ({ revision, title, graph, layout }), [requirement])
  return <SaveTemplatePanel page={useTestPage()} parent={parent} captured={captured} onClose={() => onClose?.()} />
}
