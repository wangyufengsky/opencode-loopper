<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { Icon } from '@iconify/vue'
import { ElMessage } from 'element-plus'
import PageHeader from '@/components/PageHeader.vue'
import { api, ApiError } from '@/api/client'
import type { AppSettings, AvailableModel } from '@/types/domain'
import { useTaskStore } from '@/stores/taskStore'

const defaults = (): AppSettings => ({
  runtime: { serverPort: 8080, openBrowser: true, allowedRoot: '', monitorDelaySeconds: 2, designerMonitorDelayMillis: 750, abortCleanupAttempts: 3 },
  openCode: { cliPath: 'opencode', mode: 'auto', baseUrl: 'http://127.0.0.1:4096', provider: '', model: '', connectTimeoutSeconds: 5, requestTimeoutSeconds: 30, startupTimeoutSeconds: 15 },
  limits: { maxStageAttempts: 3, maxTaskAttempts: 12, sessionErrorLimit: 3, maxDurationMinutes: 120, attemptTimeoutMinutes: 30, verifierTimeoutMinutes: 10, designerTimeoutMinutes: 30 },
  retryWait: { rateLimitBaseSeconds: 60, rateLimitMaxSeconds: 300, sessionBaseSeconds: 10, sessionMaxSeconds: 60, verificationBaseSeconds: 5, verificationMaxSeconds: 30 },
  publication: { httpWebHosts: ['gitlab.spdb.com'], gitlabHost: 'gitlab.spdb.com', gitlabApiBaseUrl: 'http://gitlab.spdb.com/api/v4', connectTimeoutSeconds: 3, requestTimeoutSeconds: 10 },
  appliedLiveFields: [], restartRequiredFields: [],
})

const store = useTaskStore()
const settings = ref<AppSettings>(defaults())
const publicationHosts = ref('gitlab.spdb.com')
const availableModels = ref<AvailableModel[]>([])
const loading = ref(true)
const saving = ref(false)
const refreshingModels = ref(false)
const switchingDemo = ref(false)
const fieldError = ref('')
const modelError = ref('')

const providers = computed(() => [...new Set(availableModels.value.map((item) => item.provider))].sort())
const providerModels = computed(() => availableModels.value.filter((item) => item.provider === settings.value.openCode.provider))

watch(() => settings.value.openCode.provider, (provider, previous) => {
  if (provider === previous || providerModels.value.length === 0 || providerModels.value.some((item) => item.model === settings.value.openCode.model)) return
  settings.value.openCode.model = providerModels.value[0]?.model ?? ''
})

function isAbsoluteProjectPath(value: string) {
  return value.startsWith('/') || /^[A-Za-z]:[\\/]/.test(value) || /^\\\\[^\\]+\\[^\\]+/.test(value)
}

function message(error: unknown) {
  return error instanceof ApiError || error instanceof Error ? error.message : '请求失败，请检查配置。'
}

async function refreshModels(selectFallback = true) {
  refreshingModels.value = true
  modelError.value = ''
  try {
    availableModels.value = await api.getSettingsModels(settings.value.openCode.cliPath.trim())
    if (!providers.value.includes(settings.value.openCode.provider) && selectFallback) settings.value.openCode.provider = providers.value[0] ?? ''
    if (!providerModels.value.some((item) => item.model === settings.value.openCode.model) && selectFallback) settings.value.openCode.model = providerModels.value[0]?.model ?? ''
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
    publicationHosts.value = settings.value.publication.httpWebHosts.join(', ')
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
  if (!settings.value.openCode.cliPath.trim()) { fieldError.value = 'OpenCode CLI 路径不能为空。'; return }
  if (settings.value.runtime.allowedRoot.trim() && !isAbsoluteProjectPath(settings.value.runtime.allowedRoot.trim())) { fieldError.value = '允许项目根必须是绝对路径。'; return }
  if (!settings.value.openCode.provider || !settings.value.openCode.model) { modelError.value = '请先刷新并选择一个可用模型。'; return }
  settings.value.publication.httpWebHosts = publicationHosts.value.split(',').map((value) => value.trim()).filter(Boolean)
  saving.value = true
  try {
    settings.value = await api.updateSettings(settings.value)
    publicationHosts.value = settings.value.publication.httpWebHosts.join(', ')
    ElMessage.success('设置已保存；运行项立即生效，启动项将在下次启动生效。')
  } catch (error) {
    ElMessage.error(message(error))
  } finally {
    saving.value = false
  }
}

async function toggleDemo() {
  switchingDemo.value = true
  try {
    if (store.usingDemo) await store.deactivateDemo()
    else store.activateDemo()
  } finally {
    switchingDemo.value = false
  }
}

onMounted(load)
</script>

<template>
  <PageHeader eyebrow="System / Settings" title="详细配置">
    <template #actions><el-button class="settings-save" type="primary" :loading="saving" :disabled="loading" @click="save"><Icon icon="lucide:save" />保存设置</el-button></template>
  </PageHeader>
  <main id="main-content" class="content" tabindex="-1" v-loading="loading">
    <el-alert type="info" :closable="false" show-icon class="settings-notice">
      <template #title>显式环境变量优先，其次读取页面保存的 {{ settings.startupConfigPath || 'startup-overrides.properties' }}，最后使用启动脚本默认值；保存不会自动重启 Loopper。</template>
    </el-alert>
    <section class="settings-layout">
      <article class="card card-pad">
        <div class="card-header"><div><p class="eyebrow">RUNTIME</p><h2 class="card-title">运行环境</h2></div><span class="activation restart">重启生效</span></div>
        <el-form label-position="top">
          <div class="form-grid"><el-form-item label="服务端口"><el-input-number v-model="settings.runtime.serverPort" :min="1" :max="65535" /></el-form-item><el-form-item label="启动后打开浏览器"><el-switch v-model="settings.runtime.openBrowser" /></el-form-item></div>
          <el-form-item label="允许项目根（立即生效）"><el-input v-model="settings.runtime.allowedRoot" class="mono" autocomplete="off" /><p v-if="fieldError" class="inline-field-error">{{ fieldError }}</p></el-form-item>
          <div class="form-grid"><el-form-item label="任务监控间隔（秒）"><el-input-number v-model="settings.runtime.monitorDelaySeconds" :min="1" :max="60" /></el-form-item><el-form-item label="Designer 监控间隔（毫秒）"><el-input-number v-model="settings.runtime.designerMonitorDelayMillis" :min="250" :max="10000" /></el-form-item><el-form-item label="终止清理次数（立即生效）"><el-input-number v-model="settings.runtime.abortCleanupAttempts" :min="1" :max="10" /></el-form-item></div>
        </el-form>
      </article>

      <article class="card card-pad">
        <div class="card-header"><div><p class="eyebrow">OPENCODE</p><h2 class="card-title">OpenCode 与默认模型</h2></div><el-button text :loading="refreshingModels" @click="refreshModels(true)"><Icon icon="lucide:refresh-cw" />刷新模型</el-button></div>
        <p class="card-description">CLI 与默认模型从下一次 Session 生效；模式、服务地址和网络超时需重启 Loopper。</p>
        <el-form label-position="top">
          <el-form-item label="CLI 路径（下一 Session）"><el-input v-model="settings.openCode.cliPath" class="mono" autocomplete="off" /></el-form-item>
          <div class="form-grid"><el-form-item label="模式（重启生效）"><el-select v-model="settings.openCode.mode"><el-option label="auto" value="auto" /><el-option label="http" value="http" /></el-select></el-form-item><el-form-item label="服务地址（重启生效）"><el-input v-model="settings.openCode.baseUrl" class="mono" /></el-form-item></div>
          <div class="form-grid"><el-form-item label="Provider"><el-select v-model="settings.openCode.provider" filterable><el-option v-for="provider in providers" :key="provider" :label="provider" :value="provider" /></el-select></el-form-item><el-form-item label="Model"><el-select v-model="settings.openCode.model" filterable><el-option v-for="item in providerModels" :key="item.id" :label="item.model" :value="item.model" /></el-select></el-form-item></div>
          <div class="form-grid"><el-form-item label="连接超时（秒）"><el-input-number v-model="settings.openCode.connectTimeoutSeconds" :min="1" :max="120" /></el-form-item><el-form-item label="请求超时（秒）"><el-input-number v-model="settings.openCode.requestTimeoutSeconds" :min="1" :max="600" /></el-form-item><el-form-item label="启动超时（秒）"><el-input-number v-model="settings.openCode.startupTimeoutSeconds" :min="1" :max="300" /></el-form-item></div>
        </el-form>
        <p v-if="modelError" class="inline-field-error">{{ modelError }}</p>
      </article>

      <article class="card card-pad">
        <div class="card-header"><div><p class="eyebrow">LIMITS</p><h2 class="card-title">全局执行上限</h2></div><span class="activation live">立即生效</span></div>
        <p class="card-description">与 LoopSpec 显式限制取较小值。</p>
        <el-form label-position="top"><div class="form-grid limits-grid">
          <el-form-item label="Stage 最大尝试"><el-input-number v-model="settings.limits.maxStageAttempts" :min="1" :max="10" /></el-form-item><el-form-item label="Task 最大尝试"><el-input-number v-model="settings.limits.maxTaskAttempts" :min="1" :max="50" /></el-form-item><el-form-item label="Session 错误上限"><el-input-number v-model="settings.limits.sessionErrorLimit" :min="1" :max="10" /></el-form-item>
          <el-form-item label="任务总时长（分钟）"><el-input-number v-model="settings.limits.maxDurationMinutes" :min="1" :max="1440" /></el-form-item><el-form-item label="Attempt 超时（分钟）"><el-input-number v-model="settings.limits.attemptTimeoutMinutes" :min="1" :max="120" /></el-form-item><el-form-item label="验证超时（分钟）"><el-input-number v-model="settings.limits.verifierTimeoutMinutes" :min="1" :max="120" /></el-form-item><el-form-item label="Designer 超时（分钟）"><el-input-number v-model="settings.limits.designerTimeoutMinutes" :min="1" :max="120" /></el-form-item>
        </div></el-form>
      </article>

      <article class="card card-pad retry-settings">
        <div class="card-header"><div><p class="eyebrow">RETRY_WAIT</p><h2 class="card-title">分类指数退避</h2></div><span class="activation live">新计划生效</span></div>
        <p class="card-description">已进入等待的任务保持原到期时间，不追加随机抖动。</p>
        <el-form label-position="top"><div class="retry-grid">
          <strong>限流</strong><el-form-item label="起始秒数"><el-input-number v-model="settings.retryWait.rateLimitBaseSeconds" :min="5" :max="600" /></el-form-item><el-form-item label="最大秒数"><el-input-number v-model="settings.retryWait.rateLimitMaxSeconds" :min="settings.retryWait.rateLimitBaseSeconds" :max="3600" /></el-form-item>
          <strong>Session</strong><el-form-item label="起始秒数"><el-input-number v-model="settings.retryWait.sessionBaseSeconds" :min="1" :max="300" /></el-form-item><el-form-item label="最大秒数"><el-input-number v-model="settings.retryWait.sessionMaxSeconds" :min="settings.retryWait.sessionBaseSeconds" :max="1800" /></el-form-item>
          <strong>验证失败</strong><el-form-item label="起始秒数"><el-input-number v-model="settings.retryWait.verificationBaseSeconds" :min="1" :max="120" /></el-form-item><el-form-item label="最大秒数"><el-input-number v-model="settings.retryWait.verificationMaxSeconds" :min="settings.retryWait.verificationBaseSeconds" :max="600" /></el-form-item>
        </div></el-form>
      </article>

      <article class="card card-pad publication-settings">
        <div class="card-header"><div><p class="eyebrow">PUBLICATION</p><h2 class="card-title">发布网络</h2></div><span class="activation restart">重启生效</span></div>
        <el-form label-position="top">
          <el-form-item label="HTTP Web Hosts（逗号分隔）"><el-input v-model="publicationHosts" class="mono" /></el-form-item>
          <div class="form-grid"><el-form-item label="GitLab Host"><el-input v-model="settings.publication.gitlabHost" class="mono" /></el-form-item><el-form-item label="GitLab API"><el-input v-model="settings.publication.gitlabApiBaseUrl" class="mono" /></el-form-item></div>
          <div class="form-grid"><el-form-item label="连接超时（秒）"><el-input-number v-model="settings.publication.connectTimeoutSeconds" :min="1" :max="120" /></el-form-item><el-form-item label="请求超时（秒）"><el-input-number v-model="settings.publication.requestTimeoutSeconds" :min="1" :max="300" /></el-form-item></div>
        </el-form>
      </article>
    </section>
    <section class="card card-pad settings-demo"><div><p class="eyebrow">DEVELOPMENT DATA</p><h2 class="card-title">演示数据模式</h2><p class="card-description">敏感凭据、Java/JAR 路径和数据目录不会进入页面配置。</p></div><el-button :type="store.usingDemo ? 'warning' : 'primary'" plain :loading="switchingDemo" @click="toggleDemo">{{ store.usingDemo ? '退出演示数据' : '启用演示数据' }}</el-button></section>
  </main>
</template>

<style scoped>
.settings-notice { margin-bottom: 16px; }
.settings-layout { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 16px; }
.retry-settings, .publication-settings { grid-column: 1 / -1; }
.form-grid { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 12px; }
.limits-grid { grid-template-columns: repeat(4, minmax(0, 1fr)); }
.retry-grid { display: grid; grid-template-columns: minmax(100px, .5fr) repeat(2, minmax(0, 1fr)); gap: 8px 16px; align-items: center; }
.retry-grid strong { color: var(--color-text-secondary); }
.activation { border: 1px solid; border-radius: 999px; padding: 3px 8px; font-size: 11px; }
.activation.live { color: var(--color-success); }.activation.restart { color: var(--color-session-warning); }
.settings-demo { display: flex; align-items: center; justify-content: space-between; gap: 16px; margin-top: 16px; }
.mono { font-family: var(--font-mono); }
@media (max-width: 1000px) { .settings-layout { grid-template-columns: 1fr; }.retry-settings, .publication-settings { grid-column: auto; }.limits-grid { grid-template-columns: repeat(2, minmax(0, 1fr)); } }
@media (max-width: 700px) { .form-grid, .limits-grid, .retry-grid { grid-template-columns: 1fr; }.settings-demo { align-items: flex-start; flex-direction: column; } }
</style>
