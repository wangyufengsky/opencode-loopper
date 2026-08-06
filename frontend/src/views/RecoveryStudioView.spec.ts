import { flushPromises, mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { createMemoryHistory, createRouter } from 'vue-router'
import { afterEach, describe, expect, it, vi } from 'vitest'
import RecoveryStudioView from '@/views/RecoveryStudioView.vue'

const parent = {
  id: 'parent-1', title: '失败的导入任务', status: 'FAILED', projectName: '演示项目', goal: '导入并验证数据',
  stages: [{ id: 'stage-1', ordinal: 0, objective: '准备输入', status: 'SUCCEEDED' }, { id: 'stage-2', ordinal: 1, objective: '写入导入器', status: 'FAILED' }],
  errors: [{ code: 'VERIFICATION_FAILED', message: '输出缺少摘要', stageId: 'stage-2' }],
}

function response(body: unknown, status = 200) {
  return { ok: status >= 200 && status < 300, status, json: async () => body } as Response
}

async function mountView(fetchMock: ReturnType<typeof vi.fn>) {
  vi.stubGlobal('fetch', fetchMock)
  const router = createRouter({ history: createMemoryHistory(), routes: [
    { path: '/tasks/:id/recovery', component: RecoveryStudioView },
    { path: '/tasks/:id', component: { template: '<div />' } },
  ] })
  await router.push('/tasks/parent-1/recovery')
  await router.isReady()
  const wrapper = mount(RecoveryStudioView, { global: { plugins: [router, ElementPlus], stubs: { Icon: true, PageHeader: { template: '<header><slot name="actions" /></header>' } } } })
  await flushPromises()
  return wrapper
}

afterEach(() => { vi.unstubAllGlobals(); vi.restoreAllMocks() })

describe('RecoveryStudioView', () => {
  it('shows the failed context and creates a derived from-failed-stage recovery', async () => {
    const fetchMock = vi.fn().mockResolvedValueOnce(response(parent)).mockResolvedValueOnce(response([])).mockResolvedValueOnce(response({
      taskId: 'child-1', parentTaskId: 'parent-1', mode: 'FROM_FAILED_STAGE', parentStageId: 'stage-2', workspaceFingerprint: 'baseline-abc', writableSession: true,
    }))
    const wrapper = await mountView(fetchMock)

    expect(wrapper.text()).toContain('VERIFICATION_FAILED')
    expect(wrapper.text()).toContain('阶段 2 · 写入导入器')
    await wrapper.findAll('button').find((button) => button.text().includes('创建派生'))!.trigger('click')
    await flushPromises()

    expect(fetchMock.mock.calls[2]?.[0]).toBe('/api/tasks/parent-1/recoveries')
    expect(JSON.parse(fetchMock.mock.calls[2]?.[1].body)).toEqual({ mode: 'FROM_FAILED_STAGE' })
    expect(fetchMock.mock.calls[2]?.[1].headers['X-Loopper-Local-UI']).toBe('1')
    expect(wrapper.text()).toContain('派生草稿已创建')
    expect(wrapper.text()).toContain('child-1')
  })

  it('renders a 409 direct workspace conflict without claiming a draft was created', async () => {
    const fetchMock = vi.fn().mockResolvedValueOnce(response({ ...parent, status: 'CANCELLED', errors: [] })).mockResolvedValueOnce(response([]))
      .mockResolvedValueOnce(response({ detail: '工作区指纹已经变化', errorCode: 'RECOVERY_WORKSPACE_FINGERPRINT_MISMATCH' }, 409))
    const wrapper = await mountView(fetchMock)

    await wrapper.findAll('button').find((button) => button.text().includes('创建派生'))!.trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('恢复被安全阻止（409）')
    expect(wrapper.text()).toContain('RECOVERY_WORKSPACE_FINGERPRINT_MISMATCH')
    expect(wrapper.text()).not.toContain('派生草稿已创建')
  })
})
