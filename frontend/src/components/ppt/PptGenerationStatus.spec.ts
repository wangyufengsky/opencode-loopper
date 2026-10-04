import { beforeEach, afterEach, describe, expect, it, vi } from 'vitest'
import { mount, flushPromises } from '@/pages/w6-tests/knowledge-ppt-template/react-test-root'
import { PptStudioPage } from '@/pages/w3/ppt/PptStudioPage'
import { pageProps, studioFixture } from '@/pages/w3/ppt/testFixture'
import { pptApi } from '@/api/ppt'
import { pptAgent, pptGeneration } from './pptTestFixtures'
import { semanticName } from '@/foundation/semanticRegistry'
let f:ReturnType<typeof studioFixture>,p:ReturnType<typeof pageProps>
beforeEach(()=>{sessionStorage.clear();vi.useFakeTimers({toFake:['setTimeout','clearTimeout']});f=studioFixture();f.setDocument({phase:'BRIEFING'});f.setDeck({title:'空草案',width:1280,height:720,theme:'minimal',slides:[]});p=pageProps()})
afterEach(()=>{p.retire();vi.useRealTimers();vi.restoreAllMocks()})
async function open(){const wrapper=mount(PptStudioPage,{props:p.props});await flushPromises();return wrapper}
async function update(){f.events.at(-1)?.onmessage?.(new MessageEvent('message'));await vi.advanceTimersByTimeAsync(180);await flushPromises()}
describe('PPT requirements progress',()=>{
 it('shows discussion and confirmation before design and follows the server confirmation state',async()=>{
 vi.mocked(pptApi.generation).mockResolvedValue({...pptGeneration(),detail:''});vi.mocked(pptApi.agent).mockResolvedValue({...pptAgent(),state:'RUNNING',requirementsState:'CLARIFYING'});const wrapper=await open();expect(wrapper.get('h2').text()).toBe('先聊清你的想法');expect(wrapper.find('.ppt-generation-steps').exists()).toBe(false);
 vi.mocked(pptApi.agent).mockResolvedValue({...pptAgent(),state:'WAITING_INPUT',requirementsState:'AWAITING_CONFIRMATION'});await update();expect(wrapper.get('h2').text()).toBe('请确认制作需求');expect(wrapper.text()).toContain('才会开始第一轮设计');
 vi.mocked(pptApi.agent).mockResolvedValue({...pptAgent(),state:'RUNNING',requirementsState:'CONFIRMED'});await update();expect(wrapper.get('h2').text()).toBe('正在构思内容');expect(wrapper.find('.ppt-generation-steps').exists()).toBe(true)
 });
 it('preserves a stopped workflow and resume action while requirements are unconfirmed',async()=>{vi.mocked(pptApi.generation).mockResolvedValue(pptGeneration('STOPPED'));vi.mocked(pptApi.agent).mockResolvedValue({...pptAgent(),state:'STOPPED',requirementsState:'CLARIFYING'});const wrapper=await open();expect(wrapper.get('h2').text()).toBe('已暂停');expect(wrapper.get(`button[aria-label="${semanticName('ppt.resume','按当前要求继续')}"]`).attributes('aria-label')).toContain('按当前要求继续')});
 it('describes the pre-generation freeform discussion and the explicit execution action',async()=>{vi.mocked(pptApi.agent).mockResolvedValue({...pptAgent(),state:'COMPLETED'});const wrapper=await open();expect(wrapper.get('h2').text()).toBe('需求讨论中');expect(wrapper.text()).toContain('确认需求并执行')})
})
