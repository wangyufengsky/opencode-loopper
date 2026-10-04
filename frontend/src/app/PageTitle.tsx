import { useId } from 'react'
export function PageTitle({ title, titleTooltip, eyebrow }: { title: string; titleTooltip?: string; eyebrow?: string }) {
  const id = useId()
  return <div className="page-title-group">{eyebrow && <span className="eyebrow">{eyebrow}</span>}<h1 className={titleTooltip ? 'page-title-tooltip' : undefined} tabIndex={titleTooltip ? 0 : undefined} aria-describedby={titleTooltip ? id : undefined}>{title}</h1>{titleTooltip && <span className="page-title-tooltip-content" role="tooltip" id={id}>{titleTooltip}</span>}</div>
}
