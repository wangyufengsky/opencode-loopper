import { Alert, Button, Tag } from 'antd'
import { useId, useRef, useState, type ReactNode } from 'react'
import { UiActionButton, UiContextPanel } from '@/foundation/components'
import { PageLink, useOwnerSnapshot } from '@/pages/w2/shared'
import type { W2PageProps } from '@/pages/w2/shared/types'
import type { AutomationRule, AutomationRun } from '@/types/domain'
import { ReadOnlyCode } from '@/pages/w3/shared/ReadOnlyCode'
import type { createTemplateHistoryArchiveController, HistoryTab } from './history'

const triggers = { MANUAL: '手动', CRON: '定时', GIT_HEAD_CHANGED: 'Git 提交变化', WEBHOOK: 'Webhook' }
const states: Record<string, string> = { DETECTED: '已检测', REVIEW_REQUIRED: '等待审批', QUEUED: '已排队', RUNNING: '运行中', SUCCEEDED: '已完成', FAILED: '失败', SKIPPED: '已跳过' }
function Health({ rule }: { rule: AutomationRule }) {
  const health = rule.health
  return <div aria-label="自动化检测状态">
    <strong>{!health ? '未检查' : health.status === 'CHECKED' ? '检测正常' : `检测失败 · 连续 ${health.consecutiveFailures} 次`}</strong>
    {health?.lastCheckedAt && <p>最近检测：{health.lastCheckedAt}</p>}
    {health?.lastSuccessAt && <p>最近成功：{health.lastSuccessAt}</p>}
    {health?.status === 'FAILED' && <p role="alert">{health.errorMessage || health.errorCode || '检测未完成，请重新读取记录'}</p>}
  </div>
}
function Rule({ rule, select }: { rule: AutomationRule; select?(): void }) {
  return <article className="catalog-history-record"><h3>{rule.name}</h3>
    <p>{triggers[rule.triggerType]} · {rule.state === 'ENABLED' ? '历史启用' : '历史禁用'} · {rule.approvalMode === 'AUTO_START' ? '自动开始' : '需要审批'}</p>
    <p>项目：{rule.projectId} · 冻结模板版本：{rule.templateVersionId} · 版本：{rule.version}</p>
    <ReadOnlyCode content={JSON.stringify(rule.triggerConfig, null, 2)} language="json" label="旧规则触发配置" />
    <Health rule={rule} />{select && <UiActionButton actionKey="ui.open" target={rule.name} onAction={select} />}
  </article>
}
function Run({ run, select }: { run: AutomationRun; select(): void }) {
  return <article className="catalog-history-record"><h3>{run.id}</h3><p>{states[run.state] || run.state} · {triggers[run.triggerType]} · {run.detectedAt}</p>
    <p>草稿：{run.draftId || '未绑定'} · 任务：{run.taskId || '未绑定'}</p><UiActionButton actionKey="ui.open" target={run.id} onAction={select} /></article>
}
/** Manual activation: moving focus is never a GET/export. Only this React tree owns the tabs. */
export function ArchiveTabs({activeKey,onChange,busy,items}:{activeKey:HistoryTab;onChange(key:HistoryTab):void;busy:boolean;items:{key:HistoryTab;label:string;children:ReactNode}[]}) {
  const id=useId(),[focused,setFocused]=useState<HistoryTab>(activeKey),buttons=useRef<(HTMLButtonElement|null)[]>([])
  const selected=items.find(item=>item.key===activeKey)
  return <><div role="tablist" aria-label="历史归档分类" className="catalog-archive-tabs">{items.map((item,index)=><Button key={item.key} ref={node=>{buttons.current[index]=node as HTMLButtonElement|null}} role="tab" id={`${id}-${item.key}`} aria-selected={activeKey===item.key} aria-controls={`${id}-panel`} disabled={busy} tabIndex={focused===item.key?0:-1} onFocus={()=>setFocused(item.key)} onClick={()=>{if(!busy)onChange(item.key)}} onKeyDown={event=>{
    if(busy)return
    const keys=['ArrowLeft','ArrowRight','Home','End'];if(keys.includes(event.key)){event.preventDefault();const next=event.key==='Home'?0:event.key==='End'?items.length-1:(index+(event.key==='ArrowRight'?1:-1)+items.length)%items.length;setFocused(items[next]!.key);buttons.current[next]?.focus()}
    else if(event.key==='Enter'||event.key===' '){event.preventDefault();onChange(item.key)}
  }}>{item.label}</Button>)}</div><section role="tabpanel" id={`${id}-panel`} aria-labelledby={`${id}-${activeKey}`} tabIndex={0}>{selected?.children}</section></>
}
/** Explicitly opened, read-only consumer of the original nine GET endpoints. */
export function HistoryDrawer({ owner, props }: { owner: ReturnType<typeof createTemplateHistoryArchiveController>; props: W2PageProps }) {
  const s = useOwnerSnapshot(owner)
  return <UiContextPanel open={s.open} title="历史模板与自动化记录" onClose={owner.close} closePolicy={{ kind: 'allow' }} expanded>
    <p>这里保留旧模板合同、规则和运行记录。原写入接口已退役；不会重新启动、审批、导入或生成密钥。</p>
    <UiActionButton actionKey="ui.refresh" target="历史模板与自动化记录" busy={s.loading} onAction={() => { void owner.refresh() }} />
    {s.error && <Alert role="alert" type="error" title={s.error} />}
    <ArchiveTabs activeKey={s.tab} busy={s.loading} onChange={key => { void owner.tab(key) }} items={[
      { key: 'overview', label: '归档概览', children: s.workspace && <><p>历史模板 {s.workspace.templates.length} · 旧规则 {s.workspace.rules.length} · 运行记录 {s.workspace.runs.length}</p><p>服务器时间：{s.serverTime}</p>{s.workspace.rules.map(rule => <Rule key={rule.id} rule={rule} />)}</> },
      { key: 'templates', label: '历史模板', children: <>{s.templates.map(t => <article key={t.id} className="catalog-history-record"><h3>{t.name}</h3><p>{t.description}</p><Tag>{t.state === 'ARCHIVED' ? '已归档' : '历史有效'}</Tag><p>版本 {t.version} · {t.updatedAt}</p><UiActionButton actionKey="ui.open" target={t.name} onAction={() => { void owner.template(t.id) }} /></article>)}
        {s.selectedTemplate && <section aria-label="冻结模板版本"><h3>{s.selectedTemplate.name}</h3>{s.versions.map(v => <details key={v.id}><summary>版本 {v.versionNumber} · {v.immutable ? '不可变' : '原可变版本'} · {v.autoStartApproved ? '原自动开始已批准' : '原自动开始未批准'}</summary><p>SHA-256：{v.specSha256}</p><p>{v.createdAt}</p><ReadOnlyCode content={JSON.stringify(v.spec, null, 2)} language="json" label="历史冻结合同" /></details>)}<p>下面导出的是此模板最新原合同，不代表当前选中的历史版本。</p><UiActionButton actionKey="automation.exportLatest" busy={s.loading} onAction={() => { void owner.download('latest') }} /></section>}</> },
      { key: 'rules', label: '旧规则与检测记录', children: <>{s.rules.map(rule => <Rule key={rule.id} rule={rule} select={() => { void owner.rule(rule) }} />)}{s.selectedRule && <section aria-label="旧规则运行记录">{s.runs.map(run => <Run key={run.id} run={run} select={() => owner.selectRun(run)} />)}</section>}</> },
      { key: 'runs', label: '运行记录', children: <><p>服务器时间：{s.serverTime}</p>{s.runs.map(run => <Run key={run.id} run={run} select={() => owner.selectRun(run)} />)}</> },
      { key: 'export', label: '导出说明', children: <><p>旧格式 v1 只含模板版本和部分规则配置。不会包含运行记录、检测信息或 Webhook 密钥；这不是完整备份。</p><UiActionButton actionKey="automation.exportWorkspace" busy={s.loading} onAction={() => { void owner.download('workspace') }} /></> },
    ]} />
    {s.selectedRun && <section aria-label="历史运行详情"><h3>{s.selectedRun.id} · {states[s.selectedRun.state] || s.selectedRun.state}</h3><p>规则：{s.selectedRun.ruleId} · 草稿：{s.selectedRun.draftId || '未绑定'}</p><p>开始：{s.selectedRun.startedAt || '未开始'} · 结束：{s.selectedRun.endedAt || '未结束'}</p>{s.selectedRun.error && <Alert title={s.selectedRun.error} type="error" />}<ReadOnlyCode content={JSON.stringify(s.selectedRun.evidence, null, 2)} language="json" label="原始运行证据" />
      {s.selectedRun.taskId && <UiActionButton actionKey="automation.verifyTask" busy={s.loading} onAction={() => { void owner.verifyTask() }} />}{s.taskError && <Alert role="alert" title={s.taskError} type="warning" />}{s.taskVerified && <PageLink navigation={props.navigation} to={`/tasks/${s.taskVerified}`}>查看关联任务</PageLink>}</section>}
  </UiContextPanel>
}
