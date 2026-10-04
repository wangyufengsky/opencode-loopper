import { mountApplicationHarness } from '@/test/applicationHarness'
import { navigationHarness } from '@/test/navigationHarness'
import { flushPromises } from '@/test/async'
/** Frozen W0 assertions now exercise the real production React page and sole React history. */
import { act, fireEvent } from '@testing-library/react'
import { expect, vi, type Mock } from 'vitest'
import { api } from '@/api/client'
import { foundationDOM } from '@/pages/w2/workflow/page.test-support'
import type { TemplateTaskCatalog } from '@/types/domain'
type Kind = 'report' | 'source' | 'document'
type Mode = 'inflight' | 'unknown' | 'hash' | 'storage' | 'start-inflight' | 'start-unknown'
const deferred = <T,>() => { let resolve!: (value: T) => void, reject!: (cause: unknown) => void; const promise = new Promise<T>((yes, no) => { resolve = yes; reject = no }); return { promise, resolve, reject } }
const mock = (name: keyof typeof api) => vi.spyOn(api, name) as Mock
async function settle() { await act(async () => { await vi.dynamicImportSettled(); for (let i = 0; i < 6; i++) await flushPromises() }) }
async function click(host: HTMLElement, key: string) { const button = host.querySelector<HTMLButtonElement>(`button[data-semantic="${key}"]`); expect(button, `reachable ${key}`).toBeTruthy(); expect(button!.disabled).toBe(false); await act(async () => { button!.click(); await flushPromises() }); await settle() }

export async function creationUiW0Contract(kind: Kind, mode: Mode, options: {
  catalog: (kind: Kind) => TemplateTaskCatalog; file: () => File; proof: (name: string, evidence: unknown) => void
}) {
  foundationDOM()
  const receipt = deferred<unknown>(), hashing = deferred<ArrayBuffer>()
  const write = mock(kind === 'report' ? 'createTemplateTask' : kind === 'source' ? 'createSourceTemplate' : 'createDocumentTemplate')
  const starts = mode.startsWith('start-'), pending = mode === 'inflight' || starts
  if (starts) write.mockResolvedValue({ id: 'task-accepted' })
  else if (pending) write.mockReturnValue(receipt.promise)
  else write.mockRejectedValue(new Error('原操作回执未知'))
  const start = starts ? mock('startTemplateTask').mockReturnValue(receipt.promise) : undefined
  if (starts) mock('getTask').mockResolvedValue({ id: 'task-accepted', status: 'PENDING_START' })
  let denied: { getItem: Mock; setItem: Mock; removeItem: Mock } | undefined
  if (mode === 'storage') {
    denied = { getItem: vi.fn(() => { throw new Error('storage get denied') }), setItem: vi.fn(() => { throw new Error('storage set denied') }), removeItem: vi.fn(() => { throw new Error('storage remove denied') }) }
    vi.stubGlobal('sessionStorage', denied)
  }
  mock('templateCatalog').mockResolvedValue(options.catalog(kind))
  if (kind === 'source') mock('sourcePreview').mockResolvedValue({ sourcePath: 'src/main', manifestSha256: 'sha', targetCount: 1, excludedCount: 0, moduleCount: 1, truncated: false, files: [], testProfile: { manifestSha256: 'sha', modules: [] }, configurationProblem: null })
  const root = await mountApplicationHarness({ initialEntries: ['/template-tasks?projectId=p'], routes: [{ path: '/template-tasks' }, { path: '/exit', element: <p>安全离开目标</p> }, { path: '/tasks/:id', element: <p>原任务检视</p> }] })
  const router = navigationHarness(root)
  try {
    await settle(); const host = root.element as HTMLElement
    expect(host.querySelectorAll('[data-react-page]')).toHaveLength(1)
    expect(host.querySelector('[data-react-page="nav.templateTasks"]')).toBeTruthy()
    if (kind === 'source') { await act(async () => { fireEvent.change(host.querySelector<HTMLInputElement>('[aria-label="源码路径"]')!, { target: { value: 'src/main' } }); await flushPromises() }); await click(host, 'template.checkScope') }
    let originalFile: File | undefined
    if (kind === 'document') {
      originalFile = options.file()
      if (mode === 'hash') Object.defineProperty(originalFile, 'arrayBuffer', { configurable: true, value: () => hashing.promise })
      await act(async () => { fireEvent.change(host.querySelector('input[type="file"]')!, { target: { files: [originalFile] } }); await flushPromises() })
    }
    const submit = async () => { await act(async () => { fireEvent.submit(host.querySelector('form')!); await flushPromises() }); await settle() }
    await submit()
    if (mode === 'hash') {
      expect(write).not.toHaveBeenCalled(); expect(host.textContent).toContain('正在计算原文档哈希')
      expect(host.querySelector<HTMLInputElement>('input[type="file"]')!.disabled).toBe(true)
    } else {
      if (kind === 'document') await vi.waitFor(() => expect(write).toHaveBeenCalledTimes(1))
      expect(write).toHaveBeenCalledTimes(1)
      if (starts) expect(start).toHaveBeenCalledWith('task-accepted')
      if (mode === 'start-unknown') { receipt.reject(new Error('原 start 回执未知')); await settle() }
      const field = host.querySelector<HTMLInputElement>(kind === 'source' ? '[aria-label="源码路径"]' : kind === 'document' ? 'input[type="file"]' : '[aria-label="项目"]')!
      expect(field.disabled).toBe(true)
      if (mode === 'unknown' || mode === 'storage') {
        const original = structuredClone(write.mock.calls[0]![0]); expect(original.requestKey).toBeTruthy()
        // Disabled controls are the reachable UI negative control; the owner contract separately challenges a changed body.
        await submit(); expect(write).toHaveBeenCalledTimes(1)
        await click(host, 'receipt.retryOriginal')
        if (kind === 'document') await vi.waitFor(() => expect(write).toHaveBeenCalledTimes(2))
        expect(write.mock.calls[1]?.[0]).toEqual(original)
        if (kind === 'document') expect(write.mock.calls[1]?.[1][0]).toBe(originalFile)
      }
      if (mode === 'start-unknown') { await submit(); await submit(); expect(write).toHaveBeenCalledTimes(1); expect(start!.mock.calls.every(call => call[0] === 'task-accepted')).toBe(true) }
    }
    await act(async () => { await router.push('/exit'); await flushPromises() }); await settle()
    expect.soft(router.currentRoute.value.path).toBe('/template-tasks')
    options.proof(`W3/${kind}/${mode}`, { route: router.currentRoute.value.fullPath, writes: write.mock.calls.length, original: write.mock.calls[0]?.[0], retry: write.mock.calls[1]?.[0], starts: start?.mock.calls, storageCalls: denied ? { get: denied.getItem.mock.calls.length, set: denied.setItem.mock.calls.length } : undefined, actualReact: true })
    if (mode === 'inflight' || mode === 'start-inflight') { receipt.reject(new Error('最终未知回执')); await settle(); expect(write).toHaveBeenCalledTimes(1) }
    if (mode === 'hash') { hashing.reject(new Error('终止原哈希读取')); await settle() }
  } finally { await act(async () => { root.unmount(); await flushPromises() }) }
}
