import { createElement } from 'react'
import { fireEvent, render, screen } from '@testing-library/react'
import { afterEach, vi } from 'vitest'
import { api } from '@/api/client'
import { ToolsPage } from '@/pages/w2/secondary/ToolsPage'
import { coreFixture, coreFrame, projectFixture } from '@/pages/w2/core/coreTestHelpers'
import { semanticName, type UiActionKey } from '@/foundation/semanticRegistry'
import { flushPromises } from './render'
const releases: Array<() => void> = []
afterEach(()=>{releases.splice(0).forEach(release=>release())})
export async function toolsFixture() {
  if (!vi.isMockFunction(api.getProjects)) vi.spyOn(api,'getProjects').mockResolvedValue([{...projectFixture,id:'project'}])
  if (!vi.isMockFunction(api.getMcpServers)) vi.spyOn(api,'getMcpServers').mockResolvedValue({servers:[{id:'external',name:'外部工具',status:'connected',type:'remote'},{id:'@loopper-internal',name:'系统工具',status:'connected',type:'local'}],complete:true,checkedAt:''})
  const f=coreFixture(),view=render(coreFrame(createElement(ToolsPage,f.props)));releases.push(f.dispose);await flushPromises();return {view,f}
}
export async function action(key:UiActionKey,target?:string){fireEvent.click(screen.getByRole('button',{name:semanticName(key,target)}));await flushPromises()}
export async function skillsTab(){fireEvent.click(screen.getByRole('tab',{name:'技能（Skill）'}));await flushPromises()}
export async function toolsTab(){fireEvent.click(screen.getByRole('tab',{name:'工具'}));await flushPromises()}
export async function project(id:string){fireEvent.change(screen.getByLabelText('工具所属项目'),{target:{value:id}});await flushPromises()}
