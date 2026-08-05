<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { Icon } from '@iconify/vue'
import { ElMessage } from 'element-plus'
import PageHeader from '@/components/PageHeader.vue'
import { api, ApiError } from '@/api/client'
import type { AppSettings, AvailableModel } from '@/types/domain'
import { useTaskStore } from '@/stores/taskStore'

const store = useTaskStore()
const settings = ref<AppSettings>({ cliPath: 'opencode', allowedRoot: '', provider: '', model: '', maxTaskAttempts: 12, timeoutMinutes: 30, autoApprove: false })
const availableModels = ref<AvailableModel[]>([])
const loading = ref(true)
const saving = ref(false)
const refreshingModels = ref(false)
const fieldError = ref('')
const modelError = ref('')

const providers = computed(() => [...new Set(availableModels.value.map((item) => item.provider))].sort())
const providerModels = computed(() => availableModels.value.filter((item) => item.provider === settings.value.provider))

watch(() => settings.value.provider, (provider, previous) => {
  if (provider === previous || providerModels.value.length === 0 || providerModels.value.some((item) => item.model === settings.value.model)) return
  settings.value.model = providerModels.value[0]?.model ?? ''
})

function isAbsoluteProjectPath(value: string) {
  return value.startsWith('/') || /^[A-Za-z]:[\\/]/.test(value) || /^\\\\[^\\]+\\[^\\]+/.test(value)
}

function message(error: unknown) {
  return error instanceof ApiError || error instanceof Error ? error.message : '请求失败，请检查 OpenCode CLI 配置。'
}

async function refreshModels(selectFallback = true) {
  refreshingModels.value = true
  modelError.value = ''
  try {
    availableModels.value = await api.getSettingsModels(settings.value.cliPath.trim())
    if (!providers.value.includes(settings.value.provider) && selectFallback) settings.value.provider = providers.value[0] ?? ''
    if (!providerModels.value.some((item) => item.model === settings.value.model) && selectFallback) settings.value.model = providerModels.value[0]?.model ?? ''
  } catch (error) {
    availableModels.value = []
    modelError.value = message(error)
  } finally {
    refreshingModels.value = false
  }
}

async function load() {
  loading.value = true
  try {
    settings.value = await api.getSettings()
    await refreshModels(true)
  } catch (error) {
    ElMessage.error(message(error))
  } finally {
    loading.value = false
  }
}

async function save() {
  fieldError.value = ''
  modelError.value = ''
  if (!settings.value.cliPath.trim()) { fieldError.value = 'OpenCode CLI 路径不能为空。'; return }
  if (settings.value.allowedRoot.trim() && !isAbsoluteProjectPath(settings.value.allowedRoot.trim())) { fieldError.value = '允许项目根必须是 POSIX、Windows 盘符或 UNC 绝对路径。'; return }
  if (!settings.value.provider || !settings.value.model) { modelError.value = '请先刷新并选择一个可用模型。'; return }
  saving.value = true
  try {
    settings.value = await api.updateSettings(settings.value)
    ElMessage.success(`设置已保存；新建 Session 将使用 ${settings.value.provider}/${settings.value.model}`)
  } catch (error) {
    ElMessage.error(message(error))
  } finally {
    saving.value = false
  }
}

onMounted(load)
</script>

<template>
  <PageHeader eyebrow="System / Settings" title="执行策略与模型">
    <template #actions><el-button class="settings-save" type="primary" :loading="saving" :disabled="loading" @click="save"><Icon icon="lucide:save" />保存设置</el-button></template>
  </PageHeader>
  <main id="main-content" class="content" tabindex="-1" v-loading="loading">
    <section class="settings-layout">
      <article class="card card-pad">
        <div class="card-header"><div><p class="eyebrow">RUNTIME</p><h2 class="card-title">OpenCode 发现</h2></div><Icon icon="lucide:terminal-square" color="var(--color-accent-cyan)" /></div>
        <el-form label-position="top">
          <el-form-item label="CLI 路径"><el-input v-model="settings.cliPath" class="mono" name="opencode-cli" autocomplete="off" /><p v-if="fieldError" class="inline-field-error"><Icon icon="lucide:circle-alert" /> {{ fieldError }}</p></el-form-item>
          <el-form-item label="允许项目根（可选）"><el-input v-model="settings.allowedRoot" class="mono" name="allowed-project-root" autocomplete="off" placeholder="例如 /workspace/my-project…" /></el-form-item>
        </el-form>
      </article>

      <article class="card card-pad">
        <div class="card-header"><div><p class="eyebrow">MODEL</p><h2 class="card-title">默认模型</h2></div><el-button text :loading="refreshingModels" @click="refreshModels(true)"><Icon icon="lucide:refresh-cw" />刷新模型</el-button></div>
        <el-form label-position="top">
          <el-form-item label="Provider"><el-select v-model="settings.provider" :disabled="refreshingModels || !providers.length" filterable style="width:100%" aria-label="选择模型提供方"><el-option v-for="provider in providers" :key="provider" :label="provider" :value="provider" /></el-select></el-form-item>
          <el-form-item label="Model"><el-select v-model="settings.model" :disabled="refreshingModels || !providerModels.length" filterable style="width:100%" placeholder="选择 OpenCode 模型…" aria-label="选择 OpenCode 模型"><el-option v-for="item in providerModels" :key="item.id" :label="item.model" :value="item.model"><span class="mono" translate="no">{{ item.model }}</span></el-option></el-select></el-form-item>
        </el-form>
        <p v-if="modelError" class="inline-field-error"><Icon icon="lucide:circle-alert" /> {{ modelError }}</p>
      </article>

      <article class="card card-pad">
        <div class="card-header"><div><p class="eyebrow">LIMITS</p><h2 class="card-title">调度上限</h2></div><Icon icon="lucide:gauge" color="var(--color-session-warning)" /></div>
        <el-form label-position="top"><el-form-item label="每个任务最大尝试次数"><el-input-number v-model="settings.maxTaskAttempts" :min="1" :max="50" aria-label="每个任务最大尝试次数" style="width:100%" /></el-form-item><el-form-item label="单次尝试超时（分钟）"><el-input-number v-model="settings.timeoutMinutes" :min="1" :max="120" aria-label="单次尝试超时分钟数" style="width:100%" /></el-form-item></el-form>
      </article>

      <article class="card card-pad">
        <div class="card-header"><div><p class="eyebrow">PERMISSIONS</p><h2 class="card-title">自动批准策略</h2></div><Icon icon="lucide:shield-check" color="var(--color-success)" /></div>
        <el-switch :model-value="false" disabled aria-label="自动批准权限，当前不可用" inactive-text="所有权限请求均等待确认" />
        <p class="card-description" style="margin-top:16px">当前版本保持 fail-closed：外部路径、git push、显式 deny 与破坏性命令不会自动批准。</p>
      </article>
    </section>
    <section class="card card-pad settings-demo"><div><p class="eyebrow">DEVELOPMENT DATA</p><h2 class="card-title">演示数据模式</h2></div><el-button :type="store.usingDemo ? 'warning' : 'primary'" plain @click="store.activateDemo('已手动启用演示数据。')">{{ store.usingDemo ? '演示数据已启用' : '启用演示数据' }}</el-button></section>
  </main>
</template>

<style scoped>
.settings-layout { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 16px; }
.settings-demo { display: flex; align-items: center; justify-content: space-between; gap: 16px; margin-top: 16px; }
code { font-family: var(--font-mono); }
@media (max-width: 900px) { .settings-layout { grid-template-columns: 1fr; } }
</style>
