import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, resolveDialog } from '@/pages/w6-tests/workflow/react-test-root'
import { mountPageApplication } from '@/pages/w6-tests/workflow/application-test-root'
import { workflowApi } from '@/api/workflow'
import { WorkflowLibraryPage } from '@/pages/w2/workflow/WorkflowLibraryPage'
import { summary } from '@/components/workflow/workflowTestFixtures'
vi.mock('@/api/workflow', () => ({ workflowApi: { list: vi.fn(), copy: vi.fn(), archive: vi.fn() } }))
const api = vi.mocked(workflowApi)
let wrapper: Awaited<ReturnType<typeof mountPageApplication>> | undefined
beforeEach(() => { vi.resetAllMocks(); api.list.mockResolvedValue({ items: [summary()], nextCursor: 'page-2' }) })
afterEach(() => { wrapper?.unmount(); wrapper = undefined; vi.restoreAllMocks() })
async function render() { wrapper=await mountPageApplication(WorkflowLibraryPage,'/workflows',['/workflows','/workflows/:id','/requirements/new']);return wrapper }
const keys:Record<string,string>={'复制':'workflow.copyDefinition','删除':'workflow.deleteDefinition','加载更多流程':'ui.loadMore','重试原操作':'ui.retry'}
const button=(label:string)=>wrapper!.get(`button[data-semantic="${keys[label]}"]`)
const openActions=()=>{const trigger=wrapper!.get('button[data-semantic="selection.select"]');trigger.element.focus();return trigger.trigger('click')}
async function filter(label:string) { await wrapper!.get('[role=combobox]').trigger('mousedown');await flushPromises();await wrapper!.findAll('.ant-select-item-option').find(item=>item.text()===label)!.trigger('click');await flushPromises() }
describe('workflow library',()=>{
 it('reveals secondary actions on demand and returns keyboard focus on Escape',async()=>{
  await render();expect(wrapper!.find('aside:not([hidden])').exists()).toBe(false);await openActions();expect(wrapper!.get('button[data-semantic="selection.select"]').attributes('aria-pressed')).toBe('true');expect(button('复制').exists()).toBe(true);button('复制').element.focus();await button('复制').trigger('keydown',{key:'Escape'});expect(wrapper!.find('aside:not([hidden])').exists()).toBe(false);expect(document.activeElement).toBe(wrapper!.get('button[data-semantic="selection.select"]').element)
  await openActions();document.body.dispatchEvent(new Event('pointerdown',{bubbles:true}));await flushPromises();expect(wrapper!.find('aside:not([hidden])').exists()).toBe(false)
 })
 it('passes cursor and server filters, resetting pagination for a new search',async()=>{
  await render();api.list.mockResolvedValueOnce({items:[summary({id:'next',title:'第二页流程'})]});await button('加载更多流程').trigger('click');await flushPromises();expect(api.list).toHaveBeenLastCalledWith('','ALL','page-2');expect(wrapper!.findAll('button[data-semantic="selection.select"]')).toHaveLength(2);await wrapper!.get('input[aria-label="搜索流程"]').setValue('第二');await wrapper!.get('form').trigger('submit');await flushPromises();expect(api.list).toHaveBeenLastCalledWith('第二','ALL','');await filter('程序内置');expect(api.list).toHaveBeenLastCalledWith('第二','BUILTIN','')
 })
 it('reuses the copy key after an uncertain response, including after changing filters',async()=>{
  await render();api.copy.mockRejectedValueOnce(new Error('timeout')).mockResolvedValue({id:'copy',revision:1,version:0,layoutVersion:0,state:'ACTIVE'});await openActions();await button('复制').trigger('click');await flushPromises();await wrapper!.get('button[data-semantic="selection.select"]').trigger('keydown',{key:'Escape'});expect(wrapper!.get('[role="alert"]').text()).toContain('结果尚未确认');await filter('我的流程');expect(button('重试原操作').exists()).toBe(true);await button('重试原操作').trigger('click');await flushPromises();expect(api.copy.mock.calls[0]).toEqual(api.copy.mock.calls[1])
 })
 it('requires confirmation and sends the displayed version for deletion',async()=>{
  await render();await openActions();await button('删除').trigger('click');await resolveDialog(wrapper!,false);expect(api.archive).not.toHaveBeenCalled();api.archive.mockResolvedValue({id:'example',revision:2,version:4,layoutVersion:4,state:'ARCHIVED'});await button('删除').trigger('click');await resolveDialog(wrapper!,true);await flushPromises();expect(api.archive).toHaveBeenCalledWith('example',{expectedVersion:3,requestKey:expect.any(String)})
 })
 it('keeps built-in flows read-only while allowing copies',async()=>{
  api.list.mockResolvedValue({items:[summary({builtin:true})]});await render();await openActions();expect(wrapper!.get('aside:not([hidden])').text()).toContain('查看流程');expect(wrapper!.get('aside:not([hidden])').text()).not.toContain('删除');expect(button('复制').attributes('disabled')).toBeUndefined()
 })
})
