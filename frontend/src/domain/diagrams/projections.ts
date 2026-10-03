import type { RoleSlotBinding, Stage, TemplateTaskProgress } from '@/types/domain'
import { roleWorkflow } from '@/utils/roleWorkflow'

export interface DiagramNode<T extends Record<string, unknown>> {
  id: string
  position: { x: number; y: number }
  width: number
  height: number
  data: T
}
export interface DiagramEdge {
  id: string
  source: string
  target: string
  data: { tone: string }
}
export interface DiagramProjection<T extends Record<string, unknown>> {
  nodes: DiagramNode<T>[]
  edges: DiagramEdge[]
  height: number
}

function sequence<T extends Record<string, unknown>>(nodes: DiagramNode<T>[], tone: (index: number) => string): DiagramProjection<T> {
  return {
    nodes,
    // These edges express the displayed sequence only, never new execution dependencies.
    edges: nodes.slice(1).map((node, index) => ({ id: `sequence-${index}`, source: nodes[index]!.id, target: node.id, data: { tone: tone(index) } })),
    height: Math.max(120, ...nodes.map(node => node.height)) + 118,
  }
}

export function projectStages(stages: Stage[]): DiagramProjection<{ stage: Stage }> {
  return sequence(stages.map((stage, index) => ({
    id: stage.id, data: { stage }, position: { x: index * 322, y: 0 }, width: 292,
    height: Math.max(240, 164 + stage.objective.split('\n').reduce((lines, line) => lines + Math.max(1, Math.ceil(line.length / 19)), 0) * 22),
  })), index => {
    const current = stages[index], next = stages[index + 1]
    return current?.status === 'SUCCEEDED' && next?.status === 'SUCCEEDED' ? 'connector-complete'
      : current?.status === 'SUCCEEDED' && next?.status === 'RUNNING' ? 'connector-active' : 'connector-pending'
  })
}

export interface RoleWorkflowGroup {
  name: string
  steps: ReturnType<typeof roleWorkflow>['steps']
  bindings: RoleSlotBinding[]
}
export function groupRoleWorkflows(bindings: RoleSlotBinding[]): RoleWorkflowGroup[] {
  const groups = new Map<string, RoleWorkflowGroup>()
  for (const binding of bindings) {
    const flow = roleWorkflow(binding.slot), key = JSON.stringify(flow)
    const group = groups.get(key) ?? { ...flow, bindings: [] }
    group.bindings.push(binding)
    groups.set(key, group)
  }
  return [...groups.values()]
}
export function projectRoleSteps(group: RoleWorkflowGroup): DiagramProjection<{ label: string; current: boolean; ordinal: number }> {
  return sequence(group.steps.map((step, index) => ({ id: `step-${index}`, data: { ...step, ordinal: index + 1 }, position: { x: index * 226, y: 0 }, width: 194, height: 110 })), () => 'connector-pending')
}

export type TemplateStep = NonNullable<TemplateTaskProgress['steps']>[number]
export function projectTemplateSteps(steps: TemplateStep[]): DiagramProjection<{ step: TemplateStep }> {
  return sequence(steps.map((step, index) => ({ id: step.key, data: { step }, position: { x: index * 226, y: 0 }, width: 194, height: 116 })), index => {
    const next = steps[index + 1]
    return steps[index]?.state === 'COMPLETE' && next?.state === 'COMPLETE' ? 'connector-complete'
      : steps[index]?.state === 'COMPLETE' && next?.state === 'ACTIVE' ? 'connector-active' : 'connector-pending'
  })
}
