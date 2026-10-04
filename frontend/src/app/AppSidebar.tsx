import { useSyncExternalStore } from 'react'
import { useLocation } from 'react-router'
import { SemanticIcon, semanticLabel, type UiObjectKey } from '@/foundation/semanticRegistry'
import { statusLabel } from '@/utils/displayLabels'
import { PageLink } from '@/pages/w2/shared'
import type { ApplicationOwnership } from './ownership'

const navigation: Array<[string, UiObjectKey]> = [
  ['/', 'nav.home'], ['/projects', 'nav.projects'], ['/knowledge', 'nav.knowledge'], ['/ppt', 'nav.ppt'], ['/requirements', 'nav.requirements'], ['/workflows', 'nav.workflows'], ['/designs', 'nav.designs'], ['/tasks', 'nav.tasks'], ['/inbox', 'nav.inbox'], ['/insights', 'nav.insights'], ['/template-tasks', 'nav.templateTasks'],
]
const system: Array<[string, UiObjectKey]> = [['/runtime', 'nav.runtime'], ['/tools', 'nav.tools'], ['/databases', 'nav.databases'], ['/roles', 'nav.roles'], ['/settings', 'nav.settings']]
export function AppSidebar({ application }: { application: ApplicationOwnership }) {
  const location = useLocation(), path = location.pathname + location.search + location.hash
  const task = useSyncExternalStore(application.task.subscribe, application.task.getSnapshot, application.task.getSnapshot)
  const {knowledgePath}=useSyncExternalStore(application.subscribeNavigation,application.getNavigationSnapshot,application.getNavigationSnapshot)
  const appNavigation = {
    go: async (to: import('@/pages/w2/shared/types').W2Target, replace?: boolean) => {
      const current = application.current
      if (current?.active) return current.navigation.go(to, replace)
      await application.driver?.navigate(typeof to === 'string' ? to : to.path, { replace }); return application.healthy
    },
  } as import('@/pages/w2/shared/types').W2Navigation
  const link = (to: string, key: UiObjectKey) => <PageLink key={to} to={to === '/knowledge' ? knowledgePath : to} navigation={appNavigation} className={`nav-item${(to === '/' ? path === '/' : location.pathname.startsWith(to)) ? ' router-link-active' : ''}`}><SemanticIcon semanticKey={key} /><span>{semanticLabel(key)}</span></PageLink>
  return <aside className="app-sidebar">
    <PageLink className="brand" to="/" navigation={appNavigation} aria-label="OpenCode Loopper 首页"><span className="brand-mark"><SemanticIcon semanticKey="nav.tasks" /></span><span className="brand-copy">OpenCode Loopper<small>本地控制台</small></span></PageLink>
    <p className="nav-label">工作区</p><nav aria-label="主导航">{navigation.map(([to, key]) => link(to, key))}</nav>
    <p className="nav-label">系统</p><nav aria-label="系统导航">{system.map(([to, key]) => link(to, key))}</nav>
    <div className="sidebar-spacer" /><PageLink className="runtime-mini" to="/runtime" navigation={appNavigation}>
      <div className="runtime-mini-title"><span>OpenCode 运行环境</span><span className={`status-badge ${!task.runtime ? 'status-pending' : task.runtime.status === 'ONLINE' ? 'status-success' : 'status-danger'}`}>{task.runtime ? statusLabel(task.runtime.status) : '未检查'}</span></div><strong>{task.runtime?.model ?? '等待连接'}</strong>
    </PageLink>
  </aside>
}
