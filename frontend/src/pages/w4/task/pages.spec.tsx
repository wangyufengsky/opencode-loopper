import { StrictMode, type ReactNode } from 'react'
import { act, cleanup, fireEvent, render, within } from '@testing-library/react'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { api } from '@/api/client'
import { FoundationProvider } from '@/foundation/provider'
import { skins } from '@/themes/registry'
import { semanticName } from '@/foundation/semanticRegistry'
import type { Task, Artifact } from '@/types/domain'
import { TaskDetailPage } from './TaskDetailPage'
import { SessionMonitorPanel } from './SessionMonitorPanel'
import { TemplateReportsPanel, SnapshotReviewPartialReportPanel } from './TaskEvidencePanels'
import { createTaskDetailController } from './taskController'
import { createTaskEvidenceController } from './evidenceController'
import { createSessionMonitorController } from './sessionController'
import { activityFixture, deferred, flush, foundationDOM, mockReads, pageProps, sessionFixture, taskFixture } from './test-support'
import type { TaskPanelProps } from '../shared/types'

// Other team-owned writers have their own independent suites; this suite exercises A's real page/views/controllers.
vi.mock('../actions', () => ({ TaskJudgeApprovalPanel: () => null, TaskDecisionPanel: () => null, DirtyWorkspaceDialog: () => null, GitDiffScopeApprovalDialog: () => null, RollingPackageWorkbench: () => null }))
vi.mock('../publication', () => ({ TaskPublicationActions: () => null }))
const native = vi.hoisted(() => ({ subscribe: vi.fn() }))
vi.mock('@/api/client', async original => ({ ...await original<typeof import('@/api/client')>(), subscribeTaskEvents: native.subscribe }))
const disposers: Array<() => void> = []
beforeEach(() => { vi.useFakeTimers(); foundationDOM(); mockReads(); native.subscribe.mockReset().mockImplementation(() => ({ close: vi.fn() })) })
afterEach(() => { cleanup(); for (const dispose of disposers.splice(0)) dispose(); vi.clearAllTimers(); vi.useRealTimers(); vi.restoreAllMocks(); vi.unstubAllGlobals() })
async function settle() { await act(async () => { await flush(); await flush() }) }
function props(task = taskFixture()): TaskPanelProps {
  const page = pageProps(`/tasks/${task.id}`), retained = new Map<object, () => void>(); page.route = { ...page.route, params: { id: task.id } }; page.lifecycle.retain = (key, dispose) => { retained.set(key, dispose) }
  disposers.push(() => { for (const dispose of retained.values()) dispose() })
  return { task, page, parent: { registerChild: () => () => {}, canStartWrite: () => true, refresh: async () => undefined } }
}
function frame(node: ReactNode, skin = skins[0]!) { return <FoundationProvider skin={skin} reducedMotion>{node}</FoundationProvider> }
async function mountTask(task = taskFixture(), strict = false) {
  vi.mocked(api.getTaskOverview).mockResolvedValue(task); const panel = props(task), owner = createTaskDetailController(task.id, panel.page.navigation)
  const node = frame(<TaskDetailPage {...panel.page} controller={owner} />), view = render(strict ? <StrictMode>{node}</StrictMode> : node); await settle(); return { view, owner, panel }
}

it('renders a real React Task page with discoverable selection details and only PENDING_START Start', async () => {
  const { view, owner, panel } = await mountTask(), start = vi.spyOn(api, 'startTask').mockResolvedValue(taskFixture('A', { status: 'READY', version: 4 }))
  expect(view.container.querySelector('[data-react-page="object.task"]')).toBeTruthy(); expect(view.getByText('尚未分配')).toBeTruthy()
  expect(view.getByRole('combobox', { name: semanticName('settings.changeSkin') })).toBeTruthy()
  expect(view.getByRole('button', { name: semanticName('task.start') })).toBeTruthy(); fireEvent.click(view.getByRole('button', { name: semanticName('selection.select', '结果摘要') })); await settle()
  expect(view.getByRole('complementary').hidden).toBe(false); expect(view.getByText('文件变更')).toBeTruthy()
  fireEvent.keyDown(view.getByRole('complementary'), { key: 'Escape' }); await settle(); expect(view.container.querySelector<HTMLElement>('[data-foundation-component="context"]')!.hidden).toBe(true)
  vi.mocked(api.getTaskOverview).mockResolvedValue(taskFixture('A', { status: 'READY', version: 4 })); await act(async () => { await owner.overview() }); expect(view.queryByRole('button', { name: semanticName('task.start') })).toBeNull(); expect(start).not.toHaveBeenCalled()
  expect(panel.page.legacy.task.getSnapshot().tasks).toEqual([])
})
it('close/deselect keeps Session draft and critical status visible, with Start blocked and original answer intact', async () => {
  const question = { id: 'q', questions: [{ header: '范围', question: '如何继续？', options: [{ label: '保持范围', description: '不扩大' }], multiple: false, custom: true }] }
  vi.mocked(api.getTaskSessionActivity).mockResolvedValue(activityFixture(undefined, { pendingQuestions: [question] })); const { view, owner } = await mountTask()
  fireEvent.click(view.getByRole('button', { name: semanticName('selection.select', '任务会话') })); await settle()
  const input = view.getByRole('radio'); fireEvent.click(input); await settle(); expect(owner.canLeave().kind).toBe('CONFIRM_DISCARD')
  const context = view.getByRole('complementary'); fireEvent.keyDown(context, { key: 'Escape' }); await settle()
  expect(view.getByText('回答草稿已保留。')).toBeTruthy(); const start = view.getByRole('button', { name: semanticName('task.start') }); expect(start).toBeInstanceOf(HTMLButtonElement); expect((start as HTMLButtonElement).disabled).toBe(true)
  fireEvent.click(view.getByRole('button', { name: semanticName('selection.select', '任务会话') })); await settle(); expect((view.getByRole('radio') as HTMLInputElement).checked).toBe(true)
})
it('real session form submits its own dirty input through parent sibling gate and preserves unknown four-level context', async () => {
  const task = taskFixture(), parent = createTaskDetailController('A'), page = props(task).page, owner = createSessionMonitorController('A', parent.parent), pending = deferred<void>()
  disposers.push(() => parent.retire(true)); vi.spyOn(api, 'replyTaskSessionQuestion').mockReturnValue(pending.promise)
  const row = { id: 'q', questions: [{ header: '范围', question: '如何继续？', options: [{ label: '保持范围', description: '不扩大' }], multiple: false, custom: true }] }
  vi.mocked(api.getTaskSessionActivity).mockResolvedValue(activityFixture(undefined, { pendingQuestions: [row] }))
  const view = render(frame(<SessionMonitorPanel task={task} page={page} parent={parent.parent} controller={owner} />)); await settle(); fireEvent.click(view.getByRole('radio')); await settle()
  const button = view.getByRole('button', { name: semanticName('inbox.answer') }); expect(button).toBeInstanceOf(HTMLButtonElement); expect((button as HTMLButtonElement).disabled).toBe(false); fireEvent.click(button); await settle()
  const answer = view.getByRole('textbox', { name: '范围补充回答' }); expect(answer).toBeInstanceOf(HTMLTextAreaElement)
  // A disabled fieldset makes the real input effectively disabled without changing its own disabled property.
  expect(api.replyTaskSessionQuestion).toHaveBeenCalledTimes(1); expect(answer.matches(':disabled')).toBe(true); expect(parent.canLeave().kind).toBe('BLOCK')
  pending.reject(new Error('未知回执')); await settle(); expect(view.container.querySelector('[data-operation-phase="UNKNOWN"]')).toBeTruthy()
  vi.mocked(api.getTaskSessions).mockResolvedValue([]); await act(async () => { await owner.load() })
  expect(view.getByRole('textbox', { name: '范围补充回答' }).matches(':disabled')).toBe(true); expect(view.getByRole('radio')).toBeTruthy()
  fireEvent.click(view.getByRole('button', { name: semanticName('receipt.readOriginal') })); await settle(); expect(api.replyTaskSessionQuestion).toHaveBeenCalledTimes(1)
})
it('true root StrictMode releases each Session ResizeObserver, retained owners, stream and timers before late observer callback', async () => {
  const observations: { observer: ResizeObserver; callback: ResizeObserverCallback; disconnected: boolean }[] = [], Original = ResizeObserver
  vi.stubGlobal('ResizeObserver', class extends Original { constructor(callback: ResizeObserverCallback) { super(callback); observations.push({ observer: this, callback, disconnected: false }) } disconnect() { const record = observations.find(row => row.observer === this)!; record.disconnected = true; super.disconnect() } })
  vi.mocked(api.getTaskSessionActivity).mockResolvedValue(activityFixture(undefined, { parts: [{ id: 'part', type: 'OUTPUT', label: 'output', content: '真实输出' }] }))
  const { view } = await mountTask(taskFixture(), true); expect(observations.length).toBeGreaterThan(1); view.unmount(); for (const dispose of disposers.splice(0)) dispose()
  expect(observations.every(row => row.disconnected)).toBe(true); expect(vi.getTimerCount()).toBe(0)
  for (const row of observations) row.callback([], row.observer); expect(view.container.textContent).toBe('')
})
it('three skin changes preserve same pending command and do not automatically rePOST', async () => {
  const { view, owner, panel } = await mountTask(), pending = deferred<Task>(); vi.spyOn(api, 'startTask').mockReturnValue(pending.promise)
  fireEvent.click(view.getByRole('button', { name: semanticName('task.start') })); await settle(); const identity = owner.operation()!.identity
  for (const skin of skins) { view.rerender(frame(<TaskDetailPage {...panel.page} skin={skin} controller={owner} />, skin)); await settle(); expect(owner.operation()!.identity).toBe(identity); expect(api.startTask).toHaveBeenCalledTimes(1) }
  pending.reject(new Error('原回执未知')); await settle(); expect(owner.canLeave().kind).toBe('BLOCK')
})
it('model output, bounded Todo dock and live questions use normal flow without overlaying answer inputs', async () => {
  const panel = props(taskFixture()), owner = createSessionMonitorController('A'), rows = Array.from({ length: 25 }, (_, i) => ({ id: `todo-${i}`, content: `实施项 ${i}`, status: i === 0 ? 'IN_PROGRESS' as const : 'PENDING' as const, ordinal: i }))
  vi.mocked(api.getTaskSessionActivity).mockResolvedValue(activityFixture(undefined, { todos: rows, parts: [{ id: 'thinking', type: 'THINKING', label: 'think', content: '分析中' }, { id: 'output', type: 'OUTPUT', label: 'output', content: '真实动态输出' }] }))
  const view = render(frame(<SessionMonitorPanel {...panel} controller={owner} />)); await settle(); const todo = view.getByRole('region', { name: '实施计划' }), output = view.getByRole('region', { name: '任务会话' }).querySelector('.w4-output-scroll')!
  expect(todo.parentElement!.contains(output)).toBe(true); expect(output.contains(todo)).toBe(false); expect(view.getByText('真实动态输出')).toBeTruthy(); fireEvent.click(view.getByRole('button', { name: semanticName('ui.expand', '其余实施项') })); await settle(); expect(view.container.querySelector('.w4-todo-list')!.children).toHaveLength(24)
  vi.mocked(api.getTaskSessionActivity).mockResolvedValue(activityFixture(undefined, { todos: rows, pendingQuestions: [{ id: 'q', questions: [{ header: '继续', question: '请回答', options: [], custom: true, multiple: false }] }] })); await act(async () => { await owner.load() })
  expect(output.contains(view.getByRole('region', { name: '实施计划' }))).toBe(true); expect(view.getByRole('textbox', { name: '继续补充回答' })).toBeTruthy()
})
it('report selection is lazy, reports show deterministic acceptance and linked report stays inside same exact bundle', async () => {
  const reports: Artifact[] = [{ id: 'summary', taskId: 'A', kind: 'REPORT', title: '周报/总结.md', createdAt: 'now', content: '', metadata: { bundleId: 'b', reportRole: 'SUMMARY', repairRound: 1 } }, { id: 'detail', taskId: 'A', kind: 'REPORT', title: '周报/人员.md', createdAt: 'now', content: '', metadata: { bundleId: 'b', repairRound: 1 } }]
  const task = taskFixture('A', { status: 'COMPLETED', artifacts: reports, executionMode: 'TEMPLATE_REPORT', templateProgress: { dualReviewRequired: false, reviewBatches: 0, contributorBatches: 0, completedReviews: 0, completedContributors: 0, activeBatches: 0, failedBatches: 0, repairRound: 0, documentPath: null } }), owner = createTaskEvidenceController('A'), read = vi.spyOn(api, 'getArtifactContent').mockImplementation(async (_task, id) => ({ id, kind: 'REPORT', content: id === 'summary' ? '[详细报告](%E4%BA%BA%E5%91%98.md)' : '# 人员报告', metadata: {} }))
  const view = render(frame(<TemplateReportsPanel {...props(task)} owner={owner} />)); await settle(); expect(read).not.toHaveBeenCalled(); fireEvent.change(view.getByRole('combobox'), { target: { value: 'summary' } }); await settle()
  expect(view.getByText('已校验并保存')).toBeTruthy(); expect(view.queryByText('已通过评审')).toBeNull(); fireEvent.click(view.getByRole('link', { name: '详细报告' })); await settle(); expect(read).toHaveBeenLastCalledWith('A', 'detail'); expect(view.getByText('人员报告')).toBeTruthy()
})
it('partial report is on demand, preserves original count boundaries and rejects late success after actual root loss', async () => {
  const owner = createTaskEvidenceController('A'), panel = props(), late = deferred<Awaited<ReturnType<typeof api.snapshotReviewPartialReport>>>(), read = vi.spyOn(api, 'snapshotReviewPartialReport').mockReturnValue(late.promise)
  const view = render(frame(<SnapshotReviewPartialReportPanel page={panel.page} owner={owner} />)); await settle(); expect(read).not.toHaveBeenCalled(); fireEvent.click(view.getByRole('button', { name: semanticName('ui.open', '阶段报告') })); await settle(); view.unmount(); for (const dispose of disposers.splice(0)) dispose(); const before = owner.getSnapshot()
  late.resolve({ content: '# 迟到报告', sha256: 'a', capturedAt: 'now', analyzedUnits: 1, pendingUnits: 2, excludedUnits: 3 }); await settle(); expect(owner.getSnapshot()).toBe(before)
})
it('frozen role permission details stay lazy and show technical identifiers only inside disclosure', async () => {
  const role = vi.spyOn(api, 'getTaskSessionRole').mockResolvedValue({ configured: true, roleId: 'role-original', revisionId: 'revision-original', revisionSha256: 'a'.repeat(64), permissionSha256: 'b'.repeat(64), slot: 'IMPLEMENTATION', adapterProfile: 'local', adapterVersion: '1', permissions: [{ permission: 'edit', pattern: 'src/**', action: 'allow' }] })
  const view = render(frame(<SessionMonitorPanel {...props()} />)); await settle(); expect(role).not.toHaveBeenCalled(); fireEvent.click(view.getByRole('button', { name: semanticName('ui.expand', '冻结角色权限') })); await settle(); expect(role).toHaveBeenCalledWith('A', sessionFixture().key)
  expect(view.getByText('编辑文件 · src/** · 允许')).toBeTruthy(); expect(view.container.querySelector('.w4-role-summary details pre')!.textContent).toContain('"permission": "edit"'); expect(within(view.getByRole('region', { name: '任务会话' })).getByText('技术标识')).toBeTruthy(); expect(view.container.querySelector<HTMLDetailsElement>('.w4-role-summary details')!.open).toBe(false)
})
