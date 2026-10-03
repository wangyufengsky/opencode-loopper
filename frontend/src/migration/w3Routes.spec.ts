import { describe, expect, it } from 'vitest'
import { w3PageLoader, w3RouteRecords } from './w3Routes'

describe('W3 production route partition', () => {
  it.each([
    ['/template-tasks', 'TemplateTasksPage'],
    ['/template-tasks/document-runs/mock-document', 'DocumentTemplatePage'],
    ['/template-tasks/source-runs/mock-source', 'SourceTemplatePage'],
    ['/knowledge', 'KnowledgePage'], ['/knowledge/mock-conversation', 'KnowledgePage'],
    ['/ppt/mock-deck', 'PptStudioPage'],
  ])('loads the actual production React page for %s', async (path, name) => {
    const component = await w3PageLoader(path)!()
    expect(typeof component).toBe('function'); expect(component.name).toBe(name)
    expect(w3RouteRecords.filter(record => record.matches(path))).toHaveLength(1)
  })
  it.each(['/ppt', '/knowledge/history', '/projects', '/requirements/new', '/requirements/req', '/tasks/task', '/designer', '/automations', '/template-tasks/source-runs/A/extra', '/knowledge/A/extra'])('does not take ownership of another wave or redirect: %s', path => {
    expect(w3PageLoader(path)).toBeUndefined()
  })
})
