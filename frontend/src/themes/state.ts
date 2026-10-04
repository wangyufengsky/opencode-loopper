import { resolveSkin, SKIN_STORAGE_KEY } from './registry'

let selected = resolveSkin(typeof document === 'undefined' ? undefined : document.documentElement.dataset.skin)
const listeners = new Set<() => void>()
export const getSkinSnapshot = () => selected
export const subscribeSkin = (listener: () => void) => { listeners.add(listener); return () => { listeners.delete(listener) } }
export function applySkin(id: unknown, persist = true): void {
  const skin = resolveSkin(id), changed = selected.id !== skin.id
  selected = skin
  document.documentElement.dataset.skin = skin.id
  document.querySelector('meta[name="theme-color"]')?.setAttribute('content', skin.colors.canvas)
  if (persist) { try { localStorage.setItem(SKIN_STORAGE_KEY, skin.id) } catch { /* Optional preference. */ } }
  if (changed) for (const listener of [...listeners]) listener()
}
export function initializeSkin(): () => void {
  let saved: string | null = null
  try { saved = localStorage.getItem(SKIN_STORAGE_KEY) } catch { /* Default remains usable. */ }
  applySkin(saved, false)
  const synchronize = (event: StorageEvent) => {
    if (event.key !== SKIN_STORAGE_KEY && event.key !== null) return
    try { if (event.storageArea && event.storageArea !== localStorage) return } catch { return }
    applySkin(event.newValue, false)
  }
  window.addEventListener('storage', synchronize)
  return () => window.removeEventListener('storage', synchronize)
}
