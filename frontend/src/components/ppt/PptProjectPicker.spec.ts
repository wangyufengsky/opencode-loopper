import {beforeEach,afterEach,describe,expect,it,vi} from 'vitest'
import {flushPromises,mount} from '@/pages/w6-tests/knowledge-ppt-template/react-test-root'
import {pptApi} from '@/api/ppt'
import {PptListPage} from '@/pages/w2/secondary/PptListPage'
import {pageProps} from '@/pages/w3/ppt/testFixture'
import type {createPptCreation} from '@/pages/w2/secondary/pptCreation'
let p:ReturnType<typeof pageProps>,owner:ReturnType<typeof createPptCreation>
beforeEach(()=>{sessionStorage.clear();p=pageProps();p.props.route={path:'/ppt',fullPath:'/ppt',query:{},params:{}};const retain=p.props.lifecycle.retain;p.props.lifecycle.retain=(key,dispose)=>{retain(key,dispose);owner=key as typeof owner};vi.spyOn(pptApi,'list').mockResolvedValue({items:[],facets:{}})})
afterEach(()=>{p.retire();vi.restoreAllMocks()})
describe('PPT project selection',()=>{
 it('loads choices on demand, follows the server cursor and selects only the requested project',async()=>{const choices=vi.spyOn(pptApi,'projects').mockResolvedValueOnce({items:[{id:'one',name:'支付平台'}],nextCursor:'next',facets:{}}).mockResolvedValueOnce({items:[{id:'two',name:'清算平台'}],facets:{}});const wrapper=mount(PptListPage,{props:p.props});expect(choices).not.toHaveBeenCalled();await wrapper.get('[data-semantic="project.select"]').trigger('click');await flushPromises();await wrapper.get('[data-semantic="ui.loadMore"]').trigger('click');await flushPromises();expect(choices).toHaveBeenLastCalledWith('','next');await wrapper.findAll('button').find(b=>b.text()==='清算平台')!.trigger('click');expect(owner.getSnapshot().project).toEqual({id:'two',name:'清算平台'});expect(wrapper.find('[aria-label="选择关联项目"]').exists()).toBe(false)});
 it('keeps restored association visible and allows unassociated work when catalog loading fails',async()=>{vi.spyOn(pptApi,'projects').mockRejectedValue(new Error('offline'));const wrapper=mount(PptListPage,{props:p.props});owner.setProject({id:'one',name:'支付平台'});await flushPromises();expect(wrapper.text()).toContain('支付平台');await wrapper.get('[data-semantic="project.select"]').trigger('click');await flushPromises();expect(wrapper.get('[role="alert"]').exists()).toBe(true);await wrapper.findAll('button').find(b=>b.text()==='不关联项目')!.trigger('click');expect(owner.getSnapshot().project).toBeNull();owner.setPrompt('原要求');vi.spyOn(pptApi,'create').mockRejectedValue(new Error('lost'));await owner.submit();await flushPromises();expect(wrapper.get('[data-semantic="project.select"]').attributes('disabled')).toBeDefined()})
})
