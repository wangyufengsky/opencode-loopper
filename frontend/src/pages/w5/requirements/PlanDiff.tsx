import type { WorkflowGraph, WorkflowNode } from '@/types/domain'
const same = (a: unknown, b: unknown) => JSON.stringify(a) === JSON.stringify(b)
const incoming = (graph: WorkflowGraph, key: string) => graph.edges.filter(edge => edge.to === key).sort((a, b) => a.id.localeCompare(b.id))
export function PlanDiff({ before, after }: { before: WorkflowGraph; after: WorkflowGraph }) {
  const added = after.nodes.filter(node => !before.nodes.some(old => old.id === node.id)), removed = before.nodes.filter(node => !after.nodes.some(next => next.id === node.id))
  const changed = after.nodes.flatMap(next => { const old = before.nodes.find(node => node.id === next.id); return old && (!same(old, next) || !same(incoming(before, next.id), incoming(after, next.id))) ? [{ old, next }] : [] })
  function fields(old: WorkflowNode, next: WorkflowNode) {
    const changes: string[] = []
    if (!same(old.inputs, next.inputs)) changes.push('输入来源')
    if (!same(old.outputs, next.outputs)) changes.push('预期交付物')
    if (!same(old.completion, next.completion)) changes.push('完成规则')
    if (!same(old.outcomes, next.outcomes)) changes.push('业务结果')
    if (old.roleId !== next.roleId || old.roleRevisionId !== next.roleRevisionId) changes.push('角色或版本')
    if (old.moduleId !== next.moduleId || old.moduleVersion !== next.moduleVersion || old.kind !== next.kind) changes.push('工作类型')
    if (old.maxRetries !== next.maxRetries) changes.push('重试次数')
    if (old.pauseAfter !== next.pauseAfter) changes.push('执行后暂停')
    if (!same(old.parameters, next.parameters)) changes.push('工作参数')
    if (!same(incoming(before, old.id), incoming(after, next.id))) changes.push('前置依赖')
    return changes.join('、')
  }
  const ids = new Set([...added, ...removed, ...changed.map(value => value.next)].map(node => node.id)), direct = new Set(ids)
  let more = true; while (more) { more = false; for (const edge of [...before.edges, ...after.edges]) if (ids.has(edge.from) && !ids.has(edge.to)) { ids.add(edge.to); more = true } }
  const affected = after.nodes.filter(node => ids.has(node.id) && !direct.has(node.id))
  return <details><summary>查看变更：新增 {added.length}，修改 {changed.length}，移除 {removed.length}</summary><ul>{added.map(node => <li key={`add-${node.id}`}><strong>新增 · {node.title}</strong><p>{node.task}</p></li>)}{changed.map(({ old, next }) => <li key={`change-${next.id}`}><strong>修改 · {next.title}</strong>{old.title !== next.title && <p>原名称：{old.title}</p>}{old.task !== next.task && <><p><b>原任务</b>{old.task}</p><p><b>调整后</b>{next.task}</p></>}{fields(old, next) && <p>调整项：{fields(old, next)}。可在画布选中节点查看。</p>}</li>)}{removed.map(node => <li key={`remove-${node.id}`}><strong>移除 · {node.title}</strong><p>{node.task}</p></li>)}</ul>{!!affected.length && <p>受影响的后续节点：{affected.map(node => node.title).join('、')}</p>}{!added.length && !changed.length && !removed.length && <p>节点与依赖保持原样。</p>}</details>
}
