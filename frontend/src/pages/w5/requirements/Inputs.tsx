import { useEffect, useMemo } from 'react'
import { api } from '@/api/client'
import { workflowDocuments } from '@/api/workflowDocuments'
import type { TemplateBranchChoice, WorkflowOutput } from '@/types/domain'
import type { W2PageProps } from '@/pages/w2/shared'
import { RichDocument } from '@/pages/w3/shared/RichDocument'
import { repositoryBranchLabel } from '@/components/workflow/repository'
import { userFacingError } from '@/utils/displayLabels'
import { createRequirementScope, ownedState } from './core'
import { createUploadController, type UploadController } from './uploadController'
import type { RequirementController } from './controller'
import { Action, CommandNotice, Labeled, ReadNotice, useRequirementOwner } from './parts'

export function BranchInput({ page, project, title, value, disabled, onChange }: { page: W2PageProps; project: string; title: string; value: string; disabled: boolean; onChange: (value: string) => void }) {
  const owner = useMemo(() => {
    const scope = createRequirementScope('requirement-branch', project, { ...ownedState(), rows: [] as TemplateBranchChoice[], query: '', cursor: null as string | null, opened: false, loaded: false, problems: [] as string[] })
    async function search(more = false) { if (!project) return; const ticket = scope.ticket('branches'), s = scope.getSnapshot(); scope.patch({ loading: true, opened: true }); try { const reply = await api.templateBranches(project, s.query.trim(), more ? s.cursor ?? undefined : undefined); if (ticket.current()) scope.patch({ rows: more ? [...s.rows, ...reply.page.items] : reply.page.items, cursor: reply.page.nextCursor ?? null, problems: reply.remoteProblems ?? [], loaded: true, error: '' }) } catch (cause) { if (ticket.current()) scope.fail(cause, '分支无法读取，请检查项目仓库和连接后重试。') } finally { if (ticket.current()) scope.patch({ loading: false }) } }
    return Object.assign(scope, { search })
  }, [project]), s = useRequirementOwner(page, owner)
  return <section aria-label={title}><p>{repositoryBranchLabel(value)}</p><Action action="ui.open" target="代码分支" disabled={disabled || s.loading} onClick={() => { void owner.search() }} />
    {s.opened && <><Labeled label="搜索分支"><input disabled={disabled} value={s.query} onChange={event => owner.patch({ query: event.target.value })} onKeyDown={event => { if (event.key === 'Enter') { event.preventDefault(); void owner.search() } }} /></Labeled><Action action="ui.search" target="分支" disabled={disabled} busy={s.loading} onClick={() => { void owner.search() }} /><ReadNotice error={s.error} loading={s.loading} retry={() => { void owner.search() }} />{s.problems.map((problem, index) => <p key={index} role="status">{userFacingError(problem, '部分远程分支无法读取，请检查连接与 Git 凭据后刷新。')}</p>)}{s.rows.length ? <Labeled label={title}><select aria-label={title} disabled={disabled || s.loading} value={s.rows.some(row => row.id === value) ? value : ''} onChange={event => { if (s.rows.some(row => row.id === event.target.value)) onChange(event.target.value) }}><option value="">请选择分支</option>{s.rows.map(row => <option key={row.id} value={row.id}>{repositoryBranchLabel(row.id)}</option>)}</select></Labeled> : s.loaded && !s.loading && <p>没有匹配的分支，请调整搜索条件。</p>}{s.cursor && <Action action="ui.loadMore" target="分支" busy={s.loading} disabled={disabled} onClick={() => { void owner.search(true) }} />}</>}
    <p>开始采集时固定所选分支的提交。节点重试沿用该提交；需要新版本时新增采集节点。</p>
  </section>
}
export function DocumentInput({ page, parent, field, disabled }: { page: W2PageProps; parent: RequirementController; field: WorkflowOutput; disabled: boolean }) {
  const initial = parent.getSnapshot(), base = initial.base!, execution = initial.execution!
  const owner = useMemo(() => { let result: UploadController; result = createUploadController(parent.id, { version: execution.execution.version, revision: base.revision, value: initial.inputValues[field.name] ?? '' }, value => parent.setInput(field.name, value, result)); result.bindParent(parent); return result }, [parent, field.name])
  const s = useRequirementOwner(page, owner, parent), current = parent.getSnapshot()
  useEffect(() => { owner.updateContext({ version: current.execution!.execution.version, revision: current.base!.revision, value: current.inputValues[field.name] ?? '' }) }, [owner, current.execution?.execution.version, current.base?.revision, current.inputValues[field.name]])
  const blocked = disabled && !s.dirty || owner.locked() || s.hashing || !parent.canStartWrite(owner) || parent.inputsFrozen()
  return <section aria-label={field.title}>
    {s.chosen?.ready && <p role="status">已选 {s.chosen.originals.length} 份文档</p>}
    {s.chosen && <><Action action="selection.deselect" target={field.title} disabled={blocked} onClick={owner.clearSelection} /><ul>{s.chosen.originals.map(file => <li key={file.path}><a href={workflowDocuments.fileUrl(parent.id, s.chosen!.id, file.path)} download>{file.filename}</a> · {file.sections} 个章节{file.limitations.map((limitation, index) => <small key={index}>{limitation}</small>)}</li>)}</ul></>}
    <Labeled label={`选择${field.title}`}><input type="file" accept=".docx,.md,.markdown,.pdf" multiple disabled={blocked} onChange={event => { const files = Array.from(event.target.files ?? []); event.target.value = ''; if (files.length) void owner.chooseFiles(files) }} /></Labeled>
    {!!s.fileNames.length && <p>待上传：{s.fileNames.join('、')}</p>}{s.hashing && <p role="status">正在核对原文档字节…</p>}{s.dirty && <p role="status">本次上传尚未完成，文件与请求已保留。请上传并选用后再关闭面板、离开或执行。</p>}
    <small>DOCX、Markdown、文本 PDF；单份 20 MiB，合计 50 MiB。</small>
    <div className="w5-toolbar"><Action action="workflow.uploadAndSelect" disabled={blocked || !s.fileNames.length} busy={s.command.busy} onClick={() => { void owner.upload() }} /><Action action="workflow.selectUploaded" busy={s.loading} onClick={() => { void owner.load() }} /></div>
    <CommandNotice command={s.command} recover={() => { void owner.recover() }} /><ReadNotice error={s.error} />
    <ul>{s.history.map(row => <li key={row.id}><span>{row.originals.map(file => file.filename).join('、')}</span><small>{new Date(row.createdAt).toLocaleString()} · {row.ready ? '已保存' : '等待补传'}</small><Action action={row.ready ? 'workflow.useUpload' : 'workflow.resumeUpload'} disabled={blocked} onClick={() => { if (row.ready) void owner.select(row); else owner.resume(row) }} /></li>)}</ul>
    {s.cursor && <Action action="ui.loadMore" target="上传记录" busy={s.loading} onClick={() => { void owner.load(true) }} />}
    {s.chosen?.ready && <Action action="ui.open" target="解析内容" busy={s.loading} onClick={() => { void owner.loadFiles() }} />}
    <ul>{s.paths.filter(file => file.path.startsWith('parsed/')).map(file => <li key={file.path}><span>文档 {Number(file.path.split('/')[1])} · 第 {Number(file.path.split('/')[2]?.replace('.md', ''))} 节</span><Action action="ui.open" target="文档章节" onClick={() => { void owner.read(file.path) }} /></li>)}</ul>
    {s.pathCursor && <Action action="ui.loadMore" target="文档章节" onClick={() => { void owner.loadFiles(true) }} />}
    {s.preview && <RichDocument content={s.preview} skin={page.skin} allowImages={false} />}{s.nextOffset !== null && <Action action="ui.loadMore" target="解析正文" busy={s.loading} onClick={() => { void owner.read(s.previewPath, true) }} />}
  </section>
}
export function PublicValues({ page, parent }: { page: W2PageProps; parent: RequirementController }) {
  const s = parent.getSnapshot(), disabled = parent.inputsFrozen() || parent.lockedForUi()
  const dates = new Set(s.graph.nodes.filter(node => ['system.git.history', 'system.review.snapshot'].includes(node.moduleId ?? '')).flatMap(node => node.inputs.filter(input => ['startDate', 'endDate'].includes(input.name) && input.source === 'REQUIREMENT').map(input => input.sourceId)))
  const branches = new Set(s.graph.nodes.filter(node => ['system.repository.snapshot', 'system.git.history', 'system.review.snapshot'].includes(node.moduleId ?? '')).flatMap(node => node.inputs.filter(input => input.name === 'branch' && input.source === 'REQUIREMENT').map(input => input.sourceId)))
  return <section aria-label="填写公共资料">{parent.inputsFrozen() && <p>公共资料已固定，重试和继续使用原资料。新增采集节点可获取新版本。</p>}{s.graph.inputs.map(field => <div key={field.name} className="w5-section"><h3>{field.title}{field.required ? '（必填）' : '（可选）'}</h3>{field.kind === 'DOCUMENT' ? <DocumentInput page={page} parent={parent} field={field} disabled={disabled} /> : field.kind === 'TEXT' && branches.has(field.name) ? <BranchInput page={page} project={s.base!.projectId} title={field.title} value={s.inputValues[field.name] ?? ''} disabled={disabled} onChange={value => parent.setInput(field.name, value)} /> : <Labeled label={field.title}>{field.kind === 'TEXT' && dates.has(field.name) ? <input aria-label={field.title} type="date" min="1900-01-01" max="9998-12-31" disabled={disabled} value={s.inputValues[field.name] ?? ''} onChange={event => parent.setInput(field.name, event.target.value)} /> : <textarea aria-label={field.title} aria-required={field.required} disabled={disabled} value={s.inputValues[field.name] ?? ''} rows={field.kind === 'TEXT' ? 3 : 7} onChange={event => parent.setInput(field.name, event.target.value)} />}</Labeled>}</div>)}</section>
}
