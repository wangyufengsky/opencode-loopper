<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { isNavigationFailure, onBeforeRouteLeave, onBeforeRouteUpdate, useRoute, useRouter } from 'vue-router'
import type { ComponentType } from 'react'
import { useTaskStore } from '@/stores/taskStore'
import { currentSkin, applySkin } from '@/themes/state'
import { mountReactView } from '@/react/bridge'
import { navigateAcceptedHandoff } from '@/foundation/contracts/receipt'
import type { NavigationRequest } from '@/foundation/contracts/navigation'
import type { LeaveDecision } from '@/foundation/contracts/types'
import type { W2PageProps, W2Target } from '@/pages/w2/shared/types'
import { createRouteLeaveCoordinator } from '@/pages/w2/shared/leave'
import { createW2TaskPort } from './w2TaskPort'
import { w2PageLoaders } from './w2Routes'
import { W2BridgeView, type BridgeDialogSnapshot, type BridgeDialogPort } from './w2BridgeView'

const route = useRoute(), router = useRouter(), store = useTaskStore(), host = ref<HTMLElement>()
const failed = ref('')
type ViewProps = Parameters<typeof W2BridgeView>[0]
let view: ReturnType<typeof mountReactView<ViewProps>> | undefined
let currentPath = '', sequence = 0, active = true
let component: ComponentType<W2PageProps> | undefined
let pageProps: W2PageProps | undefined
let taskBoundary: ReturnType<typeof createW2TaskPort> | undefined
const owners = new Map<object, () => void>()
const dialogListeners = new Set<() => void>()
let dialogSnapshot: BridgeDialogSnapshot = { open: false, reason: '', blocked: false, notice: '' }
let resolveConfirmation: ((value: boolean) => void) | undefined
let readConfirmation: (() => LeaveDecision) | undefined
let requested: NavigationRequest | undefined
const publishDialog = (value: BridgeDialogSnapshot) => {
  if (JSON.stringify(value) === JSON.stringify(dialogSnapshot)) return
  dialogSnapshot = Object.freeze(value)
  for (const listener of dialogListeners) listener()
}
const dialog: BridgeDialogPort = {
  getSnapshot: () => dialogSnapshot,
  subscribe: listener => { dialogListeners.add(listener); return () => { dialogListeners.delete(listener) } },
  choose(allow) {
    if (!resolveConfirmation) return
    const current = readConfirmation?.()
    if (allow && current?.kind === 'BLOCK') {
      publishDialog({ ...dialogSnapshot, blocked: true, reason: current.reason, notice: current.reason }); return
    }
    const complete = resolveConfirmation; resolveConfirmation = undefined; readConfirmation = undefined
    publishDialog({ ...dialogSnapshot, open: false, blocked: false })
    complete(allow)
  },
}
function coordinator() {
  return createRouteLeaveCoordinator((decision, current) => new Promise<boolean>(resolve => {
    resolveConfirmation = resolve; readConfirmation = current
    publishDialog({ open: true, reason: decision.description, blocked: false, notice: '' })
  }))
}
let leaves = coordinator()
function render() {
  if (!active || !view || !component || !pageProps) return
  pageProps = { ...pageProps, route: { path: route.path, fullPath: route.fullPath, query: { ...route.query } as W2PageProps['route']['query'], params: { ...route.params } }, skin: currentSkin.value }
  view.render({ page: component, pageProps, dialog })
}
function destroyPage() {
  sequence++
  resolveConfirmation?.(false); resolveConfirmation = undefined; readConfirmation = undefined
  leaves.dispose()
  let cleanupFailed = false
  try { view?.unmount() } catch { cleanupFailed = true }
  view = undefined
  const cleanups = [...owners.values()]; owners.clear()
  // Retire every actual owner even if one disposer fails; no cleanup of another instance.
  for (const dispose of cleanups) { try { dispose() } catch { cleanupFailed = true } }
  try { taskBoundary?.dispose() } catch { cleanupFailed = true }
  taskBoundary = undefined
  pageProps = undefined; component = undefined
  dialogListeners.clear(); dialogSnapshot = { open: false, reason: '', blocked: false, notice: '' }
  if (cleanupFailed) failed.value = '页面资源未完全释放，请保留当前操作身份并重新检查'
  return !cleanupFailed
}
async function mountPage() {
  failed.value = ''
  if (!destroyPage()) return
  leaves = coordinator(); currentPath = route.path
  const ticket = sequence, path = currentPath
  const load = w2PageLoaders[path]
  if (!load || !host.value) { failed.value = '此页面尚未接入，请返回原入口'; return }
  try {
    const loaded = await load()
    if (!active || ticket !== sequence || route.path !== path || !host.value) return
    taskBoundary = createW2TaskPort(store)
    component = loaded
    const go = async (to: W2Target, replace = false, handoff?: NavigationRequest['handoff']): Promise<boolean> => {
      if (!active || !pageProps || route.path !== path) return false
      const request: NavigationRequest = { destination: router.resolve(to).fullPath, replace, handoff }
      requested = request
      try { const result = await (replace ? router.replace(to) : router.push(to)); return !isNavigationFailure(result) && route.fullPath === request.destination }
      finally { if (requested === request) requested = undefined }
    }
    pageProps = {
      route: { path: route.path, fullPath: route.fullPath, query: { ...route.query } as W2PageProps['route']['query'], params: { ...route.params } },
      skin: currentSkin.value, setSkin: id => applySkin(id), legacy: { task: taskBoundary.port },
      lifecycle: { retain: (key, dispose) => { if (active && route.path === path && !owners.has(key)) owners.set(key, dispose) } },
      navigation: {
        go, goAccepted: (to, permit) => navigateAcceptedHandoff(permit, router.resolve(to).fullPath, () => go(to, false, permit)),
        back: () => { if (active && route.path === path) router.back() },
        registerGuard: guard => leaves.register(guard),
        guardChanged: () => {
          if (!readConfirmation) return
          const latest = readConfirmation()
          publishDialog({ ...dialogSnapshot, blocked: latest.kind === 'BLOCK', reason: latest.kind === 'BLOCK' ? latest.reason : latest.kind === 'CONFIRM_DISCARD' ? latest.description : dialogSnapshot.reason })
        },
      },
    }
    view = mountReactView(host.value, W2BridgeView, { strict: true }); render()
  } catch { if (active && ticket === sequence) failed.value = '页面加载失败，请返回并重新打开' }
}
async function canNavigate(to: { fullPath: string }) {
  const request = requested?.destination === to.fullPath ? requested : { destination: to.fullPath }
  const result = await leaves.allow(request)
  if (!result && active) {
    const decision = leaves.read(request)
    if (decision.kind === 'BLOCK') publishDialog({ ...dialogSnapshot, notice: decision.reason })
  }
  return result
}
onBeforeRouteLeave(canNavigate)
onBeforeRouteUpdate(canNavigate)
function beforeUnload(event: BeforeUnloadEvent) {
  if (leaves.read().kind === 'ALLOW') return
  event.preventDefault(); event.returnValue = ''
}
onMounted(() => { window.addEventListener('beforeunload', beforeUnload); void mountPage() })
watch(() => [route.fullPath, currentSkin.value.id], () => {
  if (route.path !== currentPath) void mountPage()
  else render()
}, { flush: 'post' })
onBeforeUnmount(() => { active = false; window.removeEventListener('beforeunload', beforeUnload); destroyPage() })
</script>
<template><p v-if="failed" role="alert">{{ failed }}</p><div ref="host" data-page-runtime="react" data-w2-route-bridge /></template>
