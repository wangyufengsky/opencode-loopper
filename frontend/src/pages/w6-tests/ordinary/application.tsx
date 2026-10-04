import {afterEach} from 'vitest'
import {Dom,flushPromises} from './render'
import {mountApplicationHarness} from '@/test/applicationHarness'
import {navigationHarness} from '@/test/navigationHarness'
const roots:Awaited<ReturnType<typeof mountApplicationHarness>>[]=[]
afterEach(()=>{roots.splice(0).forEach(page=>page.unmount())})
export async function application(options:Parameters<typeof mountApplicationHarness>[0]){const page=await mountApplicationHarness(options);roots.push(page);return{...page,view:Object.assign(new Dom(page.element),{unmount:page.unmount}),navigation:navigationHarness(page)}}
export {flushPromises}
