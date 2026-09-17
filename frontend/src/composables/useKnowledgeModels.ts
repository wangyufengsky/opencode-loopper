import { computed, onBeforeUnmount, ref } from 'vue'
import { api } from '@/api/client'
import type { AvailableModel } from '@/types/domain'

/** Global configuration is the default; catalog discovery only prepares alternative choices. */
export function useKnowledgeModels() {
  const models = ref<AvailableModel[]>([]), model = ref(''), defaultModel = ref(''), mode = ref('')
  const settingsError = ref(''), catalogError = ref(''), catalogLoading = ref(false), settingsLoading = ref(false)
  const chosen = ref(false), catalogLoaded = ref(false)
  let settingsSequence = 0, alive = true, pendingCatalog: Promise<void> | undefined
  const ready = computed(() => !!model.value && !settingsError.value && (model.value === defaultModel.value || models.value.some(item => item.id === model.value)))
  async function loadCatalog(force = false) {
    if (pendingCatalog) return pendingCatalog
    if (catalogLoaded.value && !force) return
    catalogLoading.value = true; catalogError.value = ''
    pendingCatalog = (async () => {
      try { const result = await api.getSettingsModels(); if (alive) { models.value = result; catalogLoaded.value = true } }
      catch { if (alive) catalogError.value = '暂时无法读取其他模型，仍可使用全局默认模型。' }
      finally { if (alive) catalogLoading.value = false; pendingCatalog = undefined }
    })()
    return pendingCatalog
  }
  async function loadSettings() {
    const ticket = ++settingsSequence; settingsLoading.value = true
    try {
      const settings = await api.getSettings()
      if (!alive || ticket !== settingsSequence) return
      const provider = settings.openCode.provider.trim(), name = settings.openCode.model.trim()
      defaultModel.value = provider && name ? `${provider}/${name}` : ''
      mode.value = settings.openCode.mode; settingsError.value = ''
      if (!chosen.value) model.value = defaultModel.value
    } catch { if (alive && ticket === settingsSequence) settingsError.value = '无法读取全局模型配置，请重新读取。' }
    finally { if (alive && ticket === settingsSequence) settingsLoading.value = false }
  }
  function select(value: string) { model.value = value; chosen.value = value !== defaultModel.value }
  onBeforeUnmount(() => { alive = false; settingsSequence++ })
  return { models, model, defaultModel, mode, ready, settingsError, settingsLoading, catalogError, catalogLoading, loadSettings, loadCatalog, select }
}
