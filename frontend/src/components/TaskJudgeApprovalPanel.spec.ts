import {describe,expect,it,vi} from 'vitest'
import {api} from '@/api/client'
import {TaskJudgeApprovalPanel} from '@/pages/w4/actions/judge'
import {mountPanel,taskFixture} from '@/pages/w6-tests/ordinary/task'
import {action,confirm,flushPromises} from '@/pages/w6-tests/ordinary/actions'
describe('human review decision',()=>{
 it('requires confirmation, sends the displayed generation, and reloads after acceptance',async()=>{const value={available:true,approved:false,taskVersion:7,cycleId:'cycle',cycleVersion:0,reviewBatchId:'batch'};vi.spyOn(api,'getJudgeApproval').mockResolvedValue(value);const send=vi.spyOn(api,'approveJudges').mockImplementation(async()=>{vi.mocked(api.getJudgeApproval).mockResolvedValue({...value,available:false,approved:true});return{...value,available:false,approved:true}});const p=mountPanel(TaskJudgeApprovalPanel,taskFixture('task',{status:'WAITING_INPUT',version:7}),{});await flushPromises();expect(send).not.toHaveBeenCalled();await action('judge.approve');expect(send).not.toHaveBeenCalled();await confirm('judge.approve');expect(send).toHaveBeenCalledWith('task',{expectedTaskVersion:7,cycleId:'cycle',expectedCycleVersion:0,reviewBatchId:'batch'});expect(p.view.text()).toContain('已由人工认定通过');expect(p.parent.refresh).toHaveBeenCalledOnce()})
 it('does not approve after the user cancels confirmation',async()=>{vi.spyOn(api,'getJudgeApproval').mockResolvedValue({available:true,approved:false,taskVersion:7,cycleId:'cycle',cycleVersion:0,reviewBatchId:'batch'});const send=vi.spyOn(api,'approveJudges');mountPanel(TaskJudgeApprovalPanel,taskFixture('task',{status:'WAITING_INPUT'}),{});await flushPromises();await action('judge.approve');await confirm('ui.stay');expect(send).not.toHaveBeenCalled()})
})
