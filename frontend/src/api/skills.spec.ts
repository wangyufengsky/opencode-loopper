import { afterEach, describe, expect, it, vi } from 'vitest'
import { api } from './client'

afterEach(() => vi.unstubAllGlobals())
describe('Skill API', () => {
  it('encodes scope and exact skill name without turning them into paths or extra parameters', async () => {
    const fetch = vi.fn().mockResolvedValue({ ok: true, status: 200, json: async () => ({ skills: [], complete: true }) })
    vi.stubGlobal('fetch', fetch)
    await api.getSkills('project & one')
    await api.getSkillDocument('project & one', 'review/a?b&c')
    expect(fetch.mock.calls[0]?.[0]).toBe('/api/runtime/tools/skills?projectId=project%20%26%20one')
    expect(fetch.mock.calls[1]?.[0]).toBe('/api/runtime/tools/skills/document?projectId=project%20%26%20one&name=review%2Fa%3Fb%26c')
  })
})
