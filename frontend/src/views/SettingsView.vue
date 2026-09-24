<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { Icon } from '@iconify/vue'
import { ElMessage } from 'element-plus'
import PageHeader from '@/components/PageHeader.vue'
import GitCredentialForm from '@/components/GitCredentialForm.vue'
import { api } from '@/api/client'
import type { AppSettings, AvailableModel } from '@/types/domain'
import { useTaskStore } from '@/stores/taskStore'
import { userFacingError } from '@/utils/displayLabels'

const defaults = (): AppSettings => ({
  runtime: { serverPort: 8080, openBrowser: true, allowedRoot: '', monitorDelaySeconds: 2, designerMonitorDelayMillis: 750, abortCleanupAttempts: 3 },
  openCode: { cliPath: 'opencode', mode: 'managed', baseUrl: 'http://127.0.0.1:4096', provider: '', model: '', connectTimeoutSeconds: 5, requestTimeoutSeconds: 30, startupTimeoutSeconds: 15 },
  limits: { templateAnalysisConcurrency: 4, timeoutEnabled: false, maxStageAttempts: 3, maxTaskAttempts: 12, sessionErrorLimit: 3, maxDurationMinutes: 120, attemptTimeoutMinutes: 30, verifierTimeoutMinutes: 10, designerTimeoutMinutes: 30 },
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

const sections = [
  { id: 'runtime', title: '服务设置', subtitle: '端口、目录与监控', icon: 'lucide:server' },
  { id: 'models', title: '模型服务', subtitle: 'OpenCode 与默认模型', icon: 'lucide:bot' },
  { id: 'limits', title: '执行限制', subtitle: '并发、尝试与超时', icon: 'lucide:sliders-horizontal' },
  { id: 'retry', title: '重试等待', subtitle: '各类失败的等待时间', icon: 'lucide:timer' },
  { id: 'git-credentials', title: 'Git 账号', subtitle: '全局默认账号与凭据', icon: 'lucide:key-round' },
  { id: 'publication', title: '发布网络', subtitle: 'GitLab 与连接配置', icon: 'lucide:globe' },
  { id: 'demo', title: '开发辅助', subtitle: '演示数据模式', icon: 'lucide:flask-conical' },
] as const
const activeSection = ref<string>('runtime')

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
  return userFacingError(error, '请求失败，请检查配置。')
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
  if (!settings.value.openCode.cliPath.trim()) { modelError.value = 'OpenCode CLI 路径不能为空。'; activeSection.value = 'models'; return }
  if (settings.value.runtime.allowedRoot.trim() && !isAbsoluteProjectPath(settings.value.runtime.allowedRoot.trim())) { fieldError.value = '允许项目根必须是绝对路径。'; activeSection.value = 'runtime'; return }
  if (!settings.value.openCode.provider || !settings.value.openCode.model) { modelError.value = '请先刷新并选择一个可用模型。'; activeSection.value = 'models'; return }
  settings.value.publication.httpWebHosts = publicationHosts.value.split(',').map((value) => value.trim()).filter(Boolean)
  saving.value = true
  try {
    settings.value = await api.updateSettings(settings.value)
    publicationHosts.value = settings.value.publication.httpWebHosts.join(', ')
    await store.refreshRuntime()
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
  <PageHeader eyebrow="系统" title="设置">
    <template #actions><el-button v-if="activeSection !== 'git-credentials'" class="settings-save" type="primary" :loading="saving" :disabled="loading" @click="save"><Icon icon="lucide:save" />保存设置</el-button></template>
  </PageHeader>
  <main id="main-content" class="content settings-content" tabindex="-1" v-loading="loading">
    <div class="settings-layout">
      <nav class="settings-nav" aria-label="设置分区">
        <p class="nav-caption">偏好与配置</p>
        <button v-for="section in sections" :key="section.id" type="button"
          :class="['settings-nav-item', { active: activeSection === section.id }]"
          :aria-pressed="activeSection === section.id" :aria-controls="`settings-${section.id}`"
          @click="activeSection = section.id">
          <Icon :icon="section.icon" width="20" />
          <span><strong>{{ section.title }}</strong><small>{{ section.subtitle }}</small></span>
          <Icon class="nav-chevron" icon="lucide:chevron-right" width="16" />
        </button>
        <p class="nav-note"><Icon icon="lucide:info" width="16" />本页设置修改后请保存。各项生效时间见面板说明。</p>
      </nav>
      <div class="settings-panels">
      <article v-if="activeSection === 'git-credentials'" id="settings-git-credentials" class="card settings-panel"><div class="card-header"><h2 class="card-title">全局 Git 账号</h2></div><GitCredentialForm :demo="store.usingDemo" /></article>
      <article v-show="activeSection === 'runtime'" id="settings-runtime" class="card settings-panel runtime-settings" aria-labelledby="runtime-title">
        <div class="card-header"><div><p class="eyebrow">运行环境</p><h2 id="runtime-title" class="card-title">服务设置</h2></div><span class="activation restart">重启生效</span></div>
        <el-form label-position="top">
          <div class="form-grid"><el-form-item label="服务端口"><el-input-number v-model="settings.runtime.serverPort" :min="1" :max="65535" /></el-form-item></div>
          <div class="switch-row"><div><strong>启动后打开浏览器</strong><p>服务启动时自动打开本地控制台。</p></div><el-switch v-model="settings.runtime.openBrowser" aria-label="启动后打开浏览器" /></div>
          <h3 class="form-section-title">项目与监控</h3><el-form-item label="允许项目根（立即生效）"><el-input v-model="settings.runtime.allowedRoot" class="mono" autocomplete="off" /><p v-if="fieldError" class="inline-field-error">{{ fieldError }}</p></el-form-item>
          <div class="form-grid three-columns"><el-form-item label="任务监控间隔（秒）"><el-input-number v-model="settings.runtime.monitorDelaySeconds" :min="1" :max="60" /></el-form-item><el-form-item label="设计监控间隔（毫秒）"><el-input-number v-model="settings.runtime.designerMonitorDelayMillis" :min="250" :max="10000" /></el-form-item><el-form-item label="终止清理次数（立即生效）"><el-input-number v-model="settings.runtime.abortCleanupAttempts" :min="1" :max="10" /></el-form-item></div>
        </el-form>
      </article>

      <article v-show="activeSection === 'models'" id="settings-models" class="card settings-panel models-settings" aria-labelledby="models-title">
        <div class="card-header"><div><p class="eyebrow">模型服务</p><h2 id="models-title" class="card-title">OpenCode 与默认模型</h2></div><el-button text :loading="refreshingModels" @click="refreshModels(true)"><Icon icon="lucide:refresh-cw" />刷新模型</el-button></div>
        <el-form label-position="top">
          <el-form-item label="命令行路径（下次会话生效）"><el-input v-model="settings.openCode.cliPath" class="mono" autocomplete="off" /></el-form-item>
          <div class="form-grid"><el-form-item label="连接模式（重启生效）"><el-select v-model="settings.openCode.mode"><el-option label="Loopper 受管（推荐）" value="managed" /><el-option label="自动发现（兼容）" value="auto" /><el-option label="固定地址" value="http" /></el-select></el-form-item><el-form-item label="服务地址（重启生效）"><el-input v-model="settings.openCode.baseUrl" class="mono" /></el-form-item></div>
          <div class="form-grid"><el-form-item label="模型提供方"><el-select v-model="settings.openCode.provider" filterable><el-option v-for="provider in providers" :key="provider" :label="provider" :value="provider" /></el-select></el-form-item><el-form-item label="模型"><el-select v-model="settings.openCode.model" filterable><el-option v-for="item in providerModels" :key="item.id" :label="item.model" :value="item.model" /></el-select></el-form-item></div>
          <h3 class="form-section-title">连接超时</h3><div class="form-grid three-columns"><el-form-item label="连接超时（秒）"><el-input-number v-model="settings.openCode.connectTimeoutSeconds" :min="1" :max="120" /></el-form-item><el-form-item label="请求超时（秒）"><el-input-number v-model="settings.openCode.requestTimeoutSeconds" :min="1" :max="600" /></el-form-item><el-form-item label="启动超时（秒）"><el-input-number v-model="settings.openCode.startupTimeoutSeconds" :min="1" :max="300" /></el-form-item></div>
        </el-form>
        <p v-if="modelError" class="inline-field-error">{{ modelError }}</p>
      </article>

      <article v-show="activeSection === 'limits'" id="settings-limits" class="card settings-panel limits-settings" aria-labelledby="limits-title">
        <div class="card-header"><div><p class="eyebrow">执行限制</p><h2 id="limits-title" class="card-title">全局执行上限</h2></div><span class="activation live">新任务生效</span></div>
        <div class="timeout-policy-control switch-row"><div><strong>启用业务超时限制</strong><p>默认关闭，任务、设计和评审不因运行时长自动停止。</p></div><el-switch v-model="settings.limits.timeoutEnabled" aria-label="启用业务超时限制" /></div>
        <el-form label-position="top"><div class="form-grid limits-grid">
          <el-form-item label="模板分析并发数"><el-input-number v-model="settings.limits.templateAnalysisConcurrency" :min="1" :max="16" aria-label="模板分析并发数" /></el-form-item>
          <el-form-item label="阶段最大尝试"><el-input-number v-model="settings.limits.maxStageAttempts" :min="1" :max="10" /></el-form-item><el-form-item label="任务最大尝试"><el-input-number v-model="settings.limits.maxTaskAttempts" :min="1" :max="50" /></el-form-item><el-form-item label="会话错误上限"><el-input-number v-model="settings.limits.sessionErrorLimit" :min="1" :max="10" /></el-form-item>
          <el-form-item v-if="settings.limits.timeoutEnabled" label="任务总时长（分钟）"><el-input-number v-model="settings.limits.maxDurationMinutes" :min="1" :max="10080" /></el-form-item><el-form-item v-if="settings.limits.timeoutEnabled" label="尝试超时（分钟）"><el-input-number v-model="settings.limits.attemptTimeoutMinutes" :min="1" :max="1440" /></el-form-item><el-form-item label="验证超时（分钟）"><el-input-number v-model="settings.limits.verifierTimeoutMinutes" :min="1" :max="120" /></el-form-item><el-form-item v-if="settings.limits.timeoutEnabled" label="设计超时（分钟）"><el-input-number v-model="settings.limits.designerTimeoutMinutes" :min="1" :max="1440" /></el-form-item>
        </div></el-form><p class="field-note">模板分析并发数仅用于新模板任务，需求开发不适用；运行任务保持原设置。</p>
      </article>

      <article v-show="activeSection === 'retry'" id="settings-retry" class="card settings-panel retry-settings" aria-labelledby="retry-title">
        <div class="card-header"><div><p class="eyebrow">重试等待</p><h2 id="retry-title" class="card-title">分类退避</h2></div><span class="activation live">新计划生效</span></div>
        <p class="panel-description">发生失败后逐步延长等待时间，直至设定的上限。</p><el-form label-position="top"><div class="retry-grid">
          <strong>限流</strong><el-form-item label="起始秒数"><el-input-number v-model="settings.retryWait.rateLimitBaseSeconds" :min="5" :max="600" /></el-form-item><el-form-item label="最大秒数"><el-input-number v-model="settings.retryWait.rateLimitMaxSeconds" :min="settings.retryWait.rateLimitBaseSeconds" :max="3600" /></el-form-item>
          <strong>会话</strong><el-form-item label="起始秒数"><el-input-number v-model="settings.retryWait.sessionBaseSeconds" :min="1" :max="300" /></el-form-item><el-form-item label="最大秒数"><el-input-number v-model="settings.retryWait.sessionMaxSeconds" :min="settings.retryWait.sessionBaseSeconds" :max="1800" /></el-form-item>
          <strong>验证失败</strong><el-form-item label="起始秒数"><el-input-number v-model="settings.retryWait.verificationBaseSeconds" :min="1" :max="120" /></el-form-item><el-form-item label="最大秒数"><el-input-number v-model="settings.retryWait.verificationMaxSeconds" :min="settings.retryWait.verificationBaseSeconds" :max="600" /></el-form-item>
        </div></el-form>
      </article>

      <article v-show="activeSection === 'publication'" id="settings-publication" class="card settings-panel publication-settings" aria-labelledby="publication-title">
        <div class="card-header"><div><p class="eyebrow">发布</p><h2 id="publication-title" class="card-title">发布网络</h2></div><span class="activation restart">重启生效</span></div>
        <el-form label-position="top">
          <el-form-item label="强制 HTTP 的网站主机（逗号分隔）"><el-input v-model="publicationHosts" class="mono" /></el-form-item>
          <div class="form-grid"><el-form-item label="GitLab 主机"><el-input v-model="settings.publication.gitlabHost" class="mono" /></el-form-item><el-form-item label="GitLab 接口地址（HTTP / HTTPS）"><el-input v-model="settings.publication.gitlabApiBaseUrl" class="mono" placeholder="http://gitlab.internal/api/v4" /></el-form-item></div>
          <el-alert v-if="settings.publication.gitlabApiBaseUrl.startsWith('http://')" title="已选择 HTTP 模式，支持内网 GitLab；此连接的网络传输不加密。" type="warning" :closable="false" />
          <div class="form-grid"><el-form-item label="连接超时（秒）"><el-input-number v-model="settings.publication.connectTimeoutSeconds" :min="1" :max="120" /></el-form-item><el-form-item label="请求超时（秒）"><el-input-number v-model="settings.publication.requestTimeoutSeconds" :min="1" :max="300" /></el-form-item></div>
        </el-form>
      </article>
        <section v-show="activeSection === 'demo'" id="settings-demo" class="card settings-panel settings-demo" aria-labelledby="demo-title">
          <div class="card-header"><div><p class="eyebrow">开发辅助</p><h2 id="demo-title" class="card-title">演示数据模式</h2></div><span class="activation">立即生效</span></div>
          <div class="switch-row"><div><strong>{{ store.usingDemo ? '正在使用演示数据' : '查看示例项目与任务' }}</strong><p>用于体验界面；退出后重新读取实际项目和任务。</p></div><el-button :type="store.usingDemo ? 'warning' : 'primary'" plain :loading="switchingDemo" @click="toggleDemo">{{ store.usingDemo ? '退出演示数据' : '启用演示数据' }}</el-button></div>
        </section>
      </div>
    </div>
  </main>
</template>

<style scoped>
.settings-content { width: 100%; max-width: 1440px; }
.settings-layout { display: grid; grid-template-columns: 220px minmax(0, 1fr); gap: 32px; align-items: start; }
.settings-nav { display: grid; gap: 6px; min-width: 0; }
.nav-caption { margin: 0 12px 12px; color: var(--color-text-secondary); font-size: 12px; }
.settings-nav-item { display: flex; align-items: center; gap: 12px; padding: 15px 12px; border: 1px solid transparent; border-radius: var(--radius-card); background: transparent; color: var(--color-text-secondary); text-align: left; text-decoration: none; cursor: pointer; font: inherit; }
.settings-nav-item > svg { flex-shrink: 0; }
.settings-nav-item > span { flex: 1; min-width: 0; display: grid; gap: 6px; }
.settings-nav-item strong { font-size: 14px; font-weight: 500; }
.settings-nav-item small { font-size: 11px; color: var(--color-text-secondary); line-height: 1.5; }
.settings-nav-item:hover { background: var(--color-bg-hover); }
.settings-nav-item.active { color: var(--color-text-primary); border-color: var(--color-border-default); background: var(--color-bg-elevated); }
.settings-nav-item.active > svg { color: var(--color-accent-cyan); }
.nav-chevron { opacity: 0; }.active .nav-chevron { opacity: 1; }
.settings-nav-item:focus-visible { outline: 2px solid var(--color-accent-cyan); outline-offset: 2px; }
.nav-note { display: flex; gap: 8px; margin: 20px 12px 0; padding-top: 20px; border-top: 1px solid var(--color-border-default); font-size: 12px; line-height: 1.8; color: var(--color-text-secondary); }
.nav-note svg { flex-shrink: 0; margin-top: 3px; }
.settings-panels, .settings-panel { min-width: 0; }
.settings-panel { padding: 28px 32px; background: var(--color-bg-surface); box-shadow: none; }
.settings-panel .card-header { align-items: center; flex-wrap: wrap; margin-bottom: 28px; padding-bottom: 24px; border-bottom: 1px solid var(--color-border-default); }
.settings-panel .card-title { font-size: 20px; font-weight: 600; }
.settings-panel .eyebrow { margin-bottom: 8px; }
.form-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 0 24px; align-items: end; }
.three-columns { grid-template-columns: repeat(3, minmax(0, 1fr)); }
.settings-panel :deep(.el-form-item) { min-width: 0; margin-bottom: 24px; }
.settings-panel :deep(.el-form-item__label) { height: auto; line-height: 1.6; margin-bottom: 10px; color: var(--color-text-secondary); }
.settings-panel :deep(.el-form-item__content) { min-width: 0; }
.settings-panel :deep(.el-input-number), .settings-panel :deep(.el-select) { width: 100%; min-width: 0; }
.settings-panel :deep(.el-input__wrapper), .settings-panel :deep(.el-select__wrapper) { min-height: 40px; }
.form-section-title { margin: 8px 0 22px; padding-top: 24px; border-top: 1px solid var(--color-border-default); font-size: 14px; font-weight: 600; }
.switch-row { display: flex; align-items: center; justify-content: space-between; gap: 24px; padding: 18px 20px; margin-bottom: 24px; border-radius: var(--radius-card); background: var(--color-bg-elevated); font-size: 13px; }
.switch-row strong { font-weight: 500; }.switch-row p { margin: 6px 0 0; color: var(--color-text-secondary); font-size: 12px; line-height: 1.7; }
.switch-row .el-switch, .switch-row .el-button { flex-shrink: 0; }
.field-note, .panel-description { margin: 0 0 24px; color: var(--color-text-secondary); font-size: 12px; line-height: 1.8; }
.field-note { padding: 14px 16px; margin-bottom: 0; border-left: 2px solid var(--color-border-default); background: var(--color-bg-elevated); }
.retry-grid { display: grid; grid-template-columns: minmax(80px, .65fr) repeat(2, minmax(0, 1fr)); gap: 0 24px; align-items: center; }
.retry-grid > strong { color: var(--color-text-primary); font-size: 13px; font-weight: 500; }
.activation { flex-shrink: 0; border: 1px solid var(--color-border-default); border-radius: var(--radius-control); padding: 5px 9px; font-size: 11px; color: var(--color-text-secondary); }
.activation.live { color: var(--color-success); }.activation.restart { color: var(--color-session-warning); }
.mono :deep(input) { font-family: var(--font-code); }
.settings-save :deep(svg), .card-header :deep(button svg) { margin-right: 8px; }
@media (max-width: 1200px) { .settings-layout { grid-template-columns: 188px minmax(0, 1fr); gap: 20px; }.settings-panel { padding: 24px; }.three-columns { grid-template-columns: repeat(2, minmax(0, 1fr)); } }
@media (max-width: 1000px) { .settings-layout { grid-template-columns: 1fr; }.settings-nav { grid-template-columns: repeat(3, minmax(0, 1fr)); }.nav-caption, .nav-note, .nav-chevron, .settings-nav-item small { display: none; }.settings-nav-item { padding: 12px; } }
@media (max-width: 600px) { .settings-nav { grid-template-columns: repeat(2, minmax(0, 1fr)); }.settings-panel { padding: 20px 16px; }.form-grid, .three-columns { grid-template-columns: 1fr; }.retry-grid { grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 0 16px; }.retry-grid > strong { grid-column: 1 / -1; margin-bottom: 16px; }.switch-row { padding: 16px; gap: 16px; }.settings-demo .switch-row { align-items: flex-start; flex-direction: column; } }
</style>
