import { describe, expect, it } from 'vitest'
import { w5PageLoader, w5RouteRecords } from './w5Routes'

describe('W5 existing production route partition', () => {
  it('contains exactly five existing records and preserves distinct new/edit entries', () => {
    expect(w5RouteRecords.map(row => row.pattern)).toEqual(['/requirements/new', '/requirements/:id', '/workflows/new', '/workflows/:id', '/designer'])
  })
  it.each([
    ['/requirements/new', 'NewRequirementPage'], ['/requirements/frozen', 'RequirementPage'],
    ['/workflows/new', 'WorkflowEditorPage'], ['/workflows/frozen', 'WorkflowEditorPage'], ['/designer', 'DesignerPage'],
  ])('loads the actual React production component at %s', async (path, name) => {
    expect(w5RouteRecords.filter(row => row.matches(path))).toHaveLength(1)
    const component = await w5PageLoader(path)!()
    expect(typeof component).toBe('function'); expect(component.name).toBe(name)
  })
  it.each(['/requirements', '/workflows', '/designs', '/tasks/A', '/inbox', '/knowledge', '/ppt/A', '/automations', '/requirements/A/unowned', '/workflows/A/unowned', '/designer/A'])('does not claim another wave or invent an entry: %s', path => {
    expect(w5PageLoader(path)).toBeUndefined()
  })
})
