import {afterEach,describe,expect,it,vi} from 'vitest'
import {act} from '@testing-library/react'
import {SessionMonitorPanel} from '@/pages/w4/task/SessionMonitorPanel'
import {createSessionMonitorController} from '@/pages/w4/task/sessionController'
import {mountPanel,sessionReads,taskFixture,activityFixture,flushPromises} from '@/pages/w6-tests/ordinary/task'
afterEach(()=>vi.useRealTimers())
describe('TokenUsageWindow',()=>{
 it('uses the first authoritative total as a silent baseline and animates only a positive delta',async()=>{
  const read=sessionReads();read.activity.mockResolvedValue(activityFixture(undefined,{usage:{totalTokens:1200,unknownUsageCount:0,observedAt:'now'}}))
  const owner=createSessionMonitorController('task-1'),{view}=mountPanel(SessionMonitorPanel,taskFixture('task-1'),{controller:owner});await flushPromises()
  expect(view.get('[aria-label="累计 Token"]').text()).toContain('1,200');expect(view.get('[aria-label="累计 Token"]').text()).not.toContain('+1,200')
  vi.useFakeTimers();read.activity.mockResolvedValue(activityFixture(undefined,{usage:{totalTokens:1584,unknownUsageCount:0,observedAt:'later'}}));await act(async()=>owner.load())
  expect(view.get('[aria-label="累计 Token"]').text()).toContain('1,584');expect(view.get('[aria-label="累计 Token"]').text()).toContain('+384')
  await act(async()=>vi.advanceTimersByTimeAsync(850));expect(view.get('[aria-label="累计 Token"]').text()).not.toContain('+384')
 })
 it('does not render a negative delta when an older snapshot arrives',async()=>{
  const read=sessionReads();read.activity.mockResolvedValue(activityFixture(undefined,{usage:{totalTokens:2000,unknownUsageCount:0,observedAt:'now'}}))
  const owner=createSessionMonitorController('task-1'),{view}=mountPanel(SessionMonitorPanel,taskFixture('task-1'),{controller:owner});await flushPromises()
  read.activity.mockResolvedValue(activityFixture(undefined,{usage:{totalTokens:1800,unknownUsageCount:0,observedAt:'old'}}));await act(async()=>owner.load())
  expect(view.get('[aria-label="累计 Token"]').text()).toContain('2,000');expect(view.get('[aria-label="累计 Token"]').text()).not.toContain('-200')
 })
})
