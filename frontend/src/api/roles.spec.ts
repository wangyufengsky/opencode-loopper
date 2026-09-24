import { afterEach, describe, expect, it, vi } from 'vitest'
import { api } from '@/api/client'

const response = (value: unknown) => new Response(JSON.stringify(value), {
  status: 200, headers: { 'Content-Type': 'application/json' },
})

afterEach(() => vi.unstubAllGlobals())

describe('role configuration API', () => {
  it('reads the owner-checked frozen role summary for one exact task Session', async () => {
    const summary = { configured: false, roleId: null, revisionId: null, revisionSha256: null,
      slot: null, adapterProfile: null, adapterVersion: null, permissions: [], permissionSha256: null }
    const fetchMock = vi.fn().mockResolvedValue(response(summary))
    vi.stubGlobal('fetch', fetchMock)
    expect(await api.getTaskSessionRole('task/1', 'execution:local/1')).toEqual(summary)
    expect(fetchMock.mock.calls[0]?.[0]).toBe('/api/tasks/task%2F1/sessions/execution%3Alocal%2F1/role')
  })

  it('downloads one exact frozen revision as a ZIP', async () => {
    const zip = new Uint8Array([0x50, 0x4b, 0x03, 0x04, 0x00, 0xff])
    const fetchMock = vi.fn().mockResolvedValue(new Response(zip, {
      status: 200, headers: { 'Content-Type': 'application/zip' },
    }))
    vi.stubGlobal('fetch', fetchMock)
    const downloaded = await api.exportRoleRevision('role/x', 'rev+2')
    expect(downloaded.type).toBe('application/zip')
    const bytes = typeof downloaded.arrayBuffer === 'function' ? await downloaded.arrayBuffer()
      : await new Promise<ArrayBuffer>((resolve, reject) => {
        const reader = new FileReader()
        reader.onload = () => resolve(reader.result as ArrayBuffer)
        reader.onerror = () => reject(reader.error)
        reader.readAsArrayBuffer(downloaded)
      })
    expect(new Uint8Array(bytes)).toEqual(zip)
    expect(fetchMock).toHaveBeenCalledWith('/api/roles/role%2Fx/export?revision=rev%2B2', {
      headers: { Accept: 'application/zip' },
    })
  })

  it('sends server search and lazy revision requests with encoded identifiers', async () => {
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(response({ items: [], nextCursor: null }))
      .mockResolvedValueOnce(response({ items: [], nextCursor: null }))
    vi.stubGlobal('fetch', fetchMock)
    await api.getRoles('需求 设计', 'cursor/+', 12)
    await api.getRoleRevisions('role/x', '', 12)
    expect(fetchMock.mock.calls[0]?.[0]).toBe('/api/roles?query=%E9%9C%80%E6%B1%82%20%E8%AE%BE%E8%AE%A1&cursor=cursor%2F%2B&limit=12')
    expect(fetchMock.mock.calls[1]?.[0]).toBe('/api/roles/role%2Fx/revisions?cursor=&limit=12')
  })

  it('uses the same ZIP and publication identity for validation and CAS activation', async () => {
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(response({ sourceSha256: 'a'.repeat(64), valid: true, roles: [], activations: [], diagnostics: [] }))
      .mockResolvedValueOnce(response({ sourceSha256: 'a'.repeat(64), roles: [], bindings: [], replayed: false }))
    vi.stubGlobal('fetch', fetchMock)
    const file = new File(['zip bytes'], 'roles.zip', { type: 'application/zip' })
    await api.validateRoleImport(file)
    await api.publishRoleImport(file, { sourceSha256: 'a'.repeat(64), idempotencyKey: 'same-attempt',
      activations: [{ slot: 'DESIGNER', roleId: 'designer', expectedVersion: 3 }] })
    const validate = fetchMock.mock.calls[0] as [string, RequestInit]
    const publish = fetchMock.mock.calls[1] as [string, RequestInit]
    expect(validate[0]).toBe('/api/role-imports/validate')
    expect(validate[1].body).toBeInstanceOf(FormData)
    expect((validate[1].body as FormData).get('file')).toBe(file)
    expect(validate[1].headers).toMatchObject({ 'X-Loopper-Local-UI': '1' })
    expect(publish[0]).toBe('/api/role-imports/publish')
    expect(publish[1].body).toBeInstanceOf(FormData)
    expect((publish[1].body as FormData).get('file')).toBe(file)
    const request = (publish[1].body as FormData).get('request') as Blob
    const requestText = await new Promise<string>((resolve, reject) => {
      const reader = new FileReader()
      reader.onload = () => resolve(String(reader.result))
      reader.onerror = () => reject(reader.error)
      reader.readAsText(request)
    })
    expect(JSON.parse(requestText)).toEqual({ sourceSha256: 'a'.repeat(64), idempotencyKey: 'same-attempt',
      activations: [{ slot: 'DESIGNER', roleId: 'designer', expectedVersion: 3 }] })
    expect(publish[1].headers).toMatchObject({ 'X-Loopper-Local-UI': '1' })
    expect(publish[1].headers).not.toHaveProperty('Content-Type')
  })
})
