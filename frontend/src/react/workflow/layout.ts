import type { WorkflowGraph, WorkflowLayout, WorkflowPoint } from '@/types/domain'
import { autoLayout, NODE_HEIGHT, NODE_WIDTH } from '@/components/workflow/graph'

export function copyLayout(layout: WorkflowLayout): WorkflowLayout {
  return { ...layout, positions: Object.fromEntries(Object.entries(layout.positions).map(([id, point]) => [id, { ...point }])) }
}

export function positionOf(graph: WorkflowGraph, layout: WorkflowLayout, id: string): WorkflowPoint {
  return layout.positions[id] ?? autoLayout(graph)[id] ?? { x: 0, y: 0 }
}

export function fitLayout(graph: WorkflowGraph, layout: WorkflowLayout, width: number, height: number): WorkflowLayout {
  const defaults = autoLayout(graph), points = graph.nodes.map(node => layout.positions[node.id] ?? defaults[node.id]!)
  const x = Math.min(0, ...points.map(point => point.x)), y = Math.min(0, ...points.map(point => point.y))
  const graphWidth = Math.max(300, ...points.map(point => point.x + NODE_WIDTH)) - x
  const graphHeight = Math.max(200, ...points.map(point => point.y + NODE_HEIGHT)) - y
  const zoom = Math.max(.1, Math.min(1.25, (width - 80) / graphWidth, (height - 100) / graphHeight))
  return { ...copyLayout(layout), zoom, x: (width - graphWidth * zoom) / 2 - x * zoom, y: (height - graphHeight * zoom) / 2 - y * zoom }
}

export function revealLayout(graph: WorkflowGraph, layout: WorkflowLayout, id: string, width: number, height: number): WorkflowLayout {
  const point = positionOf(graph, layout, id), zoom = Math.min(layout.zoom, 1.25), available = width > 760 ? width - 380 : width
  return { ...copyLayout(layout), zoom, x: (available - NODE_WIDTH * zoom) / 2 - point.x * zoom, y: (height - NODE_HEIGHT * zoom) / 2 - point.y * zoom }
}

export function zoomLayout(layout: WorkflowLayout, amount: number, width: number, height: number): WorkflowLayout {
  const zoom = Math.max(.1, Math.min(4, layout.zoom * amount)), ratio = zoom / layout.zoom
  return { ...copyLayout(layout), zoom, x: width / 2 - (width / 2 - layout.x) * ratio, y: height / 2 - (height / 2 - layout.y) * ratio }
}
