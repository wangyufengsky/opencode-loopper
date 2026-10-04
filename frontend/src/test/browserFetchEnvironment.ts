import { transferableAbortController } from 'node:util'
import { webcrypto } from 'node:crypto'
import { isArrayBuffer } from 'node:util/types'
import { Buffer } from 'node:buffer'

// Vitest populates the globals with jsdom's constructors before setup files run.
// Node Buffer keeps its original native backing-buffer and typed-array intrinsics.
const NativeArrayBuffer = Buffer.alloc(0).buffer.constructor as ArrayBufferConstructor
const NativeUint8Array = Object.getPrototypeOf(Buffer.prototype).constructor as Uint8ArrayConstructor
type DigestInput = Parameters<webcrypto.SubtleCrypto['digest']>[1]
const digestAdapter = Symbol.for('loopper.test.cross-realm-arraybuffer-digest')

/** Node 22 rejects jsdom FileReader's genuine cross-realm ArrayBuffer. */
export function normalizeTestDigestInput(data: DigestInput): DigestInput {
  return isArrayBuffer(data) && !(data instanceof NativeArrayBuffer)
    ? new NativeUint8Array(data)
    : data
}

/** Test-only: retain Node's real hash, errors, algorithm and method receiver. */
export function installTestWebCryptoArrayBufferAdapter() {
  const original = webcrypto.subtle.digest
  if ((original as typeof original & { [digestAdapter]?: true })[digestAdapter]) return
  const digest: typeof original = function (this: webcrypto.SubtleCrypto, algorithm, data) {
    return original.call(this, algorithm, normalizeTestDigestInput(data))
  }
  Object.defineProperty(digest, digestAdapter, { value: true })
  Object.defineProperty(webcrypto.subtle, 'digest', { value: digest, configurable: true, writable: true })
}

// Patch the native test object also used by explicit vi.stubGlobal('crypto', ...)
// fixtures. This setup is not imported into a production bundle.
installTestWebCryptoArrayBufferAdapter()

// jsdom replaces AbortSignal while fetch/Request remain Node's native undici.
// Use matching native web primitives in tests, as React Router creates genuine Requests.
const controller = transferableAbortController()
globalThis.AbortController = controller.constructor as typeof AbortController
globalThis.AbortSignal = controller.signal.constructor as typeof AbortSignal
