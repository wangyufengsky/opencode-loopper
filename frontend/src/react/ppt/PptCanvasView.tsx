import { useCallback, useEffect, useLayoutEffect, useRef, useState } from 'react'
import type { CSSProperties, KeyboardEvent, MouseEvent, PointerEvent } from 'react'
import type { PptDeck, PptElement, PptSlide } from '@/types/domain'
import { pptElementLabel } from '@/utils/displayLabels'
import { ReactIcon } from '@/react/ReactIcon'
import {
  changeZoom, dragGeometry, fitCanvasWidth, geometryChanged, keyboardGeometry,
  type PptDragSnapshot, type PptGeometry,
} from '@/domain/pptCanvas/geometry'

export interface PptCanvasViewProps {
  deck: PptDeck
  slide: PptSlide
  selected: string
  preview?: string
  previewRevision?: number
  revision: number
  disabled?: boolean
  editing?: boolean
  onSelect(id: string): void
  onPatch(id: string, patch: Partial<PptElement>, revision: number): void
  onRemove(id: string): void
}

type Gesture = PptDragSnapshot & { target: HTMLElement }
type Ghost = PptGeometry & { id: string }

function releasePointer(gesture: Gesture | undefined) {
  if (gesture?.target.hasPointerCapture?.(gesture.pointer))
    gesture.target.releasePointerCapture?.(gesture.pointer)
}

export function PptCanvasView(props: PptCanvasViewProps) {
  const surface = useRef<HTMLDivElement>(null)
  const viewport = useRef<HTMLDivElement>(null)
  const drag = useRef<Gesture | undefined>(undefined)
  const ghostRef = useRef<Ghost | undefined>(undefined)
  const suppressClick = useRef(false)
  const [ghost, setGhost] = useState<Ghost>()
  const [zoom, setZoom] = useState(1)
  const [available, setAvailable] = useState({ width: 960, height: 588 })
  // Selecting an object opens its contextual panel only after pointer release.
  // Freeze the surface during a gesture so resize observations cannot alter its scale.
  const canvasWidth = drag.current?.canvasWidth ?? fitCanvasWidth(available, props.deck) * zoom

  const cancel = useCallback(() => {
    const gesture = drag.current
    if (gesture) suppressClick.current = true
    drag.current = undefined
    ghostRef.current = undefined
    setGhost(undefined)
    releasePointer(gesture)
  }, [])

  useLayoutEffect(cancel, [cancel, props.slide.id, props.disabled, props.revision, props.slide.locked])
  useLayoutEffect(() => {
    if (drag.current && drag.current.id !== props.selected) cancel()
  }, [cancel, props.selected])
  useLayoutEffect(() => {
    const gesture = drag.current
    if (gesture && !props.slide.elements.some(element => element.id === gesture.id && !element.locked)) cancel()
  }, [cancel, props.slide.elements])
  useEffect(() => {
    if (!viewport.current || typeof ResizeObserver === 'undefined') return
    const observer = new ResizeObserver(([entry]) => {
      if (entry) setAvailable({ width: entry.contentRect.width, height: entry.contentRect.height })
    })
    observer.observe(viewport.current)
    return () => observer.disconnect()
  }, [])
  useEffect(() => () => {
    const gesture = drag.current
    drag.current = undefined
    releasePointer(gesture)
  }, [])

  function scale(delta: number) {
    setZoom(value => changeZoom(value, delta))
  }

  function canvasKeyboard(event: KeyboardEvent<HTMLDivElement>) {
    if (event.key === 'Escape') {
      event.preventDefault()
      event.stopPropagation()
      cancel()
      props.onSelect('')
    } else if (event.key === '+' || event.key === '=') {
      event.preventDefault()
      scale(0.1)
    } else if (event.key === '-') {
      event.preventDefault()
      scale(-0.1)
    } else if (event.key === '0') {
      event.preventDefault()
      setZoom(1)
    }
  }

  function objectStyle(element: PptElement): CSSProperties {
    const box = ghost?.id === element.id ? ghost : element
    return {
      left: `${100 * box.x / props.deck.width}%`,
      top: `${100 * box.y / props.deck.height}%`,
      width: `${100 * box.width / props.deck.width}%`,
      height: `${100 * box.height / props.deck.height}%`,
      transform: `rotate(${element.rotation || 0}deg)`,
    }
  }

  function start(event: PointerEvent<HTMLElement>, element: PptElement, resize = false) {
    suppressClick.current = false
    event.currentTarget.closest('button')?.focus({ preventScroll: true })
    if (props.disabled || props.slide.locked || element.locked || event.button !== 0 || !surface.current) return
    const pointScale = surface.current.getBoundingClientRect().width / props.deck.width
    if (!Number.isFinite(pointScale) || pointScale <= 0) return
    event.preventDefault()
    event.stopPropagation()
    cancel()
    suppressClick.current = false
    drag.current = {
      id: element.id, startX: event.clientX, startY: event.clientY,
      x: element.x, y: element.y, width: element.width, height: element.height,
      scale: pointScale, resize, pointer: event.pointerId, revision: props.revision,
      canvasWidth, target: event.currentTarget,
    }
    ghostRef.current = { id: element.id, x: element.x, y: element.y, width: element.width, height: element.height }
    setGhost(ghostRef.current)
    event.currentTarget.setPointerCapture?.(event.pointerId)
  }

  function move(event: PointerEvent<HTMLDivElement>) {
    const gesture = drag.current
    if (!gesture || gesture.pointer !== event.pointerId) return
    if (event.clientX !== gesture.startX || event.clientY !== gesture.startY) suppressClick.current = true
    ghostRef.current = { id: gesture.id, ...dragGeometry(gesture, event.clientX, event.clientY) }
    setGhost(ghostRef.current)
  }

  function stop(event: PointerEvent<HTMLDivElement>) {
    const gesture = drag.current
    if (!gesture || gesture.pointer !== event.pointerId) return
    const box = ghostRef.current
    drag.current = undefined
    ghostRef.current = undefined
    setGhost(undefined)
    releasePointer(gesture)
    props.onSelect(gesture.id)
    if (box && geometryChanged(gesture, box))
      props.onPatch(gesture.id, { x: box.x, y: box.y, width: box.width, height: box.height }, gesture.revision)
  }

  function cancelPointer(event: PointerEvent<HTMLDivElement>) {
    if (drag.current?.pointer === event.pointerId) cancel()
  }

  function clickSelect(event: MouseEvent<HTMLButtonElement>, id: string) {
    event.stopPropagation()
    if (!suppressClick.current || event.detail === 0) props.onSelect(id)
    suppressClick.current = false
  }

  function keyboard(event: KeyboardEvent<HTMLButtonElement>, element: PptElement) {
    if (props.disabled || props.slide.locked || element.locked || props.selected !== element.id) return
    const patch = keyboardGeometry(element, event.key, event.shiftKey, event.altKey)
    if (patch) {
      event.preventDefault()
      props.onPatch(element.id, patch, props.revision)
    } else if (event.key === 'Delete' || event.key === 'Backspace') {
      event.preventDefault()
      props.onRemove(element.id)
    }
  }

  function deselectBackground(event: MouseEvent<HTMLDivElement>) {
    if (event.target === event.currentTarget) props.onSelect('')
  }

  return (
    <div className="ppt-canvas-wrap" data-canvas-runtime="react" data-canvas-kind="ppt" onKeyDown={canvasKeyboard}>
      {props.preview && props.previewRevision !== props.revision ? (
        <p className="ppt-notice">
          预览来自版本 {props.previewRevision}，当前草稿为版本 {props.revision}。生成预览可查看最新效果。
        </p>
      ) : !props.preview ? <p className="ppt-muted">当前为对象位置示意，生成预览后查看实际排版。</p> : null}
      <div ref={viewport} className="ppt-canvas-viewport" tabIndex={0}
        aria-label="幻灯片画布视口" aria-keyshortcuts="+ - 0 Escape" onClick={deselectBackground}>
        <div className="ppt-canvas-stage"
          style={{ width: `${canvasWidth + 48}px`, height: `${canvasWidth * props.deck.height / props.deck.width + 48}px` }}
          onClick={deselectBackground}>
          <div ref={surface} className="ppt-canvas" data-theme={props.deck.theme}
            style={{ width: `${canvasWidth}px`, aspectRatio: `${props.deck.width} / ${props.deck.height}` }}
            aria-label="幻灯片画布" onClick={deselectBackground} onPointerMove={move} onPointerUp={stop}
            onPointerCancel={cancelPointer} onLostPointerCapture={cancelPointer}>
            {props.preview ? <img src={props.preview} alt="当前幻灯片实际预览" className="ppt-preview-image" draggable={false} /> : null}
            {props.slide.elements.map(element => (
              <button key={element.id} type="button"
                className={[
                  'ppt-canvas-object', props.selected === element.id ? 'selected' : '',
                  ghost?.id === element.id ? 'dragging' : '', !props.preview ? 'placeholder' : '',
                ].filter(Boolean).join(' ')} style={objectStyle(element)}
                aria-label={`${pptElementLabel(element.type)}：${element.text?.slice(0, 30) || pptElementLabel(element.type)}`}
                aria-pressed={props.selected === element.id} onClick={event => clickSelect(event, element.id)}
                onPointerDown={event => start(event, element)} onKeyDown={event => keyboard(event, element)}>
                {!props.preview ? <span>{element.text || pptElementLabel(element.type)}</span> : null}
                {props.selected === element.id && !element.locked && !props.slide.locked && !props.disabled ? (
                  <span className="ppt-resize-handle" role="button" aria-label="拖动调整对象大小"
                    onPointerDown={event => { event.stopPropagation(); start(event, element, true) }} />
                ) : null}
              </button>
            ))}
            {!props.slide.elements.length ? <span className="ppt-canvas-empty">添加对象，或让 PPT 助手制作这一页</span> : null}
          </div>
        </div>
      </div>
      <footer className="ppt-canvas-footer">
        <small className="ppt-muted">
          {props.slide.locked ? '本页已锁定' : props.selected
            ? (props.editing ? '方向键移动 · Alt + 方向键缩放 · Esc 取消选择' : '已选择对象，可向助手提出修改意见')
            : '选择对象，开始修改'}
        </small>
        <div className="ppt-zoom-controls" aria-label="画布缩放">
          <button type="button" aria-label="缩小画布" title="缩小画布（−）" disabled={zoom <= 0.5} onClick={() => scale(-0.1)}>
            <ReactIcon icon="lucide:minus" />
          </button>
          <button type="button" className="ppt-zoom-value" aria-label="适应画布" title="适应画布（0）" onClick={() => setZoom(1)}>
            {Math.round(zoom * 100)}%
          </button>
          <button type="button" aria-label="放大画布" title="放大画布（+）" disabled={zoom >= 2} onClick={() => scale(0.1)}>
            <ReactIcon icon="lucide:plus" />
          </button>
        </div>
      </footer>
    </div>
  )
}
