import { useMemo, useState } from 'react'
import { workflowApi } from '@/api/workflow'
import { PageChrome, SkinControl, queryString, type W2PageProps } from '@/pages/w2/shared'
import { UiContextPanel } from '@/foundation/components'
import { createNewRequirementController } from './newController'
import { Action, CommandNotice, Labeled, ReadNotice, useRequirementOwner } from './parts'
import { RequirementChoice } from './Choice'
import './requirements.css'

export function NewRequirementPage(props: W2PageProps) {
  const legacy = useMemo(() => { try { return { text: queryString(props.route, 'legacyDraft') === '1' ? sessionStorage.getItem('opencode-loopper.designer-draft-prompt') ?? '' : '', error: '' } } catch { return { text: '', error: '旧本地草稿无法读取，请重新填写需求目标。' } } }, [props.navigation])
  const owner = useMemo(() => createNewRequirementController({ template: queryString(props.route, 'template'), projectId: queryString(props.route, 'projectId'), legacyDraft: legacy.text, navigation: props.navigation }), [props.navigation, legacy])
  const s = useRequirementOwner(props, owner), [choice, setChoice] = useState<'project' | 'template' | null>(null)
  const locked = owner.locked() || !!s.knownId
  return <PageChrome title="新建需求" objectKey="object.requirement" actions={<SkinControl {...props} />} status={<><ReadNotice error={s.error} loading={s.loading} retry={() => { void owner.load() }} /><CommandNotice command={s.command} recover={() => { void owner.recover() }} /></>}>
    <section className="w5-new-requirement" data-w5-workspace="new-requirement">
      <ReadNotice error={legacy.error} />
      <form onSubmit={event => { event.preventDefault(); void owner.create() }}>
        <fieldset disabled={locked} className="w5-fields">
          <Labeled label="项目"><span>{s.project?.title ?? '请选择项目'}</span><Action action="workflow.chooseProject" onClick={() => setChoice('project')} disabled={locked} /></Labeled>
          <ReadNotice error={s.projectError} />
          <Labeled label="流程"><span>{s.flow?.title ?? '请选择流程'}</span><Action action="workflow.chooseTemplate" onClick={() => setChoice('template')} disabled={locked} /></Labeled>
          <ReadNotice error={s.flowError} />
          <Labeled label="需求名称"><input id="workflow-requirement-title" value={s.title} maxLength={120} required onChange={event => owner.change({ title: event.target.value })} /></Labeled>
          <Labeled label="需求目标"><textarea id="workflow-requirement-objective" value={s.objective} rows={7} required onChange={event => owner.change({ objective: event.target.value })} /></Labeled>
        </fieldset>
        {s.knownId ? <Action action="workflow.openCreatedRequirement" onClick={() => { void owner.recover() }} busy={s.command.busy} /> : <Action action="workflow.createRequirement" disabled={!owner.valid() || locked || s.loading} busy={s.command.busy} onClick={() => { void owner.create() }} />}
      {['SENDING', 'UNKNOWN'].includes(s.command.phase) && <Action action="workflow.retryCreate" busy={s.command.busy} onClick={() => { void owner.recover() }} />}
      </form>
      <UiContextPanel open={!!choice} title={choice === 'project' ? '选择项目' : '选择流程'} onClose={() => setChoice(null)} closePolicy={locked ? { kind: 'block', reason: '原创建结果仍待确认，请先恢复原操作。' } : { kind: 'allow' }}>
        {choice && <RequirementChoice page={props} kind={choice} disabled={locked} onSelect={value => {
          if (locked) return
          if ('name' in value) { owner.selectProject({ id: value.id, title: value.name }); setChoice(null) }
          else { const ticket = owner.ticket('choose-template'); void workflowApi.get(value.id).then(flow => { if (ticket.current() && !owner.locked()) { owner.selectFlow({ id: flow.id, title: flow.title, revision: flow.revision }); setChoice(null) } }).catch(cause => { if (ticket.current()) owner.fail(cause, '流程版本暂时无法读取，请重选。') }) }
        }} />}
      </UiContextPanel>
    </section>
  </PageChrome>
}
