import { flushPromises, mount } from '@vue/test-utils'
import { createPinia } from 'pinia'
import { createMemoryHistory, createRouter } from 'vue-router'
import { describe, expect, it } from 'vitest'
import { router as appRouter } from '@/router'
import AppSidebar from '@/components/AppSidebar.vue'
import HomeView from './HomeView.vue'

describe('主页导航', () => {
  it('所有工作区与系统入口使用真实路由，点击后可通过品牌返回主页', async () => {
    const destinations = ['/projects', '/designer', '/tasks', '/inbox', '/designs', '/insights', '/automations', '/runtime', '/tools', '/settings']
    const router = createRouter({ history: createMemoryHistory(), routes: [
      { path: '/', component: HomeView },
      ...destinations.map(path => ({ path, component: { template: '<main>目标页面</main>' } })),
    ] })
    await router.push('/')
    const wrapper = mount({ components: { AppSidebar }, template: '<AppSidebar /><RouterView />' }, {
      global: { plugins: [createPinia(), router], stubs: { Icon: true } },
    })
    for (const path of destinations) {
      expect(appRouter.resolve(path).matched[0]?.path).toBe(path)
      await wrapper.get(`main a[href="${path}"]`).trigger('click')
      await flushPromises()
      expect(router.currentRoute.value.path).toBe(path)
      expect(wrapper.get('a.nav-item[href="/"]').classes()).not.toContain('router-link-active')
      await wrapper.get('a.brand').trigger('click')
      await flushPromises()
      expect(router.currentRoute.value.path).toBe('/')
      expect(wrapper.get('a.nav-item[href="/"]').attributes('aria-current')).toBe('page')
    }
    wrapper.unmount()
  })

  it('根路径与未知地址进入主页，同时保留任务深层链接', async () => {
    const router = createRouter({ history: createMemoryHistory(), routes: appRouter.options.routes })
    await router.push('/')
    expect(router.currentRoute.value.path).toBe('/')
    expect(router.currentRoute.value.matched[0]?.components?.default).toBe(HomeView)
    await router.push('/unknown/deep/path')
    expect(router.currentRoute.value.path).toBe('/')
    expect(router.resolve('/tasks/example/recovery').matched[0]?.path).toBe('/tasks/:id/recovery')
    expect(router.resolve('/tasks/example/design').matched[0]?.path).toBe('/tasks/:id/design')
  })
})
