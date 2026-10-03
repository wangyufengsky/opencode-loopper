import { useLayoutEffect } from 'react'
import type { PageOwner } from '@/foundation/contracts/types'
import { useOwnerSnapshot, useRetainedOwner, type W2PageProps } from '../shared'

export function useCoreOwner<T>(props: W2PageProps, owner: PageOwner<T> & { retire(forced?: boolean): unknown }) {
  useRetainedOwner(props, owner, () => owner.retire(true))
  useLayoutEffect(() => owner.attachView(), [owner])
  return useOwnerSnapshot(owner)
}
