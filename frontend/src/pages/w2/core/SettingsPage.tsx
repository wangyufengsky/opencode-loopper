import { Alert, Input, InputNumber, Switch } from 'antd'
import { useEffect, useState } from 'react'
import { UiActionButton, UiDisclosure } from '@/foundation/components'
import { SemanticIcon, semanticLabel, type UiObjectKey } from '@/foundation/semanticRegistry'
import type { LeaveDecision } from '@/foundation/contracts/types'
import { canvasAreas, readCanvasPreferences, writeCanvasPreference, type CanvasPreferences, type CanvasArea, type CanvasRuntime } from '@/migration/canvasRuntime'
import { PageChrome, SkinControl, useLeaveGuard, useOwnerSnapshot, useRetainedOwner, type W2PageProps } from '../shared'
import { CoreStatus, Field } from './CoreUi'
import { CredentialsForm } from './CredentialsForm'
import { createCredentialsController } from './credentialsController'
import { createSettingsController, type SettingsSection } from './settingsController'
import { useCoreOwner } from './useCoreOwner'
import './core.css'

function storage() { try { return window.localStorage } catch { return undefined } }
function CanvasPreferencesForm() {
  const [preferences, setPreferences] = useState<CanvasPreferences>(() => readCanvasPreferences(storage()))
  const [message, setMessage] = useState(''), [failed, setFailed] = useState(false)
  function change(area: CanvasArea, runtime: CanvasRuntime) {
    const saved = writeCanvasPreference(storage(), area, runtime)
    setFailed(!saved)
    if (saved) setPreferences(previous => ({ ...previous, [area]: runtime }))
    setMessage(saved ? '已保存。下次重新进入对应页面时使用所选画布；当前打开的页面保持不变。' : '浏览器未能保存偏好，请检查本地存储权限后重试。')
  }
  return <>
    <p>遇到显示或交互问题时，可选择兼容画布。偏好仅保存在此浏览器，下次重新进入对应页面时生效。</p>
    <p>请先完成或恢复当前操作、保存草稿后再离开；更改偏好不会刷新页面，也不会重发操作。</p>
    {canvasAreas.map(area => <Field key={area.id} label={area.title}>{id => <select id={id} className="core-select" aria-label={area.title} value={preferences[area.id] ?? 'react'} onChange={event => change(area.id, event.target.value as CanvasRuntime)}>
      <option value="react">新版画布</option><option value="vue">兼容画布</option>
    </select>}</Field>)}
    {message && <Alert role={failed ? 'alert' : 'status'} type={failed ? 'error' : 'success'} title={message} />}
  </>
}
const sections: { id: SettingsSection; title: string; key: UiObjectKey }[] = [
  { id: 'canvas', title: '画布显示', key: 'section.settingsAppearance' }, { id: 'runtime', title: '服务设置', key: 'section.settingsRuntime' },
  { id: 'models', title: '模型服务', key: 'section.settingsModel' }, { id: 'limits', title: '执行限制', key: 'section.settingsLimits' },
  { id: 'retry', title: '重试等待', key: 'section.settingsLimits' }, { id: 'git-credentials', title: 'Git 账号', key: 'section.settingsGit' },
  { id: 'publication', title: '发布网络', key: 'section.settingsPublication' }, { id: 'demo', title: '开发辅助', key: 'object.settings' },
]
export function SettingsPage(props: W2PageProps) {
  const [owner] = useState(() => createSettingsController(props.legacy.task))
  const state = useCoreOwner(props, owner), task = useOwnerSnapshot(props.legacy.task)
  const [credentials] = useState(() => createCredentialsController(undefined, () => props.legacy.task.getSnapshot().usingDemo))
  const credentialState = useOwnerSnapshot(credentials)
  const [advanced, setAdvanced] = useState(false)
  useRetainedOwner(props, credentials, () => credentials.retire(true))
  useLeaveGuard(props, (): LeaveDecision => {
    const current = owner.canLeave(), child = credentials.canLeave()
    if (current.kind === 'BLOCK') return current
    if (child.kind === 'BLOCK') return child
    if (child.kind === 'CONFIRM_DISCARD') return { ...child, draftRevision: child.draftRevision + state.draftRevision }
    return current
  })
  useEffect(() => { void owner.initialize() }, [owner])
  const settings = state.settings, blocked = state.loading || owner.locked(), saving = state.mutation.busy
  const number = (label: string, value: number | undefined, min: number, max: number, change: (value: number) => void) => <Field label={label}>{id => <InputNumber id={id} aria-label={label} value={value} min={min} max={max} disabled={blocked} onChange={value => { if (value !== null) change(value) }} />}</Field>
  const text = (label: string, value: string, change: (value: string) => void) => <Field label={label}>{id => <Input id={id} aria-label={label} value={value} disabled={blocked} onChange={event => change(event.target.value)} />}</Field>
  const availability = blocked || state.refreshingModels || credentials.canLeave().kind === 'BLOCK' ? { kind: 'disabled' as const, reason: '请先等待设置读取，或核对原保存操作。' } : { kind: 'enabled' as const }
  return <div className="core-page"><PageChrome objectKey="page.settings" title="设置" actions={<>
    <SkinControl skin={props.skin} setSkin={props.setSkin} />
    {!['canvas', 'git-credentials'].includes(state.section) && <UiActionButton actionKey="settings.save" variant="primary" busy={saving} availability={availability} onAction={() => { void owner.save() }} />}
  </>} status={<><CoreStatus state={state} recover={owner.recover} />
    {state.section !== 'git-credentials' && credentialState.dirty && <Alert type="warning" title="Git 账号有未保存修改，请返回 Git 账号分区处理。" />}
    {state.section !== 'git-credentials' && !['IDLE', 'SETTLED'].includes(credentialState.mutation.phase) && <CoreStatus state={credentialState} recover={credentials.recover} />}
  </>}>
    <div className="core-settings-layout"><nav className="core-settings-nav" aria-label="设置分区">
      {sections.map(section => <button key={section.id} type="button" className="ant-btn ant-btn-default" aria-pressed={state.section === section.id} aria-controls={`settings-${section.id}`} onClick={() => owner.setSection(section.id)}>
        <SemanticIcon semanticKey={section.key} />{section.title}
      </button>)}
      <p className="w2-muted">各项修改请保存。分区切换保留草稿，设置不会自动写入。</p>
    </nav><div>
      {sections.map(section => <section key={section.id} id={`settings-${section.id}`} hidden={state.section !== section.id} className="core-section">
        <h2>{section.title}</h2>
        {section.id === 'canvas' && <CanvasPreferencesForm />}
        {section.id === 'git-credentials' && state.section === 'git-credentials' && <CredentialsForm owner={credentials} />}
        {section.id === 'runtime' && <>
          <div className="core-form-grid">
            {number('服务端口（重启生效）', settings.runtime.serverPort, 1, 65535, value => owner.change('runtime', 'serverPort', value))}
            <Field label="自动打开浏览器（下次启动生效）">{id => <Switch id={id} aria-label="自动打开浏览器（下次启动生效）" checked={settings.runtime.openBrowser} disabled={blocked} onChange={value => owner.change('runtime', 'openBrowser', value)} />}</Field>
          </div>
          {text('允许项目根（立即生效）', settings.runtime.allowedRoot, value => owner.change('runtime', 'allowedRoot', value))}
          {state.fieldError && <Alert role="alert" type="error" title={state.fieldError} />}
          <p className="core-field-note">留空允许本机任意绝对目录。限制项目根后，只能登记该目录下的项目。</p>
          <UiDisclosure titleKey="section.advanced" open={advanced} onOpenChange={setAdvanced}><div className="core-form-grid">
            {number('任务监控间隔（秒）', settings.runtime.monitorDelaySeconds, 1, 60, value => owner.change('runtime', 'monitorDelaySeconds', value))}
            {number('设计监控间隔（毫秒）', settings.runtime.designerMonitorDelayMillis, 250, 10000, value => owner.change('runtime', 'designerMonitorDelayMillis', value))}
            {number('中止清理尝试次数', settings.runtime.abortCleanupAttempts, 1, 10, value => owner.change('runtime', 'abortCleanupAttempts', value))}
          </div></UiDisclosure>
        </>}
        {section.id === 'models' && <>
          {text('命令行路径（下次会话生效）', settings.openCode.cliPath, value => owner.change('openCode', 'cliPath', value))}
          <Field label="连接方式（重启生效）">{id => <select id={id} className="core-select" aria-label="连接方式（重启生效）" value={settings.openCode.mode} disabled={blocked} onChange={event => owner.change('openCode', 'mode', event.target.value as 'managed' | 'auto' | 'http')}>
            <option value="managed">由 Loopper 托管</option><option value="auto">自动检测</option><option value="http">连接现有服务</option>
          </select>}</Field>
          {text('服务地址（重启生效）', settings.openCode.baseUrl, value => owner.change('openCode', 'baseUrl', value))}
          <UiActionButton actionKey="settings.refreshModels" busy={state.refreshingModels} availability={blocked ? { kind: 'disabled', reason: '请先核对原设置操作。' } : { kind: 'enabled' }} onAction={() => { void owner.refreshModels() }} />
          <Field label="提供方">{id => <select id={id} className="core-select" aria-label="提供方" value={settings.openCode.provider} disabled={blocked || state.refreshingModels} onChange={event => owner.change('openCode', 'provider', event.target.value)}>
            <option value="">请选择提供方</option>{[...new Set(state.models.map(item => item.provider))].sort().map(provider => <option key={provider} value={provider}>{provider}</option>)}
          </select>}</Field>
          <Field label="模型">{id => <select id={id} className="core-select" aria-label="模型" value={settings.openCode.model} disabled={blocked || state.refreshingModels} onChange={event => owner.change('openCode', 'model', event.target.value)}>
            <option value="">请选择模型</option>{state.models.filter(item => item.provider === settings.openCode.provider).map(model => <option key={model.id} value={model.model}>{model.label}</option>)}
          </select>}</Field>
          <p className="core-field-note">模型资格由服务端返回目录决定。默认模型只用于下次会话；已有会话保留原提供方与模型。</p>
          {state.modelError && <Alert role="alert" type="error" title={state.modelError} />}
          <UiDisclosure titleKey="section.advanced" open={advanced} onOpenChange={setAdvanced}><div className="core-form-grid">
            {number('连接超时（秒）', settings.openCode.connectTimeoutSeconds, 1, 120, value => owner.change('openCode', 'connectTimeoutSeconds', value))}
            {number('请求超时（秒）', settings.openCode.requestTimeoutSeconds, 1, 600, value => owner.change('openCode', 'requestTimeoutSeconds', value))}
            {number('启动超时（秒）', settings.openCode.startupTimeoutSeconds, 1, 300, value => owner.change('openCode', 'startupTimeoutSeconds', value))}
          </div></UiDisclosure>
        </>}
        {section.id === 'limits' && <>
          <p className="core-field-note">模板分析并发数只用于新建模板任务，不适用于需求主链。默认不因业务耗时限制中断任务。</p>
          <Field label="启用业务超时限制">{id => <Switch id={id} aria-label="启用业务超时限制" checked={settings.limits.timeoutEnabled ?? false} disabled={blocked} onChange={value => owner.change('limits', 'timeoutEnabled', value)} />}</Field>
          <div className="core-form-grid limits-grid">
            {number('模板分析并发数', settings.limits.templateAnalysisConcurrency ?? 4, 1, 16, value => owner.change('limits', 'templateAnalysisConcurrency', value))}
            {number('阶段最大尝试', settings.limits.maxStageAttempts, 1, 10, value => owner.change('limits', 'maxStageAttempts', value))}
            {number('任务最大尝试', settings.limits.maxTaskAttempts, 1, 50, value => owner.change('limits', 'maxTaskAttempts', value))}
            {number('会话异常阈值', settings.limits.sessionErrorLimit, 1, 10, value => owner.change('limits', 'sessionErrorLimit', value))}
            {settings.limits.timeoutEnabled && number('最大时长（分钟）', settings.limits.maxDurationMinutes, 1, 10080, value => owner.change('limits', 'maxDurationMinutes', value))}
            {settings.limits.timeoutEnabled && number('尝试超时（分钟）', settings.limits.attemptTimeoutMinutes, 1, 1440, value => owner.change('limits', 'attemptTimeoutMinutes', value))}
            {number('验证超时（分钟）', settings.limits.verifierTimeoutMinutes, 1, 120, value => owner.change('limits', 'verifierTimeoutMinutes', value))}
            {settings.limits.timeoutEnabled && number('设计超时（分钟）', settings.limits.designerTimeoutMinutes, 1, 1440, value => owner.change('limits', 'designerTimeoutMinutes', value))}
          </div>
        </>}
        {section.id === 'retry' && <div className="core-form-grid">
          {number('限流基础等待（秒）', settings.retryWait.rateLimitBaseSeconds, 5, 600, value => owner.change('retryWait', 'rateLimitBaseSeconds', value))}
          {number('限流最大等待（秒）', settings.retryWait.rateLimitMaxSeconds, settings.retryWait.rateLimitBaseSeconds, 3600, value => owner.change('retryWait', 'rateLimitMaxSeconds', value))}
          {number('会话异常基础等待（秒）', settings.retryWait.sessionBaseSeconds, 1, 300, value => owner.change('retryWait', 'sessionBaseSeconds', value))}
          {number('会话异常最大等待（秒）', settings.retryWait.sessionMaxSeconds, settings.retryWait.sessionBaseSeconds, 1800, value => owner.change('retryWait', 'sessionMaxSeconds', value))}
          {number('验证失败基础等待（秒）', settings.retryWait.verificationBaseSeconds, 1, 120, value => owner.change('retryWait', 'verificationBaseSeconds', value))}
          {number('验证失败最大等待（秒）', settings.retryWait.verificationMaxSeconds, settings.retryWait.verificationBaseSeconds, 600, value => owner.change('retryWait', 'verificationMaxSeconds', value))}
        </div>}
        {section.id === 'publication' && <>
          {text('允许 HTTP 的 Git Host', state.hosts, owner.setHosts)}
          <p className="core-field-note">多个 Host 使用英文逗号分隔，仅用于声明允许 HTTP 的 Git 服务器。</p>
          {text('GitLab Host', settings.publication.gitlabHost, value => owner.change('publication', 'gitlabHost', value))}
          {text('GitLab API 地址', settings.publication.gitlabApiBaseUrl, value => owner.change('publication', 'gitlabApiBaseUrl', value))}
          {settings.publication.gitlabApiBaseUrl.startsWith('http://') && <Alert type="warning" title="当前 GitLab API 使用 HTTP，网络传输不加密。服务器支持时可改为 HTTPS。" />}
          <div className="core-form-grid">
            {number('发布连接超时（秒）', settings.publication.connectTimeoutSeconds, 1, 120, value => owner.change('publication', 'connectTimeoutSeconds', value))}
            {number('发布请求超时（秒）', settings.publication.requestTimeoutSeconds, 1, 300, value => owner.change('publication', 'requestTimeoutSeconds', value))}
          </div>
        </>}
        {section.id === 'demo' && <div className="settings-demo">
          <p>当前来源：{task.usingDemo ? '演示数据' : '实时数据'}</p>
          <UiActionButton actionKey="settings.toggleDemo" target={task.usingDemo ? '退出演示数据' : '启用演示数据'} busy={state.switchingDemo} availability={owner.canLeave().kind === 'BLOCK' ? { kind: 'disabled', reason: '请先处理未完成操作。' } : { kind: 'enabled' }} onAction={() => { void owner.toggleDemo() }} />
        </div>}
      </section>)}
      <p className="core-field-note">{semanticLabel('settings.save')}后，以服务端回执为准；全局 Git 账号独立保存。</p>
    </div></div>
  </PageChrome></div>
}
