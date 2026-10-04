import {beforeEach,afterEach,describe,expect,it,vi} from 'vitest'
import {flushPromises,mount} from '@/pages/w6-tests/knowledge-ppt-template/react-test-root'
import {pptApi} from '@/api/ppt'
import type {PptKnowledge} from '@/types/ppt'
import {PptStudioPage} from '@/pages/w3/ppt/PptStudioPage'
import {studioFixture,pageProps} from '@/pages/w3/ppt/testFixture'
const knowledge:PptKnowledge={project:{id:'project',name:'支付平台'},sources:[{id:'code',kind:'CODE',name:'项目代码',state:'READY',detail:'',version:0},{id:'documents',kind:'DIRECTORY',name:'汇报资料',state:'FAILED',detail:'目录无法读取，请检查来源配置。',version:0}],detail:''}
let p:ReturnType<typeof pageProps>
beforeEach(()=>{sessionStorage.clear();p=pageProps()})
afterEach(()=>{p.retire();vi.restoreAllMocks()})
async function open(id:string){p.props.route={path:'/ppt/'+id,fullPath:'/ppt/'+id,params:{id},query:{}};const wrapper=mount(PptStudioPage,{props:p.props});await flushPromises();await wrapper.findAll('[data-semantic="ui.open"]').find(button=>button.attributes('aria-label')?.includes('作品菜单'))!.trigger('click');await wrapper.get('[data-semantic="ppt.openSources"]').trigger('click');await flushPromises();return wrapper}
describe('PPT frozen project source summary',()=>{
 it('shows usable sources and individual source failures without hiding the rest of the material panel',async()=>{const f=studioFixture('doc');f.setDocument({projectId:'project'});const read=vi.spyOn(pptApi,'knowledge').mockResolvedValue(knowledge);const wrapper=await open('doc');expect(wrapper.text()).toContain('支付平台');expect(wrapper.text()).toContain('可用');expect(wrapper.text()).toContain('目录无法读取');expect(wrapper.get('input[accept=".md,.docx,.xlsx,.pptx,.pdf"]').exists()).toBe(true);expect(read).toHaveBeenCalledWith('doc')});
 it('ignores an earlier document response and recovers a failed source read explicitly',async()=>{const f=studioFixture('old');f.setDocument({projectId:'project'});let resolve!:(value:PptKnowledge)=>void;const read=vi.spyOn(pptApi,'knowledge').mockReturnValueOnce(new Promise(done=>{resolve=done})).mockRejectedValueOnce(new Error('offline'));const wrapper=await open('old');await wrapper.setProps({...p.props,route:{path:'/ppt/current',fullPath:'/ppt/current',params:{id:'current'},query:{}}});await wrapper.findAll('[data-semantic="ui.open"]').find(button=>button.attributes('aria-label')?.includes('作品菜单'))!.trigger('click');await wrapper.get('[data-semantic="ppt.openSources"]').trigger('click');await flushPromises();resolve(knowledge);await flushPromises();expect(wrapper.text()).not.toContain('支付平台');expect(wrapper.get('[role="alert"]').text()).toContain('内容暂时无法读取，请重试。');read.mockResolvedValue({project:{id:'legacy',name:'历史项目'},sources:[],detail:'此作品尚未开放项目来源，请新建作品并选择项目'});await wrapper.findAll('[data-semantic="ui.refresh"]').find(button=>button.attributes('aria-label')?.includes('关联项目来源'))!.trigger('click');await flushPromises();expect(wrapper.text()).toContain('此作品尚未开放项目来源')})
})
