import {beforeEach,afterEach,expect,it,vi} from 'vitest'
import {navigateAcceptedHandoff} from '@/foundation/contracts/receipt'
import {api} from '@/api/client'
import type {SourceTemplatePreview,TemplateTaskDefinition} from '@/types/domain'
import {sourceRun} from '@/pages/w3/templates/catalog/fixtures'
import {catalogFixture} from '@/pages/w3/templates/catalog/fixtures'
import {cataloguePage,catalogueTransport} from '@/pages/w6-tests/knowledge-ppt-template/catalog-test'
import {flushPromises} from '@/pages/w6-tests/knowledge-ppt-template/react-test-root'
const unit:TemplateTaskDefinition={id:'UNIT_TEST_DEVELOPMENT',version:'1',title:'单元测试开发',description:'',contentRepairLimit:0,stages:[],scoringVersion:null,inputs:{documents:false,branch:false,dates:false,extensions:[],maxFiles:0,maxFileMiB:0,maxTotalMiB:0,sourcePath:true,testOutputPath:true}}
const design:TemplateTaskDefinition={...unit,id:'DETAILED_DESIGN_WRITING',title:'详细设计文档生成',inputs:{...unit.inputs!,testOutputPath:false,documentOutputPath:true}}
const preview:SourceTemplatePreview={sourcePath:'src/main',testOutputPath:null,documentPath:null,manifestSha256:'abc',targetCount:2,excludedCount:1,moduleCount:1,truncated:false,files:[{path:'src/main/Service.java',target:true,sizeBytes:10,sha256:'abc',exclusion:null}],testProfile:{manifestSha256:'abc',modules:[{root:'.',framework:'junit',sourcePaths:['src/main/Service.java'],testRoots:['src/test/java'],fixtureRoots:['src/test/resources'],command:['mvn','test']}]},configurationProblem:null}
beforeEach(()=>{sessionStorage.clear();catalogueTransport();vi.mocked(api.templateCatalog).mockResolvedValue({...catalogFixture,templates:[unit,design]})})
afterEach(()=>vi.restoreAllMocks())
it('requires a current preflight and sends only capability-supported output fields',async()=>{
 vi.spyOn(api,'sourcePreview').mockResolvedValue(preview);const run=sourceRun('new-run');const create=vi.spyOn(api,'createSourceTemplate').mockResolvedValue(run);vi.spyOn(api,'sourceTemplate').mockResolvedValue(run)
 const {view,owner}=await cataloguePage(unit.id);await view.get('input[aria-label="源码路径"]').setValue('src/main');expect(await owner.submit()).toBeUndefined();expect(create).not.toHaveBeenCalled()
 await view.get('button[data-semantic="template.checkScope"]').trigger('click');await flushPromises();expect(view.get('[aria-label="处理范围"]').text()).toContain('目标 2');expect(view.text()).toContain('src/test/java')
 expect(await owner.submit()).toBe('new-run');expect(create).toHaveBeenCalledWith(expect.objectContaining({templateId:unit.id,projectId:'p1',sourcePath:'src/main'}));expect(create.mock.calls[0]![0]).not.toHaveProperty('documentPath')
 // Retire the completed handoff identity before editing a distinct later draft.
 const permit=owner.currentCreation().prepareHandoff()!;await navigateAcceptedHandoff(permit,permit.destination,async()=>true);owner.currentCreation().completeHandoff();await flushPromises();await view.get('textarea[aria-label="补充要求"]').setValue('优先异常边界');expect(owner.getSnapshot().preview).toBeUndefined();await owner.submit();expect(create).toHaveBeenCalledTimes(1);view.unmount()
})
it('ignores late preflight and folder selection after switching project and template',async()=>{
 let resolve!:(value:SourceTemplatePreview)=>void;vi.spyOn(api,'sourcePreview').mockImplementation(()=>new Promise(done=>{resolve=done}));let choose!:(value:{selected:boolean;path:string})=>void;vi.spyOn(api,'pickProjectDirectory').mockImplementation(()=>new Promise(done=>{choose=done}))
 const {view,owner}=await cataloguePage(unit.id);await view.get('input[aria-label="源码路径"]').setValue('old');await view.get('button[data-semantic="template.checkScope"]').trigger('click');void owner.pick('sourcePath');
 await owner.project('p2');await owner.select(design.id,true);owner.change('documentPath','new/docs');resolve(preview);choose({selected:true,path:'/old/project'});await flushPromises()
 expect(view.find('[aria-label="处理范围"]').exists()).toBe(false);expect(view.get<HTMLInputElement>('input[aria-label="源码路径"]').element.value).toBe('');expect(view.get<HTMLInputElement>('input[aria-label="文档生成路径"]').element.value).toBe('new/docs');expect(view.find('input[aria-label="测试输出路径"]').exists()).toBe(false);view.unmount()
})
it('shows configuration failures and preserves input when the picker is cancelled',async()=>{
 vi.spyOn(api,'sourcePreview').mockResolvedValue({...preview,configurationProblem:'未找到 JUnit 配置，请先处理配置',testProfile:null});vi.spyOn(api,'pickProjectDirectory').mockResolvedValue({selected:false});const create=vi.spyOn(api,'createSourceTemplate');const {view,owner}=await cataloguePage(unit.id)
 await view.get('input[aria-label="源码路径"]').setValue('src/main');await view.findAll('button').find(b=>b.attributes('data-semantic')==='project.chooseDirectory'&&b.attributes('aria-label')?.includes('源码路径'))!.trigger('click');await flushPromises();expect(view.get<HTMLInputElement>('input[aria-label="源码路径"]').element.value).toBe('src/main')
 await view.get('button[data-semantic="template.checkScope"]').trigger('click');await flushPromises();expect(view.text()).toContain('未找到 JUnit 配置');await owner.submit();expect(create).not.toHaveBeenCalled();view.unmount()
})
it('renders a picker failure without the full Element Plus plugin and keeps the typed path',async()=>{
 vi.spyOn(api,'pickProjectDirectory').mockRejectedValue(new Error('文件夹选择失败，请手动填写路径'));const {view}=await cataloguePage(unit.id);await view.get('input[aria-label="源码路径"]').setValue('src/main');await view.findAll('button').find(b=>b.attributes('data-semantic')==='project.chooseDirectory'&&b.attributes('aria-label')?.includes('源码路径'))!.trigger('click');await flushPromises();expect(view.get('[role="alert"]').text()).toContain('文件夹选择失败，请手动填写路径');expect(view.get<HTMLInputElement>('input[aria-label="源码路径"]').element.value).toBe('src/main');view.unmount()
})
