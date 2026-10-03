import { ReactIcon } from '@/react/ReactIcon'
import type { PptDeck } from '@/types/domain'

export interface PptSlideNavigatorViewProps {
  deck: PptDeck
  selected: string
  previews: ReadonlyMap<string, string>
  manual?: boolean
  disabled?: boolean
  onSelect(id: string): void
  onAdd(): void
}

export function PptSlideNavigatorView(props: PptSlideNavigatorViewProps) {
  return (
    <nav className="ppt-page-rail" aria-label="演示页面" data-canvas-runtime="react" data-canvas-kind="ppt-navigator">
      {props.deck.slides.map((slide, index) => (
        <button key={slide.id} type="button"
          className={`ppt-page-thumbnail${props.selected === slide.id ? ' selected' : ''}`}
          aria-label={`第 ${index + 1} 页：${slide.title}`}
          aria-current={props.selected === slide.id ? 'page' : undefined}
          onClick={() => props.onSelect(slide.id)}>
          <span className="ppt-thumbnail-image">
            {props.previews.get(slide.id)
              ? <img src={props.previews.get(slide.id)} alt="" loading="lazy" />
              : <span className="ppt-thumbnail-title">{slide.title}</span>}
            {slide.locked ? <span aria-label="已锁定"><ReactIcon className="ppt-thumbnail-lock" icon="lucide:lock" /></span> : null}
          </span>
          <span className="ppt-thumbnail-caption">
            <span>{String(index + 1).padStart(2, '0')}</span>
            <span>{slide.title || '未命名页面'}</span>
          </span>
        </button>
      ))}
      {props.manual ? (
        <button type="button" className="ppt-add-page" disabled={props.disabled} onClick={props.onAdd}>
          <ReactIcon icon="lucide:plus" />新增页面
        </button>
      ) : null}
    </nav>
  )
}
