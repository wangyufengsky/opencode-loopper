import { Alert, Input } from 'antd'
import { useEffect, useState } from 'react'
import { UiActionButton, UiConfirmDialog } from '@/foundation/components'
import { useOwnerSnapshot } from '../shared'
import type { createCredentialsController } from './credentialsController'
import { CoreStatus, Field } from './CoreUi'

export function CredentialsForm({ owner }: { owner: ReturnType<typeof createCredentialsController> }) {
  const state = useOwnerSnapshot(owner)
  const [disabling, setDisabling] = useState(false)
  useEffect(() => { const release = owner.attachView(); void owner.initialize(); return release }, [owner])
  const disabled = state.loading || state.testing || owner.demo || owner.locked()
  const availability = disabled ? { kind: 'disabled' as const, reason: owner.demo ? '演示模式不保存或验证 Git 凭据。' : '请先等待或核对原账号操作。' } : { kind: 'enabled' as const }
  return <section aria-label="Git 账号管理" className="core-credentials">
    <p className="core-field-note">{owner.projectId ? '项目默认继承全局 Git 账号，也可以单独配置。' : '为同一 Git 服务器设置默认账号，项目默认继承，可单独覆盖。'}</p>
    {owner.demo && <Alert type="info" title="演示模式不保存或验证 Git 凭据" />}
    <CoreStatus state={state} recover={owner.recover} />
    {owner.projectId && <Field label="Git 账号来源">{id => <select id={id} className="core-select" aria-label="Git 账号来源" value={state.mode} disabled={disabled} onChange={event => owner.change('mode', event.target.value as 'INHERIT' | 'CUSTOM')}>
      <option value="INHERIT">继承全局账号</option><option value="CUSTOM">使用独立账号</option>
    </select>}</Field>}
    {owner.projectId && state.mode === 'INHERIT' ? <p>{state.saved?.source === 'GLOBAL' && state.saved.configured ? `全局账号：${state.saved.username} · ${state.saved.serverUrl}` : '保存继承设置后使用全局账号；可在设置页查看和维护。'}</p>
      : <>
        <Field label="Git 服务器地址">{id => <Input id={id} aria-label="Git 服务器地址" autoComplete="off" value={state.serverUrl} disabled={disabled} onChange={event => owner.change('serverUrl', event.target.value)} />}</Field>
        <Field label="用户名">{id => <Input id={id} aria-label="Git 用户名" autoComplete="off" value={state.username} disabled={disabled} onChange={event => owner.change('username', event.target.value)} />}</Field>
        <Field label="认证方式">{id => <select id={id} className="core-select" aria-label="Git 认证方式" value={state.kind} disabled={disabled} onChange={event => owner.change('kind', event.target.value as 'TOKEN' | 'PASSWORD')}>
          <option value="TOKEN">访问令牌（推荐）</option><option value="PASSWORD">账号密码</option>
        </select>}</Field>
        <Field label={state.kind === 'TOKEN' ? '访问令牌' : '密码'}>{id => <Input.Password id={id} aria-label="Git 密码或令牌" autoComplete="new-password" value={state.secret} disabled={disabled}
          placeholder={state.saved?.mode === 'CUSTOM' && state.saved.configured ? '已加密保存；留空保持不变' : '请输入密码或令牌'} onChange={event => owner.change('secret', event.target.value)} />}</Field>
        <p className="core-field-note">仅用于 HTTP(S) Git 操作。启用双重认证的 GitLab 通常需要访问令牌；账号密码是否可用取决于服务器配置。</p>
        {state.serverUrl.startsWith('http://') && <Alert type="warning" title="当前地址使用 HTTP，网络传输不加密；服务器支持时请使用 HTTPS。" />}
      </>}
    <Field label={owner.projectId ? '验证仓库地址（留空使用项目 origin）' : '验证仓库地址'}>{id => <Input id={id} aria-label="Git 验证仓库地址" autoComplete="off" value={state.repositoryUrl} disabled={disabled} onChange={event => owner.change('repositoryUrl', event.target.value)} />}</Field>
    <p className="core-field-note">验证仅检查仓库读取权限，不会推送代码；验证成功后仍需保存。密码和令牌不会回显。</p>
    {state.probe && <Alert role="status" type={state.probe.success ? 'success' : 'error'} title={state.probe.message} />}
    <div className="core-actions">
      <UiActionButton actionKey="settings.saveCredentials" variant="primary" availability={availability} busy={state.mutation.busy} onAction={() => { void owner.save() }} />
      <UiActionButton actionKey="settings.testCredentials" availability={availability} busy={state.testing} onAction={() => { void owner.test() }} />
      <UiActionButton actionKey="ui.refresh" target="Git 账号" availability={availability} onAction={() => { void owner.load() }} />
      {!owner.projectId && state.saved?.configured && <UiActionButton actionKey="settings.disableCredentials" variant="danger" availability={availability} onAction={() => setDisabling(true)} />}
    </div>
    <UiConfirmDialog open={disabling} title="停用全局 Git 账号" confirmActionKey="settings.disableCredentials" onCancel={() => setDisabling(false)}
      policy={disabled ? { kind: 'block', reason: '请先等待或核对原账号操作。' } : { kind: 'allow' }}
      onConfirm={() => { if (!disabled) { setDisabling(false); void owner.save(true) } }}>
      停用后，继承全局账号的项目将使用原有系统 Git 凭据。项目独立账号不受影响。
    </UiConfirmDialog>
  </section>
}
