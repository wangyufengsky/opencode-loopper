import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { workflowPublication } from '@/api/workflowPublication'
import type { WorkflowPublicationPreview, WorkflowPublicationSource } from '@/types/domain'
import WorkflowPublication from './WorkflowPublication.vue'
import WorkflowCodeChanges from './WorkflowCodeChanges.vue'
vi.mock('@/api/workflowPublication', () => ({ workflowPublication: { sources: vi.fn(), preview: vi.fn() } }))
afterEach(() => vi.resetAllMocks())
const source = (id: string, state = 'SUCCEEDED'): WorkflowPublicationSource => ({ nodeKey: id, nodeTitle: `开发阶段 ${id}`, attemptId: `private-${id}`, ordinal: 1, attemptState: state, outputName: 'code', outputTitle: '代码', createdAt: '2026-09-29T00:00:00Z', changedFiles: 3, totalFiles: 8 })
const preview = (value: WorkflowPublicationSource): WorkflowPublicationPreview => ({ requirementId: 'req', requirementVersion: 4, planRevision: 2, requirementState: 'COMPLETED', source: value, workspaceKind: 'GIT', sourceBranch: 'main', reference: { version: 1, snapshotId: 'private-snapshot', sha256: 'a'.repeat(64) }, deliverySha256: 'b'.repeat(64), baseTree: 'private-base', resultTree: 'private-result', added: 1, modified: 1, deleted: 1, totalBytes: 2048, sha256: value.attemptId })
const render = (disabled = false) => mount(WorkflowPublication, { props: { requirement: 'req', revision: 2, disabled }, global: { stubs: { WorkflowCodeChanges: true, WorkflowPublicationCommit: true, WorkflowWriteback: true } } })
const button = (view: ReturnType<typeof render>, name: string) => view.findAll('button').find(b => b.text().includes(name))!
describe('需求代码成果选择', () => {
  it('按需读取并显式选择来源，失败成果保留实际结果与累计改动', async () => {
    const value = source('一', 'FAILED'); vi.mocked(workflowPublication.sources).mockResolvedValue({ items: [value], nextCursor: null }); vi.mocked(workflowPublication.preview).mockResolvedValue(preview(value))
    const view = render(); expect(workflowPublication.sources).not.toHaveBeenCalled(); await button(view, '查看代码成果').trigger('click'); await flushPromises()
    expect(workflowPublication.preview).not.toHaveBeenCalled(); await button(view, '开发阶段 一').trigger('click'); await flushPromises()
    expect(view.text()).toContain('该节点执行失败'); expect(view.text()).toContain('新增 1 · 修改 1 · 删除 1'); expect(view.text()).not.toContain('private-')
    expect(view.getComponent(WorkflowCodeChanges).props()).toMatchObject({ requirement: 'req', node: '一', attempt: 'private-一', direction: 'outputs', name: 'code' }); view.unmount()
  })
  it('分页失败保留成果并使用同一游标重试', async () => {
    vi.mocked(workflowPublication.sources).mockResolvedValueOnce({ items: [source('一')], nextCursor: 'next' }).mockRejectedValueOnce(new Error('unavailable')).mockResolvedValueOnce({ items: [source('二')], nextCursor: null })
    const view = render(); await button(view, '查看代码成果').trigger('click'); await flushPromises(); await button(view, '更多代码成果').trigger('click'); await flushPromises()
    expect(view.text()).toContain('开发阶段 一'); await button(view, '重试读取成果').trigger('click'); await flushPromises()
    expect(workflowPublication.sources).toHaveBeenLastCalledWith('req', 2, 'next', expect.any(AbortSignal)); expect(view.text()).toContain('开发阶段 二'); view.unmount()
  })
  it('切换成果后忽略旧预览，计划修订后清空选择', async () => {
    let resolve!: (result: WorkflowPublicationPreview) => void
    vi.mocked(workflowPublication.sources).mockResolvedValue({ items: [source('一'), source('二')], nextCursor: null })
    vi.mocked(workflowPublication.preview).mockReturnValueOnce(new Promise(done => { resolve = done })).mockResolvedValueOnce(preview(source('二')))
    const view = render(); await button(view, '查看代码成果').trigger('click'); await flushPromises(); await button(view, '开发阶段 一').trigger('click'); const signal = vi.mocked(workflowPublication.preview).mock.calls[0]![3]!
    await button(view, '开发阶段 二').trigger('click'); await flushPromises(); expect(signal.aborted).toBe(true); resolve(preview(source('一'))); await flushPromises()
    expect(view.get('[aria-label="所选代码成果"] h3').text()).toContain('开发阶段 二'); await view.setProps({ revision: 3 }); await flushPromises()
    expect(view.find('[aria-label="所选代码成果"]').exists()).toBe(false); expect(workflowPublication.sources).toHaveBeenLastCalledWith('req', 3, '', expect.any(AbortSignal)); view.unmount()
  })
  it('响应身份不匹配时拒绝显示，重试仍读取所选来源', async () => {
    const value = source('一'); vi.mocked(workflowPublication.sources).mockResolvedValue({ items: [value], nextCursor: null }); vi.mocked(workflowPublication.preview).mockResolvedValueOnce({ ...preview(value), requirementId: 'other' }).mockResolvedValueOnce(preview(value))
    const view = render(); await button(view, '查看代码成果').trigger('click'); await flushPromises(); await button(view, '开发阶段 一').trigger('click'); await flushPromises()
    expect(view.findComponent(WorkflowCodeChanges).exists()).toBe(false); await button(view, '重试预览').trigger('click'); await flushPromises(); expect(view.getComponent(WorkflowCodeChanges).props('attempt')).toBe(value.attemptId); view.unmount()
  })
  it('关闭面板终止请求且晚到数据不会再次打开面板', async () => {
    let resolve!: (page: { items: WorkflowPublicationSource[]; nextCursor: null }) => void
    vi.mocked(workflowPublication.sources).mockReturnValue(new Promise(done => { resolve = done }))
    const view = render(); await button(view, '查看代码成果').trigger('click'); const signal = vi.mocked(workflowPublication.sources).mock.calls[0]![3]!
    await button(view, '收起代码成果').trigger('click'); expect(signal.aborted).toBe(true); resolve({ items: [source('一')], nextCursor: null }); await flushPromises(); expect(view.text()).not.toContain('开发阶段 一'); view.unmount()
  })
  it('回填确认锁定来源、分页和收起，写操作结束后恢复操作', async () => {
    vi.mocked(workflowPublication.sources).mockResolvedValue({ items: [source('一')], nextCursor: 'next' }); const view = render()
    await button(view, '查看代码成果').trigger('click'); await flushPromises(); view.getComponent({ name: 'WorkflowWriteback' }).vm.$emit('busy', true); await flushPromises()
    for (const name of ['收起代码成果', '刷新成果', '更多代码成果', '开发阶段 一']) expect(button(view, name).attributes('disabled')).toBeDefined()
    expect(view.emitted('busy')?.at(-1)).toEqual([true]); view.getComponent({ name: 'WorkflowWriteback' }).vm.$emit('busy', false); await flushPromises()
    expect(button(view, '更多代码成果').attributes('disabled')).toBeUndefined(); view.unmount()
  })
  it('锁定时不读取，空成果说明与加载失败可恢复', async () => {
    const locked = render(true); await locked.get('button').trigger('click'); expect(workflowPublication.sources).not.toHaveBeenCalled(); locked.unmount()
    vi.mocked(workflowPublication.sources).mockRejectedValueOnce(new Error('unavailable')).mockResolvedValueOnce({ items: [], nextCursor: null }); const view = render()
    await button(view, '查看代码成果').trigger('click'); await flushPromises(); await button(view, '重试读取成果').trigger('click'); await flushPromises(); expect(view.text()).toContain('还没有已保存'); view.unmount()
  })
})
