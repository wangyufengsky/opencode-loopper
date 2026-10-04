import {afterEach,beforeEach,describe,expect,it,vi} from 'vitest'
import {mount,flushPromises} from '@/pages/w6-tests/knowledge-ppt-template/react-test-root'
import {PptListPage} from '@/pages/w2/secondary/PptListPage'
import {pageProps,message} from '@/pages/w3/ppt/testFixture'
import {pptApi} from '@/api/ppt'
import {pptDocument} from '@/components/ppt/pptTestFixtures'
beforeEach(()=>{sessionStorage.clear();vi.spyOn(pptApi,'list').mockResolvedValue({items:[],facets:{}});vi.spyOn(pptApi,'create').mockResolvedValue({...pptDocument('created'),phase:'BRIEFING',revision:0});vi.spyOn(pptApi,'get').mockResolvedValue({...pptDocument('created'),phase:'BRIEFING',revision:0});vi.spyOn(pptApi,'send').mockImplementation(async(_id,input)=>message(input.idempotencyKey,input.text,input.scope,input.expectedRevision))})
afterEach(()=>vi.restoreAllMocks())
describe('PPT first message',()=>{it('creates the work and starts discussion without creating a generation authorization',async()=>{const props=pageProps().props,generate=vi.spyOn(pptApi,'generate'),view=mount(PptListPage,{props});await flushPromises();await view.get('#ppt-first-prompt').setValue('制作一份介绍项目框架的 PPT');await view.get('.ppt-prompt-card').trigger('submit');await flushPromises();expect(pptApi.send).toHaveBeenCalledWith('created',expect.objectContaining({text:'制作一份介绍项目框架的 PPT',expectedRevision:0,scope:{kind:'DOCUMENT'},idempotencyKey:expect.any(String)}));expect(generate).not.toHaveBeenCalled();expect(props.navigation.goAccepted).toHaveBeenCalledWith('/ppt/created',expect.any(Object))})})
