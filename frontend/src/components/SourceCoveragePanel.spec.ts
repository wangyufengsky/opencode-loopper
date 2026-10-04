import { flushPromises, mount } from '@/pages/w6-tests/knowledge-ppt-template/react-test-root'
import { afterEach, expect, it, vi } from 'vitest'
import { api } from '@/api/client'
import type { SourceTemplateCoverage } from '@/types/domain'
import { SourceCoveragePanel } from '@/pages/w6-tests/knowledge-ppt-template/template-react'
afterEach(() => vi.restoreAllMocks())
it('pages metadata and only loads the requested evidence body', async () => {
  const row: SourceTemplateCoverage = { runId: 's1', ordinal: 0, path: 'A.java', target: 1, sizeBytes: 10,
    sha256: 'abc', exclusion: null, status: 'REVIEWED', resultJson: null }
  const list = vi.spyOn(api, 'sourceCoverage').mockResolvedValueOnce({ items: [row], nextCursor: '0', facets: {} })
    .mockResolvedValueOnce({ items: [{ ...row, path: 'B.java', ordinal: 1 }], facets: {} })
  const body = vi.spyOn(api, 'sourceCoverageItem').mockResolvedValue({ ...row, resultJson: '{"documents":["module-0-a.md#class-a"]}' })
  const wrapper = mount(SourceCoveragePanel, { props: { runId: 's1', revision: '1', ready: true }, global: { plugins: [] } })
  await flushPromises(); expect(body).not.toHaveBeenCalled()
  await wrapper.findAll('button').find(button => button.attributes('aria-label')?.includes('文件') && button.attributes('data-semantic') === 'ui.loadMore')!.trigger('click'); await flushPromises()
  expect(list).toHaveBeenLastCalledWith('s1', '0'); expect(wrapper.text()).toContain('B.java')
  await wrapper.findAll('button').find(button => button.attributes('data-semantic') === 'ui.expand')!.trigger('click'); await flushPromises()
  expect(body).toHaveBeenCalledExactlyOnceWith('s1', 'A.java')
  expect(wrapper.text()).toContain('module-0-a.md#class-a')
  wrapper.unmount()
})
it('shows coverage read failure and clears it after a successful retry', async () => {
  vi.spyOn(api, 'sourceCoverage').mockRejectedValueOnce(new Error('覆盖清单读取失败，请重试'))
    .mockResolvedValue({ items: [], facets: {} })
  const wrapper = mount(SourceCoveragePanel, { props: { runId: 's1', revision: '1', ready: true }, global: { plugins: [] } })
  await flushPromises()
  expect(wrapper.get('[role="alert"]').text()).toContain('覆盖清单读取失败，请重试')
  await wrapper.findAll('button').find(button => button.attributes('data-semantic') === 'ui.refresh')!.trigger('click'); await flushPromises()
  expect(wrapper.find('[role="alert"]').exists()).toBe(false)
  wrapper.unmount()
})
