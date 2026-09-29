import type { WorkflowGraph, WorkflowInput, WorkflowPreset } from '@/types/domain'
import { clone, connect } from './graph'
export function presetSources(graph: WorkflowGraph, kind: WorkflowInput['kind']) {
  return [...graph.inputs.filter(input => input.kind === kind).map(input => ({ value: `REQUIREMENT|${input.name}|`, title: `公共资料 · ${input.title}` })),
    ...graph.nodes.flatMap(node => node.outputs.filter(output => output.kind === kind).map(output => ({ value: `NODE|${node.id}|${output.name}`, title: `${node.title} · ${output.title}` })))]
}
export function appendPreset(graph: WorkflowGraph, preset: WorkflowPreset, values: Record<string, string>) {
  const node = clone(preset.node); node.id = `n_${crypto.randomUUID()}`; node.inputs = []
  for (const input of preset.inputs) {
    const selection = values[input.name]
    if (!selection) { if (input.required) throw new Error(`请选择“${input.title}”的输入来源。`); continue }
    if (!presetSources(graph, input.kind).some(source => source.value === selection)) throw new Error(`“${input.title}”的来源已变化，请重新选择。`)
    const [source, sourceId, output] = selection.split('|')
    node.inputs.push({ name: input.name, source: source as WorkflowInput['source'], sourceId: sourceId!, output: output || null, kind: input.kind, required: true })
  }
  let next = { ...graph, nodes: [...graph.nodes, node] }
  for (const parent of new Set(node.inputs.filter(input => input.source === 'NODE').map(input => input.sourceId))) next = connect(next, parent, node.id)
  return { graph: next, node }
}
