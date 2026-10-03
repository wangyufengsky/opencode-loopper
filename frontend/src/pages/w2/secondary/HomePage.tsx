import { useEffect, useRef, useState } from 'react'
import { api } from '@/api/client'
import { knowledgeApi } from '@/api/knowledge'
import { UiActionButton, UiContextPanel } from '@/foundation/components'
import { SemanticIcon, type UiSemanticKey } from '@/foundation/semanticRegistry'
import { PageChrome, PageLink } from '../shared'
import type { W2PageProps } from '../shared/types'
import { ReadFeedback } from './feedback'
import { useLatestRead } from './state'
import './secondary.css'

const destinations: [string, string, UiSemanticKey][] = [
  ['/projects', '项目', 'nav.projects'], ['/requirements', '需求任务', 'nav.requirements'], ['/tasks', '任务', 'nav.tasks'], ['/inbox', '待处理中心', 'nav.inbox'],
  ['/workflows', '流程', 'nav.workflows'], ['/designs', '历史设计', 'nav.designs'], ['/insights', '质量与用量', 'nav.insights'], ['/template-tasks', '模板任务', 'nav.templateTasks'],
  ['/runtime', '运行环境', 'nav.runtime'], ['/tools', '工具与 Skill', 'nav.tools'], ['/settings', '设置', 'nav.settings'],
]
export function HomePage(props: W2PageProps) {
  const data = useLatestRead(async () => { const [projects, history] = await Promise.all([api.getProjects(), knowledgeApi.history('')]); return { projects, conversations: history.items } }, { projects: [] as Awaited<ReturnType<typeof api.getProjects>>, conversations: [] as Awaited<ReturnType<typeof knowledgeApi.history>>['items'] })
  const [query, setQuery] = useState(''), [selected, setSelected] = useState<{ name: string; target: string } | null>(null)
  const trigger = useRef<HTMLElement | null>(null)
  useEffect(() => { void data.reload() }, [data.reload])
  const needle = query.trim().toLocaleLowerCase()
  const projects = data.value.projects.filter(project => `${project.name} ${project.rootPath}`.toLocaleLowerCase().includes(needle)).slice(0, 6)
  const conversations = data.value.conversations.filter(item => `${item.title} ${item.model}`.toLocaleLowerCase().includes(needle)).slice(0, 6)
  return <PageChrome title="主页" objectKey="nav.home" actions={<UiActionButton actionKey="workflow.newRequirement" variant="primary" onAction={() => { void props.navigation.go('/requirements/new') }} />}
    status={<ReadFeedback error={data.error} loading={data.loading} retry={() => { void data.reload() }} />}
    context={<UiContextPanel open={!!selected} title={selected?.name ?? '工作区对象'} returnFocus={trigger} onClose={() => setSelected(null)}>
      {selected && <UiActionButton actionKey="ui.open" target={selected.name} onAction={() => { void props.navigation.go(selected.target) }} />}
    </UiContextPanel>}>
    <div className="w2-secondary-grid">
      <label className="w2-secondary-fields w2-secondary-home-search">搜索项目与最近对话<input value={query} onChange={event => setQuery(event.target.value)} placeholder="项目名称或对话标题" /></label>
      <div className="w2-secondary-actions">{destinations.slice(0, 4).map(([target, label, key]) => <PageLink key={target} to={target} navigation={props.navigation}><SemanticIcon semanticKey={key} /> {label}</PageLink>)}</div>
      <div className="w2-secondary-columns">
        <section aria-label="最近项目"><h2>项目</h2>{projects.length ? <ul className="w2-secondary-list">{projects.map(project => <li key={project.id}><button className="w2-secondary-select" aria-pressed={selected?.target === `/projects?project=${encodeURIComponent(project.id)}`} onClick={event => { trigger.current = event.currentTarget; setSelected({ name: project.name, target: `/projects?project=${encodeURIComponent(project.id)}` }) }}><SemanticIcon semanticKey="object.project" /> {project.name}<small>{project.rootPath}</small></button></li>)}</ul> : !data.loading && !data.error && <p className="w2-secondary-muted">{needle ? '没有匹配的项目' : '尚未登记项目'}</p>}</section>
        <section aria-label="最近对话"><h2>最近对话</h2>{conversations.length ? <ul className="w2-secondary-list">{conversations.map(item => <li key={item.id}><button className="w2-secondary-select" aria-pressed={selected?.target === `/knowledge/${item.id}`} onClick={event => { trigger.current = event.currentTarget; setSelected({ name: item.title, target: `/knowledge/${item.id}` }) }}><SemanticIcon semanticKey="nav.knowledge" /> {item.title}</button></li>)}</ul> : !data.loading && !data.error && <p className="w2-secondary-muted">{needle ? '没有匹配的对话' : '尚无历史对话'}</p>}<UiActionButton actionKey="knowledge.openHistory" onAction={() => { void props.navigation.go('/knowledge/history') }} /></section>
      </div>
      <details><summary>更多入口</summary><nav className="w2-secondary-actions" aria-label="其他工作区入口">{destinations.slice(4).map(([target, label, key]) => <PageLink key={target} to={target} navigation={props.navigation}><SemanticIcon semanticKey={key} /> {label}</PageLink>)}</nav></details>
    </div>
  </PageChrome>
}
