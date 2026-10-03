import { useLayoutEffect, useRef } from 'react'
import type { SnapshotController } from '@/foundation/contracts/controller'
import type { W2PageProps } from '@/pages/w2/shared/types'
import { useLeaveGuard, useOwnerSnapshot, useRetainedOwner } from '@/pages/w2/shared'
import type { TaskParentPort } from './types'
export { CommandNotice, ReadNotice, closePolicy, useProtectedOwner, selectionTrigger } from '@/pages/w3/templates/runs/parts'

export function useW4Owner<S>(page: W2PageProps, owner: SnapshotController<S>, parent?: TaskParentPort) {
  const previous = useRef(owner)
  useLayoutEffect(() => {
    if (previous.current !== owner && previous.current.canLeave().kind === 'ALLOW') previous.current.retire(true)
    previous.current = owner
  }, [owner])
  useRetainedOwner(page, owner, () => { owner.retire(true) })
  useLeaveGuard(page, request => owner.canLeave(request))
  useLayoutEffect(() => parent?.registerChild(owner), [owner, parent])
  useLayoutEffect(() => owner.attachView(), [owner])
  return useOwnerSnapshot(owner)
}
