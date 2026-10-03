import type { OwnerIdentity, OwnerScope } from './types'

/** Each instance has a distinct token even when domain/id/epoch happen to match. */
export function createOwnerScope(value: OwnerIdentity): OwnerScope {
  const identity = Object.freeze({ ...value })
  let active = true
  return {
    identity,
    capture: () => Object.freeze({ identity, isCurrent: () => active }),
    isCurrent: token => active && token.identity === identity && token.isCurrent(),
    isActive: () => active,
    retire: () => { active = false },
  }
}
