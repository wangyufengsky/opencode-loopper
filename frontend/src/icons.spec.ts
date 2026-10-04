import { semanticGlyph } from '@/foundation/semanticRegistry'
import { icons } from '@iconify-json/lucide'
import { describe, expect, it } from 'vitest'

describe('bundled icons', () => {
  it('registers representative Lucide icons without an Iconify API request', () => {
    expect(semanticGlyph('nav.tasks')).toBeTruthy()

    expect(icons.icons['folder-open']).toBeTruthy()
    expect(icons.icons.orbit).toBeTruthy()
    expect(icons.icons['triangle-alert']).toBeTruthy()
  })
})
