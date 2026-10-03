import { act, fireEvent } from '@testing-library/react'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { api } from '@/api/client'
import type { DocumentTemplateOverview, DocumentRequirementSummary } from '@/types/domain'
import { createRunController } from './runController'
import { DocumentTemplatePage } from './RunPages'
import { mountRunChild, childClick, settleChild } from './run-child-w0-contract'
import { documentRun, deferred, flush, stream } from './test-support'

const waitingRun = (): DocumentTemplateOverview => ({ ...documentRun(), templateId: 'REQUIREMENT_DEVELOPMENT', state: 'WAITING_INPUT', sourceRevision: 1 })
const requirement: DocumentRequirementSummary = { ordinal: 0, requirementKey: 'REQ-1', title: '待澄清需求', kind: 'FUNCTIONAL', groupName: '业务', conclusion: 'UNDETERMINED', issueCount: 1 }
beforeEach(() => {
  vi.useFakeTimers(); vi.stubGlobal('EventSource', class {})
  vi.spyOn(api, 'documentTemplate').mockResolvedValue(waitingRun()); vi.spyOn(api, 'documentEvents').mockImplementation(() => stream() as unknown as EventSource)
  vi.spyOn(api, 'documentRequirements').mockResolvedValue({ items: [requirement], revision: 1, nextOffset: null })
  vi.spyOn(api, 'documentRequirement').mockResolvedValue({ requirement: { key: 'REQ-1', title: requirement.title, group: '业务', kind: 'FUNCTIONAL', statement: '需要补充', sources: [], acceptance: [], issues: ['需要澄清'] }, assessment: null })
  vi.spyOn(api, 'documentReports').mockResolvedValue({ items: [], facets: {} }); vi.spyOn(api, 'documentSupplementOptions').mockResolvedValue({ available: true, message: '补传', request: { requestKey: 'original-upload', expectedVersion: 3, expectedTaskVersion: -1 } })
})
afterEach(() => { vi.clearAllTimers(); vi.useRealTimers(); vi.restoreAllMocks(); vi.unstubAllGlobals() })
for (const phase of ['dirty', 'sending', 'unknown'] as const) it(`external eligibility/revision update preserves ${phase} supplement owner, original File and visible recovery`, async () => {
  const pending = deferred<DocumentTemplateOverview>(), write = vi.spyOn(api, 'uploadDocumentSupplement').mockReturnValue(pending.promise), owner = createRunController('document', 'A'), view = await mountRunChild(props => <DocumentTemplatePage {...props} route={{ ...props.route, params: { id: 'A' } }} controller={owner} />)
  try {
    await childClick(view.container, 'template.supplement'); const file = new File(['原始上传字节'], '原需求.md'), input = view.container.querySelector<HTMLInputElement>('input[type=file]')!; fireEvent.change(input, { target: { files: [file] } }); await settleChild()
    if (phase !== 'dirty') { await childClick(view.container, 'template.uploadSupplement'); expect(write).toHaveBeenCalledTimes(1); if (phase === 'unknown') { pending.reject(new Error('未知回执')); await settleChild() } }
    await act(async () => { owner.applyRun({ ...waitingRun(), version: 9, requirementRevision: 2, state: 'ANALYZING' }); await flush() })
    expect(owner.getSnapshot().run!.version).toBe(9); expect(owner.getSnapshot().documentContext!.requirementRevision).toBe(1); expect(view.container.textContent).toContain('原需求.md'); expect(view.container.querySelector<HTMLInputElement>('input[type=file]')).toBe(input)
    expect(owner.canLeave().kind).toBe(phase === 'dirty' ? 'CONFIRM_DISCARD' : 'BLOCK')
    if (phase === 'unknown') { const original = write.mock.calls[0]!; write.mockRejectedValue(new Error('仍未确认')); const recovery = view.container.querySelector<HTMLElement>('[aria-label="补充文档恢复"]')!; expect(recovery).toBeTruthy(); await childClick(recovery, 'receipt.retryOriginal'); expect(write.mock.calls[1]).toEqual(original); expect(write.mock.calls[1]![2]![0]).toBe(file) }
    if (phase === 'sending') { pending.reject(new Error('未确认')); await settleChild() }
  } finally { await view.unmountRoot() }
})
it('external requirement revision cannot retire pending clarification or replace its original draft/key/body', async () => {
  const pending = deferred<DocumentTemplateOverview>(), write = vi.spyOn(api, 'answerDocumentRequirements').mockReturnValue(pending.promise), owner = createRunController('document', 'A'), view = await mountRunChild(props => <DocumentTemplatePage {...props} route={{ ...props.route, params: { id: 'A' } }} controller={owner} />)
  try {
    await childClick(view.container, 'selection.select', requirement.title); const textarea = view.container.querySelector<HTMLTextAreaElement>('textarea')!; fireEvent.change(textarea, { target: { value: '原始回答' } }); fireEvent.submit(textarea.closest('form')!); await settleChild()
    const original = write.mock.calls[0]!; await act(async () => { owner.applyRun({ ...waitingRun(), version: 8, requirementRevision: 2, state: 'ANALYZING' }); await flush() })
    expect(view.container.querySelector('textarea')).toBe(textarea); expect(textarea.value).toBe('原始回答'); expect(textarea.disabled).toBe(true); expect(owner.canLeave().kind).toBe('BLOCK')
    pending.reject(new Error('未知回答回执')); await settleChild(); write.mockRejectedValue(new Error('仍未知')); await childClick(view.container.querySelector<HTMLElement>('[aria-label="业务回答恢复"]')!, 'receipt.retryOriginal'); expect(write.mock.calls[1]).toEqual(original); expect(original[1].requirementRevision).toBe(1)
  } finally { await view.unmountRoot() }
})

it('actual StrictMode run root disposes exact SSE handlers and fallback/debounce timers before late natural callbacks', async () => {
  const write = vi.spyOn(api, 'documentTemplateCommand'), owner = createRunController('document', 'A'), view = await mountRunChild(props => <DocumentTemplatePage {...props} route={{ ...props.route, params: { id: 'A' } }} controller={owner} />, { strict: true })
  const events = vi.mocked(api.documentEvents), actual = events.mock.results[0]!.value as unknown as ReturnType<typeof stream>
  expect(events).toHaveBeenCalledTimes(1); expect(actual.addEventListener).toHaveBeenCalledTimes(1)
  const listener = actual.addEventListener.mock.calls[0]![1] as () => void, error = actual.onerror; await act(async () => { listener(); await flush() }); expect(vi.getTimerCount()).toBeGreaterThan(0)
  await view.unmountRoot(); const before = owner.getSnapshot(); expect(actual.close).toHaveBeenCalledTimes(1); expect(actual.removeEventListener).toHaveBeenCalledWith('progress', listener); expect(vi.getTimerCount()).toBe(0)
  listener(); error?.(); await vi.advanceTimersByTimeAsync(20_000); expect(owner.getSnapshot()).toEqual(before); expect(write).not.toHaveBeenCalled(); expect(vi.getTimerCount()).toBe(0)
})
