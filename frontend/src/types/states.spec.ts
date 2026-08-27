import { describe, expect, it } from 'vitest'
import { demoTaskStatusGroups } from '@/mock/demoData'
import { TASK_STATUSES, requirePublicState } from '@/types/states'

describe('public runtime states', () => {
  it('classifies every demo Task status explicitly', () => {
    expect(Object.keys(demoTaskStatusGroups).sort()).toEqual([...TASK_STATUSES].sort())
  })

  it('rejects unknown server states instead of coercing them', () => {
    expect(() => requirePublicState(TASK_STATUSES, 'FUTURE_STATE', 'Task')).toThrow('unknown state')
  })
})
