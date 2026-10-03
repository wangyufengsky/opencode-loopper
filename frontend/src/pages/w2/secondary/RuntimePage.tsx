import { useEffect, useRef, useState } from 'react'
import { UiActionButton } from '@/foundation/components'
import { statusLabel, userFacingError } from '@/utils/displayLabels'
import { formatDateTime } from '@/utils/dateTime'
import { PageChrome, useLeaveGuard, useOwnerSnapshot } from '../shared'
import type { W2PageProps } from '../shared/types'
import { MutationFeedback } from './feedback'
import { useMutationOwner } from './state'
import './secondary.css'

export function RuntimePage(props: W2PageProps) {
  const task = props.legacy.task, snapshot = useOwnerSnapshot(task), runtime = snapshot.runtime
  const command = useMutationOwner(props), [notice, setNotice] = useState(''), [readError, setReadError] = useState('')
  const mounted = useRef(true)
  useLeaveGuard(props, () => command.owner.canLeave())
  useEffect(() => { mounted.current = true; let current = true; void task.refreshRuntime().catch(failure => { if (current) setReadError(userFacingError(failure)) }); return () => { current = false; mounted.current = false } }, [task])
  const inspect = { kind: 'READ_ORIGINAL' as const, readOriginal: async () => { await task.refreshRuntime(); return { kind: 'UNCONFIRMED' as const } } }
  const starting = command.snapshot.busy || runtime?.status === 'STARTING'
  const start = () => {
    setNotice('')
    void command.owner.run('/runtime/opencode/start', {}, async () => {
      const result = await task.startRuntime()
      if (!result) throw new Error('未收到启动结果，请保留原操作并检查服务状态')
      return result
    }, async (result, context) => { context.apply(() => { if (result.status === 'ONLINE') setNotice('OpenCode 已启动并通过连接检查') }) }, 'POST', inspect)
  }
  const restart = () => {
    setNotice('')
    void command.owner.run('/runtime/opencode/restart', {}, async () => task.restartRuntime(), async () => {}, 'POST', inspect)
  }
  const mcp = runtime?.internalMcp, mcpLabel = mcp?.status === 'CONNECTED' ? '已就绪' : mcp?.status === 'CONNECTING' ? '连接中' : mcp?.status === 'UNAVAILABLE' ? '不可用' : mcp?.status === 'INACTIVE' ? '未启用' : '—'
  return <PageChrome title="OpenCode 运行环境" objectKey="nav.runtime" actions={runtime?.managed
    ? <UiActionButton actionKey="runtime.restart" variant="primary" busy={starting} availability={command.blocked ? { kind: 'disabled', reason: '请先核对原操作' } : { kind: 'enabled' }} onAction={restart} />
    : runtime?.startupFailure ? <UiActionButton actionKey="runtime.start" variant="primary" busy={starting} availability={command.blocked ? { kind: 'disabled', reason: '请先核对原操作' } : { kind: 'enabled' }} onAction={start} />
      : <UiActionButton actionKey="runtime.detect" busy={starting} onAction={() => { void task.refreshRuntime().then(() => { if (mounted.current) setReadError('') }).catch(failure => { if (mounted.current) setReadError(userFacingError(failure)) }) }} />}
    status={<><MutationFeedback owner={command.owner} />{(snapshot.error || readError) && <p role="alert">{userFacingError(snapshot.error || readError)}</p>}{command.snapshot.phase === 'UNKNOWN' && <p>当前运行状态可显式读取，但此接口没有原命令回执，不能仅凭在线状态确认原启动或重启。</p>}{notice && <p role="status">{notice}</p>}{runtime?.startupFailure && <div className="w2-secondary-status runtime-startup-error" role="alert"><h2>OpenCode 启动失败</h2><p>{userFacingError(runtime.startupFailure, 'OpenCode 启动失败，请检查配置后重试')}</p>{runtime.endpoint && <p>尝试地址：{runtime.endpoint}</p>}</div>}</>}>
    {runtime ? <div className="w2-secondary-grid"><section className="w2-secondary-record"><h2>{runtime.version ? `OpenCode ${runtime.version}` : '等待连接'}</h2><p>{statusLabel(runtime.status)}</p><p className="w2-secondary-muted">当前模型：<span translate="no">{runtime.model || '未配置'}</span></p></section><details className="w2-secondary-record"><summary>{runtime.managed ? '受管进程' : '运行连接'}</summary><dl className="w2-secondary-definition">
      <div className="loopper-version"><dt>Loopper 版本</dt><dd translate="no">{runtime.loopperVersion ?? '未知'}</dd></div><div><dt>{runtime.startupFailure ? '尝试地址' : '监听地址'}</dt><dd>{runtime.endpoint ?? '—'}</dd></div><div><dt>进程 PID</dt><dd>{runtime.pid ?? '—'}</dd></div><div><dt>进程类型</dt><dd>{runtime.managed ? 'Loopper 受管' : runtime.startupFailure ? '未启动' : '外部服务'}</dd></div>
      {runtime.managed && <><div><dt>受管代次</dt><dd className="managed-generation">{runtime.generation ?? '—'}</dd></div><div><dt>内部 MCP</dt><dd>{mcpLabel}{mcp?.configured ? ' · 配置已注入' : ''}</dd></div></>}<div><dt>上次检查</dt><dd><time dateTime={runtime.checkedAt}>{formatDateTime(runtime.checkedAt)}</time></dd></div>
    </dl></details></div> : <p className="w2-secondary-empty">尚未读取运行环境</p>}
  </PageChrome>
}
