import {afterEach,vi} from 'vitest'
import {api} from '@/api/client'
import {TemplateTasksPage} from '@/pages/w3/templates/catalog/TemplateTasksPage'
import {catalogFixture} from '@/pages/w3/templates/catalog/fixtures'
import {pageProps} from '@/pages/w3/ppt/testFixture'
import type {TemplateCatalogController} from '@/pages/w3/templates/catalog/controller'
import {mount,flushPromises} from './react-test-root'
const contexts:ReturnType<typeof pageProps>[]=[]
afterEach(()=>{contexts.splice(0).forEach(p=>p.retire())})
export function catalogueTransport(){vi.spyOn(api,'templateCatalog').mockResolvedValue(catalogFixture);vi.spyOn(api,'templateProjects').mockResolvedValue({items:[],facets:{}});vi.spyOn(api,'templateProject').mockResolvedValue({id:'p1',name:'项目',createdAt:'now',documentPath:'docs'});vi.spyOn(api,'templateBranches').mockResolvedValue({page:{items:[{id:'main',label:'main',ref:'main',remote:null}],nextCursor:null,facets:{}},defaultBranch:{id:'main',label:'main',ref:'main',remote:null},defaultBranchId:'main',remoteAvailable:true})}
export async function cataloguePage(templateId:string){const p=pageProps();contexts.push(p);p.props.route={path:'/template-tasks',fullPath:'/template-tasks?projectId=p1',query:{projectId:'p1'},params:{}};let owner!:TemplateCatalogController;const retain=p.props.lifecycle.retain;p.props.lifecycle.retain=(key,dispose)=>{retain(key,dispose);if((key as TemplateCatalogController).identity?.domain==='template-catalog')owner=key as TemplateCatalogController};const view=mount(TemplateTasksPage,{props:p.props});await flushPromises();if(owner.getSnapshot().selected!==templateId){await view.findAll('button').find(b=>b.attributes('aria-label')===owner.templates().find(t=>t.id===templateId)?.title)!.trigger('click');await flushPromises()}return {view,owner,p}}
