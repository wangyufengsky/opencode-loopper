import { computed, ref } from 'vue'
import { defineStore } from 'pinia'
import { api, ApiError, subscribeTaskEvents, type TaskEventStream } from '@/api/client'
import { demoArtifacts, demoProjects, demoRuntime, demoTasks } from '@/mock/demoData'
import type { Artifact, DirtyWorkspaceAction, Project, RuntimeInfo, Task, TaskEvent, TaskStatus } from '@/types/domain'

function copy<T>(value: T): T {
  return JSON.parse(JSON.stringify(value)) as T
}

function taskStatus(value: unknown): TaskStatus | undefined {
  const valid = ['PENDING_START', 'QUEUED', 'PREPARING', 'READY', 'RUNNING', 'VERIFYING', 'RETRY_WAIT', 'PAUSED', 'WAITING_INPUT', 'JUDGING', 'SUCCEEDED', 'FAILED', 'CANCELLED']
  return typeof value === 'string' && valid.includes(value) ? value as TaskStatus : undefined
}

export function reduceTaskEvent(task: Task, event: TaskEvent): Task {
  const next = copy(task)
  if (event.type === 'task.status' || event.type.startsWith('task.')) {
    const status = taskStatus(event.data.status ?? event.data.state)
    if (status) next.status = status
    next.updatedAt = event.at
  }
  if (event.type === 'stage.status' && typeof event.data.stageId === 'string' && next.stages) {
    const stage = next.stages.find((item) => item.id === event.data.stageId)
    if (stage && typeof event.data.status === 'string') {
      stage.status = event.data.status as typeof stage.status
    }
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
  return /^(task|stage|attempt|session|verification)\./.test(type)
}

export const useTaskStore = defineStore('task', () => {
  const projects = ref<Project[]>([])
  const tasks = ref<Task[]>([])
  const runtime = ref<RuntimeInfo>()
  const artifacts = ref<Artifact[]>([])
  const loading = ref(false)
  const error = ref<string>()
  const usingDemo = ref(import.meta.env.VITE_DEMO === 'true')
  const stream = ref<TaskEventStream>()
  const streamState = ref<'connected' | 'reconnecting' | 'idle'>('idle')
  // This is a browser SPA timer; keep it as a numeric DOM handle even though
  // Node's ambient types are available to Vitest and Maven's typecheck.
  let snapshotTimer: number | undefined

  const activeTasks = computed(() => tasks.value.filter((task) => ['RUNNING', 'VERIFYING', 'RETRY_WAIT', 'JUDGING'].includes(task.status)))
  const selectedTask = (id: string) => computed(() => tasks.value.find((task) => task.id === id))

  function activateDemo() {
    usingDemo.value = true
    projects.value = copy(demoProjects)
    tasks.value = copy(demoTasks)
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

  async function loadTask(id: string) {
    if (usingDemo.value) return tasks.value.find((task) => task.id === id)
    try {
      const detail = await api.getTask(id)
      artifacts.value = [
        ...artifacts.value.filter((artifact) => artifact.taskId !== id),
        ...(detail.artifacts ?? []),
      ]
      const index = tasks.value.findIndex((task) => task.id === id)
      if (index === -1) tasks.value.push(detail)
      else tasks.value[index] = detail
      return detail
    } catch (cause) {
      error.value = cause instanceof Error ? cause.message : '无法加载任务详情'
      return tasks.value.find((task) => task.id === id)
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
    try {
      const changed = await update(id)
      tasks.value = tasks.value.map((task) => task.id === id ? changed : task)
    } catch (cause) {
      error.value = cause instanceof Error ? cause.message : '任务操作失败'
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

  async function failDirtyWorkspace(id: string) {
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

  function watchTask(id: string) {
    stream.value?.close()
    if (snapshotTimer) window.clearTimeout(snapshotTimer)
    streamState.value = 'reconnecting'
    stream.value = subscribeTaskEvents(id, (event) => {
      tasks.value = tasks.value.map((task) => task.id === id ? reduceTaskEvent(task, event) : task)
      if (requiresTaskSnapshot(event.type) && !snapshotTimer) {
        // Events can arrive in short bursts (session + attempt + verification).
        // Coalesce them into one REST read after the persistence transaction ends.
        snapshotTimer = window.setTimeout(() => {
          snapshotTimer = undefined
          void loadTask(id)
        }, 180)
      }
    }, (state) => { streamState.value = state })
  }

  function stopWatching() {
    stream.value?.close()
    stream.value = undefined
    if (snapshotTimer) window.clearTimeout(snapshotTimer)
    snapshotTimer = undefined
    streamState.value = 'idle'
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

  return { projects, tasks, runtime, artifacts, loading, error, usingDemo, streamState, activeTasks, selectedTask, activateDemo, deactivateDemo, loadOverview, loadTask, updateTask, retryJudges, retryWaitingLoop, resolveDirtyWorkspace, failDirtyWorkspace, reworkTask, setTaskArchived, deleteArchivedTask, watchTask, stopWatching, refreshRuntime, restartRuntime, startRuntime }
})
