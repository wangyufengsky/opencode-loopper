import { useLayoutEffect, useState, type ReactNode } from 'react'
import { UiActionButton } from '@/foundation/components'
import type { UiActionKey } from '@/foundation/semanticRegistry'
import type { PptEditablePlan } from '@/types/ppt'
import { pptLayoutLabel } from '@/utils/displayLabels'
import { studioActive, type PptStudioController, type StudioSnapshot } from './controller'

const tabs = [['brief', '制作目标'], ['narrative', '叙事结构'], ['slides', '页面内容'], ['visual', '视觉规范'], ['assets', '图表素材'], ['notes', '演讲辅助'], ['delivery', '交付设置']] as const
export function PptPlanEditor({ owner, state: s, confirm }: { owner: PptStudioController; state: Readonly<StudioSnapshot>; confirm(title: string, action: () => void, actionKey?: UiActionKey): void }) {
  const [tab, setTab] = useState<string>('brief')
  const key = `loopper.ppt.plan.${owner.identity.id}`
  useLayoutEffect(() => { owner.openDraft('plan', owner.identity.id); return () => owner.pauseDraft(key) }, [owner, key])
  const draft = s.drafts[key]
  if (!draft) return null
  const plan = draft.value as unknown as PptEditablePlan
  const disabled = !!s.pending || s.busy || studioActive(s) || !!s.document?.archived || !['BRIEFING', 'DIRECTION', 'DESIGN'].includes(s.document?.phase ?? '')
  const directionFrozen = s.document?.phase === 'DESIGN'
  const update = (write: (next: PptEditablePlan) => void) => {
    if (disabled) return
    const next = JSON.parse(JSON.stringify(plan)) as PptEditablePlan; write(next)
    owner.changeDraft(key, next, next.brief.pageCount >= 1 && next.brief.pageCount <= (s.capabilities?.maxSlides ?? 100) && next.slides.every(slide => !!slide.title.trim()) && /^#?[a-fA-F0-9]{6}$/.test(next.visualRules.accentColor))
  }
  function field(label: string, value: string | number, onChange: (value: string) => void, multiline = false, numeric = false): ReactNode {
    return <label>{label}{multiline ? <textarea rows={4} value={value} onChange={event => onChange(event.target.value)} /> : <input type={numeric ? 'number' : 'text'} min={numeric ? 1 : undefined} max={numeric ? s.capabilities?.maxSlides ?? 100 : undefined} value={value} onChange={event => onChange(event.target.value)} />}</label>
  }
  const brief = (label: string, name: keyof PptEditablePlan['brief'], multiline = false, numeric = false) => field(label, String(plan.brief[name] ?? ''), value => update(next => { next.brief[name] = numeric ? Number(value) : value }), multiline, numeric)
  return <section className="ppt-plan" aria-label="制作方案">
    <nav className="ppt-tabs" aria-label="方案模块">{tabs.map(([id, label]) => <button key={id} type="button" role="tab" aria-selected={tab === id} onClick={() => setTab(id)}>{label}</button>)}</nav>
    {draft.dirty && draft.revision !== s.document?.revision && <p role="alert">方案已有新版本，当前输入已保留，自动保存已暂停。<UiActionButton actionKey="ui.refresh" target="读取最新方案" onAction={() => owner.reloadDraft(key)} /></p>}
    <form onSubmit={event => { event.preventDefault(); if (event.currentTarget.checkValidity()) void owner.saveDraft(key) }}><fieldset disabled={disabled}>
      {tab === 'brief' && <><h2>制作目标</h2>{brief('受众', 'audience')}{brief('目的', 'purpose', true)}{brief('汇报时长', 'duration')}{brief('预计页数', 'pageCount', false, true)}{brief('补充要求', 'requirements', true)}</>}
      {tab === 'narrative' && <><h2>叙事结构</h2><fieldset disabled={directionFrozen}><legend>整体方向</legend>{directionFrozen && <p>方向已经确认，重新打开需求后可以修改。</p>}
        {plan.directions.map((direction, index) => <article key={direction.id} className="ppt-direction">
          <label><input type="radio" name="ppt-direction" checked={plan.selectedDirectionId === direction.id} onChange={() => update(next => { next.selectedDirectionId = direction.id })} />选择这个方向</label>
          {(['title', 'description', 'story', 'chapters', 'pageCount', 'visual'] as const).map((name, at) => <div key={name}>{field(['方案名称', '方向说明', '讲述主线', '章节安排', '建议页数', '视觉方向'][at]!, String(direction[name] ?? ''), value => update(next => { if (name === 'pageCount') next.directions[index]!.pageCount = Number(value); else next.directions[index]![name] = value }), ['description', 'story'].includes(name), name === 'pageCount')}</div>)}
        </article>)}
        <UiActionButton actionKey="ppt.addObject" target="自行填写方向" onAction={() => update(next => { next.directions.push({ id: crypto.randomUUID(), title: '新方向', description: '', story: '', chapters: '', pageCount: next.brief.pageCount, visual: '' }) })} />
      </fieldset>{field('全稿主线与章节衔接', plan.narrative.story, value => update(next => { next.narrative.story = value }), true)}
        {plan.narrative.chapters.map((chapter, index) => <article key={chapter.id} className="ppt-direction"><h3>第 {index + 1} 章</h3>{field('章节名称', chapter.title, value => update(next => { next.narrative.chapters[index]!.title = value }))}{field('本章目的', chapter.purpose, value => update(next => { next.narrative.chapters[index]!.purpose = value }), true)}{field('页数安排', chapter.pageCount, value => update(next => { next.narrative.chapters[index]!.pageCount = Number(value) }), false, true)}<UiActionButton actionKey="ui.delete" target="章节" onAction={() => confirm('移除这个章节安排？历史版本仍会保留。', () => update(next => { next.narrative.chapters.splice(index, 1) }))} /></article>)}
        <UiActionButton actionKey="ppt.addObject" target="章节" onAction={() => update(next => { next.narrative.chapters.push({ id: crypto.randomUUID(), title: '新章节', purpose: '', pageCount: 3 }) })} />
      </>}
      {tab === 'slides' && <><h2>页面内容</h2>{plan.slides.map((slide, index) => <article key={slide.id} className="ppt-plan-slide"><h3>第 {index + 1} 页</h3>
        <div className="w3-ppt-actions">{[-1, 1].map(step => <UiActionButton key={step} actionKey={step < 0 ? 'ppt.moveSlideUp' : 'ppt.moveSlideDown'} availability={index + step < 0 || index + step >= plan.slides.length ? { kind: 'disabled', reason: '已到边界。' } : { kind: 'enabled' }} onAction={() => update(next => { const [row] = next.slides.splice(index, 1); if (row) next.slides.splice(index + step, 0, row) })} />)}<UiActionButton actionKey="ppt.deleteSlide" onAction={() => confirm('移除这页设计？历史版本仍会保留。', () => update(next => { next.slides.splice(index, 1) }), 'ppt.deleteSlide')} /></div>
        {(['title', 'section', 'message', 'content'] as const).map((name, at) => <div key={name}>{field(['标题', '章节', '核心观点', '页面内容'][at]!, slide[name], value => update(next => { next.slides[index]![name] = value }), ['message', 'content'].includes(name))}</div>)}
        <label>版式<select value={slide.layout} onChange={event => update(next => { next.slides[index]!.layout = event.target.value })}>{(s.capabilities?.layouts ?? []).map(layout => <option key={layout} value={layout}>{pptLayoutLabel(layout)}</option>)}</select></label>
      </article>)}<UiActionButton actionKey="ppt.addSlide" onAction={() => update(next => { next.slides.push({ id: crypto.randomUUID(), title: `第 ${next.slides.length + 1} 页`, section: '', message: '', content: '', layout: 'title_content', sourceIds: [], notes: '' }) })} /></>}
      {tab === 'visual' && <><h2>视觉规范</h2>{field('整体风格', plan.visualRules.style, value => update(next => { next.visualRules.style = value }), true)}<label>演示主题<select value={plan.theme} onChange={event => update(next => { next.theme = event.target.value })}>{s.capabilities?.themes.map(theme => <option key={theme.id} value={theme.id}>{theme.name}</option>)}</select></label><label>字体<select value={plan.visualRules.fontFamily} onChange={event => update(next => { next.visualRules.fontFamily = event.target.value })}>{s.capabilities?.fonts.map(font => <option key={font}>{font}</option>)}</select></label>{field('强调色', plan.visualRules.accentColor, value => update(next => { next.visualRules.accentColor = value }))}<label>文字密度<select value={plan.visualRules.density} onChange={event => update(next => { next.visualRules.density = event.target.value })}>{['精简', '适中', '详细'].map(label => <option key={label}>{label}</option>)}</select></label><label>页面比例<input value="16:9" readOnly /></label><p>视觉要求指导助手制作，实际对象样式可在页面制作中调整。</p></>}
      {tab === 'assets' && <><h2>图表与素材</h2>{(['requirements', 'chartGuidance', 'imageGuidance'] as const).map((name, index) => <div key={name}>{field(['素材使用要求', '图表表达要求', '配图要求'][index]!, plan.assets[name], value => update(next => { next.assets[name] = value }), true)}</div>)}{!s.sources.length && <p>上传资料后，可为每页选择依据。</p>}{plan.slides.map((slide, index) => <article key={slide.id}><h3>第 {index + 1} 页 · {slide.title}</h3>{s.sources.map(source => <label key={source.id}><input type="checkbox" disabled={source.state !== 'READY'} checked={slide.sourceIds.includes(source.id)} onChange={event => update(next => { next.slides[index]!.sourceIds = event.target.checked ? [...slide.sourceIds, source.id] : slide.sourceIds.filter(id => id !== source.id) })} />{source.name}</label>)}</article>)}</>}
      {tab === 'notes' && <><h2>演讲辅助</h2>{plan.slides.map((slide, index) => <article key={slide.id}><h3>第 {index + 1} 页 · {slide.title}</h3>{field('讲稿、时长与过渡', slide.notes, value => update(next => { next.slides[index]!.notes = value }), true)}</article>)}</>}
      {tab === 'delivery' && <><h2>交付设置</h2>{field('文件名称', plan.delivery.fileName, value => update(next => { next.delivery.fileName = value }))}{field('目标办公软件', plan.delivery.targetSoftware, value => update(next => { next.delivery.targetSoftware = value }))}<label><input type="checkbox" checked={plan.delivery.includeNotes} onChange={event => update(next => { next.delivery.includeNotes = event.target.checked })} />包含演讲备注</label><p>正式导出为可编辑 PPTX，页面预览为 PNG。</p></>}
      <footer><p>{!draft.dirty ? '已自动保存' : draft.scheduled ? '等待自动保存…' : '修改已保留，请核对后保存'}</p><UiActionButton actionKey="ppt.savePlan" variant="primary" availability={!disabled && draft.dirty && draft.valid && draft.revision === s.document?.revision ? { kind: 'enabled' } : { kind: 'disabled', reason: '请核对当前方案、版本和原操作。' }} onAction={() => void owner.saveDraft(key)} /></footer>
    </fieldset></form>
  </section>
}
