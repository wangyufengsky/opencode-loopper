import { computed, readonly, ref } from 'vue'
import { resolveSkin, SKIN_STORAGE_KEY } from './registry'

const selected = ref(resolveSkin(typeof document === 'undefined' ? undefined : document.documentElement.dataset.skin).id)
export const currentSkin = computed(() => resolveSkin(selected.value))
export const skinId = readonly(selected)

export function applySkin(id: unknown, persist = true): void {
  const skin = resolveSkin(id)
  selected.value = skin.id
  document.documentElement.dataset.skin = skin.id
  document.querySelector('meta[name="theme-color"]')?.setAttribute('content', skin.colors.canvas)
  if (persist) {
    try { localStorage.setItem(SKIN_STORAGE_KEY, skin.id) } catch { /* Optional browser preference. */ }
  }
}

export function initializeSkin(): () => void {
  let saved: string | null = null
  try { saved = localStorage.getItem(SKIN_STORAGE_KEY) } catch { /* Default remains usable. */ }
  applySkin(saved, false)
  const synchronize = (event: StorageEvent) => {
    if (event.key !== SKIN_STORAGE_KEY && event.key !== null) return
    // sessionStorage preferences must not change the persistent appearance.
    try { if (event.storageArea && event.storageArea !== localStorage) return } catch { return }
    applySkin(event.newValue, false)
  }
  window.addEventListener('storage', synchronize)
  return () => window.removeEventListener('storage', synchronize)
}
