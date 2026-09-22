import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { pptApi } from '@/api/ppt'
import type { PptKnowledge } from '@/types/ppt'
import PptProjectSources from './PptProjectSources.vue'
vi.mock('@/api/ppt', () => ({ pptApi: { knowledge: vi.fn() } }))
const api = vi.mocked(pptApi)
beforeEach(() => { vi.clearAllMocks() })
const knowledge: PptKnowledge = { project: { id: 'project', name: '支付平台' }, sources: [{ id: 'code', kind: 'CODE', name: '项目代码', state: 'READY', detail: '', version: 0 }, { id: 'documents', kind: 'DIRECTORY', name: '汇报资料', state: 'FAILED', detail: '目录无法读取，请检查来源配置。', version: 0 }], detail: '' }
describe('PPT frozen project source summary', () => {
  it('shows usable sources and individual source failures without hiding the rest of the material panel', async () => {
    api.knowledge.mockResolvedValue(knowledge)
    const wrapper = mount(PptProjectSources, { props: { documentId: 'doc' } })
    await flushPromises()
    expect(wrapper.text()).toContain('支付平台')
    expect(wrapper.text()).toContain('可用')
    expect(wrapper.text()).toContain('目录无法读取')
    expect(api.knowledge).toHaveBeenCalledWith('doc')
  })
  it('ignores an earlier document response and recovers a failed source read explicitly', async () => {
    let resolve!: (value: PptKnowledge) => void
    api.knowledge.mockReturnValueOnce(new Promise<PptKnowledge>(done => { resolve = done }))
    api.knowledge.mockRejectedValueOnce(new Error('offline'))
    const wrapper = mount(PptProjectSources, { props: { documentId: 'old' } })
    await wrapper.setProps({ documentId: 'current' })
    await flushPromises()
    resolve(knowledge)
    await flushPromises()
    expect(wrapper.text()).not.toContain('支付平台')
    expect(wrapper.get('[role="alert"]').text()).toContain('项目来源暂时无法读取')
    api.knowledge.mockResolvedValue({ project: { id: 'legacy', name: '历史项目' }, sources: [], detail: '此作品尚未开放项目来源，请新建作品并选择项目' })
    await wrapper.get('button').trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('此作品尚未开放项目来源')
  })
})
