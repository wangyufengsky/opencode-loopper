import { useEffect, useRef, useState } from 'react'
import { api } from '@/api/client'
import type { InsightQuery, TaskInsight } from '@/types/domain'
import { TASK_STATUSES } from '@/types/states'
import { statusLabel } from '@/utils/displayLabels'
import { UiActionButton, UiContextPanel } from '@/foundation/components'
import { SemanticIcon, type UiSemanticKey } from '@/foundation/semanticRegistry'
import { PageChrome, PageLink } from '../shared'
import type { W2PageProps } from '../shared/types'
import { ReadFeedback } from './feedback'
import { useLatestRead } from './state'
import './secondary.css'

const blank = () => ({ projectId: '', state: '', quality: '', archive: 'ACTIVE', query: '' })
const quality: Record<TaskInsight['quality']['state'], { label: string; key: UiSemanticKey }> = {
  PASS: { label: '质量通过', key: 'status.success' }, PENDING: { label: '待验收', key: 'status.idle' }, REVIEW_REQUIRED: { label: '待评审', key: 'status.waiting' },
}
const tokens = (value: number | null | undefined) => value == null ? '未知' : value.toLocaleString('zh-CN')
export function InsightsPage(props: W2PageProps) {
  const [filters, setFilters] = useState(blank), applied = useRef<InsightQuery>({ archive: 'ACTIVE' }), [append, setAppend] = useState(false)
  const mode = useRef(false), cursor = useRef<string | undefined>(undefined), [selected, setSelected] = useState<TaskInsight | null>(null), trigger = useRef<HTMLElement | null>(null)
  const data = useLatestRead(async () => api.getInsightsPage({ ...applied.current, cursor: mode.current ? cursor.current : undefined }), { tasks: [] as TaskInsight[], usage: { totalTokens: null, unknownUsageCount: 0, costByCurrency: {} }, nextCursor: undefined } as Awaited<ReturnType<typeof api.getInsightsPage>>)
  const projects = useLatestRead(api.getProjects, [] as Awaited<ReturnType<typeof api.getProjects>>)
  const refresh = async (more = false) => {
    mode.current = more; const old = data.value.tasks; const result = await data.reload(result => more ? { ...result, tasks: [...new Map([...old, ...result.tasks].map(task => [task.taskId, task])).values()] } : result)
    if (result) data.apply(result, () => { cursor.current = result.nextCursor; setAppend(more) })
  }
  useEffect(() => { void refresh(); void projects.reload() }, [])
  const apply = () => { applied.current = { ...filters }; cursor.current = undefined; setSelected(null); void refresh() }
  return <PageChrome title="用量与质量" objectKey="nav.insights" actions={<UiActionButton actionKey="ui.refresh" busy={data.loading} onAction={() => { void refresh() }} />}
    status={<ReadFeedback error={data.error} loading={data.loading && !append} retry={() => { void refresh() }} />}
    context={<UiContextPanel open={!!selected} title={selected?.title ?? '任务洞察'} returnFocus={trigger} onClose={() => setSelected(null)}>{selected && <><dl className="w2-secondary-definition"><div><dt>用量</dt><dd>{tokens(selected.usage.totalTokens)}{selected.usage.unknownUsageCount ? ` + ${selected.usage.unknownUsageCount} 未知` : ''}</dd></div><div><dt>耗时</dt><dd>{Math.round(selected.durationMs / 1000)} 秒</dd></div><div><dt>重试</dt><dd>{selected.retryCount}</dd></div><div><dt>质量</dt><dd>{selected.quality.deterministicPassed ? '验收通过' : '验收待定'} · {selected.quality.humanApproved ? '人工认定通过' : selected.quality.requirementJudgePassed && selected.quality.riskJudgePassed ? 'AI 双评审通过' : 'AI 评审供参考'}</dd></div></dl><PageLink to={`/tasks/${selected.taskId}#judge-review`} navigation={props.navigation}>{quality[selected.quality.state].label}，查看任务评审</PageLink></>}</UiContextPanel>}>
    <div className="w2-secondary-grid"><form className="w2-secondary-toolbar" aria-label="洞察筛选" onSubmit={event => { event.preventDefault(); apply() }}>
      <label>项目筛选<select value={filters.projectId} onChange={event => setFilters({ ...filters, projectId: event.target.value })}><option value="">全部项目</option>{projects.value.map(project => <option key={project.id} value={project.id}>{project.name}</option>)}</select></label>
      <label>任务状态筛选<select value={filters.state} onChange={event => setFilters({ ...filters, state: event.target.value })}><option value="">全部状态</option>{TASK_STATUSES.map(state => <option key={state} value={state}>{statusLabel(state)}</option>)}</select></label>
      <label>质量筛选<select value={filters.quality} onChange={event => setFilters({ ...filters, quality: event.target.value })}><option value="">全部质量</option>{Object.entries(quality).map(([key, value]) => <option key={key} value={key}>{value.label}</option>)}</select></label>
      <label>归档筛选<select value={filters.archive} onChange={event => setFilters({ ...filters, archive: event.target.value })}><option value="ACTIVE">未归档</option><option value="ARCHIVED">已归档</option><option value="ALL">全部归档状态</option></select></label>
      <label>搜索任务标题<input value={filters.query} maxLength={200} onChange={event => setFilters({ ...filters, query: event.target.value })} /></label><button type="submit">筛选</button><UiActionButton actionKey="ui.resetFilters" onAction={() => { setFilters(blank()); applied.current = { archive: 'ACTIVE' }; cursor.current = undefined; void refresh() }} />
    </form>
      <section className="w2-secondary-metrics" aria-label="筛选范围用量"><article className="w2-secondary-record">筛选范围总用量<strong>{tokens(data.value.usage?.totalTokens)}</strong><small>{data.value.usage?.unknownUsageCount ?? 0} 条未知记录</small></article><article className="w2-secondary-record">已加载且有用量的任务<strong>{data.value.tasks.filter(task => task.usage.totalTokens != null).length}</strong></article><article className="w2-secondary-record">成本（按币种）<strong>{Object.entries(data.value.usage?.costByCurrency ?? {}).map(([currency, amount]) => `${currency} ${amount}`).join(' · ') || '未知'}</strong></article></section>
      <section aria-label="任务洞察" className="w2-secondary-list">{data.value.tasks.map(task => <button key={task.taskId} className="w2-secondary-select" aria-pressed={selected?.taskId === task.taskId} onClick={event => { trigger.current = event.currentTarget; setSelected(task) }}><strong>{task.title}</strong><small>{statusLabel(task.state)} · <span className="quality" title={quality[task.quality.state].label}><SemanticIcon semanticKey={quality[task.quality.state].key} />{quality[task.quality.state].label}</span> · {tokens(task.usage.totalTokens)}</small></button>)}{!data.loading && !data.error && !data.value.tasks.length && <p>暂无可汇总数据</p>}</section>
      {data.value.nextCursor && <UiActionButton actionKey="ui.loadMore" busy={data.loading} onAction={() => { void refresh(true) }} />}
    </div>
  </PageChrome>
}
