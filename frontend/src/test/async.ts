const queue = typeof setImmediate === 'function' ? setImmediate : setTimeout
/** Drain genuine transport promises; captured before fake clock installation. */
export const flushPromises = () => new Promise<void>(resolve => queue(resolve))
