import { useLayoutEffect, useMemo, useRef, useState } from 'react'
import { Input } from 'antd'
import { UiActionButton, UiConfirmDialog, UiContextPanel } from '@/foundation/components'
import { SemanticIcon, semanticName } from '@/foundation/semanticRegistry'
import type { W2PageProps } from '@/pages/w2/shared/types'
import type { DocumentTemplateOverview } from '@/types/domain'
import { requirementConclusionLabel, requirementKindLabel } from '@/utils/displayLabels'
import { RichDocument } from '@/pages/w3/shared/RichDocument'
import { createDocumentSourcesOwner, createClarificationOwner, createSupplementOwner } from './documentOwners'
import { createRequirementsOwner } from './contentOwners'
import type { RunController } from './runController'
import { pendingCommand } from './core'
import { CommandNotice, ReadNotice, selectionTrigger, useRunOwner, useProtectedOwner } from './parts'
import { ReadOnlyCode } from '@/pages/w3/shared/ReadOnlyCode'

export type DocumentPanelProps = { run: DocumentTemplateOverview; props: W2PageProps; parent?: RunController }
export function DocumentSourcesPanel({ run, props, parent, controller }: DocumentPanelProps & { controller?: ReturnType<typeof createDocumentSourcesOwner> }) {
  const owner = useMemo(() => controller ?? createDocumentSourcesOwner(run), [controller, run.id, run.sourceRevision]), s = useRunOwner(props, owner, parent)
  return <section className="w3-card" aria-label="冻结原文"><h2>原文目录</h2><ReadNotice error={s.error} />
    {run.files.map(file => <details key={file.id} onToggle={event => { if (event.currentTarget.open) void owner.sections(file.id) }}><summary>{file.filename} · {file.sectionCount} 段</summary>
      {!s.pages[file.id] && <UiActionButton actionKey="ui.open" target="读取目录" busy={s.busy[`index:${file.id}`]} onAction={() => { void owner.sections(file.id) }} />}
      {s.pages[file.id]?.items.map(section => <details key={section.ordinal} onToggle={event => { if (event.currentTarget.open) void owner.body(file.id, section.ordinal) }}><summary>{section.title || `第 ${section.ordinal + 1} 段`}</summary>
        {s.bodies[`${file.id}:${section.ordinal}`] ? <RichDocument content={s.bodies[`${file.id}:${section.ordinal}`]!.content} skin={props.skin} /> : <UiActionButton actionKey="ui.open" target="读取原文" busy={s.busy[`${file.id}:${section.ordinal}`]} onAction={() => { void owner.body(file.id, section.ordinal) }} />}
      </details>)}
      {s.pages[file.id]?.nextOffset != null && <UiActionButton actionKey="ui.loadMore" target="章节" onAction={() => { void owner.sections(file.id, true) }} />}
    </details>)}
  </section>
}
export function DocumentClarificationForm({ run, requirementKey, props, parent, controller, updated }: DocumentPanelProps & { requirementKey: string; controller?: ReturnType<typeof createClarificationOwner>; updated: (run: DocumentTemplateOverview) => void }) {
  const callbackScope = `${run.id}:${run.requirementRevision}:${requirementKey}`
  const latest = useRef({ updated, scope: callbackScope }); latest.current = { updated, scope: callbackScope }
  const candidate = useMemo(() => controller ?? createClarificationOwner(run, requirementKey, value => { if (latest.current.scope === callbackScope) latest.current.updated(value) }), [controller, run.id, run.requirementRevision, requirementKey]), owner = useProtectedOwner(candidate)
  const s = useRunOwner(props, owner, parent)
  useLayoutEffect(() => { owner.updateRun(run) }, [owner, run])
  return <form aria-label="回答业务问题" className="w3-form" onSubmit={event => { event.preventDefault(); void owner.submit() }}>
    <label htmlFor={`clarification-${requirementKey}`}>补充业务规则或处理方式</label><Input.TextArea id={`clarification-${requirementKey}`} aria-label="补充业务规则或处理方式" value={s.answer} rows={3} maxLength={4000} disabled={pendingCommand(s.command)} onChange={event => owner.changeAnswer(event.target.value)} />
    <p>回答后将重新整理并独立复核需求，旧版本保留。</p>
    <CommandNotice command={s.command} recover={() => { void owner.recover() }} />
    <UiActionButton actionKey="template.answerClarification" variant="primary" busy={s.loading} availability={pendingCommand(s.command) || !s.answer.trim() || !owner.canStartWrite() ? { kind: 'disabled', reason: '请保留原操作或填写明确回答。' } : { kind: 'enabled' }} onAction={() => { void owner.submit() }} />
  </form>
}
export function DocumentSupplementForm({ run, props, parent, controller, updated }: DocumentPanelProps & { controller?: ReturnType<typeof createSupplementOwner>; updated: (run: DocumentTemplateOverview) => void }) {
  const latest = useRef(updated); latest.current = updated
  const candidate = useMemo(() => controller ?? createSupplementOwner(run, value => latest.current(value)), [controller, run.id]), owner = useProtectedOwner(candidate), s = useRunOwner(props, owner, parent), input = useRef<HTMLInputElement>(null)
  return <section className="w3-card" aria-label="补充需求文档"><CommandNotice command={s.command} recover={() => { void owner.recover() }} /><ReadNotice error={s.command.error ? '' : s.error} />
    {!s.opened && <UiActionButton actionKey="template.supplement" busy={s.busy} availability={pendingCommand(s.command) ? { kind: 'disabled', reason: '请先恢复原上传。' } : { kind: 'enabled' }} onAction={() => { void owner.open() }} />}
    {s.opened && <><h2>补充需求文档</h2><p>{s.options?.message}</p>{s.options?.available && <>
      <input ref={input} type="file" multiple accept=".docx,.md,.markdown,.pdf" aria-label="选择需求文档" disabled={s.busy || pendingCommand(s.command)} onChange={event => { const selected = Array.from(event.currentTarget.files ?? []); if (selected.length) owner.choose(selected); event.currentTarget.value = '' }} />
      <p>最多 10 份 · 每份 20 MiB · 总计 50 MiB</p><ul aria-label="已选需求文档">{s.files.map((file, index) => <li key={`${index}:${file.name}`}><SemanticIcon semanticKey="object.document" />{file.name} · {(file.size / 1024).toFixed(1)} KiB<UiActionButton actionKey="ui.delete" target={file.name} iconOnly availability={s.busy || pendingCommand(s.command) ? { kind: 'disabled', reason: '原上传尚未确认。' } : { kind: 'enabled' }} onAction={() => owner.remove(index)} /></li>)}</ul>
      <UiActionButton actionKey="template.uploadSupplement" variant="primary" busy={s.busy} availability={pendingCommand(s.command) || !s.files.length || s.invalidFiles || !owner.canStartWrite() ? { kind: 'disabled', reason: '请选择有效文档并先恢复原上传。' } : { kind: 'enabled' }} onAction={() => { void owner.submit() }} />
    </>}<UiActionButton actionKey="ui.collapse" onAction={() => owner.close()} /></>}
  </section>
}
export function DocumentRequirementsPanel({ run, props, parent }: DocumentPanelProps) {
  const owner = useMemo(() => createRequirementsOwner(run), [run.id, run.requirementRevision]), s = useRunOwner(props, owner, parent), trigger = useRef<HTMLElement | null>(null)
  const detail = s.details[s.selected], selected = s.items.find(row => row.requirementKey === s.selected)
  const drafts = useMemo(() => new Map<string, ReturnType<typeof createClarificationOwner>>(), [run.id, run.requirementRevision])
  const updated = useRef(parent?.applyRun); updated.current = parent?.applyRun
  const eligible = !!detail && run.templateId === 'REQUIREMENT_DEVELOPMENT' && run.state === 'WAITING_INPUT' && (!run.designerId || run.waitingReasonCode === 'DOCUMENT_SUPPLEMENT_BUSINESS_INPUT') && !!selected?.issueCount
  if (eligible && !drafts.has(s.selected)) drafts.set(s.selected, createClarificationOwner(run, s.selected, value => updated.current?.(value)))
  const draft = drafts.get(s.selected)
  const [confirmation, setConfirmation] = useState<{ key: string; revision: number; filter?: boolean }>()
  const [blockedReason, setBlockedReason] = useState('')
  function choose(key: string, filter?: boolean) {
    const parentDecision = parent?.canLeave()
    const decision = parentDecision?.kind === 'BLOCK' ? parentDecision : draft?.canLeave() ?? parentDecision ?? { kind: 'ALLOW' as const }
    if (decision.kind === 'BLOCK') { setBlockedReason(decision.reason); return }
    if (decision.kind === 'CONFIRM_DISCARD') { setConfirmation({ key, revision: decision.draftRevision, filter }); return }
    setBlockedReason(''); selectionTrigger(trigger); if (filter !== undefined) owner.filter(filter); else void owner.select(key)
  }
  function discard() {
    const decision = draft?.canLeave()
    if (!confirmation || parent?.canLeave().kind === 'BLOCK' || decision?.kind === 'BLOCK' || decision?.kind === 'CONFIRM_DISCARD' && decision.draftRevision !== confirmation.revision) return
    draft?.discard(); if (confirmation.filter !== undefined) owner.filter(confirmation.filter); else void owner.select(confirmation.key); setConfirmation(undefined)
  }
  return <section className="w3-card" aria-label="需求清单"><header><h2>需求清单 · {run.progress.requirements}</h2><label><input type="checkbox" checked={s.issuesOnly} onChange={event => choose('', event.target.checked)} />仅看待澄清事项</label></header>
    {blockedReason && <p role="alert">{blockedReason}</p>}
    <ReadNotice error={s.error} loading={s.loading || s.detailLoading} retry={() => { void owner.load() }} />
    {!s.items.length && !s.loading && <p>{s.issuesOnly ? '当前版本没有待澄清事项' : '尚未形成经原文复核的需求清单'}</p>}
    {run.requirementRevision > 1 && !s.clarifications && <UiActionButton actionKey="ui.open" target="本版采用的业务回答" onAction={() => { void owner.history() }} />}
    {s.clarifications && <details><summary>业务澄清记录 · {s.clarifications.length} 项</summary>{s.clarifications.map((answer, index) => <article key={index}><p>需求版本 {answer.sourceRevision}：{answer.statement}</p><blockquote>{answer.answer}</blockquote></article>)}</details>}
    <ul className="w3-records">{s.items.map(row => <li key={row.requirementKey}><button type="button" data-semantic="selection.select" aria-label={semanticName('selection.select', row.title)} aria-expanded={s.selected === row.requirementKey} onClick={() => choose(row.requirementKey === s.selected ? '' : row.requirementKey)}><SemanticIcon semanticKey="object.requirement" /><strong>{row.title}</strong><span>{row.groupName} · {requirementKindLabel(row.kind)}</span><span>{requirementConclusionLabel(row.conclusion)}{row.issueCount ? ` · ${row.issueCount} 项待澄清` : ''}</span></button></li>)}</ul>
    {s.next !== null && <UiActionButton actionKey="ui.loadMore" target="需求" busy={s.loading} onAction={() => { void owner.load(true) }} />}
    <UiContextPanel open={!!s.selected} title={selected?.title ?? '需求详情'} returnFocus={trigger} onClose={() => choose('')}>
      {detail && <><p>{detail.requirement.statement}</p><ul>{detail.requirement.acceptance.map((text, index) => <li key={index}>{text}</li>)}</ul>{detail.requirement.issues.map((text, index) => <p role="alert" key={index}>{text}</p>)}
        {eligible && <DocumentClarificationForm run={run} props={props} parent={parent} controller={draft} requirementKey={s.selected} updated={value => parent?.applyRun(value)} />}
        <h3>原文依据</h3>{detail.requirement.sources.map((reference, index) => <div key={index}><UiActionButton actionKey="ui.open" target={`${run.files.find(file => file.id === reference.fileId)?.filename ?? '来源文档'} · 分段 ${reference.section + 1}`} onAction={() => { void owner.source(reference) }} /><blockquote>{reference.quote}</blockquote></div>)}
        {detail.assessment && <><p>{detail.assessment.rationale}</p><p>测试源码：{detail.assessment.testSourceCoverage}；本次未执行测试。</p><details><summary>代码证据与已检查范围</summary>{detail.assessment.evidence.map((ref, index) => <div key={index}><ReadOnlyCode content={ref.quote} label={ref.path} firstLineNumber={ref.startLine} lineWrapping /></div>)}<ul>{detail.assessment.checkedPaths.map(path => <li key={path}>{path}</li>)}</ul><p>{detail.assessment.missingEntryEvidence}</p></details><ul>{detail.assessment.limitations.map((limit, index) => <li key={index}>{limit}</li>)}</ul></>}
        {s.source && <><h3>原文分段 · {s.source.title}</h3><RichDocument content={s.source.content} skin={props.skin} /></>}
      </>}
    </UiContextPanel>
    <UiConfirmDialog open={!!confirmation} title="放弃本地回答" confirmActionKey="ui.cancelEditing" onConfirm={discard} onCancel={() => setConfirmation(undefined)}
      policy={parent?.canLeave().kind === 'BLOCK' || draft && (draft.canLeave().kind === 'BLOCK' || confirmation && draft.getSnapshot().draftRevision !== confirmation.revision) ? { kind: 'block', reason: '草稿或原操作已变化，请重新核对。' } : { kind: 'allow' }}><p>仅放弃未提交的本地回答，已保存的业务版本保持。</p></UiConfirmDialog>
  </section>
}
