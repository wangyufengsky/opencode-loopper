import { useEffect, useMemo, useRef } from 'react'
import type { WorkflowInputs } from '@/types/domain'
import type { W2PageProps } from '@/pages/w2/shared'
import { RichDocument } from '@/pages/w3/shared/RichDocument'
import { ReadOnlyCode } from '@/pages/w3/shared/ReadOnlyCode'
import { KnowledgeEvidence } from '@/pages/w3/knowledge/Evidence'
import { knowledgeBody, knowledgeBundle } from '@/components/workflow/knowledgeBundle'
import { knowledgeToolLabel } from '@/utils/displayLabels'
import { WorkflowCodeChanges, WorkflowDocumentPreview, WorkflowOutputReport, WorkflowSnapshotPartialReport } from '@/pages/w5/workflow'
import { createContentController, type AttemptContentScope } from './contentController'
import { Action, ReadNotice, useRequirementOwner } from './parts'

function useContent(page: W2PageProps, scope: AttemptContentScope, input?: WorkflowInputs['values'][number]) {
  const owner = useMemo(() => createContentController(scope, input), [scope.requirement, scope.node, scope.attempt, scope.direction, scope.name, input?.sha256])
  const state = useRequirementOwner(page, owner)
  return { owner, state }
}
export function FixedInputContent({ page, scope, input, review }: { page: W2PageProps; scope: AttemptContentScope; input: WorkflowInputs['values'][number]; review?: boolean }) {
  const { owner, state: s } = useContent(page, scope, input), complete = s.inputLoaded && s.inputNext === null
  let structured: unknown = null; if (complete && input.kind !== 'TEXT') try { structured = JSON.parse(s.inputText) } catch { /* Incomplete or invalid JSON remains exact text. */ }
  const bundle = knowledgeBundle(structured)
  return <section aria-label="固定版本正文">
    {!s.inputLoaded && !s.error && <Action action="workflow.inputContent" busy={s.loading} onClick={() => { void owner.input() }} />}
    <ReadNotice error={s.error} retry={() => { void owner.input() }} />
    {s.inputLoaded && <>{!complete && <p role="status">正文尚未读完，已读取 {s.inputText.length} / {s.inputTotal} 字符。</p>}
      {complete && input.kind === 'TEXT' ? <RichDocument content={s.inputText} skin={page.skin} allowImages={false} /> : complete && review && structured ? <WorkflowOutputReport moduleId="system.review.dual" name="review" value={{ kind: input.kind, content: structured }} skin={page.skin} /> : complete && bundle ? <WorkflowOutputReport moduleId="knowledge.research" name="evidence" value={{ kind: input.kind, content: structured }} skin={page.skin} /> : complete && structured ? <ReadOnlyCode content={JSON.stringify(structured, null, 2)} language="json" /> : <pre>{s.inputText}</pre>}
      {!complete && !s.error && <Action action="ui.loadMore" target="固定输入正文" busy={s.loading} onClick={() => { void owner.input() }} />}</>}
  </section>
}
export function AttemptFiles({ page, scope, archive, changes }: { page: W2PageProps; scope: AttemptContentScope; archive?: boolean; changes?: boolean }) {
  const { owner, state: s } = useContent(page, scope)
  const readable = (file: (typeof s.files)[number]) => !!file.sha256 || !!file.blobSha && !file.exclusion
  return <section aria-label="固定版本文件">
    {changes && <WorkflowCodeChanges rows={s.changes} loaded={s.changesLoaded} busy={s.loading} error={s.error} cursor={s.changeCursor} onLoad={more => { void owner.changes(more) }} fileUrl={owner.fileUrl} />}
    {archive && <p><a href={owner.archiveUrl()} download>下载全部文档（ZIP）</a></p>}
    {!s.filesLoaded && <Action action="workflow.fixedFiles" busy={s.loading} onClick={() => { void owner.files() }} />}
    <ReadNotice error={s.error} retry={() => { void owner.files() }} />
    {s.filesLoaded && !s.files.length && <p>该版本没有文件。</p>}
    <ul>{s.files.map(file => <li key={file.path}>{readable(file) ? <a href={owner.fileUrl(file.path)} download>{file.path}</a> : <span>{file.path}</span>}
      {archive && readable(file) && file.path.endsWith('.md') && <Action action="workflow.previewDocument" target={file.path} onClick={() => { void owner.readFile(file.path) }} />}
      <small>{file.sizeBytes} 字节{file.target != null ? ` · ${file.target ? '目标范围' : readable(file) ? '只读上下文' : '范围外'}` : ''}{!readable(file) ? ' · 未采集正文' : ''}</small>{file.exclusion && <small>{file.exclusion}</small>}</li>)}</ul>
    {s.fileCursor && <Action action="ui.loadMore" target="固定版本文件" busy={s.loading} onClick={() => { void owner.files(true) }} />}
    {s.previewPath && <WorkflowDocumentPreview path={s.previewPath} body={s.body} busy={s.loading} error={s.error} skin={page.skin} onLoad={more => { void owner.readFile(s.previewPath, more) }} onNavigate={path => { void owner.readFile(path) }} onClose={owner.closePreview} />}
  </section>
}
export function AttemptKnowledge({ page, scope }: { page: W2PageProps; scope: AttemptContentScope }) {
  const { owner, state: s } = useContent(page, scope)
  // Retry the user's original read, including its page cursor or selected evidence identity.
  const retry = useRef<() => Promise<void>>(() => owner.evidence())
  const readList = (more = false) => { retry.current = () => owner.evidence(more); void retry.current() }
  const readBody = (id: string) => { retry.current = () => owner.evidenceBody(id); void retry.current() }
  useEffect(() => {
    let currentView = true
    retry.current = () => owner.evidence()
    queueMicrotask(() => { if (currentView) void retry.current() })
    return () => { currentView = false }
  }, [owner])
  return <section aria-label="本次检索证据"><h3>本次检索证据</h3><p>这里保存本次执行实际读取的资料。采集之后的文件变化不会改写这些记录。</p>
    <Action action="ui.refresh" target="检索证据" busy={s.loading} onClick={() => readList()} /><ReadNotice error={s.error} retry={() => { void retry.current() }} />
    {s.evidenceLoaded && !s.evidence.length && <p>本次执行尚无保存的检索证据。</p>}
    <ul>{s.evidence.map(entry => <li key={entry.id}><strong>{knowledgeToolLabel(entry.toolName)}</strong><time>{new Date(entry.createdAt).toLocaleString('zh-CN')}</time><Action action="selection.select" target={knowledgeToolLabel(entry.toolName)} onClick={() => readBody(entry.id)} /></li>)}</ul>
    {s.evidenceCursor && <Action action="ui.loadMore" target="检索证据" busy={s.loading} onClick={() => readList(true)} />}
    {s.evidenceBody && <KnowledgeEvidence body={knowledgeBody(s.evidenceBody.content, knowledgeToolLabel(s.evidenceBody.toolName))} skin={page.skin} />}
  </section>
}
export function AttemptPartial({ page, scope }: { page: W2PageProps; scope: AttemptContentScope }) {
  const { owner, state: s } = useContent(page, scope)
  function download() { if (!s.partial) return; const url = URL.createObjectURL(new Blob([s.partial.content], { type: 'text/markdown;charset=utf-8' })); try { const link = document.createElement('a'); link.href = url; link.download = '代码审查阶段报告.md'; link.click() } finally { URL.revokeObjectURL(url) } }
  return <WorkflowSnapshotPartialReport report={s.partial ?? undefined} loading={s.loading} error={s.error} skin={page.skin} onRead={() => { void owner.partial() }} onDownload={download} />
}
