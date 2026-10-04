import { createHash, webcrypto } from 'node:crypto'
import { isArrayBuffer, isUint8Array } from 'node:util/types'
import { Buffer } from 'node:buffer'
import { JSDOM } from 'jsdom'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { installTestWebCryptoArrayBufferAdapter, normalizeTestDigestInput } from './browserFetchEnvironment'

const NativeArrayBuffer = Buffer.alloc(0).buffer.constructor as ArrayBufferConstructor
const NativeUint8Array = Object.getPrototypeOf(Buffer.prototype).constructor as Uint8ArrayConstructor
const windows: JSDOM[] = []
function foreign(expression: string): unknown {
  const dom = new JSDOM('', { runScripts: 'outside-only' })
  windows.push(dom)
  return dom.window.eval(expression)
}
const hex = (bytes: ArrayBuffer) => Buffer.from(bytes).toString('hex')
const expected = (text: string) => createHash('sha256').update(text).digest('hex')
afterEach(() => { windows.splice(0).forEach(dom => dom.window.close()); vi.unstubAllGlobals() })

describe('test-only native WebCrypto BufferSource compatibility', () => {
  it('keeps native BufferSources and the real algorithm contract unchanged', async () => {
    const bytes = new TextEncoder().encode('original'), algorithm = { name: 'SHA-256' }
    expect(normalizeTestDigestInput(bytes)).toBe(bytes)
    expect(normalizeTestDigestInput(bytes.buffer)).toBe(bytes.buffer)
    expect(hex(await webcrypto.subtle.digest(algorithm, bytes))).toBe(expected('original'))
    expect(algorithm).toEqual({ name: 'SHA-256' })
    await expect(webcrypto.subtle.digest('not-a-real-algorithm', bytes)).rejects.toMatchObject({ name: 'NotSupportedError' })
  })

  it('hashes actual FileReader original bytes without replacing the File', async () => {
    const file = new File(['original'], '需求.md', { type: 'text/markdown', lastModified: 123 })
    const raw = await new Promise<unknown>((resolve, reject) => {
      const reader = new FileReader()
      reader.onload = () => resolve(reader.result)
      reader.onerror = () => reject(reader.error)
      reader.readAsArrayBuffer(file)
    })
    expect(isArrayBuffer(raw)).toBe(true)
    if (!isArrayBuffer(raw)) throw new Error('FileReader did not return a real ArrayBuffer')
    expect(raw instanceof NativeArrayBuffer).toBe(false)
    const normalized = normalizeTestDigestInput(raw)
    expect(normalized).toBeInstanceOf(NativeUint8Array)
    expect((normalized as Uint8Array).buffer).toBe(raw)
    vi.stubGlobal('crypto', webcrypto)
    expect(hex(await crypto.subtle.digest('SHA-256', raw))).toBe(expected('original'))
    expect(file.name).toBe('需求.md'); expect(file.size).toBe(8); expect(file.lastModified).toBe(123)
  })

  it('normalizes only a genuine foreign ArrayBuffer and keeps native digest bytes', async () => {
    const raw = foreign('new Uint8Array([65,66,67,68]).buffer')
    if (!isArrayBuffer(raw)) throw new Error('Foreign fixture did not create an ArrayBuffer')
    expect(raw instanceof NativeArrayBuffer).toBe(false)
    const normalized = normalizeTestDigestInput(raw)
    expect(normalized).toBeInstanceOf(NativeUint8Array)
    expect((normalized as Uint8Array).buffer).toBe(raw)
    expect(hex(await webcrypto.subtle.digest('SHA-256', raw))).toBe(expected('ABCD'))
  })

  it('leaves foreign typed-array offsets and lengths untouched', async () => {
    const raw = foreign('new Uint8Array([65,66,67,68]).subarray(1,3)')
    if (!isUint8Array(raw)) throw new Error('Foreign fixture did not create a typed-array view')
    expect(raw.byteOffset).toBe(1); expect(raw.byteLength).toBe(2)
    expect(normalizeTestDigestInput(raw)).toBe(raw)
    expect(hex(await webcrypto.subtle.digest('SHA-256', raw))).toBe(expected('BC'))
  })

  it('preserves native rejection of forged BufferSources and invalid receivers', async () => {
    const fake = { buffer: new ArrayBuffer(4), byteOffset: 0, byteLength: 4 } as unknown as Parameters<webcrypto.SubtleCrypto['digest']>[1]
    expect(normalizeTestDigestInput(fake)).toBe(fake)
    await expect(webcrypto.subtle.digest('SHA-256', fake)).rejects.toMatchObject({ name: 'TypeError' })
    await expect(webcrypto.subtle.digest.call({} as webcrypto.SubtleCrypto, 'SHA-256', new Uint8Array([1]))).rejects.toMatchObject({ name: 'TypeError' })
  })

  it('installs once on the native object used by explicit crypto fixtures', async () => {
    const originalCrypto = webcrypto, originalSubtle = webcrypto.subtle, digest = webcrypto.subtle.digest, uuid = webcrypto.randomUUID
    installTestWebCryptoArrayBufferAdapter(); installTestWebCryptoArrayBufferAdapter()
    expect(webcrypto.subtle.digest).toBe(digest); expect(webcrypto).toBe(originalCrypto)
    vi.stubGlobal('crypto', { randomUUID: uuid, subtle: webcrypto.subtle })
    expect(crypto.subtle).toBe(originalSubtle); expect(crypto.randomUUID).toBe(uuid)
    const raw = foreign('new Uint8Array([65,66]).buffer')
    if (!isArrayBuffer(raw)) throw new Error('Foreign fixture did not create an ArrayBuffer')
    expect(hex(await crypto.subtle.digest('SHA-256', raw))).toBe(expected('AB'))
  })
})
