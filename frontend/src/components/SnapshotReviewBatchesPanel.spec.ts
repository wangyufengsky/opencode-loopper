import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, expect, it, vi } from 'vitest'
import { api } from '@/api/client'
import type { Task } from '@/types/domain'
import SnapshotReviewBatchesPanel from './SnapshotReviewBatchesPanel.vue'
vi.mock('@/api/client', () => ({ api: { snapshotReviewBatches: vi.fn() } }))
beforeEach(() => { vi.clearAllMocks() })
const task = (id: string) => ({ id, status: 'RUNNING', templateProgress: { activeBatches: 1 } }) as Task
it('loads summaries on demand and discards a response from a previous task', async () => {
  let resolveOld!: (value: Awaited<ReturnType<typeof api.snapshotReviewBatches>>) => void
  vi.mocked(api.snapshotReviewBatches).mockImplementationOnce(() => new Promise(resolve => { resolveOld = resolve }))
    .mockResolvedValue({ items: [{ id: 'new', purpose: 'SNAPSHOT_ANALYSIS', state: 'RUNNING', ordinal: 0, generation: 0, createdAt: 'now', title: '新任务功能', errorMessage: null }], nextCursor: undefined, facets: {} })
  const wrapper = mount(SnapshotReviewBatchesPanel, { props: { task: task('a') }, global: { stubs: { 'el-button': { template: '<button><slot /></button>' } } } })
  expect(api.snapshotReviewBatches).not.toHaveBeenCalled()
  await wrapper.get('button').trigger('click'); await flushPromises()
  await wrapper.setProps({ task: task('b') }); await flushPromises()
  resolveOld({ items: [{ id: 'old', purpose: 'SNAPSHOT_PLAN', state: 'VALIDATED', ordinal: 0, generation: 0, createdAt: 'now', title: '旧任务正文', errorMessage: null }], nextCursor: undefined, facets: {} })
  await flushPromises()
  expect(wrapper.text()).toContain('新任务功能'); expect(wrapper.text()).not.toContain('旧任务正文')
  expect(wrapper.text()).toContain('功能分析'); expect(wrapper.text()).not.toContain('SNAPSHOT_ANALYSIS')
  wrapper.unmount()
})
