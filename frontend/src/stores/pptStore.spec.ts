import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { webcrypto } from 'node:crypto'
import { ApiError } from '@/api/client'
import { pptApi } from '@/api/ppt'
import { usePptStore } from './pptStore'
import {
  pptGeneration,
  pptAgent,
  pptCapabilities,
  pptDeck,
  pptDocument,
  pptPlan,
} from '@/components/ppt/pptTestFixtures'
vi.mock('@/api/ppt', () => ({
  pptApi: {
    generation: vi.fn(),
    generate: vi.fn(),
    resume: vi.fn(),
    get: vi.fn(),
    deck: vi.fn(),
    plan: vi.fn(),
    sources: vi.fn(),
    jobs: vi.fn(),
    revisions: vi.fn(),
    messages: vi.fn(),
    agent: vi.fn(),
    capabilities: vi.fn(),
    events: vi.fn(),
    send: vi.fn(),
    operations: vi.fn(),
    action: vi.fn(),
    savePlan: vi.fn(),
    reply: vi.fn(),
    createJob: vi.fn(),
    stop: vi.fn(),
    checks: vi.fn(),
  },
}))
const api = vi.mocked(pptApi)
beforeEach(() => {
  vi.clearAllMocks()
  setActivePinia(createPinia())
  sessionStorage.clear()
  vi.stubGlobal('crypto', webcrypto)
  api.generation.mockResolvedValue(null)
  api.get.mockImplementation(async (id) => pptDocument(id))
  api.deck.mockResolvedValue(pptDeck())
  api.plan.mockResolvedValue({
    plan: pptPlan(),
    revision: 3,
  })
  api.sources.mockResolvedValue({
    sources: [],
    assets: [],
  })
  api.jobs.mockResolvedValue([])
  api.revisions.mockResolvedValue({
    items: [],
    nextCursor: undefined,
    facets: {},
  })
  api.messages.mockResolvedValue({
    items: [],
    nextCursor: undefined,
    facets: {},
  })
  api.agent.mockResolvedValue(pptAgent())
  api.capabilities.mockResolvedValue(pptCapabilities())
  api.events.mockReturnValue({
    close: vi.fn(),
  } as unknown as EventSource)
})
describe('PPT authoritative workspace', () => {
  it('retains the explicit confirmation and question version when retrying an uncertain reply', async () => {
    const store = usePptStore()
    await store.load('doc')
    api.reply.mockRejectedValueOnce(new Error('network'))
    const question = { id: 'requirements', kind: 'REQUIREMENTS_CONFIRMATION' as const, prompt: '给管理层汇报', options: [], state: 'PENDING' as const, answer: null, version: 7 }
    expect(await store.reply(question, '确认以上需求，请开始设计', true)).toBe(false)
    const original = api.reply.mock.calls[0]![2]
    expect(original).toMatchObject({ confirmed: true, version: 7, expectedRevision: 3 })
    question.version = 9
    api.reply.mockResolvedValueOnce({} as never)
    await store.retryPending()
    expect(api.reply.mock.calls[1]![2]).toEqual(original)
  })
  it('retries a lost request with its original scope, text, revision and identity', async () => {
    const store = usePptStore()
    await store.load('doc')
    api.send.mockRejectedValueOnce(new Error('network'))
    const scope = {
      kind: 'SLIDE' as const,
      slideId: 'slide-1',
    }
    expect(await store.send('缩短文字', scope)).toBe(false)
    scope.slideId = 'slide-2'
    const original = api.send.mock.calls[0]![1]
    api.send.mockResolvedValueOnce({} as never)
    await store.retryPending()
    expect(api.send.mock.calls[1]![1]).toEqual(original)
    expect(original.scope.slideId).toBe('slide-1')
    expect(original.expectedRevision).toBe(3)
    expect(store.pending).toBeNull()
  })
  it('sends the edit baseline rather than replacing it with a newer visible revision', async () => {
    const store = usePptStore()
    await store.load('doc')
    api.operations.mockRejectedValueOnce(new ApiError('页面已更新，请重新读取', 409))
    await store.operations(
      [
        {
          op: 'update_element',
          slideId: 'slide-1',
          elementId: 'text-1',
          patch: {
            text: '我的输入',
          },
        },
      ],
      1,
    )
    expect(api.operations.mock.calls[0]![1]).toBe(1)
    expect(store.error).toContain('页面已更新')
    expect(store.pending).toBeNull()
    expect(api.operations).toHaveBeenCalledTimes(1)
  })
  it('does not let an old workspace response replace a newer route', async () => {
    let resolve!: (value: ReturnType<typeof pptDocument>) => void
    api.get.mockImplementationOnce(
      () =>
        new Promise((done) => {
          resolve = done
        }),
    )
    const store = usePptStore()
    const first = store.load('first')
    const second = store.load('second')
    resolve(pptDocument('first'))
    await Promise.all([first, second])
    await flushPromises()
    expect(store.document?.id).toBe('second')
  })
  it('keeps stopping blocked until the server reports a terminal state', async () => {
    const store = usePptStore()
    api.agent.mockResolvedValue({
      ...pptAgent(),
      state: 'RUNNING',
    })
    await store.load('doc')
    api.stop.mockResolvedValue({
      ...pptAgent(),
      state: 'STOPPING',
    })
    api.agent.mockResolvedValue({
      ...pptAgent(),
      state: 'STOPPING',
    })
    await store.stop()
    expect(store.active).toBe(true)
    expect(store.agent?.state).toBe('STOPPING')
  })
  it('clears layout results once a different revision is loaded', async () => {
    const store = usePptStore()
    await store.load('doc')
    api.checks.mockResolvedValue({
      issues: [
        {
          severity: 'ERROR',
          code: 'OVERFLOW',
          slideId: 'slide-1',
          elementId: 'text-1',
          message: '文字放不下',
        },
      ],
    })
    await store.check()
    expect(store.checkedRevision).toBe(3)
    api.get.mockResolvedValue({
      ...pptDocument(),
      revision: 4,
    })
    api.plan.mockResolvedValue({
      plan: pptPlan(),
      revision: 4,
    })
    await store.refresh()
    expect(store.issues).toEqual([])
    expect(store.checkedRevision).toBeNull()
  })
  it('waits for a queued SSE refresh before freezing a follow-up preview revision', async () => {
    const store = usePptStore()
    await store.load('doc')
    let release!: (value: ReturnType<typeof pptDocument>) => void
    api.get.mockImplementationOnce(
      () =>
        new Promise((done) => {
          release = done
        }),
    )
    const concurrent = store.refresh()
    api.operations.mockImplementationOnce(async () => {
      api.get.mockResolvedValue({
        ...pptDocument(),
        revision: 4,
      })
      api.plan.mockResolvedValue({
        plan: pptPlan(),
        revision: 4,
      })
      return {
        revision: 4,
        deck: pptDeck(),
        createdIds: {},
      }
    })
    const edit = store.operations([
      {
        op: 'update_element',
        slideId: 'slide-1',
        elementId: 'text-1',
        patch: {
          x: 90,
        },
      },
    ])
    await flushPromises()
    release(pptDocument())
    await Promise.all([concurrent, edit])
    await store.createJob('PREVIEW', 'slide-1')
    expect(store.document?.revision).toBe(4)
    expect(api.createJob.mock.calls[0]?.[2]).toBe(4)
  })
  it('replays an uncertain generation request using its frozen revision and key', async () => {
    const store = usePptStore()
    await store.load('doc')
    api.generate.mockRejectedValueOnce(new Error('network'))
    expect(await store.generate('给管理层制作季度汇报')).toBe(false)
    const original = api.generate.mock.calls[0]
    api.get.mockResolvedValue({
      ...pptDocument(),
      revision: 8,
    })
    api.plan.mockResolvedValue({
      plan: pptPlan(),
      revision: 8,
    })
    api.generation.mockResolvedValue(pptGeneration())
    api.generate.mockResolvedValue(pptGeneration())
    await store.retryPending()
    expect(api.generate.mock.calls[1]).toEqual(original)
    expect(original?.[1]).toBe(3)
    expect(store.generationActive).toBe(true)
    expect(store.pending).toBeNull()
  })
  it('keeps automatic production active between separate assistant runs and resumes explicitly', async () => {
    api.generation.mockResolvedValue(pptGeneration('PRODUCING'))
    const store = usePptStore()
    await store.load('doc')
    expect(store.agent?.state).toBe('IDLE')
    expect(store.active).toBe(true)
    api.generation.mockResolvedValue(pptGeneration('STOPPED'))
    await store.refresh()
    expect(store.active).toBe(false)
    api.resume.mockResolvedValue(pptGeneration('PRODUCING'))
    api.generation.mockResolvedValue(pptGeneration('PRODUCING'))
    await store.resume()
    expect(api.resume).toHaveBeenCalledWith('doc', 3, expect.any(String))
    expect(api.generate).not.toHaveBeenCalled()
    expect(store.active).toBe(true)
  })
})
