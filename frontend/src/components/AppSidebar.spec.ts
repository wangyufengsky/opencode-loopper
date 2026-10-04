import { mountApplicationHarness } from '@/test/applicationHarness'
import { navigationHarness } from '@/test/navigationHarness'
import { flushPromises } from '@/test/async'
import { ReactDOMQuery } from '@/pages/w6-tests/workflow/react-test-root'
import { createElement } from 'react'
import { applicationRoutes } from '@/router'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
let wrapper: (ReactDOMQuery & {unmount():void}) | undefined
async function render(path = '/tasks') {
  const page = await mountApplicationHarness({initialEntries:[path],shell:true,routes:applicationRoutes.map(path=>({path,Component:()=>null}))})
  wrapper=Object.assign(new ReactDOMQuery(page.element),{unmount:page.unmount})
  await flushPromises();return navigationHarness(page)
}
const knowledgeLink = () => wrapper!.findAll('a').find(link => link.text() === '知识库')!
describe('侧栏知识库返回位置', () => {
  beforeEach(() => { sessionStorage.clear() })
  afterEach(() => { wrapper?.unmount(); wrapper = undefined; vi.restoreAllMocks() })

  it('skip link focuses the mounted content without changing URL, scope or a pending owner', async () => {
    const retired=vi.fn(), owner={file:new File(['bytes'],'original.txt'),key:'original-key',body:'original-body'}
    const page=await mountApplicationHarness({initialEntries:['/tasks?original=1#evidence'],shell:true,routes:[{path:'/tasks',Component:()=>createElement('main',{id:'main-content',tabIndex:-1},createElement('input',{defaultValue:'原草稿'}))}]})
    wrapper=Object.assign(new ReactDOMQuery(page.element),{unmount:page.unmount});await flushPromises()
    const scope=page.application.current!;scope.lifecycle.retain(owner,retired);scope.navigation.registerGuard(()=>({kind:'BLOCK',reason:'原请求待核对',recoveryAction:'原身份恢复'}))
    const route=page.router.state.location, input=page.element.querySelector('input')!, file=owner.file
    await wrapper.get('.skip-link').trigger('click');await flushPromises()
    expect(document.activeElement).toBe(page.element.querySelector('#main-content'));expect(page.router.state.location).toBe(route);expect(page.application.current).toBe(scope)
    expect(owner.file).toBe(file);expect(owner).toMatchObject({key:'original-key',body:'original-body'});expect(page.element.querySelector('input')).toBe(input);expect(retired).not.toHaveBeenCalled()
  })

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
    expect([...knowledgeLink().element.classList]).toContain('router-link-active')
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
