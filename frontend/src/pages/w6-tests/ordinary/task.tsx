import {afterEach,vi} from 'vitest'
import {mount,flushPromises} from './render'
import {coreFixture} from '@/pages/w2/core/coreTestHelpers'
import {taskFixture,sessionFixture,activityFixture} from '@/pages/w4/task/test-support'
import type {TaskChildOwner,TaskPanelProps} from '@/pages/w4/shared/types'
import type {Task} from '@/types/domain'
import type {ComponentType} from 'react'
import {api} from '@/api/client'
export {taskFixture,sessionFixture,activityFixture,flushPromises}
const dispose:(()=>void)[]=[]
afterEach(()=>{dispose.splice(0).forEach(fn=>fn())})
export function panelFixture(task=taskFixture('task-1')){
 const f=coreFixture(),children=new Set<TaskChildOwner>()
 const parent={registerChild(owner:TaskChildOwner){children.add(owner);return()=>{children.delete(owner)}},canStartWrite(caller?:TaskChildOwner){return [...children].every(c=>c===caller?c.canLeave().kind!=='BLOCK':c.canLeave().kind==='ALLOW')},refresh:vi.fn(async()=>undefined)}
 dispose.push(f.dispose)
 return {...f,parent,props:{task,page:f.props,parent} satisfies TaskPanelProps}
}
export function mountPanel<P extends TaskPanelProps>(Component:ComponentType<P>,task:Task,extra:Omit<P,keyof TaskPanelProps>){const f=panelFixture(task),view=mount(Component,{props:{...f.props,...extra} as unknown as P});return{...f,view}}
export function sessionReads(key='execution:local-1'){
 const sessions=vi.spyOn(api,'getTaskSessions').mockResolvedValue([sessionFixture(key)]),activity=vi.spyOn(api,'getTaskSessionActivity').mockResolvedValue(activityFixture(key))
 vi.spyOn(api,'getTaskSessionTodos').mockResolvedValue([]);vi.spyOn(api,'getTaskSessionCheckpoints').mockResolvedValue([])
 return{sessions,activity}
}
