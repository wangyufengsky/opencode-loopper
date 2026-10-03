import { useRef, useState } from 'react'
import { Select } from 'antd'
import { UiActionButton, UiContextPanel, UiField, UiSelectableList } from '@/foundation/components'
import { semanticLabel, semanticName } from '@/foundation/semanticRegistry'
import { PageChrome, PageLink, queryString } from '@/pages/w2/shared'
import type { W2PageProps } from '@/pages/w2/shared/types'
import { workflowStateLabel } from '@/utils/displayLabels'
import { formatDateTime } from '@/utils/dateTime'
import { createRequirementListController } from './requirementListController'
import { focusSelection, ReadNotice, usePageOwner } from './pageParts'

export function RequirementListPage(props: W2PageProps & { controller?: ReturnType<typeof createRequirementListController> }) {
  const [owner] = useState(() => props.controller ?? createRequirementListController(undefined, queryString(props.route, 'projectId')))
  const s = usePageOwner(props, owner), trigger = useRef<HTMLElement | null>(null)
  const selected = s.rows.find(row => row.id === s.selectedId)
  const createTarget = { path: '/requirements/new', query: s.project ? { projectId: s.project.id } : {} }
  return <PageChrome title={semanticLabel('nav.requirements')} objectKey="object.requirement" actions={<>
    <UiActionButton actionKey="workflow.newRequirement" variant="primary" onAction={() => { void props.navigation.go(createTarget) }} />
    <PageLink navigation={props.navigation} to="/workflows" aria-label={semanticName('nav.workflows')}>{semanticLabel('nav.workflows')}</PageLink>
  </>} status={<><ReadNotice error={s.error} busy={s.busy} retry={() => { void owner.load() }}>正在读取需求…</ReadNotice>
    <ReadNotice error={s.projectError} busy={s.projectBusy} retry={() => { void owner.projects() }} /></>}
    context={<UiContextPanel open={!!selected} title={selected?.title ?? '需求详情'} returnFocus={trigger} onClose={() => owner.select('')}>
      {selected && <><p>计划版本 {selected.headRevision}</p><p>{workflowStateLabel(selected.state)}</p><time dateTime={selected.updatedAt}>{formatDateTime(selected.updatedAt)}</time>
        <PageLink navigation={props.navigation} to={`/requirements/${encodeURIComponent(selected.id)}`} aria-label={semanticName('ui.open', `需求画布：${selected.title}`)}>打开画布</PageLink></>}
    </UiContextPanel>}>
    <div className="w2-module-toolbar"><UiField labelKey="field.project">{field => <Select {...field} aria-label="工作项目" allowClear showSearch filterOption={false}
      placeholder="全部项目" value={s.project?.id} onSearch={query => { void owner.projects(query) }}
      options={[...(s.project && !s.projects.some(project => project.id === s.project!.id) ? [s.project] : []), ...s.projects].map(project => ({ value: project.id, label: project.name }))}
      onChange={value => owner.setProject(s.projects.find(project => project.id === value) ?? null)} />}</UiField>
      {s.projectCursor && <UiActionButton actionKey="ui.loadMore" target="工作项目" busy={s.projectBusy} onAction={() => { void owner.projects(s.projectQuery, true) }} />}
      <UiActionButton actionKey="ui.refresh" onAction={() => { void owner.load() }} busy={s.busy} />
    </div>
    <UiSelectableList items={s.rows} selectedKey={s.selectedId} labelKey="object.requirement" getKey={row => row.id} getName={row => row.title}
      onSelect={row => { focusSelection(trigger); owner.select(row.id === s.selectedId ? '' : row.id) }}
      renderItem={row => <div className="w2-record"><strong>{row.title}</strong><span>{workflowStateLabel(row.state)}</span><time dateTime={row.updatedAt}>{formatDateTime(row.updatedAt)}</time></div>} />
    {!s.busy && !s.error && !s.projectError && !s.projectBusy && !s.rows.length && <section className="w2-empty"><h2>{s.project ? '这个项目还没有需求' : '从一个需求开始'}</h2><p>选择流程，在画布上安排本次工作。</p></section>}
    {s.cursor && <UiActionButton actionKey="ui.loadMore" target="需求" busy={s.busy} onAction={() => { void owner.load(true) }} />}
  </PageChrome>
}
