import { createOwnerScope } from './scope'
import type { Disposer, OwnerIdentity, ResourceScope } from './types'

export class OwnedResourceCleanupError extends Error {
  constructor(readonly failures: readonly unknown[]) {
    super('本实例部分资源清理失败')
    this.name = 'OwnedResourceCleanupError'
  }
}

/** Own only this instance's resources. No global listener, RAF, timer or observer clearing. */
export function createResourceScope(identity: OwnerIdentity): ResourceScope {
  const scope = createOwnerScope(identity)
  const releases = new Set<Disposer>()
  let disposed = false
  function own(dispose: Disposer): Disposer {
    let released = false
    const release = () => {
      if (released) return
      released = true
      releases.delete(release)
      dispose()
    }
    if (disposed) release()
    else releases.add(release)
    return release
  }
  function cleanup() {
    if (disposed) return
    disposed = true
    scope.retire()
    const failures: unknown[] = []
    for (const release of [...releases].reverse()) {
      try { release() } catch (failure) { failures.push(failure) }
    }
    if (failures.length) throw new OwnedResourceCleanupError(failures)
  }
  return { ...scope, own, retire: cleanup, dispose: cleanup }
}
