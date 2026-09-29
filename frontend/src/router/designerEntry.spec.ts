import { beforeEach, afterEach, expect, it, vi } from 'vitest'
import { createMemoryHistory, createRouter } from 'vue-router'
import { designerEntry } from './designerEntry'
beforeEach(() => sessionStorage.clear())
afterEach(() => vi.restoreAllMocks())
async function navigate(path: string) {
  const router = createRouter({ history: createMemoryHistory(), routes: [
    { path: '/designer', beforeEnter: designerEntry, component: { template: '<div>历史设计</div>' } },
    { path: '/requirements/new', component: { template: '<div>新需求</div>' } },
  ] })
  await router.push(path); await router.isReady(); return router.currentRoute.value
}
it('旧新建地址进入需求画布入口，保留未发送文字供表单读取', async () => {
  sessionStorage.setItem('opencode-loopper.designer-draft-prompt', '未发送目标')
  const route = await navigate('/designer')
  expect(route.path).toBe('/requirements/new'); expect(route.query.legacyDraft).toBe('1')
  expect(sessionStorage.getItem('opencode-loopper.designer-draft-prompt')).toBe('未发送目标')
})
it('明确历史会话和编辑模式继续打开原设计', async () => {
  const route = await navigate('/designer?sessionId=history&mode=edit&projectId=project')
  expect(route.path).toBe('/designer'); expect(route.query).toEqual({ sessionId: 'history', mode: 'edit', projectId: 'project' })
})
it('原工作区中的会话可从旧书签继续，不创建新需求', async () => {
  sessionStorage.setItem('opencode-loopper.designer-workspace', JSON.stringify({ sessionId: 'saved', draftId: 'draft' }))
  const route = await navigate('/designer')
  expect(route.path).toBe('/designer'); expect(route.query.sessionId).toBe('saved')
})
it('项目创建快捷入口保持指定项目，不恢复另一项目的旧会话', async () => {
  sessionStorage.setItem('opencode-loopper.designer-workspace', JSON.stringify({ sessionId: 'saved', draftId: 'draft' }))
  const route = await navigate('/designer?projectId=selected')
  expect(route.path).toBe('/requirements/new'); expect(route.query.projectId).toBe('selected')
  expect(sessionStorage.getItem('opencode-loopper.designer-workspace')).toContain('saved')
})
it('存储不可读取时仍能进入新需求', async () => {
  vi.spyOn(Storage.prototype, 'getItem').mockImplementation(() => { throw new Error('unavailable') })
  expect((await navigate('/designer')).path).toBe('/requirements/new')
})
