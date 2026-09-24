import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { createPinia } from 'pinia'
import { createMemoryHistory, createRouter } from 'vue-router'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import AppSidebar from './AppSidebar.vue'

let wrapper: VueWrapper | undefined
async function render(path = '/tasks') {
  const router = createRouter({ history: createMemoryHistory(), routes: [
    { path: '/knowledge/history', component: { template: '<div />' } },
    { path: '/knowledge/:conversationId?', component: { template: '<div />' } },
    { path: '/:pathMatch(.*)*', component: { template: '<div />' } },
  ] })
  await router.push(path)
  wrapper = mount(AppSidebar, { global: { plugins: [createPinia(), router], stubs: { Icon: true } } })
  await flushPromises()
  return router
}
const knowledgeLink = () => wrapper!.findAll('a').find(link => link.text() === '知识库')!
describe('侧栏知识库返回位置', () => {
  beforeEach(() => { sessionStorage.clear() })
  afterEach(() => { wrapper?.unmount(); wrapper = undefined; vi.restoreAllMocks() })

  it('places role management directly in system navigation', async () => {
    const router = await render()
    const link = wrapper!.get('nav[aria-label="系统导航"] a[href="/roles"]')
    expect(link.text()).toBe('角色管理')
    await link.trigger('click'); await flushPromises()
    expect(router.currentRoute.value.fullPath).toBe('/roles')
  })

  it('opens the welcome page when this tab has not visited knowledge', async () => {
    const router = await render()
    await knowledgeLink().trigger('click'); await flushPromises()
    expect(router.currentRoute.value.fullPath).toBe('/knowledge')
  })

  it('returns to the latest conversation after visiting other modules', async () => {
    const router = await render('/knowledge/first')
    await router.push('/knowledge/second?from=history#answer'); await router.push('/tasks'); await flushPromises()
    await knowledgeLink().trigger('click'); await flushPromises()
    expect(router.currentRoute.value.fullPath).toBe('/knowledge/second?from=history#answer')
    expect(knowledgeLink().classes()).toContain('router-link-active')
  })

  it('remembers history filters and respects explicitly opening a new conversation', async () => {
    const router = await render('/knowledge/history?project=p&query=approval&archive=all')
    await router.push('/projects'); await flushPromises()
    await knowledgeLink().trigger('click'); await flushPromises()
    expect(router.currentRoute.value.fullPath).toBe('/knowledge/history?project=p&query=approval&archive=all')
    await router.push('/knowledge'); await router.push('/tasks'); await flushPromises()
    await knowledgeLink().trigger('click'); await flushPromises()
    expect(router.currentRoute.value.fullPath).toBe('/knowledge')
  })

  it('restores the location after remounting on another page without redirecting that page', async () => {
    const router = await render('/knowledge/first')
    await router.push('/tasks'); await flushPromises(); wrapper!.unmount()
    const reloaded = await render('/projects')
    expect(reloaded.currentRoute.value.path).toBe('/projects')
    await knowledgeLink().trigger('click'); await flushPromises()
    expect(reloaded.currentRoute.value.path).toBe('/knowledge/first')
  })

  it('lets an explicit deep link replace a saved location', async () => {
    sessionStorage.setItem('knowledge.lastPath', '/knowledge/old')
    const router = await render('/knowledge/direct')
    await router.push('/tasks'); await flushPromises()
    expect(knowledgeLink().attributes('href')).toBe('/knowledge/direct')
  })

  it.each(['https://example.com', '//example.com', '/tasks', '/knowledge-invalid', '/knowledge/extra/route'])('ignores a saved non-knowledge location: %s', async saved => {
    sessionStorage.setItem('knowledge.lastPath', saved)
    await render()
    expect(knowledgeLink().attributes('href')).toBe('/knowledge')
  })

  it('keeps navigation usable when tab storage is unavailable', async () => {
    vi.spyOn(Storage.prototype, 'getItem').mockImplementation(() => { throw new Error('blocked') })
    vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => { throw new Error('blocked') })
    const router = await render('/knowledge/saved')
    await router.push('/tasks'); await flushPromises()
    await knowledgeLink().trigger('click'); await flushPromises()
    expect(router.currentRoute.value.path).toBe('/knowledge/saved')
  })
})
