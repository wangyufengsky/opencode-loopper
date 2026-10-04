import { afterEach,beforeEach,describe,expect,it,vi } from 'vitest'
import { flushPromises } from '@/pages/w6-tests/workflow/react-test-root'
import { mountPageApplication } from '@/pages/w6-tests/workflow/application-test-root'
import { workflowRuns } from '@/api/workflowRuns'
import type { WorkflowRequirementSummary } from '@/types/domain'
import { RequirementListPage } from '@/pages/w2/workflow/RequirementListPage'
vi.mock('@/api/workflowRuns',()=>({workflowRuns:{list:vi.fn(),projects:vi.fn(),project:vi.fn()}}))
const api=vi.mocked(workflowRuns);let wrapper:Awaited<ReturnType<typeof mountPageApplication>>|undefined
const summary=(overrides:Partial<WorkflowRequirementSummary>={}):WorkflowRequirementSummary=>({id:'req',projectId:'project',title:'退款能力',state:'PLANNING',headRevision:2,version:1,createdAt:'2026-10-01T10:00:00Z',updatedAt:'2026-10-02T10:00:00Z',...overrides})
beforeEach(()=>{vi.resetAllMocks();api.list.mockResolvedValue({items:[summary()],nextCursor:'next-page'});api.projects.mockResolvedValue({items:[{id:'selected-project',name:'订单服务',createdAt:''}],facets:{},nextCursor:undefined})})
afterEach(()=>{wrapper?.unmount();wrapper=undefined})
async function render(){wrapper=await mountPageApplication(RequirementListPage,'/requirements',['/requirements','/requirements/:id','/workflows','/requirements/new']);return wrapper}
describe('requirement list',()=>{
 it('browses paginated requirements, resets the cursor for a project, and preserves it when creating',async()=>{
  const app=await render();api.list.mockResolvedValueOnce({items:[summary({id:'second',title:'新接口',state:'COMPLETED'})]});await wrapper!.get('button[data-semantic="ui.loadMore"]').trigger('click');await flushPromises();expect(api.list).toHaveBeenLastCalledWith(undefined,'next-page');expect(wrapper!.findAll('button[data-semantic="selection.select"]')).toHaveLength(2);expect(wrapper!.findAll('.w2-record')[1]!.text()).toContain('已完成');api.list.mockResolvedValueOnce({items:[summary({projectId:'selected-project'})]});await wrapper!.get('[role=combobox]').trigger('mousedown');await flushPromises();await wrapper!.get('.ant-select-item-option').trigger('click');await flushPromises();expect(api.list).toHaveBeenLastCalledWith('selected-project','');expect(wrapper!.findAll('button[data-semantic="selection.select"]')).toHaveLength(1);await wrapper!.get('button[data-semantic="selection.select"]').trigger('click');expect(wrapper!.get('a[aria-label="查看：需求画布：退款能力"]').attributes('href')).toBe('/requirements/req');await wrapper!.get('button[data-semantic="workflow.newRequirement"]').trigger('click');await flushPromises();expect(app.router.state.location.pathname+app.router.state.location.search).toBe('/requirements/new?projectId=selected-project')
 })
 it('shows a recoverable loading error separately from the empty state',async()=>{
  api.list.mockRejectedValueOnce(new Error('network'));await render();expect(wrapper!.get('[role="alert"]').text()).toContain('需求任务暂时无法读取');expect(wrapper!.find('.w2-empty').exists()).toBe(false);api.list.mockResolvedValueOnce({items:[]});await wrapper!.get('button[data-semantic="ui.retry"]').trigger('click');await flushPromises();expect(wrapper!.find('[role="alert"]').exists()).toBe(false);expect(wrapper!.get('.w2-empty').text()).toContain('从一个需求开始')
 })
})
