/** W0 uses real React child panels and actual retained-root disposal before any late result. */
import { act, fireEvent, render } from '@testing-library/react'
import { expect, vi } from 'vitest'
import { StrictMode, type ReactNode } from 'react'
import { FoundationProvider } from '@/foundation/provider'
import { skins } from '@/themes/registry'
import { api } from '@/api/client'
import { semanticName } from '@/foundation/semanticRegistry'
import type { DocumentTemplateOverview, DocumentSectionPage, DocumentSection } from '@/types/domain'
import { foundationDOM, pageFrame, pageProps } from '@/pages/w2/workflow/page.test-support'
import { DocumentClarificationForm, DocumentSourcesPanel, DocumentSupplementForm } from './DocumentPanels'
import { TemplateBatchRecoveryPanel, TemplateSessionDiagnosticsPanel } from './RecoveryPanels'
import { createClarificationOwner, createDocumentSourcesOwner, createSupplementOwner } from './documentOwners'
import { createBatchRecoveryOwner, createDiagnosticOwner } from './recoveryOwners'
import { batch, deferred, diagnostic, documentRun, failedPage, flush } from './test-support'

type Proof = (name: string, value: unknown) => void
export function childRunFixture(): DocumentTemplateOverview { return { ...documentRun(), files: [{ id: 'file-1', filename: '需求.md', format: 'MARKDOWN', representationSha256: 'representation-sha', parserVersion: 'parser-v1', sizeBytes: 5, sha256: 'frozen-file', sectionCount: 1, limitations: [] }] } }
const section: DocumentSection = { fileId: 'file-1', ordinal: 0, title: '第一段', sha256: 'section-hash', content: 'A原文' }
const sectionPage: DocumentSectionPage = { items: [{ ...section, characters: 3 }], nextOffset: null }
const supplementOptions = { available: true, message: '补传', request: { requestKey: 'supplement-request-key', expectedVersion: 3, expectedTaskVersion: -1 } }
export async function settleChild() { await act(async () => { await flush() }) }
export async function mountRunChild(element: (props: ReturnType<typeof pageProps>) => ReactNode, options: { strict?: boolean } = {}) {
  foundationDOM()
  const props = pageProps('/template-tasks/document-runs/A'), retained = new Map<object, () => void>()
  props.lifecycle.retain = (owner, dispose) => { if (!retained.has(owner)) retained.set(owner, dispose) }
  const frame = pageFrame(element(props)), view = render(options.strict ? <StrictMode>{frame}</StrictMode> : frame); await settleChild()
  return { ...view, props, async unmountRoot() { await act(async () => { view.unmount(); for (const dispose of retained.values()) dispose(); retained.clear(); await flush() }) } }
}
export async function childClick(host: HTMLElement, key: Parameters<typeof semanticName>[0], target?: string) {
  const name = semanticName(key, target), button = [...host.querySelectorAll<HTMLButtonElement>('button')].find(button => button.getAttribute('aria-label') === name)
  if (!button) throw new Error(`真实子面板没有 ${name}: ${host.textContent}`)
  await act(async () => { button.click(); await flush() })
}
export async function templateSourcesRetirementW0Contract(kind: 'directory' | 'body', options: { run?: DocumentTemplateOverview; page?: DocumentSectionPage; section?: DocumentSection; proof?: Proof } = {}) {
  const run = options.run ?? childRunFixture(), page = options.page ?? sectionPage, value = options.section ?? section
  const pending = deferred<DocumentSectionPage & DocumentSection>(), read = vi.mocked(kind === 'directory' ? api.documentSections : api.documentSection)
  read.mockReturnValue(pending.promise)
  if (kind === 'body') vi.mocked(api.documentSections).mockResolvedValue(page)
  const owner = createDocumentSourcesOwner(run), view = await mountRunChild(props => <DocumentSourcesPanel run={run} props={props} controller={owner} />)
  try {
    await childClick(view.container, 'ui.open', '读取目录')
    if (kind === 'body') await childClick(view.container, 'ui.open', '读取原文')
    expect(read).toHaveBeenCalledTimes(1)
    await view.unmountRoot(); const before = owner.getSnapshot(), selectedBefore = kind === 'directory' ? before.pages : before.bodies
    pending.resolve((kind === 'directory' ? page : value) as DocumentSectionPage & DocumentSection); await settleChild()
    expect(kind === 'directory' ? owner.getSnapshot().pages : owner.getSnapshot().bodies).toEqual(selectedBefore)
    expect(owner.getSnapshot()).toEqual(before)
    options.proof?.(`B8.3/sources/${kind}`, { requests: read.mock.calls, before: selectedBefore, after: kind === 'directory' ? owner.getSnapshot().pages : owner.getSnapshot().bodies, busy: owner.getSnapshot().busy, actualReact: true, rootRetiredBeforeResolve: true })
  } finally { await view.unmountRoot() }
}
export async function templateClarificationRetirementW0Contract(options: { run?: DocumentTemplateOverview; proof?: Proof } = {}) {
  const run = options.run ?? documentRun(), pending = deferred<DocumentTemplateOverview>(), write = vi.mocked(api.answerDocumentRequirements); write.mockReturnValue(pending.promise)
  const updated = vi.fn(), a = createClarificationOwner(run, 'REQ-1', updated), view = await mountRunChild(props => <DocumentClarificationForm run={run} requirementKey="REQ-1" props={props} controller={a} updated={updated} />)
  let next: Awaited<ReturnType<typeof mountRunChild>> | undefined
  try {
    const answer = view.container.querySelector<HTMLTextAreaElement>('textarea')!; fireEvent.change(answer, { target: { value: '原始回答' } }); fireEvent.submit(view.container.querySelector('form')!); await settleChild()
    expect(write).toHaveBeenCalledTimes(1); expect(answer.disabled).toBe(true)
    await view.unmountRoot(); const before = a.getSnapshot(), b = createClarificationOwner({ ...run, id: 'B' }, 'REQ-1', updated)
    next = await mountRunChild(props => <DocumentClarificationForm run={{ ...run, id: 'B' }} requirementKey="REQ-1" props={props} controller={b} updated={updated} />)
    fireEvent.change(next.container.querySelector('textarea')!, { target: { value: 'B草稿' } }); await settleChild(); pending.resolve({ ...run, version: 4 }); await settleChild()
    expect.soft(updated).not.toHaveBeenCalled(); expect.soft(a.getSnapshot().answer).toBe('原始回答'); expect(b.getSnapshot().answer).toBe('B草稿'); expect(a.getSnapshot()).toEqual(before)
    options.proof?.('B8.3/clarification', { request: write.mock.calls[0], retiredEmit: undefined, retiredDraft: a.getSnapshot().answer, nextDraft: b.getSnapshot().answer, forcedRetirement: true, actualReact: true })
  } finally { await view.unmountRoot(); await next?.unmountRoot() }
}
export async function templateSupplementRetirementW0Contract(kind: 'options' | 'upload', options: { run?: DocumentTemplateOverview; file?: File; proof?: Proof } = {}) {
  const run = options.run ?? documentRun(), updated = vi.fn(), owner = createSupplementOwner(run, updated), pending = deferred<DocumentTemplateOverview & typeof supplementOptions>()
  if (kind === 'options') vi.mocked(api.documentSupplementOptions).mockReturnValue(pending.promise)
  else { vi.mocked(api.documentSupplementOptions).mockResolvedValue(supplementOptions); vi.mocked(api.uploadDocumentSupplement).mockReturnValue(pending.promise) }
  const view = await mountRunChild(props => <DocumentSupplementForm run={run} props={props} controller={owner} updated={updated} />)
  try {
    await childClick(view.container, 'template.supplement')
    const file = options.file ?? new File(['原始字节'], '需求.md')
    if (kind === 'upload') { const input = view.container.querySelector<HTMLInputElement>('input[type=file]')!; fireEvent.change(input, { target: { files: [file] } }); await settleChild(); await childClick(view.container, 'template.uploadSupplement'); expect(input.disabled).toBe(true); expect(vi.mocked(api.uploadDocumentSupplement).mock.calls[0]![2]![0]).toBe(file) }
    await view.unmountRoot(); const before = owner.getSnapshot()
    pending.resolve((kind === 'options' ? supplementOptions : { ...run, version: 4 }) as DocumentTemplateOverview & typeof supplementOptions); await settleChild()
    expect(owner.getSnapshot()).toEqual(before)
    if (kind === 'options') { expect.soft(owner.getSnapshot().opened).toBe(false); expect.soft(owner.getSnapshot().options).toBeUndefined(); expect.soft(owner.getSnapshot().busy).toBe(true) }
    else { expect.soft(updated).not.toHaveBeenCalled(); expect.soft(owner.getFiles()).toHaveLength(1); expect(owner.getFiles()[0]).toBe(file) }
    options.proof?.(`B8.3/supplement-${kind}`, { opened: owner.getSnapshot().opened, options: owner.getSnapshot().options, busy: owner.getSnapshot().busy, files: owner.getFiles().map(file => file.name), retiredEmit: undefined, forcedRetirement: true, actualReact: true })
  } finally { await view.unmountRoot() }
}
export async function templateBatchW0Contract(mode: 'original-cas' | 'no-auto-write' | 'stop-proof', options: { proof?: Proof } = {}) {
  const read = vi.mocked(api.templateFailedBatches), write = vi.mocked(api.retrySelectedTemplateBatches); read.mockResolvedValue(failedPage()); write.mockRejectedValue(new Error('未知批次回执'))
  const owner = createBatchRecoveryOwner('task', 'task-A'), view = await mountRunChild(props => <TemplateBatchRecoveryPanel kind="task" id="task-A" props={props} controller={owner} />)
  let recovery: Awaited<ReturnType<typeof mountRunChild>> | undefined
  try {
    fireEvent.click(view.container.querySelector('input[type=checkbox]')!); await settleChild(); await childClick(view.container, 'template.retryBatches', '重新触发所选批次（1）'); expect(write).toHaveBeenCalledTimes(1)
    read.mockResolvedValue(failedPage(4, mode !== 'stop-proof')); await childClick(view.container, 'ui.refresh', '批次')
    if (mode === 'original-cas') { await childClick(view.container, 'receipt.retryOriginal'); const requests = write.mock.calls.map(call => ({ task: call[0], batches: call[1].map(row => ({ id: row.id, expectedVersion: row.version })) })); expect(requests[1]).toEqual(requests[0]); expect(write.mock.calls[1]![1]).toEqual([batch(3)]); options.proof?.('B9.3/batch-CAS', requests) }
    else if (mode === 'stop-proof') { expect([...view.container.querySelectorAll('button')].some(button => button.getAttribute('aria-label')?.startsWith(semanticName('template.retryBatches')))).toBe(false); await childClick(view.container, 'receipt.retryOriginal'); expect(write).toHaveBeenCalledTimes(1); expect(owner.getSnapshot().ready).toBe(false); options.proof?.('B9.3/stop-proof', { writes: write.mock.calls.length, ready: false, actualReact: true }) }
    else { for (const skin of skins) { view.rerender(<FoundationProvider skin={skin} reducedMotion><TemplateBatchRecoveryPanel kind="task" id="task-A" props={{ ...view.props, skin }} controller={owner} /></FoundationProvider>); await settleChild(); expect(view.container.querySelector('[data-foundation-skin]')?.getAttribute('data-foundation-skin')).toBe(skin.id); expect(write).toHaveBeenCalledTimes(1) } const diag = createDiagnosticOwner('task-A', false); vi.mocked(api.getTemplateSessionDiagnostics).mockResolvedValue({ items: [diagnostic], nextCursor: null, hasMore: false }); recovery = await mountRunChild(props => <TemplateSessionDiagnosticsPanel taskId="task-A" active={false} props={props} controller={diag} />); await childClick(recovery.container, 'ui.refresh', '诊断状态'); await vi.advanceTimersByTimeAsync(1200); await settleChild(); expect(write).toHaveBeenCalledTimes(1); options.proof?.('B9.1/batch-owner', { writes: write.mock.calls, reads: read.mock.calls.length, recoveryOpened: true, actualReact: true }) }
  } finally { await view.unmountRoot(); await recovery?.unmountRoot() }
}
export async function templateDiagnosticW0Contract(mode: 'accepted-read-failure' | 'unknown-identity', options: { proof?: Proof } = {}) {
  const read = vi.mocked(api.getTemplateSessionDiagnostics), write = vi.mocked(api.recoverTemplateSession)
  read.mockResolvedValue({ items: [diagnostic], nextCursor: null, hasMore: false })
  write.mockImplementation(async () => { if (mode === 'unknown-identity') throw new Error('未知恢复回执'); return { ...diagnostic, phase: 'STOP_REQUESTED', canFinalize: false } })
  const owner = createDiagnosticOwner('task-A', false), view = await mountRunChild(props => <TemplateSessionDiagnosticsPanel taskId="task-A" active={false} props={props} controller={owner} />)
  try {
    if (mode === 'accepted-read-failure') read.mockRejectedValueOnce(new Error('接受后读取失败')).mockResolvedValue({ items: [diagnostic], nextCursor: null, hasMore: false })
    await childClick(view.container, 'template.finalizeSession'); expect(write).toHaveBeenCalledTimes(1)
    if (mode === 'accepted-read-failure') expect(view.container.querySelector('[role=alert]')!.textContent).toBeTruthy()
    await childClick(view.container, 'ui.refresh', '诊断状态')
    if (mode === 'unknown-identity') { for (const skin of skins) { view.rerender(<FoundationProvider skin={skin} reducedMotion><TemplateSessionDiagnosticsPanel taskId="task-A" active={false} props={{ ...view.props, skin }} controller={owner} /></FoundationProvider>); await settleChild(); expect(view.container.querySelector('[data-foundation-skin]')?.getAttribute('data-foundation-skin')).toBe(skin.id); expect(write).toHaveBeenCalledTimes(1) } await childClick(view.container, 'receipt.retryOriginal'); expect(write.mock.calls[1]).toEqual(write.mock.calls[0]); expect(write.mock.calls[0]![2]).toEqual({ action: 'FINALIZE', expectedVersion: 7, commandId: expect.any(String) }); options.proof?.('B9.2/diagnostic', { original: write.mock.calls[0], retry: write.mock.calls[1], actualReact: true }) }
    else { const button = [...view.container.querySelectorAll<HTMLButtonElement>('button')].find(button => button.getAttribute('aria-label') === semanticName('template.finalizeSession')); button?.click(); await settleChild(); expect(write).toHaveBeenCalledTimes(1); expect(owner.getSnapshot().command.phase).toBe('ACCEPTED_READBACK'); await childClick(view.container, 'receipt.readOriginal'); expect(write).toHaveBeenCalledTimes(1); options.proof?.('B9.3/diagnostic-accepted-read-fail', { reads: read.mock.calls.length, writes: write.mock.calls, knownAccepted: true, staleReadCanFinalize: true, actualReact: true }) }
  } finally { await view.unmountRoot() }
}
