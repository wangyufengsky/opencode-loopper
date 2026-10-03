import type { PptElement } from '@/types/domain'

export type PptGeometry = Pick<PptElement, 'x' | 'y' | 'width' | 'height'>

export interface PptDragSnapshot extends PptGeometry {
  id: string
  startX: number
  startY: number
  scale: number
  resize: boolean
  pointer: number
  revision: number
  canvasWidth: number
}

export function dragGeometry(drag: PptDragSnapshot, clientX: number, clientY: number): PptGeometry {
  const dx = (clientX - drag.startX) / drag.scale
  const dy = (clientY - drag.startY) / drag.scale
  return {
    x: drag.resize ? drag.x : Math.round(drag.x + dx),
    y: drag.resize ? drag.y : Math.round(drag.y + dy),
    width: drag.resize ? Math.max(12, Math.round(drag.width + dx)) : drag.width,
    height: drag.resize ? Math.max(12, Math.round(drag.height + dy)) : drag.height,
  }
}

export function geometryChanged(previous: PptGeometry, next: PptGeometry): boolean {
  return previous.x !== next.x || previous.y !== next.y ||
    previous.width !== next.width || previous.height !== next.height
}

export function keyboardGeometry(
  element: PptElement,
  key: string,
  shift: boolean,
  resize: boolean,
): Partial<PptGeometry> | undefined {
  const step = shift ? 10 : 1
  const directions: Record<string, readonly [number, number]> = {
    ArrowLeft: [-step, 0],
    ArrowRight: [step, 0],
    ArrowUp: [0, -step],
    ArrowDown: [0, step],
  }
  const delta = directions[key]
  if (!delta) return undefined
  return resize
    ? { width: Math.max(12, element.width + delta[0]), height: Math.max(12, element.height + delta[1]) }
    : { x: element.x + delta[0], y: element.y + delta[1] }
}

export function fitCanvasWidth(
  available: { width: number; height: number },
  deck: { width: number; height: number },
): number {
  return Math.max(120, Math.min(available.width - 48, (available.height - 48) * deck.width / deck.height))
}

export function changeZoom(zoom: number, delta: number): number {
  return Math.max(0.5, Math.min(2, Math.round((zoom + delta) * 10) / 10))
}
