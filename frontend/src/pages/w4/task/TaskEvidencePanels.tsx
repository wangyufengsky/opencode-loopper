import { useMemo, useRef, useState } from 'react'
import { UiActionButton, UiContextPanel } from '@/foundation/components'
import { RichDocument } from '@/pages/w3/shared/RichDocument'
import { ReadOnlyCode } from '@/pages/w3/shared/ReadOnlyCode'
import { useW4Owner, ReadNotice, selectionTrigger } from '../shared/parts'
import type { TaskPanelProps } from '../shared/types'
import { createTaskEvidenceController, linkedReport, type TaskEvidenceController } from './evidenceController'
import { taskAttempts, taskFacts } from './projections'
import { displayLabel, errorEventMessage, evidenceCaptureLabel } from '@/utils/displayLabels'
import { templateBatchPurpose } from '@/utils/templateSessionLabels'
import { formatDateTime } from '@/utils/dateTime'
import type { Artifact, ErrorEvent } from '@/types/domain'

export function reportTitle(report: Artifact) { return `${typeof report.metadata?.displayName === 'string' ? report.metadata.displayName : report.title.startsWith('contributors/') ? '个人贡献周报' : report.title} · 第 ${Number(report.metadata?.repairRound ?? 0) + 1} 版` }
export function TemplateReportsPanel({ task, page, parent, owner, loadingMetadata = false, metadataError = '' }: TaskPanelProps & { owner: TaskEvidenceController; loadingMetadata?: boolean; metadataError?: string }) {
  const s = useW4Owner(page, owner), reports = (task.artifacts ?? []).filter(row => row.kind === 'REPORT'), selected = reports.find(row => row.id === s.selectedReport)
  const round = Math.max(0, ...reports.map(row => Number(row.metadata?.repairRound ?? 0)))
  const summary = [...reports].filter(row => row.metadata?.reportRole === 'SUMMARY').sort((a, b) => Number(b.metadata?.repairRound ?? 0) - Number(a.metadata?.repairRound ?? 0))[0]
  const status = task.status === 'COMPLETED' && selected && Number(selected.metadata?.repairRound ?? 0) === round ? task.templateProgress?.dualReviewRequired === false ? '已校验并保存' : '已通过评审' : selected ? '待验收版本' : reports.length ? task.status === 'COMPLETED' ? '已完成' : '已生成，待验收' : metadataError ? '读取失败' : loadingMetadata ? '加载中' : '待生成'
  return <section className="w4-card" aria-label="模板任务报告"><header><h2>报告</h2><span>{status}</span></header>
    <ReadNotice error={metadataError} loading={loadingMetadata} retry={() => { void parent.refresh().catch(() => {}) }} />
    {!reports.length && !loadingMetadata && !metadataError && <p>完整证据分析完成后，报告会显示在这里。</p>}
    <div className="w4-actions">{summary && <UiActionButton actionKey="ui.open" target="最新总结" onAction={() => { void owner.previewReport(summary) }} />}<label>选择报告<select aria-label="选择报告" value={s.selectedReport} onChange={event => { const report = reports.find(row => row.id === event.target.value); if (report) void owner.previewReport(report) }}><option value="">选择报告预览</option>{reports.map(row => <option key={row.id} value={row.id}>{reportTitle(row)}</option>)}</select></label>{selected && <><UiActionButton actionKey="ui.download" target="Markdown" availability={s.bodies[`artifact:${selected.id}`] === undefined ? { kind: 'disabled', reason: '请先读取所选报告。' } : { kind: 'enabled' }} onAction={() => owner.downloadMarkdown(selected)} />{selected.metadata?.bundleId != null && <UiActionButton actionKey="ui.download" target="整套报告" busy={s.loading.download} onAction={() => { void owner.downloadBundle(selected) }} />}</>}</div>
    <ReadNotice error={s.errors.download ?? ''} />
    {selected && <><ReadNotice error={s.errors[`artifact:${selected.id}`] ?? ''} loading={s.loading[`artifact:${selected.id}`]} retry={() => { void owner.previewReport(selected) }} />{s.bodies[`artifact:${selected.id}`] !== undefined && <RichDocument content={s.bodies[`artifact:${selected.id}`]!} skin={page.skin} resolveLink={href => {
      if (/^(?:https?:|mailto:|#)/i.test(href)) return href
      return linkedReport(selected, reports, href) ? href : null
    }} onLink={(href, event) => {
      if (/^(?:[a-z][a-z0-9+.-]*:|\/\/|#)/i.test(href)) return
      event.preventDefault(); const report = linkedReport(selected, reports, href); if (report) void owner.previewReport(report)
    }} />}</>}
  </section>
}

export function SnapshotReviewPartialReportPanel({ page, owner }: { page: TaskPanelProps['page']; owner: TaskEvidenceController }) {
  const s = useW4Owner(page, owner)
  return <section className="w4-card" aria-label="阶段报告"><header><h2>阶段报告</h2><UiActionButton actionKey="ui.open" target="阶段报告" busy={s.loading.partial} onAction={() => { void owner.partial() }} />{s.partial && <UiActionButton actionKey="ui.download" target="阶段报告" onAction={owner.downloadPartial} />}</header><p>已完成分析与未完成范围分别标注，取消后仍可读取。</p><ReadNotice error={s.errors.partial ?? ''} />{s.partial && <><p>已分析 {s.partial.analyzedUnits} · 未完成 {s.partial.pendingUnits} · 排除 {s.partial.excludedUnits}</p><RichDocument content={s.partial.content} skin={page.skin} /></>}</section>
}

export function ExecutionEvidencePanel({ owner, page }: { owner: TaskEvidenceController; page: TaskPanelProps['page'] }) {
  const s = useW4Owner(page, owner), [opened, setOpened] = useState(false)
  return <section className="w4-card" aria-label="日志与测试快照"><UiActionButton actionKey={opened ? 'ui.collapse' : 'ui.expand'} target="日志与测试快照" expanded={opened} onAction={() => { setOpened(!opened); if (!opened && !s.evidence) void owner.evidence() }} />{opened && <>
    <ReadNotice error={s.errors.evidence ?? ''} loading={s.loading.evidence} retry={() => { void owner.evidence() }} />
    <UiActionButton actionKey="ui.refresh" target="已保存证据" busy={s.loading.evidence} onAction={() => { void owner.evidence() }} />
    {s.evidence && !s.evidence.items.length && <p>暂无已采集快照，历史任务不会从当前文件补造证据。</p>}
    <ul>{s.evidence?.items.map(row => <li key={row.id}><UiActionButton actionKey="ui.open" target={row.source} onAction={() => { void owner.evidenceBody(row.reference) }} />{evidenceCaptureLabel(row.status)} · {formatDateTime(row.createdAt)}</li>)}</ul>
    {s.evidence?.nextCursor && <UiActionButton actionKey="ui.next" target="来源" onAction={() => { void owner.evidence(s.evidence!.nextCursor) }} />}
    {s.failures?.detail && <p>{s.failures.detail}</p>}<ul>{s.failures?.items.map(row => <li key={row.id}><UiActionButton actionKey="ui.open" target={`${row.className} · ${row.name}`} onAction={() => { void owner.evidenceFailure(row.id) }} /></li>)}</ul>
    {s.failures?.nextCursor && <UiActionButton actionKey="ui.next" target="失败用例" onAction={() => { void owner.evidence('', s.failures!.nextCursor) }} />}
    <form onSubmit={event => { event.preventDefault(); void owner.search() }}><label>搜索已保存证据<input aria-label="搜索已保存证据" maxLength={256} value={s.query} onChange={event => owner.changeQuery(event.target.value)} /></label><UiActionButton actionKey="ui.search" availability={s.query.trim() ? { kind: 'enabled' } : { kind: 'disabled', reason: '请输入搜索内容。' }} onAction={() => { void owner.search() }} /></form>
    <ReadNotice error={s.errors.search ?? ''} loading={s.loading.search} />
    {s.search && <>{!s.search.complete && <p>本次搜索范围不完整，请缩小范围或继续读取来源。</p>}<ul>{s.search.items.map(row => <li key={`${row.reference}:${row.offset}`}><UiActionButton actionKey="ui.open" target={row.source} onAction={() => { void owner.evidenceBody(row.reference, Math.max(0, row.offset - 100)) }} /><pre>{row.excerpt}</pre></li>)}</ul>{s.search.nextCursor && <UiActionButton actionKey="ui.next" target="搜索结果" onAction={() => { void owner.search(s.search!.nextCursor) }} />}</>}
    <ReadNotice error={s.errors.evidenceBody ?? ''} loading={s.loading.evidenceBody} />
    {s.body && <><p>{s.body.source} · {evidenceCaptureLabel(s.body.status)}</p><ReadOnlyCode content={s.body.content} language="plain" label="持久化证据正文" />{s.body.nextOffset >= 0 && <UiActionButton actionKey="ui.next" target="证据正文" onAction={() => { void owner.evidenceBody(s.body!.reference, s.body!.nextOffset) }} />}</>}
    {s.failure && <><h3>{s.failure.failure.name}</h3><ReadOnlyCode content={[s.failure.failure.message, s.failure.failure.stack, s.failure.failure.output].join('\n')} language="plain" label="失败用例证据" /></>}
  </>}</section>
}

export function TaskAuditEvidencePanel({ task, page, owner }: TaskPanelProps & { owner: TaskEvidenceController }) {
  const s = useW4Owner(page, owner), [tab, setTab] = useState<'logs' | 'diff' | 'evidence'>('evidence'), trigger = useRef<HTMLElement | null>(null)
  const attempts = [...taskAttempts(task)].sort((a, b) => b.ordinal - a.ordinal), rows = attempts.flatMap(attempt => attempt.verifiers.map(verifier => ({ attempt, verifier })))
  const artifacts = task.artifacts ?? [], handoff = artifacts.filter(row => row.title.startsWith('attempt-handoff-')), diff = artifacts.find(row => row.kind === 'DIFF')
  const strings = (value: unknown): string[] => Array.isArray(value) ? value.filter((row): row is string => typeof row === 'string') : []
  const git = rows.filter(row => row.verifier.name.toUpperCase() === 'GIT_DIFF'), paths = [...new Set([...strings(diff?.metadata?.changedPaths), ...git.flatMap(row => strings(row.verifier.evidence?.changedPaths))])]
  const untracked = new Set([...strings(diff?.metadata?.untrackedPaths), ...git.flatMap(row => strings(row.verifier.evidence?.untrackedPaths))]), violations = [...new Set(git.flatMap(row => strings(row.verifier.evidence?.violations)))]
  const body = (kind: 'verification' | 'artifact', item: string, inline = '') => <><ReadNotice error={s.errors[`${kind}:${item}`] ?? ''} loading={s.loading[`${kind}:${item}`]} /><ReadOnlyCode content={s.bodies[`${kind}:${item}`] ?? inline ?? '该验证没有原始输出'} language="plain" label="审计原始输出" /></>
  return <section className="w4-card" aria-label="审计证据"><header><h2>验证、差异与日志</h2><nav aria-label="审计类别">{(['logs', 'diff', 'evidence'] as const).map((value, index) => <button type="button" key={value} aria-label={['日志', '差异', '验证'][index]} aria-pressed={tab === value} onClick={() => setTab(value)}>{['日志', '差异', '验证'][index]}</button>)}</nav></header>
    {tab === 'logs' && <><p>验证日志与尝试交接 · {rows.length + handoff.length} 份</p>{handoff.map(row => <details key={row.id} onToggle={event => { if (event.currentTarget.open) void owner.loadBody('artifact', row.id) }}><summary>结构化重试交接</summary>{body('artifact', row.id, row.content)}</details>)}{rows.map(({ attempt, verifier }) => <details key={verifier.id} onToggle={event => { if (event.currentTarget.open) void owner.loadBody('verification', verifier.id) }}><summary>{strings(verifier.evidence?.argv).join(' ') || displayLabel(verifier.name)} · 尝试 {attempt.ordinal}</summary><p>{verifier.summary}</p>{body('verification', verifier.id, verifier.output)}</details>)}{!rows.length && !handoff.length && <p>暂无验证日志</p>}</>}
    {tab === 'diff' && <><p>本地变更 · {paths.length} 个文件</p><ul aria-label="变更文件">{paths.map(path => <li key={path}><span>{untracked.has(path) ? '新增' : '修改'}</span><button type="button" aria-label={`预览差异 ${path}`} onClick={() => { selectionTrigger(trigger); void owner.showDiff(path) }}>{path}</button></li>)}</ul>{!paths.length && <p>没有检测到文件变更</p>}{!!violations.length && <div className="w4-critical" role="alert"><h3>范围违规</h3><ul>{violations.map(row => <li key={row}>{row}</li>)}</ul></div>}{diff && <details onToggle={event => { if (event.currentTarget.open) void owner.loadBody('artifact', diff.id) }}><summary>任务基线差异快照</summary>{body('artifact', diff.id, diff.content)}</details>}</>}
    {tab === 'evidence' && <><p>确定性验证 · {taskFacts(task).verified}/{rows.length} 通过</p>{rows.map(({ attempt, verifier }, index) => <article key={verifier.id} className="w4-verification"><h3>{displayLabel(verifier.name)} · {displayLabel(verifier.status)}</h3><p>尝试 {attempt.ordinal} · 验证 {index + 1}</p><p>{verifier.summary}</p>{typeof verifier.evidence?.workingDirectory === 'string' && <p>执行目录 <code>{verifier.evidence.workingDirectory}</code></p>}{Array.isArray(verifier.evidence?.argv) && <code>{strings(verifier.evidence.argv).join(' ')}</code>}<p>{typeof verifier.evidence?.exitCode === 'number' && `退出码 ${verifier.evidence.exitCode} · `}{verifier.elapsedMs ? `${verifier.elapsedMs} ms` : ''}{verifier.evidence?.timedOut === true && ' · 已超时'}{verifier.evidence?.outputTruncated === true && ' · 输出已截断'}</p><details onToggle={event => { if (event.currentTarget.open) void owner.loadBody('verification', verifier.id) }}><summary>按需查看完整输出</summary>{body('verification', verifier.id, verifier.output)}</details></article>)}{!rows.length && <p>暂无验证结果</p>}</>}
    <UiContextPanel open={!!s.previewPath} title={`文件差异：${s.previewPath}`} returnFocus={trigger} onClose={owner.closeDiff}><ReadNotice error={s.errors.diff ?? ''} loading={s.loading.diff} retry={() => { void owner.showDiff(s.previewPath) }} />{s.preview && <><p>{s.preview.changeType === 'NEW' ? '新增文件' : '修改文件'}{s.preview.truncated && ' · 差异已截断'}</p><pre className="w4-diff">{s.preview.patch.split('\n').map((line, index) => <span key={index} data-diff-kind={line.startsWith('+') && !line.startsWith('+++') ? 'added' : line.startsWith('-') && !line.startsWith('---') ? 'removed' : line.startsWith('@@') ? 'hunk' : 'context'}>{line}{'\n'}</span>)}</pre></>}</UiContextPanel>
  </section>
}

export function LayeredTaskError({ error, owner, taskState }: { error: ErrorEvent; owner: TaskEvidenceController; taskState: string }) {
  const s = owner.getSnapshot(), title = error.layer === 'SESSION' ? '当前会话已结束，系统将使用新会话继续' : error.layer === 'VERIFICATION' ? '验证未通过，保留证据等待下一步' : taskState === 'WAITING_INPUT' ? '任务等待处理' : '任务已终止'
  return <section className={`w4-layered-error layer-${error.layer.toLowerCase()}`} role={error.layer === 'TASK' ? 'alert' : 'status'}><h3>{title}</h3><p>{errorEventMessage(error.code, error.message)}</p><time>{formatDateTime(error.occurredAt)}</time>{error.evidenceId && <details onToggle={event => { if (event.currentTarget.open) void owner.loadBody('error', error.id) }}><summary>错误证据</summary><ReadNotice error={s.errors[`error:${error.id}`] ?? ''} loading={s.loading[`error:${error.id}`]} /><ReadOnlyCode content={s.bodies[`error:${error.id}`] ?? ''} language="plain" label="错误证据正文" /></details>}</section>
}

export function SnapshotBatchesPanel({ owner, page }: { owner: TaskEvidenceController; page: TaskPanelProps['page'] }) {
  const s = useW4Owner(page, owner), [opened, setOpened] = useState(false)
  return <section aria-label="功能批次"><UiActionButton actionKey={opened ? 'ui.collapse' : 'ui.expand'} target="功能批次" expanded={opened} onAction={() => { setOpened(!opened); if (!opened) void owner.batches() }} />{opened && <><ReadNotice error={s.errors.batches ?? ''} loading={s.loading.batches} retry={() => { void owner.batches() }} /><ul className="w4-batch-list">{s.batches.map(row => <li key={row.id}><strong>{row.title}</strong><p>{templateBatchPurpose(row.purpose)} · {displayLabel(row.state)}</p>{row.errorMessage && <p role="alert">{row.errorMessage}</p>}</li>)}</ul>{!s.loading.batches && !s.batches.length && <p>尚未创建功能批次。</p>}{s.batchCursor && <UiActionButton actionKey="ui.loadMore" onAction={() => { void owner.batches(true) }} />}</>}</section>
}

export function useTaskEvidence(id: string, page: TaskPanelProps['page']) {
  const owner = useMemo(() => createTaskEvidenceController(id), [id]); useW4Owner(page, owner); return owner
}
