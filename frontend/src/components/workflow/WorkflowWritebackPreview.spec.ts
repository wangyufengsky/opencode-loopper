import { flushPromises } from '@/pages/w6-tests/workflow/react-test-root'
import { publicationFixture } from '@/pages/w6-tests/workflow/publication-fixture'
import { afterEach, expect, it, vi } from 'vitest'
import { workflowWriteback } from '@/api/workflowWriteback'
import type { WorkflowPublicationPreview, WorkflowWritebackPreview } from '@/types/domain'

vi.mock('@/api/workflowWriteback', () => ({ workflowWriteback: { preview: vi.fn() } }))
afterEach(() => vi.resetAllMocks())
const source: WorkflowPublicationPreview = { requirementId: 'req', requirementVersion: 7, planRevision: 2, requirementState: 'COMPLETED', source: { nodeKey: 'work', nodeTitle: '开发', attemptId: 'private-attempt', ordinal: 1, attemptState: 'SUCCEEDED', outputName: 'code', outputTitle: '代码', createdAt: '', changedFiles: 3, totalFiles: 8 }, workspaceKind: 'DIRECT', sourceBranch: null, reference: { version: 1, snapshotId: 'private-snapshot', sha256: 'a'.repeat(64) }, deliverySha256: 'b'.repeat(64), baseTree: 'before', resultTree: 'after', added: 1, modified: 1, deleted: 1, totalBytes: 1024, sha256: 'c'.repeat(64) }
const preview: WorkflowWritebackPreview = { requirementId: 'req', requirementVersion: 7, revision: 2, sourceSha256: source.sha256, directory: '/project/source', currentSha256: 'current', targetSha256: 'target', sha256: 'preview', added: 1, modified: 1, deleted: 1, preservedChanges: 2, conflictCount: 0, conflicts: [] }
const render=()=>publicationFixture(undefined,source)
const inspect=(f:Awaited<ReturnType<typeof render>>)=>f.view.get('[aria-label="普通目录回填检查"] button')
it('只有点击才读取现场，展示预计改动并说明仍未写入',async()=>{
 vi.mocked(workflowWriteback.preview).mockResolvedValue(preview);const f=await render();expect(workflowWriteback.preview).not.toHaveBeenCalled();await inspect(f).trigger('click');await flushPromises();expect(workflowWriteback.preview).toHaveBeenCalledWith('req',{revision:2,node:'work',attempt:'private-attempt',output:'code',sourceSha256:source.sha256},expect.any(AbortSignal))
 expect(f.view.text()).toContain('预计新增 1 · 修改 1 · 删除 1');expect(f.view.text()).toContain('保留 2 处用户后续修改');expect(f.view.text()).toContain('没有写入文件');expect(f.view.text()).not.toContain('private-');expect(f.owner.getSnapshot().checked).toEqual(preview);f.dispose()
})
it('冲突显示相对路径而不显示可应用结论',async()=>{
 vi.mocked(workflowWriteback.preview).mockResolvedValue({...preview,targetSha256:null,conflictCount:103,conflicts:['src/订单.java']});const f=await render();await inspect(f).trigger('click');await flushPromises();expect(f.view.text()).toContain('103 个路径');expect(f.view.text()).toContain('src/订单.java');expect(f.view.text()).not.toContain('预计新增');expect(f.view.text()).toContain('仅展示前 1 个');f.dispose()
})
it('切换成果取消旧检查，迟到结果不能覆盖新选择',async()=>{
 let resolve!:(value:WorkflowWritebackPreview)=>void;vi.mocked(workflowWriteback.preview).mockReturnValue(new Promise(done=>{resolve=done}));const f=await render();await inspect(f).trigger('click');const signal=vi.mocked(workflowWriteback.preview).mock.calls[0]![2]!;f.dispose();expect(signal.aborted).toBe(true)
 const current=await publicationFixture(undefined,{...source,sha256:'new'});resolve(preview);await flushPromises();expect(current.view.text()).not.toContain('/project/source');current.dispose()
})
it('读取错误可重新检查，错误作用域和版本响应不可显示',async()=>{
 vi.mocked(workflowWriteback.preview).mockResolvedValueOnce({...preview,requirementVersion:6}).mockResolvedValue(preview);const f=await render();await inspect(f).trigger('click');await flushPromises();expect(f.view.find('[role="alert"]').exists()).toBe(true);expect(f.view.text()).not.toContain('/project/source');await inspect(f).trigger('click');await flushPromises();expect(f.view.text()).toContain('/project/source');f.dispose()
})
it('Git 成果、未成功需求及外部锁定时不允许现场检查',async()=>{
 const f=await render();f.owner.patch({loading:true});await flushPromises();expect(inspect(f).attributes('disabled')).toBeDefined();f.owner.patch({preview:{...source,workspaceKind:'GIT'}});await flushPromises();expect(f.view.find('[aria-label="普通目录回填检查"] button').exists()).toBe(false);f.owner.patch({preview:{...source,requirementState:'FAILED'}});await flushPromises();expect(f.view.find('[aria-label="普通目录回填检查"] button').exists()).toBe(false);expect(workflowWriteback.preview).not.toHaveBeenCalled();f.dispose()
})
