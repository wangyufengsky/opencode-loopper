/** W0 retarget helper: real Vue history/guard + production bridge + real React library. */
import { act } from '@testing-library/react'
import { createPinia } from 'pinia'
import { defineComponent, h } from 'vue'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { createMemoryHistory, createRouter, RouterView } from 'vue-router'
import { expect, vi } from 'vitest'
import W2RouteBridge from '@/migration/W2RouteBridge.vue'
import { workflowApi } from '@/api/workflow'
import type { WorkflowReceipt } from '@/types/domain'
import { semanticName } from '@/foundation/semanticRegistry'
import { deferred, foundationDOM } from './page.test-support'

async function settle() { await act(async () => { for (let i = 0; i < 5; i++) await flushPromises() }) }
function button(root: VueWrapper, name: string): HTMLButtonElement {
  const host = root.element as HTMLElement
  const found = [...host.querySelectorAll<HTMLButtonElement>('button')].find(element => element.getAttribute('aria-label') === name)
  if (!found) throw new Error(`W0 React fixture: missing action ${name}`)
  return found
}
async function click(root: VueWrapper, name: string) { await act(async () => { button(root, name).click(); await flushPromises() }); await settle() }
export async function workflowLibraryW0Contract(mode: { action: 'copy' | 'archive'; phase: 'sending' | 'unknown' } | { filters: true }, options: {
  receipt: WorkflowReceipt
  proof?: (name: string, evidence: unknown) => void
}) {
  foundationDOM()
  const router = createRouter({ history: createMemoryHistory(), routes: [
    { path: '/previous', component: { template: '<p>原入口</p>' } }, { path: '/away', component: { template: '<p>其他页面</p>' } },
    { path: '/workflows', component: W2RouteBridge }, { path: '/workflows/:id', component: { template: '<p>已创建的原副本</p>' } },
  ] })
  await router.push('/previous'); await router.push('/workflows'); await router.isReady()
  const copy = vi.mocked(workflowApi.copy), archive = vi.mocked(workflowApi.archive), list = vi.mocked(workflowApi.list)
  const pending = deferred<WorkflowReceipt>()
  if ('filters' in mode) copy.mockRejectedValueOnce(new Error('lost')).mockResolvedValue(options.receipt)
  else vi.mocked(workflowApi[mode.action]).mockReturnValue(pending.promise)
  let root!: VueWrapper
  await act(async () => { root = mount(defineComponent({ setup: () => () => h(RouterView) }), { attachTo: document.body, global: { plugins: [createPinia(), router] } }); await flushPromises() })
  const host = root.element as HTMLElement
  try {
    // The production bridge imports its actual route module asynchronously. Resolve
    // that boundary before observing API calls; flushing promises alone does not
    // wait for Vite's module import, and would report a fixture failure as a guard bug.
    await act(async () => { await vi.dynamicImportSettled(); await flushPromises() })
    await settle()
    const page = host.querySelector('[data-react-page="object.workflow"]')
    if (!page) throw new Error(`W0 React fixture: production bridge did not mount the workflow page: ${host.textContent}`)
    const record = list.mock.results[0]
    if (!record || record.type !== 'return') throw new Error('W0 React fixture: missing original list transport')
    const row = (await record.value).items[0]
    if (!row) throw new Error('W0 React fixture: empty workflow list')
    await click(root, semanticName('selection.select', row.title))
    const action = 'filters' in mode ? 'copy' : mode.action
    await click(root, semanticName(action === 'copy' ? 'workflow.copyDefinition' : 'workflow.deleteDefinition', row.title))
    if (action === 'archive') {
      const dialog = host.querySelector('[role="dialog"]')
      expect(dialog).toBeTruthy()
      const confirm = [...dialog!.querySelectorAll<HTMLButtonElement>('button')].find(element => element.getAttribute('aria-label') === semanticName('workflow.deleteDefinition', row.title))
      expect(confirm).toBeTruthy()
      await act(async () => { confirm!.click(); await flushPromises() }); await settle()
    }
    const transport = action === 'copy' ? copy : archive
    expect(transport).toHaveBeenCalledTimes(1)
    const original = transport.mock.calls[0]
    expect(original).toBeDefined()
    if ('filters' in mode) {
      expect(host.querySelector('[data-operation-phase="UNKNOWN"]')).toBeTruthy()
      // Ant's real controlled filter is changed through its actual combobox option.
      const combobox = host.querySelector<HTMLInputElement>('input[aria-label="流程来源"]')
      expect(combobox).toBeTruthy()
      await act(async () => { combobox!.dispatchEvent(new MouseEvent('mousedown', { bubbles: true })); await flushPromises() })
      await settle()
      const option = [...host.querySelectorAll<HTMLElement>('.ant-select-item-option')].find(element => element.textContent === '我的流程')
        ?? [...document.querySelectorAll<HTMLElement>('.ant-select-item-option')].find(element => element.textContent === '我的流程')
      expect(option).toBeTruthy()
      await act(async () => { option!.click(); await flushPromises() }); await settle()
      expect(list).toHaveBeenLastCalledWith('', 'CUSTOM', '')
      await click(root, semanticName('ui.retry', '同一身份的原写入'))
      options.proof?.('B1.2', { original, retry: copy.mock.calls[1], requests: list.mock.calls, route: router.currentRoute.value.fullPath })
      expect(copy.mock.calls[1]).toEqual(original)
    } else {
      if (mode.phase === 'unknown') { pending.reject(new Error('lost acknowledgement')); await settle() }
      expect(host.querySelector(`[data-operation-phase="${mode.phase === 'sending' ? 'SENDING' : 'UNKNOWN'}"]`)).toBeTruthy()
      await act(async () => { await router.push('/away'); await flushPromises() }); await settle()
      options.proof?.('B1.2', { ...mode, route: router.currentRoute.value.path, request: original })
      expect(router.currentRoute.value.path).toBe('/workflows')
      expect(transport).toHaveBeenCalledTimes(1)
      expect(host.querySelector('[data-react-page="object.workflow"]')).toBeTruthy()
    }
  } finally { await act(async () => { root.unmount(); await flushPromises() }) }
}
