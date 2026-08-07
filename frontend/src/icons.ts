import { icons as lucideIcons } from '@iconify-json/lucide'
import { addCollection } from '@iconify/vue'

/** Keep every Lucide icon available when the console has no public internet access. */
export function registerBundledIcons() {
  addCollection(lucideIcons)
}
