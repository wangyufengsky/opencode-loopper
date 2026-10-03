/** Transport-only contracts. Historical entries render the production React page;
 * initial cases explicitly exercise the retained owner/UI capability, never a new production route. */
import { useState } from 'react'
import { act, cleanup, fireEvent, render, screen, within } from '@testing-library/react'
import { createMemoryHistory, createRouter } from 'vue-router'
import { defineComponent } from 'vue'
import { expect, vi } from 'vitest'
import { api } from '@/api/client'
import { UiConfirmDialog } from '@/foundation/components'
import { foundationDOM } from '@/pages/w2/workflow/page.test-support'
import type { AcceptedHandoff } from '@/foundation/contracts/receipt'
import { navigateAcceptedHandoff } from '@/foundation/contracts/receipt'
import type { DesignerStreamEvent } from '@/types/domain'
import { taskFixture } from '@/pages/w4/task/test-support'
import { DesignerPage } from './DesignerPage'
import { createDesignerController } from './controller'
import { deferred, draft, flush, frame, mockDesigner, pageProps, session } from './test-support'
type Context = { proof?: (caseId: string, evidence: unknown) => void }
export async function designerW0Contract(group: string, variant: string, context: Context = {}) {
  foundationDOM(); sessionStorage.clear()
  const initial = variant.startsWith('initial') || group === 'B7.3' && variant === 'accepted-file-storage'
  const pendingQuestion = { id: 'question-A', questions: [{ question: '选择实现范围', header: '范围', multiple: false, custom: false, options: [{ label: '原答案', description: '原选择' }] }] }
  const value = session('A', group === 'B5.3' ? { state: 'COMPLETED', workflowPhase: 'COMPLETED', discussionScope: 'FINAL', finalConfirmationEligible: true } : group === 'B6.1' ? { pendingQuestions: [pendingQuestion] } : {})
  const mocks = mockDesigner(value), harness = pageProps(), router = createRouter({ history: createMemoryHistory(), routes: ['/designer', '/away', '/tasks/:id'].map(path => ({ path, component: defineComponent({ render: () => null }) })) })
  await router.push(initial ? '/designer' : '/designer?sessionId=A'); await router.isReady()
  let handoff: AcceptedHandoff | undefined, confirmLeave: ((value: boolean) => void) | undefined, setConfirm: (value: boolean) => void = () => {}, mounted = true
  const props = { ...harness.props, route: { path: '/designer', fullPath: router.currentRoute.value.fullPath, query: initial ? {} : { sessionId: 'A' }, params: {} } }
  props.navigation.go = async to => { const failure = await router.push(to); return !failure }
  props.navigation.goAccepted = async (to, permit) => { const destination = typeof to === 'string' ? to : to.path; handoff = permit; try { return await navigateAcceptedHandoff(permit, destination, () => props.navigation.go(to)) } finally { handoff = undefined } }
  let owner!: ReturnType<typeof createDesignerController>
  const focus = group === 'B7.2' ? () => document.querySelector<HTMLTextAreaElement>('#designer-message')?.focus() : undefined
  owner = createDesignerController({ navigation: props.navigation, sessionId: initial ? undefined : 'A', historyOnly: !initial, focusComposer: focus })
  const leave = vi.fn()
  router.beforeEach(to => { const decision = owner.canLeave({ destination: to.fullPath, handoff }); if (decision.kind === 'BLOCK') return false; if (decision.kind === 'CONFIRM_DISCARD') { leave(); setConfirm(true); return new Promise<boolean>(resolve => { confirmLeave = resolve }) } return true })
  function Fixture() { const [open, update] = useState(false); setConfirm = update; return <><DesignerPage {...props} historyOnly={!initial} controller={owner} /><UiConfirmDialog open={open} title="离开设计草稿？" confirmActionKey="ui.discardChanges" onCancel={() => { update(false); confirmLeave?.(false) }} onConfirm={() => { update(false); confirmLeave?.(true) }}><p>离开将丢失当前内存草稿和附件。</p></UiConfirmDialog></> }
  let root = render(frame(<Fixture />))
  router.afterEach((to, _from, failure) => { if (!failure && to.path === '/away' && mounted) { mounted = false; root.unmount(); owner.retire(true); const replacement = document.createElement('textarea'); replacement.id = 'designer-message'; replacement.setAttribute('aria-label', '其它页面输入'); document.body.append(replacement) } })
  const report = (evidence: unknown) => context.proof?.(`${group}/${variant}`, { actualReact: true, historyOnly: !initial, initialProductionReachable: false, ...evidence as object })
  const click = async (label: string) => { fireEvent.click(screen.getByRole('button', { name: label })); await flush() }
  const setMessage = (value: string) => fireEvent.change(screen.getByLabelText(initial ? '草案设计目标' : '设计消息'), { target: { value } })
  const file = new File(['original bytes'], 'original.txt', { type: 'text/plain' })
  const addFile = (value: File) => fireEvent.change(screen.getByLabelText('添加资料'), { target: { files: [value] } })
  try {
    await flush()
    if (group === 'B5.1') {
      if (variant === 'saved-readonly') { await act(async () => { await router.push('/away') }); expect(router.currentRoute.value.path).toBe('/away'); expect(leave).not.toHaveBeenCalled(); expect(api.createDesignerContextTurn).not.toHaveBeenCalled(); return }
      if (variant.endsWith('text')) setMessage('未发送草稿'); else addFile(file)
      let navigation!: Promise<unknown>; await act(async () => { navigation = router.push('/away'); await Promise.resolve() }); await flush()
      expect(leave).toHaveBeenCalledOnce(); await click('留在当前页面'); await navigation; expect(router.currentRoute.value.path).toBe('/designer')
      if (variant.endsWith('text')) expect((screen.getByLabelText(initial ? '草案设计目标' : '设计消息') as HTMLTextAreaElement).value).toBe('未发送草稿'); else expect(screen.getByText(/original.txt/)).toBeTruthy()
      report({ route: router.currentRoute.value.path, confirmCount: leave.mock.calls.length, fileNames: owner.fileRefs() })
    } else if (group === 'B5.2') {
      const pending = deferred<Awaited<ReturnType<typeof api.createDesignerContextTurn>>>()
      if (initial) vi.mocked(api.createDesignerContextTurn).mockReturnValue(pending.promise); else vi.mocked(api.sendDesignerContextTurn).mockImplementation(() => pending.promise as unknown as ReturnType<typeof api.sendDesignerContextTurn>)
      setMessage('原消息'); addFile(file); await click(initial ? '开始设计' : '发送')
      if (variant.endsWith('unknown')) { pending.reject(new Error('lost acknowledgement')); await flush() }
      await router.push('/away'); await flush(); expect(router.currentRoute.value.path).toBe('/designer')
      expect(initial ? api.createDesignerContextTurn : api.sendDesignerContextTurn).toHaveBeenCalledOnce(); expect(owner.canLeave().kind).toBe('BLOCK'); expect(initial ? owner.fileRefs().initial : owner.fileRefs().followup).toEqual([file]); report({ phase: owner.getSnapshot().command.phase, route: router.currentRoute.value.path, onePost: true, sameFile: true })
    } else if (group === 'B5.3') {
      vi.mocked(api.confirmDraft).mockResolvedValue({ taskId: 'accepted-task' }); vi.mocked(api.getDraft).mockResolvedValue({ ...draft(), status: 'CONFIRMED' }); vi.mocked(api.getTaskOverview).mockResolvedValue(taskFixture('accepted-task')); vi.mocked(api.getTaskAudit).mockResolvedValue({ attempts: [], artifacts: [], errors: [], judges: [] })
      router.beforeEach(to => { if (to.path.startsWith('/tasks/')) { if (variant === 'reject') throw new Error('navigation rejected'); return false } return true })
      await click('确认设计'); const dialog = screen.getByRole('dialog', { name: '确认设计并创建任务？' }); fireEvent.click(within(dialog).getByRole('button', { name: '确认设计' })); await flush()
      expect(api.confirmDraft).toHaveBeenCalledOnce(); expect(api.getTaskOverview).toHaveBeenCalledWith('accepted-task'); expect(api.getTaskAudit).toHaveBeenCalledWith('accepted-task'); expect(screen.getByRole('button', { name: '打开已创建任务' })).toBeTruthy(); expect(screen.queryByLabelText('草案设计目标')).toBeNull(); expect(owner.getSnapshot().session?.id).toBe('A'); expect(router.currentRoute.value.path).toBe('/designer'); report({ acceptedTask: owner.getSnapshot().acceptedTask, onePost: true, originalWorkspace: sessionStorage.getItem('opencode-loopper.designer-workspace') })
    } else if (group === 'B6.1') {
      vi.mocked(api.rejectDesignerQuestion).mockResolvedValue()
      if (variant === 'mandatory-no-reject') { expect(screen.queryByRole('button', { name: '拒绝' })).toBeNull(); expect(api.rejectDesignerQuestion).not.toHaveBeenCalled(); report({ rejectReachable: false }); return }
      const pending = deferred<void>(); vi.mocked(api.replyDesignerQuestion).mockReturnValue(pending.promise); await click('采用全部推荐项'); expect(api.replyDesignerQuestion).toHaveBeenCalledWith('A', pendingQuestion.id, [['原答案']]); root.unmount(); owner.retire(true); mounted = false; const independent = session('B'); vi.mocked(api.getDesignerSession).mockResolvedValue(independent); const b = createDesignerController({ navigation: props.navigation, sessionId: 'B', historyOnly: true }); const bRoot = render(frame(<DesignerPage {...props} route={{ ...props.route, query: { sessionId: 'B' } }} controller={b} />)); await flush(); const before = b.getSnapshot(), reads = vi.mocked(api.getDesignerSession).mock.calls.length; pending.resolve(); await flush(); expect(api.getDesignerSession).toHaveBeenCalledTimes(reads); expect(b.getSnapshot()).toEqual(before); expect(b.getSnapshot().session?.id).toBe('B'); report({ lateReads: 0, independentBUnchanged: true, replyInputs: vi.mocked(api.replyDesignerQuestion).mock.calls }); bRoot.unmount(); b.retire(true)
    } else if (group === 'B6.2') {
      const pending = deferred<Awaited<ReturnType<typeof api.previewDesignerTaskProfileUpdate>>>(), response = { updateRequired: true, selectionChanged: true, sessionRestartRequired: true, targetWorkflowTemplate: 'FULL_PACKAGE_DESIGN' as const }
      vi.mocked(api.previewDesignerTaskProfileUpdate).mockImplementation(() => variant === 'preview' ? pending.promise : Promise.resolve(response))
      const previewCall = vi.spyOn(owner, 'previewProfile'); await click('修改任务设置'); const panel = screen.getByRole('complementary'); fireEvent.click(within(panel).getByRole('button', { name: '修改任务设置' })); await flush(); fireEvent.click(screen.getByLabelText('大型任务模式')); fireEvent.click(within(panel).getByRole('button', { name: '保存设计' })); await flush(); expect(api.previewDesignerTaskProfileUpdate).toHaveBeenCalledOnce()
      root.unmount(); owner.retire(true); mounted = false; if (variant === 'preview') pending.resolve(response); await flush(); if (variant === 'modal') { const proposal = await previewCall.mock.results[0]?.value; expect(proposal).toBeDefined(); await owner.applyProfile(proposal!); await flush() } expect(api.updateDesignerTaskProfile).not.toHaveBeenCalled(); report({ preview: vi.mocked(api.previewDesignerTaskProfileUpdate).mock.calls, updateCount: 0, retiredBeforePreviewOrDialogCompletion: true })
    } else if (group === 'B6.3') {
      if (variant === 'unknown-file-later-draft') { vi.mocked(api.sendDesignerContextTurn).mockRejectedValueOnce(new Error('unknown')); setMessage('原消息'); addFile(file); await click('发送'); const original = vi.mocked(api.sendDesignerContextTurn).mock.calls[0]!; expect(screen.getByLabelText('设计消息').matches(':disabled')).toBe(false); const extra = new File(['extra'], 'extra.txt'); setMessage('后来编辑'); addFile(extra); await click('发送'); const retry = vi.mocked(api.sendDesignerContextTurn).mock.calls[1]!; expect(retry[1]).toEqual(original[1]); expect(retry[2]).toEqual([file]); expect(retry[2][0]).toBe(file); expect((screen.getByLabelText('设计消息') as HTMLTextAreaElement).value).toBe('后来编辑'); expect(owner.fileRefs().followup).toEqual([extra]); report({ original: original.slice(0, 2), retry: retry.slice(0, 2), sameOriginalFile: true, laterDraft: owner.getSnapshot().message }) }
      else { const pending = deferred<Awaited<ReturnType<typeof api.sendRequirementMessage>>>(); vi.mocked(api.sendRequirementMessage).mockReturnValue(pending.promise); setMessage('原消息'); await click('发送'); expect(api.sendRequirementMessage).toHaveBeenCalledOnce(); expect(screen.getByLabelText('设计消息').matches(':disabled')).toBe(false); setMessage('后来编辑'); pending.resolve({ sessionId: 'A', state: 'REVIEWING', persistedMessages: [], notice: '' }); await flush(); expect((screen.getByLabelText('设计消息') as HTMLTextAreaElement).value).toBe('后来编辑'); report({ laterDraft: owner.getSnapshot().message, requests: vi.mocked(api.sendRequirementMessage).mock.calls }) }
    } else if (group === 'B7.1') {
      vi.useFakeTimers(); const pending = deferred<ReturnType<typeof session>>(); vi.mocked(api.getDesignerSession).mockReturnValue(pending.promise); void owner.refresh(); await flush()
      const baseSet = globalThis.setTimeout, baseClear = globalThis.clearTimeout, tracked = new Set<unknown>(); let observe = true
      vi.spyOn(globalThis, 'setTimeout').mockImplementation(((callback: TimerHandler, timeout?: number, ...args: unknown[]) => { const timer = baseSet(callback, timeout, ...args); if (observe) tracked.add(timer); return timer }) as typeof setTimeout)
      vi.spyOn(globalThis, 'clearTimeout').mockImplementation(timer => { tracked.delete(timer); baseClear(timer) })
      const stream = mocks.streams.find(stream => stream.id === 'A')!; stream.event({ sessionId: 'A', type: 'COMPLETED' } as DesignerStreamEvent); observe = false
      expect(tracked.size).toBeGreaterThan(0); const before = tracked.size; root.unmount(); owner.retire(true); mounted = false; expect(tracked.size).toBe(0); expect(stream.close).toHaveBeenCalledOnce(); const reads = vi.mocked(api.getDesignerSession).mock.calls.length; report({ before, firstAfter: tracked.size, streamClosed: stream.close.mock.calls.length, noNaturalEventBeforeSample: true }); pending.resolve(value); await flush(); await vi.advanceTimersByTimeAsync(100); expect(api.getDesignerSession).toHaveBeenCalledTimes(reads)
    } else if (group === 'B7.2') {
      root.unmount(); owner.retire(true); mounted = false
      let next = 0; const pending = new Map<number, FrameRequestCallback>(), focusFrames = new Set<number>()
      vi.stubGlobal('requestAnimationFrame', vi.fn((callback: FrameRequestCallback) => { const id = ++next; pending.set(id, callback); if (String(callback).includes('focusComposer')) focusFrames.add(id); return id })); vi.stubGlobal('cancelAnimationFrame', vi.fn(id => { pending.delete(id); focusFrames.delete(id) }))
      owner = createDesignerController({ navigation: props.navigation, sessionId: 'A', historyOnly: true, focusComposer: focus }); mounted = true; root = render(frame(<DesignerPage {...props} route={{ ...props.route, query: { sessionId: 'A', mode: 'edit' } }} controller={owner} />)); await flush()
      expect(focusFrames.size).toBe(1); const frameId = [...focusFrames][0]!, late = pending.get(frameId)!; expect(String(late)).toContain('focusComposer')
      await act(async () => { await router.push('/away') }); await flush(); expect(router.currentRoute.value.path).toBe('/away'); expect(focusFrames.size).toBe(0)
      const replacement = screen.getByLabelText('其它页面输入'); expect(document.activeElement).not.toBe(replacement); late(0); expect(document.activeElement).not.toBe(replacement); report({ before: 1, firstAfter: focusFrames.size, route: router.currentRoute.value.path, actualReactRouteExit: true, lateFocusedNewPage: false })
    } else if (group === 'B7.3') {
      vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => { throw new DOMException('storage denied', 'SecurityError') })
      if (variant === 'accepted-file-storage') { setMessage('原目标'); addFile(file); await click('开始设计'); expect(api.createDesignerContextTurn).toHaveBeenCalledOnce() } else await owner.refresh()
      expect(screen.getByLabelText('设计消息')).toBeTruthy(); expect(owner.getSnapshot().error).toBe(''); report({ recoveredVisible: true, sessionId: owner.getSnapshot().session?.id, optionalStorageFailure: true })
    } else throw new Error(`未知Designer合同：${group}/${variant}`)
  } finally { if (mounted) root.unmount(); owner.retire(true); cleanup(); document.querySelector('textarea[aria-label="其它页面输入"]')?.remove(); document.querySelector('body > textarea#designer-message')?.remove(); vi.useRealTimers(); vi.unstubAllGlobals() }
}
