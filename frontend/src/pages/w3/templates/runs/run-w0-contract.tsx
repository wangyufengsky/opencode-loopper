import { mountApplicationHarness } from '@/test/applicationHarness'
import { navigationHarness } from '@/test/navigationHarness'
import { flushPromises } from '@/test/async'
/** W0 run contracts use the actual production React history and React route module. */
import { act } from '@testing-library/react'
import { expect, vi } from 'vitest'
import { api } from '@/api/client'
import { semanticName } from '@/foundation/semanticRegistry'
import type { DocumentTemplateOverview, SourceTemplateOverview } from '@/types/domain'
import { foundationDOM } from '@/pages/w2/workflow/page.test-support'
import { deferred, documentRun, sourceRun } from './test-support'
import type { RunAction, RunKind } from './runController'

export async function settleRunBridge() { await act(async () => { await vi.dynamicImportSettled(); for (let i = 0; i < 5; i++) await flushPromises() }) }
export function runButton(host: HTMLElement, action: string) {
  const button = [...host.querySelectorAll<HTMLButtonElement>('button')].find(button => button.getAttribute('aria-label') === action)
  if (!button) throw new Error(`真实模板 React 页面未提供操作 ${action}: ${host.textContent}`)
  return button
}
export async function runClick(host: HTMLElement, action: string) { await act(async () => { runButton(host, action).click(); await flushPromises() }); await settleRunBridge() }
export async function mountRunRoute(kind: RunKind, id = 'A') {
  foundationDOM()
  // Incidental child reads remain real transports with explicit empty DTO fixtures.
  if (!vi.isMockFunction(api.documentReports)) vi.spyOn(api, 'documentReports').mockResolvedValue({ items: [], facets: {} })
  if (!vi.isMockFunction(api.documentRequirements)) vi.spyOn(api, 'documentRequirements').mockResolvedValue({ items: [], nextOffset: null, revision: 1 })
  if (!vi.isMockFunction(api.sourceArtifacts)) vi.spyOn(api, 'sourceArtifacts').mockResolvedValue({ items: [], facets: {} })
  if (!vi.isMockFunction(api.sourceBatches)) vi.spyOn(api, 'sourceBatches').mockResolvedValue({ items: [], facets: {} })
  const path = `/template-tasks/${kind}-runs/${id}`
  const root = await mountApplicationHarness({ initialEntries: [path], routes: [{ path: `/template-tasks/${kind}-runs/:id` }, { path: '/exit', element: <p>其他页面</p> }, { path: '/tasks', element: <p>模板历史</p> }] })
  const router = navigationHarness(root)
  await settleRunBridge()
  const host = root.element as HTMLElement
  if (!host.querySelector('[data-react-page="object.templateRun"]')) throw new Error(`生产模板桥未挂载真实 React 页：${host.textContent}`)
  return { router, root, host, path, async unmount() { await act(async () => { root.unmount(); await flushPromises() }) } }
}
export async function templateRunNavigationW0Contract(kind: RunKind, phase: 'inflight' | 'unknown', options: { proof?: (name: string, value: unknown) => void } = {}) {
  const pending = deferred<DocumentTemplateOverview & SourceTemplateOverview>()
  const write = kind === 'source' ? vi.mocked(api.sourceTemplateCommand) : vi.mocked(api.documentTemplateCommand)
  write.mockReturnValue(pending.promise)
  const page = await mountRunRoute(kind)
  try {
    await runClick(page.host, semanticName(kind === 'source' ? 'template.start' : 'template.resume'))
    expect(write).toHaveBeenCalledTimes(1)
    if (phase === 'unknown') { pending.reject(new Error('未知回执')); await settleRunBridge() }
    expect(page.host.querySelector(`[data-operation-phase="${phase === 'inflight' ? 'SENDING' : 'UNKNOWN'}"]`)).toBeTruthy()
    await act(async () => { await page.router.push(`/template-tasks/${kind}-runs/B`); await flushPromises() }); await settleRunBridge()
    expect.soft(page.router.currentRoute.value.path).toBe(page.path)
    await act(async () => { await page.router.push('/exit'); await flushPromises() }); await settleRunBridge()
    expect.soft(page.router.currentRoute.value.path).toBe(page.path)
    options.proof?.(`B3.2/${kind}/${phase}`, { route: page.router.currentRoute.value.path, original: write.mock.calls[0], actualReact: !!page.host.querySelector('[data-react-page]') })
    if (phase === 'inflight') { pending.reject(new Error('未知回执')); await settleRunBridge() }
    expect(write).toHaveBeenCalledTimes(1)
  } finally { await page.unmount() }
}
export async function templateRunIdentityW0Contract(kind: RunKind, action: RunAction, options: { source?: (id?: string, version?: number) => SourceTemplateOverview; document?: (id?: string, version?: number) => DocumentTemplateOverview; proof?: (name: string, value: unknown) => void } = {}) {
  const write = kind === 'source' ? vi.mocked(api.sourceTemplateCommand) : vi.mocked(api.documentTemplateCommand)
  write.mockRejectedValue(new Error('未知回执'))
  const readSource = options.source ?? sourceRun, readDocument = options.document ?? documentRun
  if (kind === 'source') vi.mocked(api.sourceTemplate).mockResolvedValue({ ...readSource(), ...(action === 'retry' ? { templateId: 'DETAILED_DESIGN_WRITING', canResume: true } : {}) })
  else vi.mocked(api.documentTemplate).mockResolvedValue(readDocument())
  const page = await mountRunRoute(kind)
  try {
    if (action === 'retry') { const input = page.host.querySelector<HTMLInputElement>('input[type="checkbox"]'); expect(input).toBeTruthy(); await act(async () => { input!.click(); await flushPromises() }); await settleRunBridge() }
    await runClick(page.host, semanticName(action === 'retry' ? 'template.retryBatches' : action === 'resume' ? 'template.resume' : 'template.cancel'))
    if (action === 'cancel') { const dialog = page.host.querySelector<HTMLElement>('[role="dialog"]'); expect(dialog).toBeTruthy(); await runClick(dialog!, semanticName('template.cancel')) }
    expect(write).toHaveBeenCalledTimes(1); const original = structuredClone(write.mock.calls[0]); expect(original).toBeDefined()
    if (kind === 'source') vi.mocked(api.sourceTemplate).mockResolvedValue({ ...readSource('A', 4), ...(action === 'retry' ? { templateId: 'DETAILED_DESIGN_WRITING', canResume: true } : {}) })
    else vi.mocked(api.documentTemplate).mockResolvedValue(readDocument('A', 4))
    await runClick(page.host, semanticName('ui.refresh'))
    if (action === 'retry') { const input = page.host.querySelectorAll<HTMLInputElement>('input[type="checkbox"]')[1]; expect(input).toBeTruthy(); await act(async () => { input!.click(); await flushPromises() }); await settleRunBridge() }
    await runClick(page.host, semanticName('receipt.retryOriginal'))
    options.proof?.(`B3.3/${kind}/${action}`, { original, retry: write.mock.calls[1] })
    expect(write.mock.calls[1]).toEqual(original)
  } finally { await page.unmount() }
}
export async function templateCancelScopeW0Contract(options: { proof?: (name: string, value: unknown) => void } = {}) {
  vi.mocked(api.documentTemplate).mockImplementation(async id => documentRun(id))
  const write = vi.mocked(api.documentTemplateCommand), page = await mountRunRoute('document')
  try {
    await runClick(page.host, semanticName('template.cancel')); expect(page.host.querySelector('[role="dialog"]')).toBeTruthy(); expect(write).not.toHaveBeenCalled()
    await act(async () => { await page.router.push('/template-tasks/document-runs/B'); await flushPromises() }); await settleRunBridge()
    expect(page.router.currentRoute.value.path).toBe('/template-tasks/document-runs/B'); expect(page.host.textContent).toContain('文档B'); expect(page.host.querySelector('[role="dialog"]')).toBeNull(); expect(page.host.querySelector('[role="alert"]')).toBeNull(); expect(page.host.querySelector('[data-operation-phase]')).toBeNull()
    expect(write).not.toHaveBeenCalled(); options.proof?.('B4.1/stale-modal', { route: page.router.currentRoute.value.path, writes: write.mock.calls, modalRetired: true })
  } finally { await page.unmount() }
}
export async function templateRunReadonlyLeaveW0Contract(kind: RunKind, options: { proof?: (name: string, value: unknown) => void } = {}) {
  const write = vi.mocked(kind === 'source' ? api.sourceTemplateCommand : api.documentTemplateCommand), events = vi.mocked(kind === 'source' ? api.sourceEvents : api.documentEvents), page = await mountRunRoute(kind)
  try {
    expect(events).toHaveBeenCalledTimes(1)
    const stream = events.mock.results[0]!.value as EventSource
    await act(async () => { await page.router.push('/exit'); await flushPromises() }); await settleRunBridge()
    expect(page.router.currentRoute.value.path).toBe('/exit'); expect(stream.close).toHaveBeenCalledTimes(1); expect(write).not.toHaveBeenCalled()
    options.proof?.(`B3.2/${kind}/read-only-leave`, { route: page.router.currentRoute.value.path, closed: vi.mocked(stream.close).mock.calls.length, writes: write.mock.calls.length, actualReact: true })
  } finally { await page.unmount() }
}
