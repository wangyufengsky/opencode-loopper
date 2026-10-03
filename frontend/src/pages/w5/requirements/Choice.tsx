import { useMemo } from 'react'
import { workflowRuns } from '@/api/workflowRuns'
import { workflowApi } from '@/api/workflow'
import type { TemplateProjectChoice, WorkflowTemplateSummary } from '@/types/domain'
import { createRequirementScope, ownedState } from './core'
import { Action, Labeled, ReadNotice, useRequirementOwner } from './parts'
import type { W2PageProps } from '@/pages/w2/shared'

export function RequirementChoice({ page, kind, disabled, onSelect }: { page: W2PageProps; kind: 'project' | 'template'; disabled?: boolean; onSelect: (value: TemplateProjectChoice | WorkflowTemplateSummary) => void }) {
  const owner = useMemo(() => {
    const scope = createRequirementScope('requirement-choice', kind, { ...ownedState(), query: '', rows: [] as Array<TemplateProjectChoice | WorkflowTemplateSummary>, cursor: null as string | null })
    async function load(more = false) {
      if (!scope.active()) return
      const ticket = scope.ticket('list'), s = scope.getSnapshot(); scope.patch({ loading: true })
      try {
        const result = kind === 'project' ? await workflowRuns.projects(s.query, more ? s.cursor ?? '' : '') : await workflowApi.list(s.query, 'ALL', more ? s.cursor ?? '' : '')
        if (ticket.current()) scope.patch({ rows: more ? [...s.rows, ...result.items] : result.items, cursor: result.nextCursor ?? null, error: '' })
      } catch (cause) { if (ticket.current()) scope.fail(cause, '选项暂时无法读取，请重试。') }
      finally { if (ticket.current()) scope.patch({ loading: false }) }
    }
    scope.setStart(() => { void load() }); return Object.assign(scope, { load })
  }, [kind])
  const s = useRequirementOwner(page, owner)
  return <section aria-label={kind === 'project' ? '选择项目' : '选择流程'}>
    <form onSubmit={event => { event.preventDefault(); void owner.load() }}><Labeled label="搜索"><input value={s.query} onChange={event => owner.patch({ query: event.target.value })} /></Labeled><Action action="ui.search" onClick={() => { void owner.load() }} busy={s.loading} /></form>
    <ReadNotice error={s.error} loading={s.loading} retry={() => { void owner.load() }} />
    <ul className="w5-list">{s.rows.map(value => <li key={value.id}><strong>{'name' in value ? value.name : value.title}</strong>{'description' in value && <p>{value.description}</p>}<Action action="selection.select" target={'name' in value ? value.name : value.title} disabled={disabled} onClick={() => onSelect(value)} /></li>)}</ul>
    {s.cursor && <Action action="ui.loadMore" busy={s.loading} onClick={() => { void owner.load(true) }} />}
  </section>
}
