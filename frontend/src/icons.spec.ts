import { getIcon } from '@iconify/vue'
import { describe, expect, it } from 'vitest'
import { registerBundledIcons } from '@/icons'

describe('bundled icons', () => {
  it('registers representative Lucide icons without an Iconify API request', () => {
    registerBundledIcons()

    expect(getIcon('lucide:folder-open')).toBeTruthy()
    expect(getIcon('lucide:orbit')).toBeTruthy()
    expect(getIcon('lucide:triangle-alert')).toBeTruthy()
  })
})
