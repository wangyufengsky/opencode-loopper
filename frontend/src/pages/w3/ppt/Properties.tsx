import { useLayoutEffect } from 'react'
import { UiActionButton } from '@/foundation/components'
import type { PptElement, PptSlide } from '@/types/ppt'
import { pptElementLabel } from '@/utils/displayLabels'
import { studioEditable, type PptStudioController, type StudioSnapshot } from './controller'

export function PptProperties({ owner, state: s, slide, element }: { owner: PptStudioController; state: Readonly<StudioSnapshot>; slide: PptSlide; element?: PptElement }) {
  const kind = element ? 'element' : 'slide', id = element?.id ?? slide.id
  useLayoutEffect(() => { const key = owner.openDraft(kind, id, slide.id); return () => owner.pauseDraft(key) }, [owner, kind, id, slide.id])
  const key = element ? `loopper.ppt.element.${owner.identity.id}.${id}` : `loopper.ppt.slideDraft.${owner.identity.id}.${id}`
  const draft = s.drafts[key]
  if (!draft) return null
  const value = draft.value, disabled = !studioEditable(s) || slide.locked || !!element?.locked
  const change = (name: string, next: unknown) => {
    const updated = { ...value, [name]: next }
    const chart = updated.chart as PptElement['chart']
    const valid = !element || Number(updated.width) >= 1 && Number(updated.height) >= 1 && Number.isFinite(Number(updated.x)) && Number.isFinite(Number(updated.y)) && Number(updated.rotation) >= -360 && Number(updated.rotation) <= 360 && (element.type !== 'text' || Number(updated.fontSize) >= 8 && Number(updated.fontSize) <= 144 && /^#?[a-fA-F0-9]{6}$/.test(String(updated.color))) && (element.type !== 'chart' || !!chart && chart.series.every(series => series.values.every(Number.isFinite))) && (!['line', 'shape'].includes(element.type) || Number(updated.lineWidth ?? 0) >= 0)
    owner.changeDraft(key, updated, valid)
  }
  const text = (label: string, field: string, multiline = false) => <label>{label}{multiline ? <textarea rows={field === 'notes' ? 8 : 4} value={String(value[field] ?? '')} onChange={event => change(field, event.target.value)} /> : <input value={String(value[field] ?? '')} onChange={event => change(field, event.target.value)} />}</label>
  const number = (label: string, field: string, min?: number, max?: number) => <label>{label}<input type="number" step={field === 'lineWidth' ? '.5' : '1'} min={min} max={max} value={Number(value[field] ?? 0)} onChange={event => change(field, event.target.value === '' ? '' : Number(event.target.value))} /></label>
  const select = (label: string, field: string, options: readonly (readonly [string, string])[]) => <label>{label}<select value={String(value[field] ?? '')} onChange={event => change(field, event.target.value)}>{options.map(([id, name]) => <option key={id} value={id}>{name}</option>)}</select></label>
  const check = (label: string, field: string) => <label className="ppt-check"><input type="checkbox" checked={!!value[field]} onChange={event => change(field, event.target.checked)} />{label}</label>
  const stale = draft.revision !== s.document?.revision
  const chart = value.chart as PptElement['chart']
  const updateChart = (next: NonNullable<PptElement['chart']>) => change('chart', next)
  return <section className="ppt-properties" aria-label={element ? '对象属性' : '页面属性'}>
    <h2>{element ? pptElementLabel(element.type) : '页面属性'}</h2>
    {element && !slide.locked && <UiActionButton actionKey={element.locked ? 'ppt.unlockObject' : 'ppt.lockObject'} availability={!studioEditable(s) ? { kind: 'disabled', reason: '请先核对原操作。' } : { kind: 'enabled' }} onAction={() => void owner.operations([{ op: 'update_element', slideId: slide.id, elementId: element.id, patch: { locked: !element.locked } }])} />}
    {draft.dirty && stale && <p role="alert">页面已更新，你的输入仍保留。自动保存已暂停。<UiActionButton actionKey="ui.refresh" target="读取最新属性" onAction={() => owner.reloadDraft(key)} /></p>}
    <form onSubmit={event => { event.preventDefault(); if (event.currentTarget.checkValidity()) void owner.saveDraft(key) }}><fieldset disabled={disabled}>
      {!element ? <>{text('页面标题', 'title')}{text('所属章节', 'section')}{text('演讲备注', 'notes', true)}</> : <>
        {element.type === 'text' && text('文字', 'text', true)}
        <div className="ppt-form-grid">{number('横向位置', 'x')}{number('纵向位置', 'y')}{number('宽度', 'width', 1)}{number('高度', 'height', 1)}</div>
        {element.type === 'text' && <>{select('字体', 'fontFamily', (s.capabilities?.fonts ?? []).map(font => [font, font]))}{number('字号', 'fontSize', 8, 144)}{text('文字颜色', 'color')}{select('对齐', 'align', [['left', '左对齐'], ['center', '居中'], ['right', '右对齐']])}{check('加粗', 'bold')}{check('项目符号', 'bullets')}</>}
        {element.type === 'image' && select('图片显示', 'fit', [['contain', '完整显示'], ['cover', '填满并裁切']])}
        {['shape', 'line'].includes(element.type) && <>{select('形状', 'shape', [['rect', '矩形'], ['roundRect', '圆角矩形'], ['ellipse', '椭圆'], ['arrow', '箭头']])}{text('填充颜色', 'fill')}{text('线条颜色', 'stroke')}{number('线宽', 'lineWidth', 0)}</>}
        {element.type === 'table' && <label>表格内容<textarea rows={8} value={(value.rows as string[][] ?? []).map(row => row.join('\t')).join('\n')} onChange={event => change('rows', event.target.value.split('\n').map(row => row.split('\t')))} /><small>每行一行数据，列之间使用制表符，可从表格直接粘贴。</small></label>}
        {element.type === 'chart' && chart && <>
          <label>图表类型<select value={chart.type} onChange={event => updateChart({ ...chart, type: event.target.value })}>{['bar', 'line', 'pie'].map((type, index) => <option key={type} value={type}>{['柱状图', '折线图', '饼图'][index]}</option>)}</select></label>
          <label>分类<textarea value={chart.categories.join('，')} onChange={event => updateChart({ ...chart, categories: event.target.value.split(/[,，]/).map(part => part.trim()) })} /></label>
          {chart.series.map((series, index) => <div className="ppt-chart-series" key={index}>
            <label>系列名称<input value={series.name} onChange={event => updateChart({ ...chart, series: chart.series.map((item, at) => at === index ? { ...item, name: event.target.value } : item) })} /></label>
            <label>数据<input value={series.values.join('，')} onChange={event => updateChart({ ...chart, series: chart.series.map((item, at) => at === index ? { ...item, values: event.target.value.split(/[,，]/).map(part => Number(part.trim())) } : item) })} /></label>
            <label>颜色<input value={series.color ?? ''} onChange={event => updateChart({ ...chart, series: chart.series.map((item, at) => at === index ? { ...item, color: event.target.value } : item) })} /></label>
            <UiActionButton actionKey="ui.delete" target="系列" availability={chart.series.length <= 1 ? { kind: 'disabled', reason: '至少保留一个系列。' } : { kind: 'enabled' }} onAction={() => updateChart({ ...chart, series: chart.series.filter((_, at) => at !== index) })} />
          </div>)}
          <UiActionButton actionKey="ppt.addObject" target="图表系列" onAction={() => updateChart({ ...chart, series: [...chart.series, { name: '新系列', values: chart.categories.map(() => 0), color: '2563EB' }] })} />
        </>}
        {!['chart', 'table'].includes(element.type) && number('旋转角度', 'rotation', -360, 360)}
        {check('允许设计重叠', 'allowOverlap')}
      </>}
      <p role="status">{!draft.dirty ? '已自动保存' : draft.scheduled ? '等待自动保存…' : '修改已保留，可核对后重新保存'}</p>
      <UiActionButton actionKey="ui.save" target={element ? '对象属性' : '页面信息'} variant="primary" availability={draft.dirty && !stale && draft.valid && !disabled ? { kind: 'enabled' } : { kind: 'disabled', reason: stale ? '请核对最新版本。' : '没有可保存的有效修改。' }} onAction={() => void owner.saveDraft(key)} />
    </fieldset></form>
  </section>
}
