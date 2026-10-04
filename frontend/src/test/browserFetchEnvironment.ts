import { transferableAbortController } from 'node:util'
// jsdom replaces AbortSignal while fetch/Request remain Node's native undici.
// Use matching native web primitives in tests, as React Router creates genuine Requests.
const controller = transferableAbortController()
globalThis.AbortController = controller.constructor as typeof AbortController
globalThis.AbortSignal = controller.signal.constructor as typeof AbortSignal
