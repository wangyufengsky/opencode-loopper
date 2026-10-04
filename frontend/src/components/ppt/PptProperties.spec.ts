import { afterEach,beforeEach,describe,expect,it,vi } from 'vitest'
import { createPptStudioController } from '@/pages/w3/ppt/controller'
import { studioFixture } from '@/pages/w3/ppt/testFixture'
import { PropertiesProjection } from '@/pages/w6-tests/knowledge-ppt-template/ppt-react'
import { flushPromises,mount } from '@/pages/w6-tests/knowledge-ppt-template/react-test-root'
import { pptDeck,pptText } from './pptTestFixtures'
let owner:ReturnType<typeof createPptStudioController>
beforeEach(()=>{sessionStorage.clear();vi.useFakeTimers()})
afterEach(()=>{owner?.retire(true);vi.useRealTimers();vi.restoreAllMocks()})
describe('PPT property autosave',()=>{
 it('debounces object edits and pauses when their baseline is no longer current',async()=>{const f=studioFixture();owner=createPptStudioController('doc');await owner.start();const w=mount(PropertiesProjection,{props:{owner}});await w.get('textarea').setValue('更简洁的标题');await vi.advanceTimersByTimeAsync(900);await flushPromises();expect(f.operations.mock.calls[0]).toEqual(['doc',3,[{op:'update_element',slideId:'slide-1',elementId:'text-1',patch:expect.objectContaining({text:'更简洁的标题'})}],expect.any(String)]);const key='loopper.ppt.element.doc.text-1';await w.get('textarea').setValue('继续保留本地修改');const deck=pptDeck();deck.slides[0]!.elements[0]={...pptText(),text:'其他修改'};f.setDeck(deck);f.setDocument({revision:5});await owner.refresh();await flushPromises();await vi.advanceTimersByTimeAsync(1500);expect(f.operations).toHaveBeenCalledTimes(1);expect(w.text()).toContain('你的输入仍保留');expect(owner.getSnapshot().drafts[key]?.value.text).toBe('继续保留本地修改')})
})
