import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { api } from '@/api/client'
import type { TemplateBranchChoice, TemplateBranchPage } from '@/types/domain'
import WorkflowBranchInput from './WorkflowBranchInput.vue'
vi.mock('@/api/client', () => ({ ApiError: class extends Error {}, api: { templateBranches: vi.fn() } }))
const main = { id: 'local:refs/heads/main', label: 'main', ref: 'refs/heads/main', remote: null }
const other = { id: 'remote:origin:refs/heads/review', label: 'review', ref: 'refs/heads/review', remote: 'origin' }
const page = (items: TemplateBranchChoice[] = [main], nextCursor: string | null = null): TemplateBranchPage => ({ page: { items, nextCursor }, defaultBranch: main, defaultBranchId: main.id, remoteAvailable: true })
beforeEach(() => vi.resetAllMocks())
describe('固定代码分支选择', () => {
  it('按需读取、显式选择，翻页和搜索不会将已有选择改成默认分支', async () => {
    vi.mocked(api.templateBranches).mockResolvedValueOnce(page([main], 'next')).mockResolvedValueOnce(page([other])).mockResolvedValueOnce(page([]))
    const view = mount(WorkflowBranchInput, { props: { project: 'p', title: '代码分支', value: '' } })
    expect(api.templateBranches).not.toHaveBeenCalled()
    await view.get('button').trigger('click'); await flushPromises(); expect(view.emitted('change')).toBeUndefined()
    await view.findAll('button').find(b => b.text() === '更多分支')!.trigger('click'); await flushPromises()
    expect(api.templateBranches).toHaveBeenLastCalledWith('p', '', 'next')
    await view.get('select').setValue(other.id); expect(view.emitted('change')![0]).toEqual([other.id]); await view.setProps({ value: other.id })
    await view.get('input').setValue('missing'); await view.get('input').trigger('keydown.enter'); await flushPromises()
    expect(api.templateBranches).toHaveBeenLastCalledWith('p', 'missing', undefined)
    expect(view.text()).toContain('远程 origin · review'); expect(view.text()).toContain('没有匹配'); expect(view.emitted('change')).toHaveLength(1)
    view.unmount()
  })
  it('切换项目后丢弃旧请求，失败不会清空已保存的输入', async () => {
    let resolve!: (value: TemplateBranchPage) => void
    vi.mocked(api.templateBranches).mockImplementationOnce(() => new Promise(r => { resolve = r })).mockRejectedValueOnce(new Error('offline'))
    const view = mount(WorkflowBranchInput, { props: { project: 'first', title: '代码分支', value: main.id } })
    await view.get('button').trigger('click'); await view.setProps({ project: 'second' }); resolve(page()); await flushPromises()
    expect(view.find('select').exists()).toBe(false)
    await view.get('button').trigger('click'); await flushPromises(); expect(view.find('[role="alert"]').exists()).toBe(true)
    expect(view.text()).toContain('本地 · main'); expect(view.emitted('change')).toBeUndefined(); view.unmount()
  })
  it('远程不可用仍允许选择服务端返回的本地分支，冻结时禁止修改', async () => {
    vi.mocked(api.templateBranches).mockResolvedValue({ ...page(), remoteAvailable: false, remoteProblems: ['远程连接失败，请检查连接'] })
    const view = mount(WorkflowBranchInput, { props: { project: 'p', title: '代码分支', value: '' } })
    await view.get('button').trigger('click'); await flushPromises(); expect(view.text()).toContain('远程连接失败')
    await view.setProps({ disabled: true }); await view.get('select').setValue(main.id)
    expect(view.emitted('change')).toBeUndefined(); expect(view.get('select').attributes('disabled')).toBeDefined(); view.unmount()
  })
})
