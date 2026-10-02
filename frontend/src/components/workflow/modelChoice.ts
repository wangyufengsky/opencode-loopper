import { onScopeDispose, ref } from 'vue'
import { api } from '@/api/client'
import type { WorkflowModel } from '@/types/domain'

/** Selection belongs to the requirement, not to the lifetime of its settings panel. */
export function useWorkflowModel() {
  const model = ref<WorkflowModel | null>(null), loading = ref(false), error = ref('')
  let generation = 0, attempted = false, source: 'none' | 'default' | 'control' | 'user' = 'none'
  let pending: Promise<void> | null = null

  function invalidate() { generation++; pending = null; loading.value = false; error.value = '' }
  function reset() { invalidate(); model.value = null; source = 'none'; attempted = false }
  function choose(value: WorkflowModel | null) { invalidate(); model.value = value ? { ...value } : null; source = 'user' }
  function adoptControl(value: WorkflowModel | null | undefined) {
    if (!value || source === 'user') return
    invalidate(); model.value = { ...value }; source = 'control'
  }
  function loadDefault(): Promise<void> {
    if (model.value) return Promise.resolve()
    if (pending) return pending
    const current = ++generation
    attempted = true; loading.value = true; error.value = ''
    pending = (async () => {
      try {
        const settings = await api.getSettings()
        if (current !== generation || model.value) return
        if (settings.openCode.provider && settings.openCode.model) {
          model.value = { providerId: settings.openCode.provider, modelId: settings.openCode.model, thinking: null }; source = 'default'
        } else error.value = '尚未配置默认执行模型，请在“需求与资料”中选择模型。'
      } catch {
        if (current === generation && !model.value) error.value = '默认执行模型暂时无法读取，请重试或在“需求与资料”中选择模型。'
      } finally { if (current === generation) { pending = null; loading.value = false } }
    })()
    return pending
  }
  function initialize() { return pending ?? (!attempted && !model.value ? loadDefault() : Promise.resolve()) }
  onScopeDispose(invalidate)
  return { model, loading, error, reset, choose, adoptControl, loadDefault, initialize }
}
