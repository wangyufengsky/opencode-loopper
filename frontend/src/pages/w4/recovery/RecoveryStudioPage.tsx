import { useMemo } from 'react'
import { UiActionButton } from '@/foundation/components'
import { PageChrome, PageLink, SkinControl } from '@/pages/w2/shared'
import type { W2PageProps } from '@/pages/w2/shared/types'
import { CommandNotice, ReadNotice, useProtectedOwner, useW4Owner } from '../shared/parts'
import { createRecoveryStudioController, recoveryEligible, recoveryModes, recoveryStage } from './controller'
import { displayLabel, errorEventMessage, statusLabel } from '@/utils/displayLabels'
import './recovery.css'

export function RecoveryStudioPage(props: W2PageProps & { taskId?: string }) {
  const id = props.taskId ?? String(props.route.params.id ?? '')
  const owner = useProtectedOwner(useMemo(() => createRecoveryStudioController(id), [id]))
  const state = useW4Owner(props, owner), task = state.task, stage = recoveryStage(task)
  const locked = !['IDLE', 'SETTLED'].includes(state.command.phase)
  return <PageChrome title={task ? `恢复工作台 · ${task.title}` : '恢复工作台'} objectKey="object.recovery" actions={<>
    <SkinControl {...props} />
    <UiActionButton actionKey="ui.refresh" target="恢复上下文" busy={state.loading} onAction={() => void owner.load()} />
    <PageLink navigation={props.navigation} to={`/tasks/${encodeURIComponent(owner.identity.id)}`}>返回父任务</PageLink>
  </>} status={<><ReadNotice error={state.error} loading={state.loading} retry={() => void owner.load()} />{state.command.phase === 'SETTLED' && !state.command.accepted && state.command.error && <p role="status">恢复未创建</p>}<CommandNotice command={state.command} recover={() => void owner.recover()} /></>}>
    {task && <div className="w4-recovery-grid">
      <section aria-label="失败上下文"><h2>父任务</h2><p>{statusLabel(task.status)}</p><p>{task.goal || '该任务未保留额外目标说明。'}</p>
        <dl><dt>项目</dt><dd>{task.projectName || '未命名项目'}</dd><dt>恢复起点</dt><dd>{stage ? `阶段 ${stage.ordinal} · ${stage.objective}` : '没有可复制阶段'}</dd></dl>
        {!!task.errors?.length && <ul>{task.errors.map((failure, index) => <li key={`${failure.code}-${index}`}><strong>{displayLabel(failure.code)}</strong><p>{errorEventMessage(failure.code, failure.message)}</p></li>)}</ul>}
      </section>
      <section aria-label="恢复草稿设置"><h2>恢复方式</h2>{recoveryEligible(task) ? <>
        <fieldset disabled={locked}><legend>恢复模式</legend>{recoveryModes.map(mode => <label key={mode}><input type="radio" name="recovery-mode" value={mode} checked={state.mode === mode} onChange={() => owner.changeMode(mode)} />{mode === 'FROM_FAILED_STAGE' ? '从失败阶段恢复' : mode === 'ALL_STAGES' ? '复制全部阶段' : '只读验证'}</label>)}</fieldset>
        <UiActionButton actionKey="task.createRecovery" busy={state.command.busy} availability={locked ? { kind: 'disabled', reason: '原恢复操作尚未结清，请先核对。' } : undefined} onAction={() => void owner.create()} />
      </> : <p role="status">当前任务不可恢复</p>}</section>
      {(state.result || state.recoveries.length > 0) && <section className="w4-recovery-lineage" aria-live="polite"><h2>{state.result ? '恢复草稿已创建' : '已创建的恢复草稿'}</h2>
        {state.recoveries.map((item, index) => <article key={item.taskId}><h3>派生任务 {index + 1}</h3><p>恢复方式：{displayLabel(item.mode)}</p><p>执行权限：{item.writableSession ? '可执行修改' : '只读验证'}</p><UiActionButton actionKey="task.open" target={`派生任务 ${index + 1}`} onAction={() => void owner.openTask(item.taskId, props.navigation)} /></article>)}
        {state.result && !state.recoveries.some(row => row.taskId === state.result!.taskId) && <UiActionButton actionKey="task.open" target="已确认恢复草稿" onAction={() => void owner.openTask(state.result!.taskId, props.navigation)} />}
      </section>}
    </div>}
  </PageChrome>
}
