import { afterEach, describe, expect, it, vi } from 'vitest'
import { createRecoveryCommandId } from '@/utils/recoveryCommandId'
import { api } from './client'
import { normalizeTemplateDiagnostic, normalizeTemplateDiagnosticPage } from './templateSessionDiagnostics'
afterEach(() => vi.unstubAllGlobals())
describe('template diagnostics REST contract', () => {
  it('creates UUIDs on intranet HTTP without randomUUID', () => {
    vi.stubGlobal('crypto', { getRandomValues: (bytes: Uint8Array) => bytes.fill(17) })
    expect(createRecoveryCommandId()).toMatch(/^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/)
  })
  it('fails closed on missing versions and non-boolean action capabilities', () => {
    expect(normalizeTemplateDiagnostic({ batchId: 'batch', canFinalize: true, canStop: true })).toMatchObject({ canFinalize: false, canStop: false })
    expect(normalizeTemplateDiagnostic({ batchId: 'batch', batchVersion: 2, canFinalize: 'true', canStop: true })).toMatchObject({ canFinalize: false, canStop: true })
    expect(normalizeTemplateDiagnosticPage({ items: [], nextCursor: 'cursor', facets: {} })).toMatchObject({ hasMore: true, nextCursor: 'cursor' })
    expect(normalizeTemplateDiagnosticPage({ items: null, hasMore: true })).toMatchObject({ items: [], hasMore: false })
  })
  it('encodes scoped identities and includes local UI guard, CAS version and unchanged command id', async () => {
    const fetchMock = vi.fn().mockImplementation(() => Promise.resolve(new Response(JSON.stringify({ batchId: 'batch/a', batchVersion: 9, items: [], nextCursor: null }), { status: 200 })))
    vi.stubGlobal('fetch', fetchMock)
    await api.getTemplateSessionDiagnostics('task/a', 'ACTIVE', 'cursor/a', 50)
    expect(fetchMock.mock.calls[0]![0]).toBe('/api/tasks/task%2Fa/session-diagnostics?filter=ACTIVE&limit=50&cursor=cursor%2Fa')
    await api.getTemplateSessionDiagnostic('task/a', 'batch/a')
    expect(fetchMock.mock.calls[1]![0]).toBe('/api/tasks/task%2Fa/session-diagnostics/batch%2Fa')
    const body = { action: 'FINALIZE' as const, expectedVersion: 9, commandId: 'one-command' }
    await api.recoverTemplateSession('task/a', 'batch/a', body)
    expect(fetchMock.mock.calls[2]).toEqual(['/api/tasks/task%2Fa/session-diagnostics/batch%2Fa/recover', expect.objectContaining({ method: 'POST', headers: expect.objectContaining({ 'X-Loopper-Local-UI': '1', 'Content-Type': 'application/json' }), body: JSON.stringify(body) })])
  })
})
