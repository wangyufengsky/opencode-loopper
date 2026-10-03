import { act, cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { StrictMode } from 'react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { api, ApiError } from '@/api/client'
import { workflowApi } from '@/api/workflow'
import { workflowRuns } from '@/api/workflowRuns'
import type { DesignerHistoryItem, WorkflowReceipt, WorkflowTemplateSummary } from '@/types/domain'
import { semanticName } from '@/foundation/semanticRegistry'
import { createDesignerHistoryController } from './designerHistoryController'
import { createWorkflowLibraryController } from './workflowLibraryController'
import { createRequirementListController } from './requirementListController'
import { WorkflowLibraryPage } from './WorkflowLibraryPage'
import { RequirementListPage } from './RequirementListPage'
import { DesignerHistoryPage } from './DesignerHistoryPage'
import { designerHistoryW0Contract } from './designer-history-w0-contract'
import { flush, foundationDOM, pageFrame, pageProps } from './page.test-support'

const row: WorkflowTemplateSummary = { id: 'template-1', title: '评审流程', description: '人工评审', builtin: false, headRevision: 2, version: 3, createdAt: '', updatedAt: '' }
const receipt: WorkflowReceipt = { id: 'copied', revision: 1, version: 1, layoutVersion: 1, state: 'DRAFT' }
function design(id: string, extra: Partial<DesignerHistoryItem> = {}): DesignerHistoryItem { return { id, projectId: 'p', projectName: 'Alpha', state: 'WAITING_INPUT', workflowPhase: 'FAILED', createdAt: '', updatedAt: '', draftId: 'draft', draftStatus: 'DRAFT_READY', goal: id, archived: false, resumable: true, stopRetryAvailable: false, ...extra } }
function historyPage(id: string) { return { items: [design(id)], facets: { ARCHIVED_TOTAL: id === 'B' ? 7 : 2 }, nextCursor: `${id}-cursor` } }
beforeEach(foundationDOM)
afterEach(() => { cleanup(); vi.restoreAllMocks(); vi.unstubAllGlobals(); vi.useRealTimers() })

describe('W2 actual React DesignerHistory preserves W0 B8.1', () => {
  for (const mode of ['query', 'cursor', 'retired-success', 'retired-error'] as const) it(`B8.1 ${mode}`, async () => {
    vi.useFakeTimers(); vi.spyOn(api, 'listDesignerHistoryPage')
    await designerHistoryW0Contract(mode, { read: api.listDesignerHistoryPage, page: historyPage })
  })
  it('retains deep-link filters, server facets and permission-specific actions', async () => {
    const read = vi.spyOn(api, 'listDesignerHistoryPage').mockResolvedValue({ items: [design('resume'), design('confirmed', { taskId: 'task', draftStatus: 'CONFIRMED' }), design('cancelled', { state: 'CANCELLED' }), design('stopping', { state: 'STOPPING', stopRetryAvailable: true })], facets: { RESUMABLE_TOTAL: 1, CONFIRMED_TOTAL: 1 }, nextCursor: '' })
    vi.spyOn(api, 'getProjects').mockResolvedValue([])
    const props = pageProps('/designs', { projectId: 'p', status: 'SESSION_ERROR', archive: 'all', order: 'oldest', q: 'design' })
    const owner = createDesignerHistoryController({ query: props.route.query }), root = render(pageFrame(<DesignerHistoryPage {...props} controller={owner} />))
    await waitFor(() => expect(read).toHaveBeenCalledWith({ projectId: 'p', status: 'FAILED', archive: 'ALL', order: 'oldest', q: 'design' }))
    fireEvent.click(screen.getByRole('button', { name: '选择：resume' })); fireEvent.click(screen.getByRole('button', { name: semanticName('designer.continue') }))
    expect(props.navigation.go).toHaveBeenCalledWith({ path: '/designer', query: { sessionId: 'resume', projectId: 'p' } })
    fireEvent.click(screen.getByRole('button', { name: '选择：cancelled' })); expect(screen.getByText('只读记录')).toBeTruthy(); expect(screen.queryByRole('button', { name: semanticName('designer.archive') })).toBeNull()
    fireEvent.click(screen.getByRole('button', { name: '选择：stopping' })); expect(screen.getByRole('button', { name: semanticName('designer.retryStop', 'stopping') })).toBeTruthy(); expect(screen.queryByRole('button', { name: semanticName('designer.continue') })).toBeNull()
    root.unmount(); owner.retire(true)
  })
  it('does not repeat a no-key archive after unknown result; explicitly reads same session', async () => {
    const archive = vi.spyOn(api, 'archiveDesignerSession').mockRejectedValue(new Error('network'))
    vi.spyOn(api, 'getProjects').mockResolvedValue([]); vi.spyOn(api, 'listDesignerHistoryPage').mockResolvedValue(historyPage('A'))
    const get = vi.spyOn(api, 'getDesignerSession').mockResolvedValue({ id: 'A', archived: true } as Awaited<ReturnType<typeof api.getDesignerSession>>)
    const owner = createDesignerHistoryController(), release = owner.attachView(); await flush(); await owner.act(design('A'), 'archive')
    expect(owner.canLeave().kind).toBe('BLOCK'); expect(owner.getSnapshot().command.recovery).toBe('READ_ORIGINAL')
    await owner.recover(); expect(archive).toHaveBeenCalledTimes(1); expect(get).toHaveBeenCalledWith('A'); expect(owner.canLeave().kind).toBe('ALLOW')
    release(); owner.retire(true)
  })
})

describe('W2 workflow and requirement production pages', () => {
  it('loads cursor pages and replaces the project filter, keeping selected-only detail', async () => {
    const list = vi.spyOn(workflowRuns, 'list').mockResolvedValueOnce({ items: [{ id: 'r1', projectId: 'p', title: '第一需求', state: 'COMPLETED', headRevision: 2, version: 1, createdAt: '', updatedAt: '' }], nextCursor: 'next' }).mockResolvedValue({ items: [], nextCursor: '' })
    vi.spyOn(workflowRuns, 'projects').mockResolvedValue({ items: [], facets: {} })
    const owner = createRequirementListController(), props = pageProps('/requirements'), root = render(pageFrame(<RequirementListPage {...props} controller={owner} />))
    await screen.findByRole('button', { name: '选择：第一需求' }); expect(screen.queryByRole('complementary')).toBeNull(); expect(screen.getByText('已完成')).toBeTruthy()
    fireEvent.click(screen.getByRole('button', { name: '选择：第一需求' })); expect(screen.getByRole('link', { name: '查看：需求画布：第一需求' }).getAttribute('href')).toBe('/requirements/r1')
    await act(async () => { await owner.load(true) }); expect(list).toHaveBeenLastCalledWith(undefined, 'next')
    await act(async () => { owner.setProject({ id: 'selected-project', name: 'Project', createdAt: '' }); await flush() }); expect(list).toHaveBeenLastCalledWith('selected-project', '')
    fireEvent.click(screen.getByRole('button', { name: semanticName('workflow.newRequirement') })); expect(props.navigation.go).toHaveBeenCalledWith({ path: '/requirements/new', query: { projectId: 'selected-project' } })
    root.unmount(); owner.retire(true)
  })
  it('builtins expose copy/use/read but no delete, and Escape returns selection focus', async () => {
    vi.spyOn(workflowApi, 'list').mockResolvedValue({ items: [{ ...row, builtin: true }], nextCursor: '' })
    const owner = createWorkflowLibraryController({ goAccepted: async () => false }), root = render(pageFrame(<WorkflowLibraryPage {...pageProps('/workflows')} controller={owner} />))
    const select = await screen.findByRole('button', { name: '选择：评审流程' }); select.focus(); fireEvent.click(select)
    expect(screen.getByRole('button', { name: semanticName('workflow.copyDefinition', row.title) })).toBeTruthy(); expect(screen.queryByRole('button', { name: semanticName('workflow.deleteDefinition', row.title) })).toBeNull()
    fireEvent.keyDown(screen.getByRole('complementary'), { key: 'Escape' }); expect(screen.queryByRole('complementary')).toBeNull(); expect(document.activeElement).toBe(select)
    root.unmount(); owner.retire(true)
  })
  it('retains identical copy body/key across filter changes and does not rewrite accepted nav failure', async () => {
    vi.spyOn(workflowApi, 'list').mockResolvedValue({ items: [row] })
    const copy = vi.spyOn(workflowApi, 'copy').mockRejectedValueOnce(new Error('unknown')).mockResolvedValue(receipt), navigate = vi.fn(async () => false)
    const owner = createWorkflowLibraryController({ goAccepted: navigate, key: () => 'original-key' }), release = owner.attachView(); await flush()
    await owner.act(row, 'copy'); expect(owner.canLeave().kind).toBe('BLOCK'); owner.query('changed'); owner.kind('BUILTIN'); await flush(); await owner.recover()
    expect(copy.mock.calls[1]).toEqual(copy.mock.calls[0]); expect(copy.mock.calls[1]?.[1]).toEqual({ requestKey: 'original-key', sourceRevision: 2, title: '评审流程 副本' })
    expect(owner.getSnapshot().command.phase).toBe('ACCEPTED_READBACK'); expect(owner.getSnapshot().resultId).toBe('copied'); await owner.recover(); expect(copy).toHaveBeenCalledTimes(2); expect(navigate).toHaveBeenCalledTimes(2)
    release(); owner.retire(true)
  })
  it('confirmed delete uses exact version once; accepted read failure recovers with no DELETE', async () => {
    const list = vi.spyOn(workflowApi, 'list').mockResolvedValueOnce({ items: [row] }).mockRejectedValueOnce(new Error('read')).mockResolvedValue({ items: [] })
    const archive = vi.spyOn(workflowApi, 'archive').mockResolvedValue(receipt), owner = createWorkflowLibraryController({ goAccepted: async () => true, key: () => 'key' })
    const release = owner.attachView(); await flush(); await owner.act(row, 'archive'); expect(archive).toHaveBeenCalledWith(row.id, { requestKey: 'key', expectedVersion: 3 })
    expect(owner.canLeave().kind).toBe('BLOCK'); await owner.recover(); expect(archive).toHaveBeenCalledTimes(1); expect(list).toHaveBeenCalledTimes(3); expect(owner.canLeave().kind).toBe('ALLOW'); release(); owner.retire(true)
  })
  it('known version rejection keeps the visible error but allows a new explicit operation', async () => {
    vi.spyOn(workflowApi, 'list').mockResolvedValue({ items: [row] }); vi.spyOn(workflowApi, 'archive').mockRejectedValue(new ApiError('版本已变化', 409))
    const owner = createWorkflowLibraryController({ goAccepted: async () => true }), release = owner.attachView(); await flush(); await owner.act(row, 'archive')
    expect(owner.getSnapshot().command.phase).toBe('SETTLED'); expect(owner.getSnapshot().command.error).toContain('版本'); expect(owner.canLeave().kind).toBe('ALLOW'); release(); owner.retire(true)
  })
  it('StrictMode starts no mutation and detaches leases without retiring retained owner', async () => {
    const list = vi.spyOn(workflowApi, 'list').mockResolvedValue({ items: [] }), copy = vi.spyOn(workflowApi, 'copy')
    const owner = createWorkflowLibraryController({ goAccepted: async () => true }), root = render(pageFrame(<StrictMode><WorkflowLibraryPage {...pageProps('/workflows')} controller={owner} /></StrictMode>))
    await act(flush); expect(list).toHaveBeenCalledTimes(1); expect(copy).not.toHaveBeenCalled(); expect(owner.viewCount()).toBe(1)
    root.unmount(); expect(owner.viewCount()).toBe(0); expect(owner.project({ ...owner.getSnapshot(), query: 'retained' })).toBe(true); owner.retire(true)
  })
})
