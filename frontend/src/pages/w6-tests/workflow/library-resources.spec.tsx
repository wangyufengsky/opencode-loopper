import { StrictMode } from 'react'
import { act, fireEvent, render } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { WorkflowLibraryPage } from '@/pages/w2/workflow/WorkflowLibraryPage'
import { createWorkflowLibraryController } from '@/pages/w2/workflow/workflowLibraryController'
import { workflowApi } from '@/api/workflow'
import { foundationDOM, pageFrame, pageProps } from '@/pages/w2/workflow/page.test-support'
import { summary } from '@/components/workflow/workflowTestFixtures'
vi.mock('@/api/workflow', () => ({ workflowApi: { list: vi.fn(), copy: vi.fn(), archive: vi.fn() } }))
beforeEach(() => { foundationDOM(); vi.mocked(workflowApi.list).mockResolvedValue({ items: [summary()] }) })
afterEach(() => vi.restoreAllMocks())

describe('Workflow library local disclosure resources', () => {
  it('removes its exact document pointer listener synchronously on dismissal and every StrictMode root exit', async () => {
    const add = vi.spyOn(document, 'addEventListener'), remove = vi.spyOn(document, 'removeEventListener')
    for (let cycle = 0; cycle < 3; cycle++) {
      const page = pageProps('/workflows'), owner = createWorkflowLibraryController({ goAccepted: async () => true })
      const root = render(<StrictMode>{pageFrame(<WorkflowLibraryPage {...page} controller={owner} />)}</StrictMode>)
      await act(async () => { for (let n = 0; n < 12; n++) await Promise.resolve() })
      const select = root.getByRole('button', { name: '选择：交付流程' })
      const checkpoint = add.mock.calls.length
      select.focus(); fireEvent.click(select)
      expect(root.container.querySelector('aside:not([hidden])')).not.toBeNull()
      const session = add.mock.calls.slice(checkpoint).filter(([type]) => type === 'pointerdown')
      expect(session).toHaveLength(1)
      const callback = session[0]![1]
      fireEvent.pointerDown(document.body)
      expect(root.container.querySelector('aside:not([hidden])')).toBeNull()
      expect(remove.mock.calls.some(([type, listener]) => type === 'pointerdown' && listener === callback)).toBe(true)
      const second = add.mock.calls.length
      select.focus(); fireEvent.click(select)
      expect(root.container.querySelector('aside:not([hidden])')).not.toBeNull()
      const live = add.mock.calls.slice(second).filter(([type]) => type === 'pointerdown')
      expect(live).toHaveLength(1)
      root.unmount() // First synchronous assertion precedes any natural pointer event or timer.
      expect(remove.mock.calls.some(([type, listener]) => type === 'pointerdown' && listener === live[0]![1])).toBe(true)
      owner.retire(true)
    }
  })
  it('leaves the original unknown command and its guard visible when outside dismissal only hides context', async () => {
    vi.mocked(workflowApi.copy).mockRejectedValue(new Error('连接中断'))
    const page = pageProps('/workflows'), owner = createWorkflowLibraryController({ goAccepted: async () => true })
    const root = render(pageFrame(<WorkflowLibraryPage {...page} controller={owner} />))
    await act(async () => { for (let n = 0; n < 12; n++) await Promise.resolve() })
    const select = root.getByRole('button', { name: '选择：交付流程' }); select.focus(); fireEvent.click(select)
    await act(async () => { fireEvent.click(root.getByRole('button', { name: '复制流程：交付流程' })); for (let n = 0; n < 12; n++) await Promise.resolve() })
    const identity = owner.getOperation()!.identity
    expect(owner.getSnapshot().command.phase).toBe('UNKNOWN')
    fireEvent.pointerDown(document.body)
    expect(root.container.querySelector('aside:not([hidden])')).toBeNull()
    expect(root.getByRole('alert').textContent).toContain('结果尚未确认')
    expect(owner.canLeave().kind).toBe('BLOCK')
    expect(owner.getOperation()!.identity).toBe(identity)
    expect(workflowApi.copy).toHaveBeenCalledTimes(1)
    root.unmount(); owner.retire(true)
  })
})
