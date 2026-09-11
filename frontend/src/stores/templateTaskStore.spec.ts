import { beforeEach, afterEach, describe, it, expect, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { api } from '@/api/client'
import { useTemplateTaskStore } from './templateTaskStore'

const input = { templateId: 'CODE_REVIEW' as const, templateVersion: '1', projectId: 'p', branchId: 'local:refs/heads/main', startDate: '2026-09-01', endDate: '2026-09-07', story: { enabled: true, systemCode: '01', storyCode: '0001' } }
beforeEach(() => setActivePinia(createPinia()))
afterEach(() => vi.restoreAllMocks())
describe('template task creation', () => {
  it('retries the same confirmed task when Start acknowledgement is lost', async () => {
    const create = vi.spyOn(api, 'createTemplateTask').mockResolvedValue({ id: 'task', state: 'PENDING_START' })
    const start = vi.spyOn(api, 'startTemplateTask').mockRejectedValueOnce(new Error('network')).mockResolvedValue({ id: 'task', state: 'RUNNING' })
    const store = useTemplateTaskStore()
    await expect(store.start(input)).rejects.toThrow('network')
    await expect(store.start(input)).resolves.toBe('task')
    expect(create).toHaveBeenCalledTimes(1)
    expect(start).toHaveBeenCalledTimes(2)
    expect(create.mock.calls[0]![0].story.storyCode).toBe('0001')
  })
  it('reuses the idempotency key after lost confirmation and creates a new key for another run', async () => {
    const create = vi.spyOn(api, 'createTemplateTask').mockRejectedValueOnce(new Error('network')).mockResolvedValue({ id: 'task', state: 'PENDING_START' })
    vi.spyOn(api, 'startTemplateTask').mockResolvedValue({ id: 'task', state: 'RUNNING' })
    const store = useTemplateTaskStore()
    await expect(store.start(input)).rejects.toThrow('network')
    await store.start(input)
    await store.start(input)
    expect(create.mock.calls[0]![0].requestKey).toBe(create.mock.calls[1]![0].requestKey)
    expect(create.mock.calls[2]![0].requestKey).not.toBe(create.mock.calls[1]![0].requestKey)
  })
})
