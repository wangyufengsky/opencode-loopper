import { flushPromises, mount } from '@/pages/w6-tests/ordinary/render'
import { afterEach, expect, it, vi } from 'vitest'
import { api } from '@/api/client'
import {ExecutionEvidencePanel} from '@/pages/w4/task/TaskEvidencePanels'
import {createTaskEvidenceController} from '@/pages/w4/task/evidenceController'
import {coreFixture} from '@/pages/w2/core/coreTestHelpers'
afterEach(() => vi.restoreAllMocks())
it('loads metadata only on expansion and clears stale content when task changes', async () => {
  const read = vi.spyOn(api, 'executionEvidence').mockResolvedValue({ items: [], nextCursor: '' })
  vi.spyOn(api, 'evidenceFailures').mockResolvedValue({ items: [], nextCursor: '', reports: { items: [], nextCursor: '' }, detail: '无用例不能推断测试通过' })
  const body = vi.spyOn(api, 'executionEvidenceBody')
  const f=coreFixture(); const wrapper=mount(ExecutionEvidencePanel,{props:{owner:createTaskEvidenceController('a'),page:f.props}})
  expect(read).not.toHaveBeenCalled()
  await wrapper.get('[data-semantic="ui.expand"]').trigger('click'); await flushPromises()
  expect(read).toHaveBeenCalledWith('a', ''); expect(body).not.toHaveBeenCalled()
  expect(wrapper.text()).toContain('不能推断测试通过')
  await wrapper.setProps({ owner:createTaskEvidenceController('b') }); expect(wrapper.text()).not.toContain('不能推断测试通过')
  wrapper.unmount(); f.dispose()
})
