import {beforeEach,afterEach,describe,expect,it,vi} from 'vitest'
import {api} from '@/api/client'
import {flushPromises} from '@/pages/w6-tests/knowledge-ppt-template/react-test-root'
import {catalogueTransport,cataloguePage} from '@/pages/w6-tests/knowledge-ppt-template/catalog-test'
const files=[new File(['需求'],'很长的需求文档名称.md'),new File(['PDF'],'验收.pdf')]
beforeEach(()=>{sessionStorage.clear();catalogueTransport()});afterEach(()=>vi.restoreAllMocks())
async function select(view:Awaited<ReturnType<typeof cataloguePage>>['view'],value:File[]){const input=view.get('input[type="file"]');Object.defineProperty(input.element,'files',{configurable:true,value});await input.trigger('change');await flushPromises()}
describe('DocumentFilePicker',()=>{
 it('shows the full selected filenames and removes only the requested file',async()=>{const {view,owner}=await cataloguePage('REQUIREMENT_CODE_REVIEW');await select(view,files);expect(owner.getSnapshot().files).toHaveLength(2);expect(view.text()).toContain(files[0]!.name);await view.get('[data-semantic="ui.delete"][aria-label="删除：验收.pdf"]').trigger('click');expect(owner.originalFiles()).toEqual(files.slice(0,1));expect(owner.originalFiles()[0]).toBe(files[0])});
 it('keeps the current list when selection is cancelled and accepts a replacement selection',async()=>{const {view,owner}=await cataloguePage('REQUIREMENT_CODE_REVIEW');await select(view,files);await select(view,[]);expect(owner.originalFiles()).toEqual(files);await select(view,[files[1]!]);expect(owner.originalFiles()).toEqual([files[1]]);expect(owner.originalFiles()[0]).toBe(files[1])});
 it('disables choosing and removing files while a submission is in progress',async()=>{const {view}=await cataloguePage('REQUIREMENT_CODE_REVIEW');await select(view,files);vi.spyOn(api,'createDocumentTemplate').mockImplementation(()=>new Promise(()=>{}));await view.get('[data-semantic="template.create"]').trigger('click');expect(view.get('input[type="file"]').attributes('disabled')).toBeDefined();expect(view.findAll('[data-semantic="ui.delete"]').every(button=>button.attributes('disabled')!==undefined)).toBe(true)})
})
