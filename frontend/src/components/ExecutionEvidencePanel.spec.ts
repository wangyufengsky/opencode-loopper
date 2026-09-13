import { flushPromises, mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { afterEach, expect, it, vi } from 'vitest'
import { api } from '@/api/client'
import ExecutionEvidencePanel from './ExecutionEvidencePanel.vue'
afterEach(() => vi.restoreAllMocks())
it('loads metadata only on expansion and clears stale content when task changes', async () => {
  const read = vi.spyOn(api, 'executionEvidence').mockResolvedValue({ items: [], nextCursor: '' })
  vi.spyOn(api, 'evidenceFailures').mockResolvedValue({ items: [], nextCursor: '', reports: { items: [], nextCursor: '' }, detail: '无用例不能推断测试通过' })
  const body = vi.spyOn(api, 'executionEvidenceBody')
  const wrapper = mount(ExecutionEvidencePanel, { props: { taskId: 'a' }, global: { plugins: [ElementPlus] } })
  expect(read).not.toHaveBeenCalled()
  wrapper.get('details').element.open = true
  await wrapper.get('details').trigger('toggle'); await flushPromises()
  expect(read).toHaveBeenCalledWith('a', ''); expect(body).not.toHaveBeenCalled()
  expect(wrapper.text()).toContain('不能推断测试通过')
  await wrapper.setProps({ taskId: 'b' }); expect(wrapper.text()).not.toContain('不能推断测试通过')
  wrapper.unmount()
})
