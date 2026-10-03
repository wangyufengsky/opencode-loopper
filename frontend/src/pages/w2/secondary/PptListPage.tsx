import { useEffect, useRef, useState } from 'react'
import { pptApi } from '@/api/ppt'
import type { PptDocument, PptProjectChoice } from '@/types/domain'
import { pptPhaseLabel } from '@/utils/displayLabels'
import { UiActionButton, UiContextPanel } from '@/foundation/components'
import { SemanticIcon } from '@/foundation/semanticRegistry'
import { PageChrome, PageLink, useLeaveGuard, useOwnerSnapshot, useRetainedOwner } from '../shared'
import type { W2PageProps } from '../shared/types'
import { createPptCreation } from './pptCreation'
import { ReadFeedback } from './feedback'
import { useLatestRead } from './state'
import './secondary.css'

export function PptListPage(props: W2PageProps) {
  const [creation] = useState(() => createPptCreation(pptApi, props.navigation)), input = useOwnerSnapshot(creation)
  useRetainedOwner(props, creation, () => creation.retire()); useLeaveGuard(props, request => creation.canLeave(request))
  const [filters, setFilters] = useState({ query: '', archived: false }), applied = useRef(filters), cursor = useRef(''), more = useRef(false)
  const [selected, setSelected] = useState<PptDocument | null>(null), [picker, setPicker] = useState(false), [projectSearch, setProjectSearch] = useState(''), projectQuery = useRef(''), projectCursor = useRef(''), projectMore = useRef(false), trigger = useRef<HTMLElement | null>(null)
  const rows = useLatestRead(() => pptApi.list({ ...applied.current, cursor: more.current ? cursor.current : '' }), { items: [] as PptDocument[], nextCursor: undefined as string | undefined, facets: {} })
  const projects = useLatestRead(() => pptApi.projects(projectQuery.current, projectMore.current ? projectCursor.current : ''), { items: [] as PptProjectChoice[], nextCursor: undefined as string | undefined, facets: {} })
  const load = async (append = false) => { more.current = append; const old = rows.value.items, result = await rows.reload(result => append ? { ...result, items: [...old, ...result.items] } : result); if (result) rows.apply(result, () => { cursor.current = result.nextCursor || '' }) }
  const loadProjects = async (append = false) => { projectMore.current = append; projectQuery.current = projectSearch.trim(); const old = projects.value.items, result = await projects.reload(result => append ? { ...result, items: [...old, ...result.items] } : result); if (result) projects.apply(result, () => { projectCursor.current = result.nextCursor || '' }) }
  useEffect(() => { void load() }, [])
  const submit = () => { if (!input.busy && input.prompt.trim()) void creation.submit() }
  return <PageChrome title="PPT 工作室" objectKey="nav.ppt" status={<>{input.detail && <p role="status">{input.detail}</p>}{input.error && <p className="w2-secondary-status" role="alert">{input.error}</p>}{input.unknown && <p role="status">原制作要求尚未确认，正文与附件仍由原操作保留。</p>}<ReadFeedback error={rows.error} loading={rows.loading && !rows.value.items.length} retry={() => { void load() }} /></>}
    context={<UiContextPanel open={!!selected} title={selected?.title ?? '演示作品'} returnFocus={trigger} onClose={() => setSelected(null)}>{selected && <><p>{pptPhaseLabel(selected.phase)}</p><UiActionButton actionKey="ui.open" target={selected.title} onAction={() => { void props.navigation.go(`/ppt/${selected.id}`) }} /></>}</UiContextPanel>}>
    <div className="w2-secondary-grid"><section className="w2-secondary-record"><h2>从一个想法，到一份好演示。</h2><p className="w2-secondary-muted">说出你的想法，和 PPT 助手一起把要求聊清楚。</p>
      <form className="w2-secondary-fields ppt-prompt-card" onSubmit={event => { event.preventDefault(); submit() }} onDragOver={event => event.preventDefault()} onDrop={event => { event.preventDefault(); if (!input.busy) void creation.addFiles(Array.from(event.dataTransfer.files)) }}>
        <label htmlFor="ppt-first-prompt">你想制作什么 PPT</label><textarea id="ppt-first-prompt" value={input.prompt} maxLength={24000} readOnly={input.locked} disabled={input.busy} onChange={event => creation.setPrompt(event.target.value)} onKeyDown={event => { if ((event.ctrlKey || event.metaKey) && event.key === 'Enter') { event.preventDefault(); submit() } }} placeholder="告诉我你要讲什么，也可以附上资料。" />
        {!!input.files.length && <ul aria-label="已选择附件">{input.files.map(file => <li key={file.id}><SemanticIcon semanticKey="field.files" />{file.name}{!file.file && !file.uploaded && <small> 请重新选择；文件字节未持久化</small>}{file.uploaded ? <span> 已读取</span> : !input.locked && <UiActionButton actionKey="ui.delete" target={`附件 ${file.name}`} iconOnly busy={input.busy} onAction={() => creation.removeFile(file.id)} />}</li>)}</ul>}
        <div className="w2-secondary-actions"><label>添加制作资料<input type="file" accept=".md,.docx,.xlsx,.pptx,.pdf,.png,.jpg,.jpeg" multiple disabled={input.busy} onChange={event => { const files = Array.from(event.target.files || []); event.target.value = ''; void creation.addFiles(files) }} /></label><UiActionButton actionKey="project.select" availability={input.locked || input.busy ? { kind: 'disabled', reason: '原作品创建要求已冻结' } : { kind: 'enabled' }} onAction={() => { setPicker(!picker); if (!picker) void loadProjects() }} /><UiActionButton actionKey={input.sentAccepted ? 'ui.open' : input.locked ? 'receipt.retryOriginal' : 'ppt.send'} variant="primary" busy={input.busy} availability={!input.prompt.trim() ? { kind: 'disabled', reason: '请填写制作要求' } : { kind: 'enabled' }} onAction={submit} /></div>
        {input.project && <p>将使用「{input.project.name}」当前可用的知识库来源</p>}<p className="w2-secondary-muted">随时补充你的想法；点击“确认需求并执行”后开始制作。</p>
      </form>
      {picker && !input.locked && <section aria-label="选择关联项目" onKeyDown={event => { if (event.key === 'Escape') { event.stopPropagation(); setPicker(false) } }}><form className="w2-secondary-toolbar" onSubmit={event => { event.preventDefault(); void loadProjects() }}><label>搜索关联项目<input value={projectSearch} onChange={event => setProjectSearch(event.target.value)} /></label><button type="submit">搜索</button></form><ReadFeedback error={projects.error} loading={projects.loading} retry={() => { void loadProjects() }} /><button onClick={() => { creation.setProject(null); setPicker(false) }}>不关联项目</button><ul className="w2-secondary-list">{projects.value.items.map(project => <li key={project.id}><button className="w2-secondary-select" aria-pressed={input.project?.id === project.id} onClick={() => { creation.setProject(project); setPicker(false) }}>{project.name}</button></li>)}</ul>{projects.value.nextCursor && <UiActionButton actionKey="ui.loadMore" busy={projects.loading} onAction={() => { void loadProjects(true) }} />}</section>}
      {input.documentId && <PageLink to={`/ppt/${input.documentId}`} navigation={props.navigation}>打开已创建的作品</PageLink>}
    </section>
      <form className="w2-secondary-toolbar" onSubmit={event => { event.preventDefault(); applied.current = { ...filters }; void load() }}><label>搜索作品<input value={filters.query} onChange={event => setFilters({ ...filters, query: event.target.value })} /></label><button type="submit">搜索</button><button type="button" aria-pressed={filters.archived} onClick={() => { const next = { ...filters, archived: !filters.archived }; setFilters(next); applied.current = next; void load() }}>{filters.archived ? '返回最近' : '归档'}</button></form>
      <section aria-label="作品列表"><h2>{filters.archived ? '已归档作品' : '最近作品'}</h2><ul className="w2-secondary-list">{rows.value.items.map(document => <li key={document.id}><button className="w2-secondary-select" aria-pressed={selected?.id === document.id} onClick={event => { trigger.current = event.currentTarget; setSelected(document) }}><SemanticIcon semanticKey="nav.ppt" /><strong>{document.title}</strong><small>{pptPhaseLabel(document.phase)} · {new Date(document.updatedAt).toLocaleDateString('zh-CN', { month: 'numeric', day: 'numeric' })}</small></button></li>)}</ul>{!rows.loading && !rows.error && !rows.value.items.length && <p>{filters.query || filters.archived ? '这里还没有符合条件的作品。' : '你的演示作品会出现在这里。'}</p>}{rows.value.nextCursor && <UiActionButton actionKey="ui.loadMore" busy={rows.loading} onAction={() => { void load(true) }} />}</section>
    </div>
  </PageChrome>
}
