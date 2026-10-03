import { describe, expect, it } from 'vitest'
import { w4PageLoader, w4RouteRecords } from './w4Routes'

describe('W4 production route partition', () => {
  it('uses exactly the four existing route records', () => {
    expect(w4RouteRecords.map(row => row.pattern)).toEqual(['/inbox','/tasks/:id/design','/tasks/:id/recovery','/tasks/:id'])
  })
  for (const path of ['/inbox','/tasks/A','/tasks/A/design','/tasks/A/recovery']) it(`loads an actual React page at ${path}`, async () => {
    expect(typeof await w4PageLoader(path)!()).toBe('function')
  })
  for (const path of ['/tasks','/tasks/A/unowned','/requirements/A','/workflows/A','/designer','/template-tasks','/knowledge','/ppt/A']) it(`does not take another wave or invent an entry at ${path}`, () => {
    expect(w4PageLoader(path)).toBeUndefined()
  })
})
