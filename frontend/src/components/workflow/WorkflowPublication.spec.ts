import { flushPromises } from '@/pages/w6-tests/workflow/react-test-root'
import { publicationFixture } from '@/pages/w6-tests/workflow/publication-fixture'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { workflowPublication } from '@/api/workflowPublication'
import type { WorkflowPublicationPreview, WorkflowPublicationSource } from '@/types/domain'

import { workflowRuns } from '@/api/workflowRuns'
vi.mock('@/api/workflowPublication', () => ({ workflowPublication: { sources: vi.fn(), preview: vi.fn(), status: vi.fn(), confirm: vi.fn() } }))
afterEach(() => vi.resetAllMocks())
const source = (id: string, state = 'SUCCEEDED'): WorkflowPublicationSource => ({ nodeKey: id, nodeTitle: `开发阶段 ${id}`, attemptId: `private-${id}`, ordinal: 1, attemptState: state, outputName: 'code', outputTitle: '代码', createdAt: '2026-09-29T00:00:00Z', changedFiles: 3, totalFiles: 8 })
const preview = (value: WorkflowPublicationSource): WorkflowPublicationPreview => ({ requirementId: 'req', requirementVersion: 4, planRevision: 2, requirementState: 'COMPLETED', source: value, workspaceKind: 'GIT', sourceBranch: 'main', reference: { version: 1, snapshotId: 'private-snapshot', sha256: 'a'.repeat(64) }, deliverySha256: 'b'.repeat(64), baseTree: 'private-base', resultTree: 'private-result', added: 1, modified: 1, deleted: 1, totalBytes: 2048, sha256: value.attemptId })

vi.mock('@/api/workflowRuns',()=>({workflowRuns:{changes:vi.fn(),fileUrl:vi.fn(()=>'/fixed-file')}}))
const render=()=>publicationFixture()
const button=(f:Awaited<ReturnType<typeof render>>,key:string)=>f.view.get(`button[data-semantic="${key}"]`)
describe('需求代码成果选择',()=>{
 it('按需读取并显式选择来源，失败成果保留实际结果与累计改动',async()=>{
  const value=source('一','FAILED');vi.mocked(workflowPublication.sources).mockResolvedValue({items:[value],nextCursor:null});vi.mocked(workflowPublication.preview).mockResolvedValue(preview(value));const f=await render();expect(workflowPublication.sources).not.toHaveBeenCalled();await button(f,'ui.refresh').trigger('click');await flushPromises();expect(workflowPublication.preview).not.toHaveBeenCalled();await button(f,'selection.select').trigger('click');await flushPromises();expect(f.view.text()).toContain('该节点执行失败');expect(f.view.text()).toContain('新增 1 · 修改 1 · 删除 1');expect(f.view.text()).not.toContain('private-')
  vi.mocked(workflowRuns.changes).mockResolvedValue({items:[],nextCursor:null});await f.view.get('.workflow-code-changes button').trigger('click');await flushPromises();expect(workflowRuns.changes).toHaveBeenCalledWith('req','一','private-一','outputs','code','');f.dispose()
 })
 it('分页失败保留成果并使用同一游标重试',async()=>{
  vi.mocked(workflowPublication.sources).mockResolvedValueOnce({items:[source('一')],nextCursor:'next'}).mockRejectedValueOnce(new Error('unavailable')).mockResolvedValueOnce({items:[source('二')],nextCursor:null});const f=await render();await button(f,'ui.refresh').trigger('click');await flushPromises();await button(f,'ui.loadMore').trigger('click');await flushPromises();expect(f.view.text()).toContain('开发阶段 一');await f.owner.retryList();await flushPromises();expect(workflowPublication.sources).toHaveBeenLastCalledWith('req',2,'next',expect.any(AbortSignal));expect(f.view.text()).toContain('开发阶段 二');f.dispose()
 })
 it('切换成果后忽略旧预览，计划修订后清空选择',async()=>{
  let resolve!:(result:WorkflowPublicationPreview)=>void;vi.mocked(workflowPublication.sources).mockResolvedValue({items:[source('一'),source('二')],nextCursor:null});vi.mocked(workflowPublication.preview).mockReturnValueOnce(new Promise(done=>{resolve=done})).mockResolvedValueOnce(preview(source('二')));const f=await render();await button(f,'ui.refresh').trigger('click');await flushPromises();await f.view.findAll('button[data-semantic="selection.select"]')[0]!.trigger('click');const signal=vi.mocked(workflowPublication.preview).mock.calls[0]![3]!;await f.view.findAll('button[data-semantic="selection.select"]')[1]!.trigger('click');await flushPromises();expect(signal.aborted).toBe(true);resolve(preview(source('一')));await flushPromises();expect(f.view.get('[aria-label="所选代码成果"] h3').text()).toContain('开发阶段 二');f.owner.patch({open:true});f.owner.updateRevision(3);await flushPromises();expect(f.view.find('[aria-label="所选代码成果"]').exists()).toBe(false);expect(workflowPublication.sources).toHaveBeenLastCalledWith('req',3,'',expect.any(AbortSignal));f.dispose()
 })
 it('响应身份不匹配时拒绝显示，重试仍读取所选来源',async()=>{
  const value=source('一');vi.mocked(workflowPublication.sources).mockResolvedValue({items:[value],nextCursor:null});vi.mocked(workflowPublication.preview).mockResolvedValueOnce({...preview(value),requirementId:'other'}).mockResolvedValueOnce(preview(value));const f=await render();await button(f,'ui.refresh').trigger('click');await flushPromises();await button(f,'selection.select').trigger('click');await flushPromises();expect(f.view.find('.workflow-code-changes').exists()).toBe(false);await f.owner.choose(value);await flushPromises();vi.mocked(workflowRuns.changes).mockResolvedValue({items:[],nextCursor:null});await f.view.get('.workflow-code-changes button').trigger('click');await flushPromises();expect(vi.mocked(workflowRuns.changes).mock.calls.at(-1)![2]).toBe(value.attemptId);f.dispose()
 })
 it('关闭面板终止请求且晚到数据不会再次打开面板',async()=>{
  let resolve!:(page:{items:WorkflowPublicationSource[];nextCursor:null})=>void;vi.mocked(workflowPublication.sources).mockReturnValue(new Promise(done=>{resolve=done}));const f=await render();await button(f,'ui.refresh').trigger('click');const signal=vi.mocked(workflowPublication.sources).mock.calls[0]![3]!;await button(f,'ui.close').trigger('click');expect(signal.aborted).toBe(true);resolve({items:[source('一')],nextCursor:null});await flushPromises();expect(f.view.text()).not.toContain('开发阶段 一');f.dispose()
 })
 it('回填确认锁定来源、分页和收起，写操作结束后恢复操作',async()=>{
  const selected=source('一');vi.mocked(workflowPublication.sources).mockResolvedValue({items:[selected],nextCursor:'next'});vi.mocked(workflowPublication.preview).mockResolvedValue(preview(selected));vi.mocked(workflowPublication.status).mockResolvedValue(null);const f=await render();await button(f,'ui.refresh').trigger('click');await flushPromises();await button(f,'selection.select').trigger('click');await flushPromises();await f.owner.readStatus('commit');await flushPromises();await button(f,'workflow.commit').trigger('click');await f.view.get('input').setValue('保存成果')
  let resolve!:(value:Awaited<ReturnType<typeof workflowPublication.confirm>>)=>void;vi.mocked(workflowPublication.confirm).mockReturnValue(new Promise(done=>{resolve=done}));await f.view.get('form').trigger('submit');await flushPromises();for(const key of ['ui.close','ui.refresh','ui.loadMore','selection.select'])expect(button(f,key).attributes('disabled')).toBeDefined();expect(f.owner.canLeave().kind).toBe('BLOCK');const record={requirementId:'req',state:'COMMITTED' as const,version:1,nodeTitle:'开发',outputTitle:'代码',attemptState:'SUCCEEDED',branch:'results',message:'保存成果',commit:'a'.repeat(40),createdAt:''};vi.mocked(workflowPublication.status).mockResolvedValue(record);resolve({...record,state:'CONFIRMED'});await flushPromises();expect(button(f,'ui.loadMore').attributes('disabled')).toBeUndefined();expect(f.owner.canLeave().kind).toBe('ALLOW');f.dispose()
 })
 it('锁定时不读取，空成果说明与加载失败可恢复',async()=>{
  const locked=await render();locked.owner.patch({loading:true});await flushPromises();await button(locked,'ui.refresh').trigger('click');expect(workflowPublication.sources).not.toHaveBeenCalled();locked.dispose();vi.mocked(workflowPublication.sources).mockRejectedValueOnce(new Error('unavailable')).mockResolvedValueOnce({items:[],nextCursor:null});const f=await render();await button(f,'ui.refresh').trigger('click');await flushPromises();await f.owner.retryList();await flushPromises();expect(f.owner.getSnapshot().rows).toHaveLength(0);expect(f.owner.getSnapshot().error).toBe('');f.dispose()
 })
})
