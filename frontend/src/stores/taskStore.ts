import { computed, ref } from 'vue'
import { defineStore } from 'pinia'
import { api, ApiError, type TaskSummaryQuery } from '@/api/client'
import { demoArtifacts, demoProjects, demoRuntime, demoTasks, demoTaskStatusGroups } from '@/mock/demoData'
import type { Artifact, DirtyWorkspaceAction, Project, RuntimeInfo, Task, TaskEvent, TaskStatus } from '@/types/domain'
import { STAGE_STATUSES, TASK_STATUSES, requirePublicState } from '@/types/states'
import { createTaskEventSubscription } from './taskEventSubscription'
import { displayLabel } from '@/utils/displayLabels'

function copy<T>(value: T): T {
  return JSON.parse(JSON.stringify(value)) as T
}

function taskStatus(value: unknown): TaskStatus | undefined {
  return typeof value === 'string' && (TASK_STATUSES as readonly string[]).includes(value) ? value as TaskStatus : undefined
}

export function reduceTaskEvent(task: Task, event: TaskEvent): Task {
  const next = copy(task)
  if (event.type === 'task.status' || event.type.startsWith('task.') || event.type.startsWith('package.')) {
    const status = taskStatus(event.data.status ?? event.data.state)
    if (status) next.status = status
    next.updatedAt = event.at
  }
  if (event.type === 'stage.status' && typeof event.data.stageId === 'string' && next.stages) {
    const stage = next.stages.find((item) => item.id === event.data.stageId)
    if (stage && typeof event.data.status === 'string') stage.status = requirePublicState(STAGE_STATUSES, event.data.status, 'Stage event')
  }
  if (event.type === 'attempt.created' && typeof event.data.attemptCount === 'number') {
    next.attemptCount = event.data.attemptCount
  }
  return next
}

/** Lifecycle event payloads are deliberately compact; fetch the persisted task
 * snapshot so the Attempt timeline, verifier output and layered errors stay
 * authoritative without reconnecting EventSource or polling log events. */
export function requiresTaskSnapshot(type: string): boolean {
  return /^(task|package|stage|attempt|session|verification)\./.test(type)
}

export function aiOutputNotice(event: TaskEvent): string | undefined {
  if (event.type === 'story_binding.failed') {
    return typeof event.data.message === 'string' ? event.data.message : 'AI 工作量统计失败，任务继续执行。'
  }
  if (event.type !== 'AI_OUTPUT_NORMALIZED' && event.type !== 'AI_TOOL_LOOP_FINALIZER_STARTED') return undefined
  const role = typeof event.data.role === 'string' ? displayLabel(event.data.role) : 'AI'
  const corrections = Array.isArray(event.data.corrections)
    ? event.data.corrections.filter((item): item is string => typeof item === 'string').map(displayLabel).join('、') : ''
  return event.type === 'AI_OUTPUT_NORMALIZED'
    ? `${role} 输出已自动规范化${corrections ? `：${corrections}` : ''}`
    : `${role} 重复工具调用已停止，正在使用一次 MCP-only 收口会话`
}

export const useTaskStore = defineStore('task', () => {
  const projects = ref<Project[]>([])
  const tasks = ref<Task[]>([])
  const runtime = ref<RuntimeInfo>()
  const artifacts = ref<Artifact[]>([])
  const loading = ref(false)
  const auditLoading = ref<Record<string, boolean>>({})
  const auditErrors = ref<Record<string, string>>({})
  const taskNextCursor = ref<string>()
  const taskFacets = ref<Record<string, number>>({})
  const error = ref<string>()
  const usingDemo = ref(import.meta.env.VITE_DEMO === 'true')
  const taskNotices = ref<Record<string, string[]>>({})
  const streamState = ref<'connected' | 'reconnecting' | 'idle'>('idle')
  let summaryGeneration = 0
  let summaryQuery = ''
  let watchedTaskId: string | undefined
  const overviewRequests = new Map<string, number>()
  const auditRequests = new Map<string, number>()
  const nextRequest = (requests: Map<string, number>, id: string) => {
    const next = (requests.get(id) ?? 0) + 1
    requests.set(id, next)
    return next
  }
  function invalidateTaskReads(id: string) {
    nextRequest(overviewRequests, id)
    nextRequest(auditRequests, id)
    auditLoading.value[id] = false
  }
  function invalidateTaskSummaries() {
    summaryGeneration += 1
    taskNextCursor.value = undefined
    loading.value = false
  }

  const selectedTask = (id: string) => computed(() => tasks.value.find((task) => task.id === id))

  function activateDemo() {
    stopWatching()
    invalidateTaskSummaries()
    usingDemo.value = true
    projects.value = copy(demoProjects)
    tasks.value = copy(demoTasks)
    taskFacets.value = tasks.value.reduce<Record<string, number>>((facets, task) => {
      facets[task.status] = (facets[task.status] ?? 0) + 1
      const group = demoTaskStatusGroups[task.status]
      if (group) facets[group] = (facets[group] ?? 0) + 1
      facets.MATCHED_TOTAL = (facets.MATCHED_TOTAL ?? 0) + 1
      if (task.archived) facets.ARCHIVED_TOTAL = (facets.ARCHIVED_TOTAL ?? 0) + 1
      return facets
    }, { ARCHIVED_TOTAL: 0 })
    runtime.value = copy(demoRuntime)
    artifacts.value = copy(demoArtifacts)
    error.value = undefined
  }

  async function deactivateDemo() {
    usingDemo.value = false
    projects.value = []
    tasks.value = []
    runtime.value = undefined
    artifacts.value = []
    error.value = undefined
    await loadOverview()
  }

  async function loadOverview() {
    loading.value = true
    error.value = undefined
    if (usingDemo.value) {
      activateDemo()
      loading.value = false
      return
    }
    try {
      const [projectData, taskData, runtimeData] = await Promise.all([api.getProjects(), api.getTasks(), api.getRuntime()])
      projects.value = projectData
      tasks.value = taskData
      runtime.value = runtimeData
    } catch (cause) {
      error.value = cause instanceof ApiError ? cause.message : '无法连接本地服务'
    } finally {
      loading.value = false
    }
  }

  async function loadProjects(refresh = false) {
    if (usingDemo.value) {
      projects.value = copy(demoProjects)
      return projects.value
    }
    try {
      projects.value = await api.getProjects(refresh)
      return projects.value
    } catch (cause) {
      error.value = cause instanceof Error ? cause.message : '项目列表加载失败'
      return projects.value
    }
  }

  async function loadTaskSummaries(query: TaskSummaryQuery = {}, append = false) {
    const queryKey = JSON.stringify(query)
    if (append && (loading.value || !taskNextCursor.value || queryKey !== summaryQuery)) return
    const generation = ++summaryGeneration
    summaryQuery = queryKey
    if (!append) taskNextCursor.value = undefined
    loading.value = true
    error.value = undefined
    if (usingDemo.value) {
      activateDemo()
      taskNextCursor.value = undefined
      loading.value = false
      return
    }
    try {
      const page = await api.getTaskSummaries({ ...query, ...(append ? { cursor: taskNextCursor.value } : {}) })
      if (generation !== summaryGeneration || usingDemo.value) return
      tasks.value = append ? [...tasks.value, ...page.items.filter(item => !tasks.value.some(task => task.id === item.id))] : page.items
      taskNextCursor.value = page.nextCursor
      taskFacets.value = page.facets
    } catch (cause) {
      if (generation === summaryGeneration) error.value = cause instanceof ApiError ? cause.message : '任务列表加载失败'
    } finally {
      if (generation === summaryGeneration) loading.value = false
    }
  }

  async function loadTaskOverview(id: string) {
    if (usingDemo.value) return tasks.value.find((task) => task.id === id)
    const request = nextRequest(overviewRequests, id)
    let overview: Task
    try { overview = await api.getTaskOverview(id) }
    catch (cause) {
      if (request !== overviewRequests.get(id) || usingDemo.value) return tasks.value.find(task => task.id === id)
      throw cause
    }
    if (request !== overviewRequests.get(id) || usingDemo.value) return tasks.value.find(task => task.id === id)
    const index = tasks.value.findIndex((task) => task.id === id)
    const previous = index < 0 ? undefined : tasks.value[index]
    if (previous?.version !== undefined && overview.version !== undefined && overview.version < previous.version) return previous
    const detail = previous ? {
      ...overview,
      stages: overview.stages?.map(stage => ({ ...stage, attempts: (previous.attempts ?? []).filter(attempt => attempt.stageId === stage.id) })),
      attempts: previous.attempts,
      artifacts: previous.artifacts,
      errors: overview.errors ?? previous.errors,
      judges: overview.judges ?? previous.judges,
    } : overview
    if (index === -1) tasks.value.push(detail)
    else tasks.value[index] = detail
    return detail
  }

  async function loadTaskAudit(id: string) {
    if (usingDemo.value) return
    const request = nextRequest(auditRequests, id)
    auditLoading.value[id] = true
    delete auditErrors.value[id]
    try {
      const audit = await api.getTaskAudit(id)
      if (request !== auditRequests.get(id) || usingDemo.value) return
      artifacts.value = [...artifacts.value.filter((artifact) => artifact.taskId !== id), ...(audit.artifacts ?? [])]
      tasks.value = tasks.value.map((task) => task.id === id ? { ...task, ...audit,
        stages: task.stages?.map(stage => ({ ...stage, attempts: (audit.attempts ?? []).filter(attempt => attempt.stageId === stage.id) })),
      } : task)
    } catch (cause) {
      if (request === auditRequests.get(id)) auditErrors.value[id] = cause instanceof Error ? cause.message : '审计信息加载失败'
    } finally {
      if (request === auditRequests.get(id)) auditLoading.value[id] = false
    }
  }

  async function loadTask(id: string) {
    if (usingDemo.value) return tasks.value.find((task) => task.id === id)
    const expected = (overviewRequests.get(id) ?? 0) + 1
    try {
      const detail = await loadTaskOverview(id)
      if (overviewRequests.get(id) !== expected || usingDemo.value) return detail
      void loadTaskAudit(id)
      return detail
    } catch (cause) {
      if (overviewRequests.get(id) !== expected || usingDemo.value) return tasks.value.find(task => task.id === id)
      try {
        const legacy = await api.getTask(id)
        if (overviewRequests.get(id) !== expected || usingDemo.value) return tasks.value.find(task => task.id === id)
        const index = tasks.value.findIndex((task) => task.id === id)
        if (index === -1) tasks.value.push(legacy)
        else tasks.value[index] = legacy
        artifacts.value = [...artifacts.value.filter((artifact) => artifact.taskId !== id), ...(legacy.artifacts ?? [])]
        return legacy
      } catch {
        error.value = cause instanceof Error ? cause.message : '无法加载任务详情'
        return tasks.value.find((task) => task.id === id)
      }
    }
  }

  async function updateTask(id: string, action: 'start' | 'pause' | 'resume' | 'cancel') {
    const old = tasks.value.find((task) => task.id === id)
    if (!old) return
    if (usingDemo.value) {
      const status = action === 'start' || action === 'resume' ? 'RUNNING' : action === 'pause' ? 'PAUSED' : 'CANCELLED'
      tasks.value = tasks.value.map((task) => task.id === id ? { ...task, status, updatedAt: new Date().toISOString() } : task)
      return
    }
    const update = action === 'start' ? api.startTask : action === 'pause' ? api.pauseTask : action === 'resume' ? api.resumeTask : api.cancelTask
    error.value = undefined
    try {
      const changed = await update(id)
      invalidateTaskReads(id)
      tasks.value = tasks.value.map((task) => task.id === id ? changed : task)
    } catch (cause) {
      error.value = cause instanceof Error ? cause.message : '任务操作失败'
      if (action === 'cancel') throw cause
    }
  }

  async function retryJudges(id: string) {
    const old = tasks.value.find((task) => task.id === id)
    if (!old) return
    if (usingDemo.value) {
      tasks.value = tasks.value.map((task) => task.id === id ? { ...task, status: 'JUDGING', updatedAt: new Date().toISOString() } : task)
      return
    }
    error.value = undefined
    try {
      const changed = await api.retryTaskJudges(id)
      tasks.value = tasks.value.map((task) => task.id === id ? changed : task)
    } catch (cause) {
      error.value = cause instanceof Error ? cause.message : '双评审启动失败'
      throw cause
    }
  }

  async function retryWaitingLoop(id: string) {
    const old = tasks.value.find((task) => task.id === id)
    if (!old) return
    if (usingDemo.value) {
      tasks.value = tasks.value.map((task) => task.id === id ? { ...task, status: 'RUNNING', updatedAt: new Date().toISOString() } : task)
      return
    }
    error.value = undefined
    try {
      const changed = await api.retryWaitingTaskLoop(id)
      tasks.value = tasks.value.map((task) => task.id === id ? changed : task)
    } catch (cause) {
      error.value = cause instanceof Error ? cause.message : '循环重试启动失败'
      throw cause
    }
  }

  async function resolveDirtyWorkspace(id: string, input: {
    snapshotId: string
    resolutions: Array<{ path: string; action: DirtyWorkspaceAction }>
    commitMessage?: string
  }) {
    error.value = undefined
    try {
      const result = await api.resolveDirtyWorkspace(id, input)
      tasks.value = tasks.value.map((task) => task.id === id ? result.task : task)
      return result
    } catch (cause) {
      error.value = cause instanceof Error ? cause.message : '未提交文件处理失败'
      throw cause
    }
  }

  async function cancelDirtyWorkspace(id: string) {
    error.value = undefined
    try {
      const changed = await api.cancelDirtyWorkspace(id)
      tasks.value = tasks.value.map((task) => task.id === id ? changed : task)
      return changed
    } catch (cause) {
      error.value = cause instanceof Error ? cause.message : '无法终止等待清理的任务'
      throw cause
    }
  }

  async function reworkTask(id: string) {
    const parent = tasks.value.find((task) => task.id === id)
    if (!parent) return undefined
    if (usingDemo.value) {
      const child = { ...copy(parent), id: `${parent.id}-rework`, title: `${parent.title} · 重做`, status: 'RUNNING' as const,
        branch: `loopper/${parent.id}-rework`, createdAt: new Date().toISOString(), updatedAt: new Date().toISOString() }
      tasks.value.push(child)
      return child.id
    }
    error.value = undefined
    try {
      const recovery = await api.createTaskRecovery(id, 'REWORK_ALL_STAGES')
      const child = await api.startTask(recovery.taskId)
      const index = tasks.value.findIndex((task) => task.id === child.id)
      if (index === -1) tasks.value.push(child)
      else tasks.value[index] = child
      return child.id
    } catch (cause) {
      error.value = cause instanceof Error ? cause.message : '新分支重做启动失败'
      throw cause
    }
  }

  async function setTaskArchived(id: string, archived: boolean) {
    const old = tasks.value.find((task) => task.id === id)
    if (!old) return
    if (usingDemo.value) {
      tasks.value = tasks.value.map((task) => task.id === id ? { ...task, archived } : task)
      return
    }
    try {
      const changed = archived ? await api.archiveTask(id) : await api.restoreArchivedTask(id)
      tasks.value = tasks.value.map((task) => task.id === id ? changed : task)
    } catch (cause) {
      error.value = cause instanceof Error ? cause.message : archived ? '任务归档失败' : '任务恢复失败'
      throw cause
    }
  }

  async function deleteArchivedTask(id: string) {
    const old = tasks.value.find((task) => task.id === id)
    if (!old) return
    if (!old.archived) throw new Error('请先归档任务，再永久删除')
    if (usingDemo.value) {
      tasks.value = tasks.value.filter((task) => task.id !== id)
      artifacts.value = artifacts.value.filter((artifact) => artifact.taskId !== id)
      return
    }
    try {
      await api.deleteArchivedTask(id)
      tasks.value = tasks.value.filter((task) => task.id !== id)
      artifacts.value = artifacts.value.filter((artifact) => artifact.taskId !== id)
    } catch (cause) {
      error.value = cause instanceof Error ? cause.message : '历史任务删除失败'
      throw cause
    }
  }

  const subscription = createTaskEventSubscription({
    receive(id, event) {
      tasks.value = tasks.value.map(task => task.id === id ? reduceTaskEvent(task, event) : task)
      const notice = aiOutputNotice(event)
      if (notice) taskNotices.value[id] = [...new Set([...(taskNotices.value[id] ?? []), notice])].slice(-4)
    },
    overview: loadTaskOverview,
    audit: loadTaskAudit,
    needsOverview: requiresTaskSnapshot,
    state: state => { streamState.value = state },
    error: cause => { error.value = cause instanceof Error ? cause.message : '任务状态刷新失败，请重新打开任务。' },
  })

  function watchTask(id: string) {
    if (watchedTaskId) invalidateTaskReads(watchedTaskId)
    watchedTaskId = id
    subscription.watch(id)
  }

  function stopWatching() {
    if (watchedTaskId) invalidateTaskReads(watchedTaskId)
    watchedTaskId = undefined
    subscription.stop()
  }

  async function refreshRuntime() {
    if (usingDemo.value) {
      runtime.value = { ...demoRuntime, status: 'STARTING', checkedAt: new Date().toISOString() }
      window.setTimeout(() => { runtime.value = { ...demoRuntime, checkedAt: new Date().toISOString() } }, 900)
      return
    }
    try { runtime.value = await api.getRuntime() } catch (cause) { error.value = cause instanceof Error ? cause.message : 'Runtime 状态检查失败' }
  }

  async function restartRuntime() {
    if (usingDemo.value) {
      runtime.value = { ...demoRuntime, status: 'STARTING', checkedAt: new Date().toISOString() }
      window.setTimeout(() => { runtime.value = { ...demoRuntime, checkedAt: new Date().toISOString() } }, 900)
      return
    }
    error.value = undefined
    try { runtime.value = await api.restartRuntime() } catch (cause) { error.value = cause instanceof Error ? cause.message : 'Runtime 重启失败' }
  }

  async function startRuntime() {
    if (usingDemo.value) {
      runtime.value = { ...demoRuntime, checkedAt: new Date().toISOString() }
      return runtime.value
    }
    error.value = undefined
    try {
      runtime.value = await api.startRuntime()
      return runtime.value
    } catch (cause) {
      error.value = cause instanceof Error ? cause.message : 'OpenCode 启动与连接检查失败'
      return undefined
    }
  }

  return { projects, tasks, runtime, artifacts, taskNotices, loading, auditLoading, auditErrors,
    taskNextCursor, taskFacets, error, usingDemo, streamState, selectedTask, activateDemo,
    deactivateDemo, loadOverview, loadProjects, loadTaskSummaries, invalidateTaskSummaries, loadTaskOverview, loadTaskAudit, loadTask,
    updateTask, retryJudges, retryWaitingLoop, resolveDirtyWorkspace, cancelDirtyWorkspace, reworkTask,
    setTaskArchived, deleteArchivedTask, watchTask, stopWatching, refreshRuntime, restartRuntime, startRuntime }
})
