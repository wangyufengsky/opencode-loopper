/** One immutable caller-owned command. A successful write is never repeated for a failed read. */
export function createAcknowledgedOperation<T>(label: string, write: () => Promise<T>, read: (receipt: T) => Promise<void>, active: () => boolean) {
  let receipt: T
  let running: Promise<void> | null = null
  const operation = {
    label,
    accepted: false,
    // Read callbacks can recheck their owner after each asynchronous read.
    isActive: active,
    execute(): Promise<void> {
      if (running) return running
      if (!active()) return Promise.resolve()
      running = (async () => {
        if (!operation.accepted) {
          receipt = await write()
          operation.accepted = true
        }
        if (active()) await read(receipt)
      })().finally(() => { running = null })
      return running
    },
  }
  return operation
}
