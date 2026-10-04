import {createElement} from 'react'
import {describe,expect,it,vi} from 'vitest'
import {fireEvent,act} from '@testing-library/react'
import {application,flushPromises} from '@/pages/w6-tests/ordinary/application'
import {applicationRoutes} from '@/router'
import {HomePage} from '@/pages/w2/secondary/HomePage'
import {api} from '@/api/client'
import {knowledgeApi} from '@/api/knowledge'
const reads=()=>{vi.spyOn(api,'getProjects').mockResolvedValue([]);vi.spyOn(knowledgeApi,'history').mockResolvedValue({items:[],nextCursor:undefined,facets:{}})}
describe('主页导航',()=>{
 it('所有工作区与系统入口使用真实路由，点击后可通过品牌返回主页',async()=>{reads();const destinations=['/projects','/requirements','/requirements/new','/workflows','/tasks','/inbox','/designs','/insights','/template-tasks','/runtime','/tools','/settings'];const p=await application({shell:true,routes:[{path:'/',Component:HomePage},...destinations.map(path=>({path,element:createElement('main',null,'目标页面')}))]});for(const path of destinations){expect(applicationRoutes).toContain(path);const target=path==='/requirements/new'?p.element.querySelector('[data-semantic="workflow.newRequirement"]'):p.element.querySelector(`main a[href="${path}"]`);expect(target).not.toBeNull();await act(async()=>fireEvent.click(target!));await flushPromises();expect(p.router.state.location.pathname).toBe(path);expect(p.element.querySelector('a.nav-item[href="/"]')?.classList.contains('router-link-active')).toBe(false);await act(async()=>fireEvent.click(p.element.querySelector('a.brand')!));await flushPromises();expect(p.router.state.location.pathname).toBe('/');expect(p.element.querySelector('a.nav-item[href="/"]')?.classList.contains('router-link-active')).toBe(true)}})
 it('根路径与未知地址进入主页，同时保留任务深层链接',async()=>{reads();const p=await application({routes:[{path:'/',Component:HomePage},{path:'*'},{path:'/tasks/:id/recovery',element:createElement('main')},{path:'/tasks/:id/design',element:createElement('main')}]});expect(p.router.state.location.pathname).toBe('/');expect(p.element.querySelector('h1')?.textContent).toBe('主页');await p.navigate('/unknown/deep/path');expect(p.router.state.location.pathname).toBe('/');await p.navigate('/tasks/example/recovery');expect(p.router.state.matches.at(-1)?.route.path).toBe('/tasks/:id/recovery');await p.navigate('/tasks/example/design');expect(p.router.state.matches.at(-1)?.route.path).toBe('/tasks/:id/design')})
})
