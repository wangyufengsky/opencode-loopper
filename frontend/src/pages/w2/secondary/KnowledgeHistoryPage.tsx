import { useEffect, useRef, useState } from 'react'
import { api } from '@/api/client'
import { knowledgeApi } from '@/api/knowledge'
import type { KnowledgeConversation } from '@/types/domain'
import { knowledgeStateLabel } from '@/utils/displayLabels'
import { UiActionButton, UiContextPanel } from '@/foundation/components'
import { PageChrome, queryString, useLeaveGuard } from '../shared'
import type { W2PageProps, W2Route } from '../shared/types'
import { ReadFeedback, MutationFeedback } from './feedback'
import { useLatestRead, useMutationOwner } from './state'
import './secondary.css'

type Filters = { project: string; query: string; archive: string; state: string; period: string }
const routeFilters = (route: W2Route): Filters => ({ project: queryString(route, 'project'), query: queryString(route, 'query'), archive: queryString(route, 'archive') || 'active', state: queryString(route, 'state'), period: queryString(route, 'period') })
const activity = (item: KnowledgeConversation) => item.options?.lastActivityAt || item.updatedAt || item.createdAt
function dayGroup(item: KnowledgeConversation) { const date = new Date(activity(item)), today = new Date(); today.setHours(0, 0, 0, 0); if (date >= today) return '今天'; today.setDate(today.getDate() - 1); return date >= today ? '昨天' : '更早' }
export function KnowledgeHistoryPage(props: W2PageProps) {
  const [filters, setFilters] = useState<Filters>(() => routeFilters(props.route))
  const applied = useRef(filters), rows = useRef<KnowledgeConversation[]>([]), cursor = useRef(''), depth = useRef(0), list = useRef<HTMLDivElement>(null)
  const [selectedSnapshot, setSelected] = useState<KnowledgeConversation | null>(null), trigger = useRef<HTMLElement | null>(null), readMode = useRef(false)
  const command = useMutationOwner(props); useLeaveGuard(props, () => command.owner.canLeave())
  const projects = useLatestRead(api.getProjects, [] as Awaited<ReturnType<typeof api.getProjects>>)
  const data = useLatestRead(async () => {
    const more = readMode.current, value = applied.current
    const result = await knowledgeApi.history(value.project, more ? cursor.current : '', { query: value.query.trim(), archive: value.archive, state: value.state, since: value.period ? new Date(Date.now() - Number(value.period) * 86400000).toISOString() : '' })
    return { ...result, more }
  }, { items: [] as KnowledgeConversation[], nextCursor: undefined as string | undefined, more: false, facets: {} })
  const selected = selectedSnapshot ? data.value.items.find(item => item.id === selectedSnapshot.id) ?? null : null
  const load = async (more = false) => {
    readMode.current = more; const result = await data.reload(result => ({ ...result, items: more ? [...new Map([...rows.current, ...result.items].map(item => [item.id, item])).values()] : result.items }))
    if (!result) return
    data.apply(result, () => { rows.current = result.items; depth.current = more ? depth.current + 1 : 1; cursor.current = result.nextCursor || ''; setSelected(previous => previous ? result.items.find(item => item.id === previous.id) ?? null : null) })
    return result
  }
  useEffect(() => {
    let current = true
    const currentFilters = routeFilters(props.route); applied.current = currentFilters; setFilters(currentFilters); setSelected(null); cursor.current = ''; depth.current = 0; rows.current = []
    const restore = async () => {
      const path = props.route.fullPath
      let savedDepth = 1, scroll = 0
      try { savedDepth = Math.min(20, Math.max(1, Number(sessionStorage.getItem(`knowledge.history.pages.${path}`) || 1))); scroll = Number(sessionStorage.getItem(`knowledge.history.scroll.${path}`) || 0) } catch { /* Browsing remains available without storage. */ }
      await load()
      while (current && cursor.current && depth.current < savedDepth) { if (!await load(true)) break }
      if (current && list.current) list.current.scrollTop = scroll
    }
    void restore(); return () => { current = false }
  }, [props.route.fullPath])
  useEffect(() => { void projects.reload() }, [])
  useEffect(() => {
    if (JSON.stringify(filters) === JSON.stringify(routeFilters(props.route))) return
    const timer = window.setTimeout(() => {
      setSelected(null)
      void props.navigation.go({ path: '/knowledge/history', query: { ...filters, project: filters.project || undefined, query: filters.query || undefined, state: filters.state || undefined, period: filters.period || undefined } }, true).then(() => { /* Actual route props own the next read; rejected navigation retains the old scope. */ })
    }, 220)
    return () => window.clearTimeout(timer)
  }, [filters, props.route.fullPath])
  const enter = (item: KnowledgeConversation) => {
    try { sessionStorage.setItem(`knowledge.history.pages.${props.route.fullPath}`, String(depth.current)); sessionStorage.setItem(`knowledge.history.scroll.${props.route.fullPath}`, String(list.current?.scrollTop || 0)) } catch { /* No persistence promise. */ }
    void props.navigation.go(`/knowledge/${item.id}`)
  }
  const organize = (item: KnowledgeConversation) => { void command.owner.run(`/knowledge/conversations/${encodeURIComponent(item.id)}/archive`, { archived: !item.options?.archivedAt, version: item.options?.version || 0 }, body => knowledgeApi.archive(item.id, body.archived, body.version), async (_receipt, context) => { if (!context.isCurrent()) return; const result = await load(); if (context.isCurrent() && !result) throw new Error('原归档操作已接受，但对话列表未能读取，请仅重读原结果') }, 'POST', { kind: 'READ_ORIGINAL', readOriginal: async identity => { const found = await knowledgeApi.get(item.id); return (found.options?.version === identity.body.version + 1 && !!found.options?.archivedAt === identity.body.archived) ? { kind: 'ACCEPTED', receipt: found } : { kind: 'UNCONFIRMED' } } }) }
  return <PageChrome title="历史对话" objectKey="nav.knowledge" actions={<UiActionButton actionKey="knowledge.newConversation" variant="primary" onAction={() => { void props.navigation.go('/knowledge') }} />}
    status={<><MutationFeedback owner={command.owner} /><ReadFeedback error={data.error || projects.error} loading={data.loading && !data.value.items.length} retry={() => { void load(); if (projects.error) void projects.reload() }} /></>}
    context={<UiContextPanel open={!!selected} title={selected?.title ?? '对话'} closePolicy={command.blocked ? { kind: 'block', reason: '请先核对原操作' } : { kind: 'allow' }} returnFocus={trigger} onClose={() => setSelected(null)}>{selected && <><p>{selected.options?.awaitingAnswer ? '等待回答' : knowledgeStateLabel(selected.state)}</p><p>{projects.value.find(project => project.id === selected.projectId)?.name || '项目'} · {selected.model}</p><div className="w2-secondary-actions"><UiActionButton actionKey="ui.open" target={selected.title} onAction={() => enter(selected)} /><UiActionButton actionKey={selected.options?.archivedAt ? 'knowledge.restoreArchive' : 'knowledge.archive'} busy={command.snapshot.busy} availability={command.blocked ? { kind: 'disabled', reason: '请先核对原操作' } : { kind: 'enabled' }} onAction={() => organize(selected)} /></div></>}</UiContextPanel>}>
    <div className="w2-secondary-grid"><div className="w2-secondary-toolbar">
      <label>搜索历史对话<input value={filters.query} maxLength={200} placeholder="搜索标题或对话内容" onChange={event => setFilters({ ...filters, query: event.target.value })} /></label>
      <label>按项目筛选<select value={filters.project} onChange={event => setFilters({ ...filters, project: event.target.value })}><option value="">全部项目</option>{projects.value.map(project => <option key={project.id} value={project.id}>{project.name}</option>)}</select></label>
      <label>按时间筛选<select value={filters.period} onChange={event => setFilters({ ...filters, period: event.target.value })}><option value="">全部时间</option><option value="7">最近 7 天</option><option value="30">最近 30 天</option></select></label>
      <label>按状态筛选<select value={filters.state} onChange={event => setFilters({ ...filters, state: event.target.value })}><option value="">全部状态</option><option value="IDLE">可继续对话</option><option value="RUNNING">进行中</option><option value="WAITING_INPUT">等待回答</option><option value="STOPPING">正在停止</option></select></label>
      <label>归档筛选<select value={filters.archive} onChange={event => setFilters({ ...filters, archive: event.target.value })}><option value="active">未归档</option><option value="archived">已归档</option><option value="all">全部对话</option></select></label>
    </div><div ref={list} className="w2-secondary-scroll" aria-label="对话列表" aria-busy={data.loading}>
      {['今天', '昨天', '更早'].map(group => { const items = data.value.items.filter(item => dayGroup(item) === group); return !!items.length && <section key={group}><h2>{group}</h2><ul className="w2-secondary-list">{items.map(item => <li key={item.id}><button className="w2-secondary-select" aria-pressed={selected?.id === item.id} onClick={event => { trigger.current = event.currentTarget; setSelected(item) }}><strong>{item.title}</strong><small>{projects.value.find(project => project.id === item.projectId)?.name || '项目'} · {item.options?.awaitingAnswer ? '等待回答' : knowledgeStateLabel(item.state)} · {new Date(activity(item)).toLocaleString('zh-CN')}</small></button></li>)}</ul></section> })}
      {!data.loading && !data.error && !data.value.items.length && <p className="w2-secondary-empty">{filters.query || filters.state || filters.period ? '没有匹配的对话，试试调整关键词或筛选条件' : filters.archive === 'archived' ? '还没有归档对话' : '从一次提问开始，你的项目讨论会保存在这里'}</p>}
      {data.value.nextCursor && <UiActionButton actionKey="ui.loadMore" busy={data.loading} onAction={() => { void load(true) }} />}
    </div></div>
  </PageChrome>
}
