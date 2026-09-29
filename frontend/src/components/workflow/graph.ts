import type { WorkflowGraph, WorkflowLayout, WorkflowNode, WorkflowPoint } from '@/types/domain'
export const NODE_WIDTH = 224, NODE_HEIGHT = 118
export const emptyGraph = (): WorkflowGraph => ({ schemaVersion: 1, nodes: [], edges: [], inputs: [] })
export const emptyLayout = (): WorkflowLayout => ({ positions: {}, x: 32, y: 36, zoom: 1 })
export const clone = <T>(value: T): T => JSON.parse(JSON.stringify(value)) as T
export function newNode(module: 'free.readonly' | 'free.write' | 'human'): WorkflowNode {
  const human = module === 'human', writer = module === 'free.write'
  return { id: `n_${crypto.randomUUID()}`, title: human ? '人工检查' : writer ? '文件工作' : '只读分析', kind: human ? 'HUMAN' : 'WORK',
    moduleId: human ? null : module, moduleVersion: human ? 0 : 1, roleId: null, roleRevisionId: null, task: '', inputs: [],
    outputs: writer ? [{ name: 'code', title: '代码交付', kind: 'CODE', required: true }, { name: 'result', title: '工作说明', kind: 'TEXT', required: true }]
      : [{ name: 'result', title: human ? '检查结果' : '分析结果', kind: 'TEXT', required: true }], outcomes: [],
    completion: { kind: human ? 'HUMAN' : 'DELIVERABLES', criterion: human ? '人工确认结果' : '提交有效交付物', expectedOutcome: null },
    maxRetries: 0, pauseAfter: human, parameters: {} }
}
export function connect(graph: WorkflowGraph, from: string, to: string): WorkflowGraph {
  if (from === to || !graph.nodes.some(n => n.id === from) || !graph.nodes.some(n => n.id === to)) throw new Error('请选择不同的已有节点。')
  if (graph.edges.some(e => e.from === from && e.to === to)) throw new Error('这两个节点已经连接。')
  const pending = [to], seen = new Set<string>()
  while (pending.length) {
    const id = pending.pop()!
    if (id === from) throw new Error('这条连接会形成循环，请调整前后顺序。')
    if (seen.has(id)) continue
    seen.add(id); pending.push(...graph.edges.filter(e => e.from === id).map(e => e.to))
  }
  return { ...graph, edges: [...graph.edges, { id: `e_${crypto.randomUUID()}`, from, to, outcome: null }] }
}
export function outcomeTitle(node: WorkflowNode | undefined, outcome: string): string {
  if (!node) return '业务结果'
  const titles = outcomeTitles(node), defaults = new Map([['pass', '通过'], ['fail', '未通过'], ['revise', '需要修复'], ['success', '成功'], ['failure', '失败']])
  return (Object.prototype.hasOwnProperty.call(titles, outcome) ? titles[outcome] : '') || defaults.get(outcome) || `业务结果 ${node.outcomes.indexOf(outcome) + 1}`
}
export function outcomeTitles(node: WorkflowNode): Record<string, string> {
  try {
    const value: unknown = JSON.parse(node.parameters.outcomeTitles || '{}')
    if (!value || typeof value !== 'object' || Array.isArray(value)) return {}
    return Object.fromEntries(Object.entries(value).filter((entry): entry is [string, string] => typeof entry[1] === 'string'))
  } catch { return {} }
}
export function removeNode(graph: WorkflowGraph, id: string): WorkflowGraph {
  const users = graph.nodes.filter(n => n.id !== id && n.inputs.some(input => input.source === 'NODE' && input.sourceId === id))
  if (users.length) throw new Error(`请先调整“${users.map(n => n.title).join('、')}”使用的输入，再删除节点。`)
  return { ...graph, nodes: graph.nodes.filter(n => n.id !== id), edges: graph.edges.filter(e => e.from !== id && e.to !== id) }
}
export function autoLayout(graph: WorkflowGraph): Record<string, WorkflowPoint> {
  const depth = new Map<string, number>(), pending = [...graph.nodes]
  for (let round = 0; pending.length && round <= graph.nodes.length; round++) {
    for (let i = pending.length - 1; i >= 0; i--) {
      const node = pending[i]!, parents = graph.edges.filter(e => e.to === node.id).map(e => e.from)
      if (parents.every(id => depth.has(id))) { depth.set(node.id, parents.length ? Math.max(...parents.map(id => depth.get(id)!)) + 1 : 0); pending.splice(i, 1) }
    }
  }
  const levels = new Map<number, WorkflowNode[]>()
  for (const node of graph.nodes) { const level = depth.get(node.id) ?? 0; levels.set(level, [...(levels.get(level) ?? []), node]) }
  const result: Record<string, WorkflowPoint> = {}, widest = Math.max(1, ...[...levels.values()].map(nodes => nodes.length))
  for (const [level, nodes] of levels) nodes.forEach((node, index) => { result[node.id] = { x: (index + (widest - nodes.length) / 2) * 280, y: level * 190 } })
  return result
}
