import { icons as lucide } from '@iconify-json/lucide'
import type { SVGProps } from 'react'

export function ReactIcon({ icon, width = 16, ...props }: SVGProps<SVGSVGElement> & { icon: string }) {
  const key = icon.startsWith('lucide:') ? icon.slice(7) : ''
  const definition = lucide.icons[key as keyof typeof lucide.icons]
  if (!definition) return null
  // Trusted, locally bundled icon source; never accepts remote SVG or model HTML.
  return <svg xmlns="http://www.w3.org/2000/svg" viewBox={`0 0 ${lucide.width} ${lucide.height}`} width={width} height={width}
    aria-hidden={props['aria-label'] ? undefined : true} focusable="false" {...props}
    dangerouslySetInnerHTML={{ __html: definition.body }} />
}
