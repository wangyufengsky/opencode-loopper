import type { WorkflowCodeChange, WorkflowPublicationPreview, WorkflowSnapshotPartialReport as PartialReport, WorkflowWritebackPreview as WritebackPreview } from '@/types/domain'
import { UiActionButton } from '@/foundation/components'
import { workflowCodeChangeLabel } from '@/utils/displayLabels'
import { Markdown, type ReportProps } from './ReportParts'

/** The caller owns the paged reader, its abort signal, and frozen content SHA. */
export function WorkflowCodeChanges({ rows, loaded, busy, error, cursor, onLoad, fileUrl }: { rows: WorkflowCodeChange[]; loaded: boolean; busy?: boolean; error?: string; cursor?: string | null; onLoad(more?: boolean): void; fileUrl(path: string): string }) {
  return <section className="workflow-code-changes" aria-label="代码改动文件">{!loaded && !error && <UiActionButton actionKey="ui.open" target="代码改动文件" busy={busy} onAction={() => onLoad()} />}{loaded && <><h5>改动文件</h5><p>相对原始基线的累计改动，包含继承的上游代码。</p>{!rows.length ? <p>该交付物与原始基线没有文件改动。</p> : <ul>{rows.map(change => <li key={change.path}><span>{workflowCodeChangeLabel(change.kind)}</span> {change.kind === 'DELETE' ? <span>{change.path}</span> : <a href={fileUrl(change.path)} download>{change.path}</a>}</li>)}</ul>}</>}{error && <p role="alert">{error}<UiActionButton actionKey="ui.retry" busy={busy} onAction={() => onLoad(loaded)} /></p>}{!error && cursor && <UiActionButton actionKey="ui.loadMore" target="改动文件" busy={busy} onAction={() => onLoad(true)} />}</section>
}
export function resolveWorkflowDocumentLink(path: string, href: string): string | null {
  if (href.startsWith('#')) return href.startsWith('#workflow-document=') ? null : href
  let decoded: string; try { decoded = decodeURIComponent(href.split('#')[0]!) } catch { return null }
  if (/^[\/\\]|[:?\\\u0000-\u001f\u007f]/.test(decoded) || !decoded.endsWith('.md')) return null
  const parts = path.split('/'); parts.pop(); const minimum = path.includes('/') ? 1 : 0
  for (const part of decoded.split('/')) { if (part === '..') { if (parts.length <= minimum) return null; parts.pop() } else if (part && part !== '.') parts.push(part); else if (!part) return null }
  return `#workflow-document=${encodeURIComponent(parts.join('/'))}`
}
export function WorkflowDocumentPreview({ path, body, busy, error, skin, onLoad, onNavigate, onClose }: { path: string; body?: { text: string; nextOffset: number | null; sha256: string } | null; busy?: boolean; error?: string; skin: NonNullable<ReportProps['skin']>; onLoad(more?: boolean): void; onNavigate(path: string): void; onClose(): void }) {
  return <section className="workflow-document-preview" aria-label="报告预览"><header className="w2-actions"><h5>{path.split('/').at(-1)}</h5><UiActionButton actionKey="ui.close" target="报告预览" onAction={onClose} /></header>{error && <p role="alert">{error}<UiActionButton actionKey="ui.retry" busy={busy} onAction={() => onLoad(!!body)} /></p>}{busy && <p role="status">读取报告…</p>}{body && <MarkdownDocumentBody content={body.text} path={path} skin={skin} onNavigate={onNavigate} />}{body?.nextOffset != null && (body.text.length >= 2 * 1024 * 1024 ? <p>正文较长，请下载完整报告继续阅读。</p> : <UiActionButton actionKey="ui.loadMore" target="报告正文" busy={busy} onAction={() => onLoad(true)} />)}</section>
}
import { RichDocument } from '@/pages/w3/shared/RichDocument'
function MarkdownDocumentBody({ content, path, skin, onNavigate }: { content: string; path: string; skin: NonNullable<ReportProps['skin']>; onNavigate(path: string): void }) { return <RichDocument content={content} skin={skin} allowImages={false} resolveLink={href => resolveWorkflowDocumentLink(path, href)} onLink={(href, event) => { if (href.startsWith('#workflow-document=')) { event.preventDefault(); onNavigate(decodeURIComponent(href.slice('#workflow-document='.length))) } }} /> }
export function WorkflowWritebackPreview({ source, result, reading, error, disabled, onInspect }: { source: WorkflowPublicationPreview; result?: WritebackPreview | null; reading?: boolean; error?: string; disabled?: boolean; onInspect(): void }) {
  if (source.workspaceKind !== 'DIRECT' || source.requirementState !== 'COMPLETED') return null
  return <section className="workflow-writeback-preview" aria-label="普通目录回填检查"><h3>回填前检查</h3><p>将所选成果与原目录当前文件比较，保留成果未改动路径上的后续修改。</p><UiActionButton actionKey="ui.refresh" target="原目录检查" busy={reading} availability={disabled ? { kind: 'disabled', reason: '当前成果不能检查。' } : { kind: 'enabled' }} onAction={onInspect} />{error && <p role="alert">{error}</p>}{result && <><p>原目录：{result.directory}</p>{result.conflictCount ? <><p role="alert">有 {result.conflictCount} 个路径与所选成果冲突，请核对后重新检查。</p><ul>{result.conflicts.map(path => <li key={path}><code>{path}</code></li>)}</ul>{result.conflictCount > result.conflicts.length && <p>仅展示前 {result.conflicts.length} 个冲突路径。</p>}</> : <p>预计新增 {result.added} · 修改 {result.modified} · 删除 {result.deleted}</p>}<p>保留 {result.preservedChanges} 处用户后续修改。本次检查没有写入文件。</p></>}</section>
}
export function WorkflowSnapshotPartialReport({ report, loading, error, skin, onRead, onDownload }: { report?: PartialReport; loading?: boolean; error?: string; skin?: ReportProps['skin']; onRead(): void; onDownload(): void }) {
  return <section aria-label="阶段审查报告"><h3>已完成批次报告</h3><p>仅包含当前已完成分析，不代表全部批次已完成；完整汇总由后续报告节点产生。</p><UiActionButton actionKey="ui.refresh" target="阶段审查报告" busy={loading} onAction={onRead} />{error && <p role="alert">{error}</p>}{report && <><p>已分析 {report.analyzedUnits} 个片段 · 待分析 {report.pendingUnits} 个 · 排除 {report.excludedUnits} 个</p><p>计划版本 {report.planRevision} · 采集时间 {report.capturedAt}</p><Markdown content={report.content} skin={skin} /><UiActionButton actionKey="ui.download" target="阶段审查报告" onAction={onDownload} /></>}</section>
}
