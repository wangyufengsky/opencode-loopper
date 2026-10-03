import { useLayoutEffect, useRef, useState } from 'react'
import { workflowApi } from '@/api/workflow'
import { appendPreset, presetSources } from '@/components/workflow/presets'
import { UiActionButton } from '@/foundation/components'
import { semanticName } from '@/foundation/semanticRegistry'
import { userFacingError } from '@/utils/displayLabels'
import type { WorkflowGraph, WorkflowNode, WorkflowPreset, WorkflowPresetSummary } from '@/types/domain'

export function WorkflowPresetPicker({ graph, disabled, onInsert, onClose }: { graph: WorkflowGraph; disabled?: boolean; onInsert(graph: WorkflowGraph, node: WorkflowNode): void; onClose(): void }) {
  const [query, setQuery] = useState(''), [rows, setRows] = useState<WorkflowPresetSummary[]>([]), [cursor, setCursor] = useState<string | null>(null), [preset, setPreset] = useState<WorkflowPreset | null>(null), [values, setValues] = useState<Record<string, string>>({}), [loading, setLoading] = useState(false), [error, setError] = useState('')
  const generation = useRef(0), alive = useRef(false), catalogQuery = useRef(''), latest = useRef({ graph, disabled, onInsert })
  latest.current = { graph, disabled, onInsert }
  const current = (ticket: number) => alive.current && generation.current === ticket
  async function list(more = false) {
    const ticket = ++generation.current; setLoading(true); setError(''); if (!more) { catalogQuery.current = query; setRows([]); setCursor(null); setPreset(null) }
    try { const page = await workflowApi.presets(catalogQuery.current, more ? cursor || '' : ''); if (current(ticket)) { setRows(previous => more ? [...previous, ...page.items] : page.items); setCursor(page.nextCursor ?? null) } }
    catch (cause) { if (current(ticket)) setError(userFacingError(cause, '预设模块读取失败，请重试。')) } finally { if (current(ticket)) setLoading(false) }
  }
  async function choose(row: WorkflowPresetSummary) { if (latest.current.disabled) return; const ticket = ++generation.current; setLoading(true); setError(''); setPreset(null); setValues({}); try { const result = await workflowApi.preset(row.id, row.version); if (current(ticket)) { if (result.id !== row.id || result.version !== row.version) throw new Error('预设版本与所选模块不一致。'); setPreset(result) } } catch (cause) { if (current(ticket)) setError(userFacingError(cause, '预设详情读取失败，请重试。')) } finally { if (current(ticket)) setLoading(false) } }
  useLayoutEffect(() => { alive.current = true; void list(); return () => { alive.current = false; generation.current++ } }, [])
  return <section className="workflow-presets" aria-label="预设工作模块"><header className="w2-actions"><h2>预设工作模块</h2><UiActionButton actionKey="ui.close" target="预设工作模块" onAction={onClose} /></header><p>预设包含已发布角色版本和标准交付物，选择输入来源后添加到画布。</p><form className="w2-actions" onSubmit={e => { e.preventDefault(); void list() }}><input value={query} aria-label="搜索预设模块" placeholder="搜索名称或说明" onChange={e => setQuery(e.target.value)} /><UiActionButton actionKey="ui.search" target="预设模块" busy={loading} onAction={() => { void list() }} /></form>
    {loading && <p role="status">读取模块…</p>}{error && <p role="alert">{error}<UiActionButton actionKey="ui.retry" onAction={() => { void list() }} /></p>}<div className="workflow-preset-list">{rows.map(row => <button type="button" key={row.id} disabled={disabled || loading} aria-label={semanticName('selection.select', row.title)} aria-pressed={preset?.id === row.id} onClick={() => { void choose(row) }}><strong>{row.title}</strong><span>{row.description}</span></button>)}</div>{cursor && <UiActionButton actionKey="ui.loadMore" target="预设" busy={loading} onAction={() => { void list(true) }} />}{!loading && !rows.length && <p>暂无已发布预设。仍可使用自由任务或人工检查。</p>}
    {preset && <div className="workflow-preset-detail"><h3>{preset.title}</h3><p>{preset.description}</p><p>{preset.roleName ? `${preset.roleName} · 版本 ${preset.roleRevisionNumber}` : preset.node.kind === 'SYSTEM' ? '程序步骤，无模型角色' : '人工检查，无模型会话'}</p><p>{preset.node.task}</p><fieldset disabled={disabled}>{preset.inputs.map(input => <label key={input.name}>{input.title}{input.required && ' *'}<select value={values[input.name] || ''} onChange={e => setValues(previous => ({ ...previous, [input.name]: e.target.value }))}><option value="">{input.required ? '请选择输入来源' : '可选，不绑定'}</option>{presetSources(graph, input.kind).map(source => <option key={source.value} value={source.value}>{source.title}</option>)}</select></label>)}<p>交付物：{preset.node.outputs.map(item => item.title).join('、')}</p><UiActionButton actionKey="workflow.addNode" target={preset.title} variant="primary" onAction={() => { if (latest.current.disabled) return; try { const result = appendPreset(latest.current.graph, preset, values); latest.current.onInsert(result.graph, result.node) } catch (cause) { setError(userFacingError(cause, '预设暂时不能添加。')) } }} /></fieldset></div>}
  </section>
}
