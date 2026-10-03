import type { WorkflowGraph, WorkflowLayout } from '@/types/domain'

export interface WorkflowCanvasHandle {
  fit(): void
  focus(id?: string): void
  reveal(id: string): void
}

export interface WorkflowCanvasProps {
  graph: WorkflowGraph
  layout: WorkflowLayout
  selected?: string
  selectedEdge?: string
  readonly?: boolean
  movable?: boolean
  connecting?: string
  roleNames?: Record<string, string>
  states?: Record<string, string>
  onSelect(id: string): void
  onEdge(id: string): void
  onConnect(id: string): void
  onConnectPair?(from: string, to: string): void
  onLayout(layout: WorkflowLayout): void
  onRemove(id: string): void
  onCancel(): void
  onReady?(handle: WorkflowCanvasHandle | undefined): void
}
