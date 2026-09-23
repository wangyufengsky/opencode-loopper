import { flushPromises, mount } from '@vue/test-utils'
import { ElButton } from 'element-plus'
import { afterEach, expect, it, vi } from 'vitest'
import { api } from '@/api/client'
import SourceArtifactsPanel from './SourceArtifactsPanel.vue'

afterEach(() => vi.restoreAllMocks())

it('renders document read failures with production component registration and clears a recovered error', async () => {
  vi.spyOn(api, 'sourceArtifacts').mockRejectedValueOnce(new Error('文档目录读取失败，请重试'))
    .mockResolvedValue({ items: [], facets: {} })
  const wrapper = mount(SourceArtifactsPanel, { props: { runId: 's1', version: 1, completed: true },
    global: { plugins: [ElButton], stubs: { MarkdownDocument: true } } })
  await flushPromises()
  expect(wrapper.get('[role="alert"]').text()).toContain('文档目录读取失败，请重试')
  await wrapper.findAll('button').find(button => button.text() === '重新读取')!.trigger('click'); await flushPromises()
  expect(wrapper.find('[role="alert"]').exists()).toBe(false)
  wrapper.unmount()
})
