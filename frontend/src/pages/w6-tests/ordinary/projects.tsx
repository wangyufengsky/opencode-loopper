import {afterEach} from 'vitest'
import {ProjectsPage} from '@/pages/w2/core/ProjectsPage'
import {coreFixture,projectFixture} from '@/pages/w2/core/coreTestHelpers'
import {mount,flushPromises} from './render'
import {action} from './actions'
import type {Project} from '@/types/domain'
const disposers:(()=>void)[]=[]
afterEach(()=>disposers.splice(0).forEach(fn=>fn()))
export function projectPage(projects:Project[]=[{...projectFixture,id:'project-1',name:'Example',rootPath:'/tmp/example',taskCount:0,openDesignerSessionCount:0}]){const f=coreFixture({projects}),view=mount(ProjectsPage,{props:f.props});disposers.push(f.dispose);return{...f,view,async select(index=0){await view.findAll('[data-semantic="selection.select"]')[index]!.trigger('click');await flushPromises()},async open(key:string){await action(key)}}}
