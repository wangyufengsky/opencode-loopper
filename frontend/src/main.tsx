import { mountReactApplication } from '@/app/bootstrap'
import '@/styles/tokens.css'
import '@/styles/app.css'
const host = document.getElementById('app')
if (!host) throw new Error('应用根容器不存在')
const retained = import.meta.hot?.data.application as ReturnType<typeof mountReactApplication> | undefined
// Failed cleanup is terminal: do not create another command/subscription owner.
if (retained && !retained.application.active) {
  const notice = document.createElement('p'); notice.setAttribute('role', 'alert')
  notice.textContent = '应用资源清理失败，已停止接管。请保留原操作记录并刷新页面重新核对。'
  host.replaceChildren(notice)
}
const application = retained ?? mountReactApplication(host)
if (import.meta.hot) import.meta.hot.dispose(data => { if (!application.unmount()) data.application = application })
