import { flushPromises, resolveDialog } from '@/pages/w6-tests/workflow/react-test-root'
import { publicationFixture } from '@/pages/w6-tests/workflow/publication-fixture'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { workflowWriteback } from '@/api/workflowWriteback'
import type { WorkflowPublicationPreview, WorkflowWritebackPreview, WorkflowWritebackView } from '@/types/domain'

vi.mock('@/api/workflowWriteback', () => ({ workflowWriteback: { status: vi.fn(), confirm: vi.fn(), retry: vi.fn() } }))
const source: WorkflowPublicationPreview = { requirementId: 'req', requirementVersion: 7, planRevision: 2, requirementState: 'COMPLETED', source: { nodeKey: 'work', nodeTitle: '开发', attemptId: 'private-attempt', ordinal: 1, attemptState: 'SUCCEEDED', outputName: 'code', outputTitle: '代码', createdAt: '', changedFiles: 3, totalFiles: 8 }, workspaceKind: 'DIRECT', sourceBranch: null, reference: { version: 1, snapshotId: 'private-snapshot', sha256: 'a'.repeat(64) }, deliverySha256: 'b'.repeat(64), baseTree: 'before', resultTree: 'after', added: 1, modified: 1, deleted: 1, totalBytes: 1024, sha256: 'c'.repeat(64) }
const preview: WorkflowWritebackPreview = { requirementId: 'req', requirementVersion: 7, revision: 2, sourceSha256: source.sha256, directory: '/project/source', currentSha256: 'current', targetSha256: 'target', sha256: 'preview', added: 1, modified: 1, deleted: 1, preservedChanges: 2, conflictCount: 0, conflicts: [] }

const record = (state: WorkflowWritebackView['state']): WorkflowWritebackView => ({ requirementId: 'req', state, version: 2, queueState: state === 'APPLIED' ? 'FINISHED' : 'ADMITTED', queuePosition: 0, preview, nodeTitle: '开发', outputTitle: '代码', attemptState: 'FAILED', createdAt: 'now', appliedAt: state === 'APPLIED' ? 'now' : null, blocker: state === 'BLOCKED' ? 'WORKFLOW_WRITEBACK_PREPARATION_CHANGED' : null })
const render=()=>publicationFixture('writeback',source,preview)
const button=(f:Awaited<ReturnType<typeof render>>,key:string)=>f.view.get(`button[data-semantic="${key}"]`)
beforeEach(()=>{vi.mocked(workflowWriteback.status).mockResolvedValue(null)})
afterEach(()=>{vi.resetAllMocks();vi.useRealTimers()})
it('确认前展示精确目录、删除和保留数量，再用本次检查摘要回填',async()=>{
 const f=await render();await button(f,'workflow.writeback').trigger('click');expect(workflowWriteback.confirm).not.toHaveBeenCalled();expect(f.view.text()).toContain('删除 1');expect(f.view.text()).toContain('/project/source');expect(f.owner.canLeave().kind).toBe('CONFIRM_DISCARD')
 vi.mocked(workflowWriteback.confirm).mockResolvedValue(record('CONFIRMED'));vi.mocked(workflowWriteback.status).mockResolvedValue(record('APPLIED'));await f.view.get('form').trigger('submit');await flushPromises()
 expect(workflowWriteback.confirm).toHaveBeenCalledWith('req',{requestKey:expect.any(String),expectedVersion:7,selection:{revision:2,node:'work',attempt:'private-attempt',output:'code',sourceSha256:source.sha256},previewSha256:preview.sha256});expect(f.view.text()).toContain('已回填原目录');expect(f.view.text()).toContain('原执行结果为失败');expect(f.view.text()).not.toContain('private-');expect(f.owner.canLeave().kind).toBe('ALLOW');f.dispose()
})
it('冲突、过期或缺少检查、非成功需求都不能出现确认入口',async()=>{
 const f=await render();f.owner.patch({checked:null});await flushPromises();expect(f.view.find('button[data-semantic="workflow.writeback"]').exists()).toBe(false)
 f.owner.patch({checked:{...preview,sourceSha256:'stale'}});await flushPromises();expect(f.owner.writebackAvailable()).toBe(false);expect(f.view.find('button[data-semantic="workflow.writeback"]').exists()).toBe(false)
 f.owner.patch({checked:{...preview,conflictCount:1}});await flushPromises();expect(f.view.find('button[data-semantic="workflow.writeback"]').exists()).toBe(false)
 f.owner.patch({checked:preview,preview:{...source,requirementState:'FAILED'}});await flushPromises();expect(f.view.find('button[data-semantic="workflow.writeback"]').exists()).toBe(false);f.dispose()
})
it('未知回执重发相同请求，已接受后的读取失败只刷新结果',async()=>{
 const f=await render();await button(f,'workflow.writeback').trigger('click');vi.mocked(workflowWriteback.confirm).mockRejectedValueOnce(new Error('lost')).mockResolvedValue(record('CONFIRMED'));await f.view.get('form').trigger('submit');await flushPromises();const original=vi.mocked(workflowWriteback.confirm).mock.calls[0]![1];vi.mocked(workflowWriteback.status).mockRejectedValueOnce(new Error('read failed'))
 await button(f,'receipt.retryOriginal').trigger('click');await flushPromises();expect(workflowWriteback.confirm).toHaveBeenLastCalledWith('req',original);vi.mocked(workflowWriteback.status).mockResolvedValue(record('APPLIED'));await button(f,'receipt.readOriginal').trigger('click');await flushPromises();expect(workflowWriteback.confirm).toHaveBeenCalledTimes(2);expect(f.view.text()).toContain('已回填原目录');f.dispose()
})
it('重开时无需重新选择成果，阻断后恢复原版本并轮询真实完成',async()=>{
 vi.useFakeTimers();vi.mocked(workflowWriteback.status).mockResolvedValueOnce(record('BLOCKED')).mockResolvedValueOnce(record('APPLYING')).mockResolvedValue(record('APPLIED'));const f=await publicationFixture('writeback');expect(f.view.text()).toContain('目录已变化');vi.mocked(workflowWriteback.retry).mockResolvedValue(record('APPLYING'))
 await button(f,'receipt.readOriginal').trigger('click');await flushPromises();expect(workflowWriteback.retry).toHaveBeenCalledWith('req',2);expect(f.view.text()).toContain('正在回填原目录');expect(workflowWriteback.confirm).not.toHaveBeenCalled();await vi.advanceTimersByTimeAsync(2500);await flushPromises();expect(f.view.text()).toContain('已回填原目录');f.dispose();expect(vi.getTimerCount()).toBe(0)
})
it('排队、禁用和取消确认不产生执行，改变来源清理旧确认',async()=>{
 const f=await render();await button(f,'workflow.writeback').trigger('click');await button(f,'ui.close').trigger('click');await resolveDialog(f.view,false);expect(workflowWriteback.confirm).not.toHaveBeenCalled();expect(f.owner.getSnapshot().writebackOpen).toBe(true)
 await button(f,'ui.close').trigger('click');await resolveDialog(f.view,true);expect(f.owner.getSnapshot().writebackOpen).toBe(false)
 f.owner.patch({checked:preview,preview:source,loading:true});await flushPromises();expect(button(f,'workflow.writeback').attributes('disabled')).toBeDefined();f.owner.patch({loading:false});await flushPromises();await button(f,'workflow.writeback').trigger('click');f.owner.close(true);f.owner.patch({preview:{...source,sha256:'another'},checked:null});await flushPromises();expect(f.view.find('form').exists()).toBe(false);f.dispose()
 vi.mocked(workflowWriteback.status).mockResolvedValue({...record('CONFIRMED'),queueState:'QUEUED',queuePosition:3});const queued=await render();expect(queued.view.text()).toContain('第 3 位');expect(workflowWriteback.confirm).not.toHaveBeenCalled();queued.dispose()
})
it('切换需求会取消旧读取，迟到状态不会显示到新需求',async()=>{
 let resolve!:(value:WorkflowWritebackView)=>void;vi.mocked(workflowWriteback.status).mockReturnValueOnce(new Promise(done=>{resolve=done}));const f=await publicationFixture(undefined,source,preview),read=f.owner.readStatus('writeback'),signal=vi.mocked(workflowWriteback.status).mock.calls[0]![1]!;f.dispose();expect(signal.aborted).toBe(true)
 const current=await publicationFixture('writeback',null,null,'other');resolve(record('APPLIED'));await read;await flushPromises();expect(current.view.text()).not.toContain('已回填原目录');current.dispose()
})
